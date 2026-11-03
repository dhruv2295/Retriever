# Query parser

## Purpose

Read the search box the way a person means it. "screenshots from last week" becomes a kind filter (screenshots) and a
date range (last week); what is left over ("wifi" in "wifi screenshots") is what the engine searches for, and nothing
left means browse that filter. The
screen shows what was understood ("the understood line") and lets the person drop it with an X, which searches the
words as typed. When the filters find nothing the facade searches again without them and says so (the relaxed note).

## Mechanism and why

- **Rules, not a model.** `QueryParser.parse(text, now, zoneOffsetMs)` is pure: it splits on whitespace, lowercases and
  strips punctuation per word, finds at most one date phrase, then kind words, and returns a `ParsedQuery`. A model
  parser would cost an embed per keystroke and gain little; rules are testable line by line and run in microseconds.
- **Date phrases (English and Hindi):** today, yesterday, the day before yesterday, this or last week, month and year,
  "N days ago", ISO dates, `d/m/y` (day first unless the second number is over 12), "15 march [2024]", "march 2023",
  "march 15", a bare month or year only beside a connector (`in`, `from`, `on`, `during`, `of`) or a kind word, so
  "march for climate" and "i may" stay words. A month or day without a year is its most recent past occurrence.
  Weekdays (English and Hindi): "last sunday" is the one before today, "this sunday" or a bare one the most recent up
  to today. A bare weekday is a date only beside a kind word ("photos from friday"), because in a query like "what did
  we decide for dinner on Friday" it is what a screenshot says, not when it was taken.
  Weeks start on Monday. The first date phrase wins; a connector right before it is consumed with it.
- **Kind words:** screenshots, photos, pdfs, videos, audio, documents (documents also covers PDFs). A kind word counts
  only when it is plural or ends its phrase ("wifi screenshot", "pdf from march"); "photo id" or "video call notes"
  keep the word in the text, because the singular there describes the next word. A name with a year ("report 2023")
  stays a name unless the year sits beside a connector or kind word.
- **Range as local midnights.** `from` (inclusive) and `to` (exclusive) are epoch milliseconds at local midnights.
  The facade passes `zoneOffsetMs`, the zone's UTC offset at an instant from the platform's own zone rules
  (`TimeZone.getOffset` on Android, `NSTimeZone.secondsFromGMTForDate` on iOS), and each end uses the offset in force at
  its own midnight, so "March" asked in April ends at April's midnight, not March's. No date library is needed.
- **Relax.** `Retriever.parsedSearch` merges the parsed kinds with the screen's kind chip, searches, and if the filters
  found nothing, searches again with the chip's kinds only and no dates, marking the result `relaxed`.
  `ParsedQuery.relaxedNote(scope)` and `missing()` build the sentence, for example "No PDFs from last week, so here are
  all your PDFs."
- **Literal mode.** `literal = true` skips the parser. The X on the understood line sets it for the current words, and
  typing different words clears it.
- **Calendar arithmetic** uses Howard Hinnant's civil-date algorithms (public domain) instead of a date library, so the
  shared code stays free of a platform date dependency.

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/QueryParser.kt`: `QueryParser`, `ParsedQuery`
- `core/src/commonMain/kotlin/app/retriever/core/Retriever.kt`: `parsedSearch`, `search`, `searchNames`, `browse`
- `core/src/commonMain/kotlin/app/retriever/core/SearchEngine.kt`: `SearchResult.parsed`, `SearchResult.relaxed`
- Screens: `androidApp/src/main/kotlin/app/retriever/search/SearchResults.kt` and `iosApp/Retriever/Views/SearchView.swift` (understood line, relaxed note)

## Invariants

1. **Pure.** Same text, same `now` and same offset function give the same `ParsedQuery`; nothing reads a clock or a store.
2. **A word is removed from the text only when it was understood.** The leftover is the user's words, in order.
3. **One date phrase per query;** a second one stays in the text.
4. **A singular kind word before another word stays a word.**
5. **The relaxed search keeps the screen's own filters** (kind chip, starred scope) and drops only what the parser
   added.
6. **Filters with no words browse.** Text left empty with a filter set lists that filter's files, newest first;
   no words and no filter returns nothing.
7. **`vectorLane` is true on that browse** (`search-engine.md`, invariant 7), so it is final.

## Tests

`QueryParserTest` (commonTest): `screenshotsFromLastWeek`, `pdfMarch`, `photosWithYear`, `yearNextToInOrKindOnly`,
`nameWithYearStaysAName`, `yesterdayAndToday`, `hindiDateWords`, `relativeAndExplicitDates`,
`namedDateWithoutYearIsTheMostRecentPastDay`, `nothingToParse`, `aMonthOrKindInsideAPhraseStaysAWord`,
`relaxedNoteSaysWhatWasDroppedAndWhatWasKept`, `localMidnightsUseTheZoneOffset`,
`aRangeAcrossADaylightSavingChangeEndsAtLocalMidnights`.
Facade level: `FacadeW5Test.parsedFiltersApplyAndFallBackWhenTheyLeaveNothing` in `core/src/androidHostTest`. UI level: `SearchViewModelTest`
`whatWasUnderstoodCanBeSearchedAsTyped` and `hitsSayWhichWordsTheyAnswerAndClearingTheWordsDropsTheFilter`.

## Known limits

- One date phrase per query. "invoices from march and april" keeps "and april" in the text.
- Languages: English and Hindi date words and English kind words. Other languages search as plain words.
- Kind words are a fixed table; a new kind needs one line in `KINDS`.
- A bare month beside a kind word is read as a date ("pdf march"), so a file called "pdf march" is only found with the
  X (literal) or by dropping the filter through the relaxed retry.

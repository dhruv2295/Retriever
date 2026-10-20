# Reading contract

Core owns the counts and state decision (`ReadingProgress`, `ReadingPolicy`, `Store.countsNow` over `files.reach`). Home, Reading status,
setup and notifications use it on both platforms. Name updates run independently of content reading.

## Counts

Distinct accessible revisions partition into Read (F), Partly read (P), Waiting to read (Q), Name only (N), and Couldn't
read (E). Content read is F + P; library size is F + P + Q + N + E. Ordinal-zero name vectors and extraction attempts
never count as content. Q separates scheduled files from files outside the sample. Notifications label sample scope;
otherwise their counts cover the library and persist across batches.

Content is extracted text, OCR or classifier labels, or an embedded image; none of these needs the model. Partly read
includes extraction caps and long-file tails missing vectors beside embedded chunks, including exhausted tail retries.
Waiting for meaning (`meaningWaiting`, phase "Adding search by meaning") counts files with queued vectors and none yet:
read without the model, or cleared by a model change. It is a subset count like later passes, so C = F + P is unchanged,
and it is zero while the model is absent. Requeued first passes remain scheduled. Only queued
work prevents completion; exhausted passes show their limit. Revoked, deleted, excluded and revised content leaves the old count immediately.

## Schedule and state

New installs default to While charging, including in the foreground. Legacy preferences keep their saved choice, even
when the old default was omitted from JSON. Retuning never changes the schedule. Settings also offers Also on battery
and When I start it. Resume lifts the persistent pause and reapplies that schedule.

Read now persists a 15-minute deadline across workers and launches. Pause, power saving and heat end that session;
expiry returns to the saved schedule. Setup's Read now saves charging for subsequent automatic work; Settings' one-off
keeps the existing schedule. Not now persists Pause at a safe boundary and retains read content. Go to Home is navigation.

Decision precedence is manual pause, safety/schedule blocker, actual reading phase, scheduled wait, completion. Both
platforms stop at low battery (20%, with a recovery margin), power saving or heat. Android also waits while another app
uses the screen. iOS background starts remain subject to an OS grant. The existing measured vision fallback stays in
place. Reading rechecks between chunks, including keyword-only reading and the meaning pass, and reserves one tail
opportunity per eight first-pass files.

## Native surfaces

Setup and Home share three selection rows and one confirmation. Selection alone does nothing. Home's compact offer and
one-time name-search explanation have persisted dismissal. The library hero retains its status mascot; kind browsing
uses leading icons. Reading status has a fixed title and separate state, read, partial, waiting and failure counts, with
no library percentage. Its mascot walks for as long as reading runs (`settles = false`); Home's status row keeps the
shared walk-and-rest cadence. Its Reading now row stays while reading runs: between two files it keeps the last one
(name, kind and date update in place, the old picture stays until the new one is decoded), and before the first an empty
row holds the height. Settings' Reading group includes the schedule, one-off session, Pause/Resume, status and collapsed
help/setup check. Audio is name only on both platforms; video reading covers frames without sound.

Automatic iOS reading leaves the idle timer alone. Older iOS offers a screen-awake switch for an explicit session;
iOS 26 requests native continued processing on the starting action. The system's progress units are separate from the
usable-file subtitle for that session. Refusal is shown in-app.

## Notifications

Android asks once when scheduling or starting reading (`Words.notificationsAsk`; its sample sentence shows only while
the sample still reads, decided before the dialog opens). Declining never prevents reading. Progress is silent, private on
the lock screen, contains no file details, and has Pause reading and a Reading status destination. The service removes
it at the end. A persisted claim permits one usable-sample notification while hidden and permission is granted; opening
the app cancels it. iOS retains its system-owned continued-task UI without a second permission or local notification.

## Verification

`ReadingProgressTest`, `ReadingContractTest`, `BatchPolicyTest`, `TasteScopeTest`, `ProgressConsistencyTest`,
`NotificationsTest`, native Settings/Setup/Home/Status tests, and Swift copy/model/accessibility tests cover this contract.

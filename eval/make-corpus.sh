#!/bin/zsh
# Rebuilds eval/corpus from eval/src: screenshots, PDFs and docs are rendered, the photos are copied (synthetic fixtures; see README.md).
set -eu
cd "${0:A:h}"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
PROFILE=$(mktemp -d)
chrome() { "$CHROME" --headless=new --disable-gpu --hide-scrollbars --user-data-dir="$PROFILE" "$@" >/dev/null 2>&1; }
mkdir -p corpus/screenshots corpus/docs corpus/photos
cp src/photos/IMG_*.jpg corpus/photos/

# name-matching 'screenshot' makes FileRules.classify() return SCREENSHOT
i=0
for s in chat flight payment weather hindi-note error; do
  i=$((i + 1))
  chrome --force-device-scale-factor=1 --window-size=1179,2556 \
    --screenshot="$PWD/corpus/screenshots/Screenshot 2026-10-0$i at 10.1$i.0$i.png" "file://$PWD/src/$s.html"
done

pdf() { chrome --no-pdf-header-footer --print-to-pdf="$PWD/corpus/docs/$2" "file://$PWD/src/$1.html"; }
pdf invoice Document_01.pdf
pdf lease Document_02.pdf
pdf report Document_03.pdf
pdf hindi-letter Document_04.pdf

# Scan_0001.pdf: a picture of a page, no text layer
chrome --force-device-scale-factor=1 --window-size=1240,1754 --screenshot="$PROFILE/scan.png" "file://$PWD/src/scan.html"
sips -s format pdf "$PROFILE/scan.png" --out corpus/docs/Scan_0001.pdf >/dev/null

textutil -convert docx src/meeting.html -output corpus/docs/Doc_A.docx
textutil -convert docx src/recipe-hi.html -output corpus/docs/Doc_B.docx
cp src/Notes_C.txt corpus/docs/Notes_C.txt
rm -rf "$PROFILE"

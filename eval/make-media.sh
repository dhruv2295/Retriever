#!/bin/zsh
# Builds eval/corpus/media on this Mac with `say` and ffmpeg, no downloads: one video whose speech and pictures
# disagree, so a hit shows whether it came from what was said or what was shown, plus two voice recordings.
#   talk-and-show.mp4  0:00-0:20 a plain teal card (no words to read) while a voice explains fixing a bicycle puncture;
#                      0:20-0:40 the bicycle photo (IMG_1010) while a voice explains baking a birthday cake.
#   Voice memo.m4a     about 15 s on a dentist appointment.
#   Garden talk.m4a    about 5.5 min on growing tomatoes on a balcony (over the 300 s audio cap, so it is read in part).
set -eu
cd "${0:A:h}"
FF=/opt/homebrew/bin/ffmpeg
OUT=corpus/media
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT"
speak() { say -v Daniel -r 165 -o "$TMP/$1.aiff" "$2"; }

speak bike "Today I am fixing a flat tyre on my bicycle. First take the wheel off and let out the rest of the air. \
Use two tyre levers to pull the tyre off the rim, then find the puncture in the inner tube by pumping it up and \
listening for the hiss. Rough up the rubber, add a patch, wait a minute, then put the tube back and pump it up."
speak cake "Now let's bake a birthday cake. Cream the butter and sugar, beat in three eggs, then fold in the flour \
and a spoon of cocoa. Pour it into a round tin and bake for thirty five minutes. When it is cool, spread the \
chocolate icing on top and add the candles for the party."
speak memo "Note to self. My dentist appointment is next Tuesday at four in the afternoon. Bring the x-ray report \
and ask about the filling in the back tooth."
speak garden "Growing tomatoes on a balcony is easier than you think. Pick a large pot with holes at the bottom, \
fill it with good compost, and plant the seedling deep. Tomatoes love six hours of sun. Water every morning, \
tie the stem to a stick as it grows, and pinch off the small side shoots. Feed them once a week when the flowers come."

# Each 20 s segment: one still picture, its narration padded with silence to exactly 20 s. A keyframe every second
# (-g 25 at 25 fps) so frame grabs and the player's seek land close to the asked time.
seg() { # name, picture input args..., narration
  local name=$1 speech=$2; shift 2
  $FF -loglevel error -y "$@" -i "$TMP/$speech.aiff" -t 20 -r 25 \
    -vf "scale=720:720:force_original_aspect_ratio=decrease,pad=720:720:(ow-iw)/2:(oh-ih)/2,format=yuv420p" \
    -af "apad" -c:v libx264 -profile:v main -g 25 -c:a aac -ar 44100 -ac 1 -shortest "$TMP/$name.mp4"
}
seg a bike -f lavfi -i "color=c=0x2A7F7A:s=720x720:r=25"
seg b cake -loop 1 -framerate 25 -i src/photos/IMG_1010.jpg
printf "file '%s'\nfile '%s'\n" "$TMP/a.mp4" "$TMP/b.mp4" > "$TMP/list.txt"
$FF -loglevel error -y -f concat -safe 0 -i "$TMP/list.txt" -c copy -movflags +faststart "$OUT/talk-and-show.mp4"

$FF -loglevel error -y -i "$TMP/memo.aiff" -c:a aac -b:a 64k -ar 44100 -ac 1 "$OUT/Voice memo.m4a"
$FF -loglevel error -y -stream_loop -1 -i "$TMP/garden.aiff" -t 330 -c:a aac -b:a 64k -ar 44100 -ac 1 "$OUT/Garden talk.m4a"

for f in "$OUT"/*; do
  printf '%s  %ss\n' "${f:t}" "$($FF -i "$f" 2>&1 | sed -n 's/.*Duration: \([0-9:.]*\).*/\1/p')"
done

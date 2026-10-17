#!/bin/bash
# Archives the R8 mapping and tags the release. Run after scripts/verify.sh (it builds the release APK).
# Fails unless versionCode and CURRENT_PROJECT_VERSION were bumped together since the last tag.
# Does not push.
set -euo pipefail

source "$(dirname "$0")/verify.sh"

main_release() {
  cd "$ROOT"
  local name code cpv tag dest prev prev_code prev_cpv mapping="androidApp/build/outputs/mapping/release/mapping.txt"
  name="$(version_name)"; code="$(version_code)"; cpv="$(xcconfig_value CURRENT_PROJECT_VERSION)"
  check_versions
  [ "$code" = "$cpv" ] || { fail "versionCode $code != CURRENT_PROJECT_VERSION $cpv"; return 1; }
  [ -z "$(git status --porcelain --untracked-files=no)" ] || { fail "working tree has uncommitted changes"; return 1; }
  [ -f "$mapping" ] || { fail "no $mapping, run scripts/verify.sh first"; return 1; }

  tag="v$name-$code"
  git rev-parse -q --verify "refs/tags/$tag" >/dev/null && { fail "tag $tag already exists"; return 1; }
  prev="$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
  if [ -n "$prev" ]; then
    prev_code="$(git show "$prev:androidApp/build.gradle.kts" | sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p')"
    prev_cpv="$(git show "$prev:iosApp/Configuration/Config.xcconfig" | sed -nE 's/^CURRENT_PROJECT_VERSION[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p')"
    { [ "$code" -gt "$prev_code" ] && [ "$cpv" -gt "$prev_cpv" ]; } \
      || { fail "bump versionCode and CURRENT_PROJECT_VERSION together (last $prev: $prev_code / $prev_cpv, now $code / $cpv)"; return 1; }
  fi

  dest="$HOME/Retriever-releases/$name-$code"
  mkdir -p "$dest"
  cp "$mapping" "$dest/mapping.txt"
  git tag -a "$tag" -m "Retriever $name ($code)"
  echo "release: tagged $tag, mapping.txt in $dest (push with: git push origin $tag)"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main_release "$@"; fi

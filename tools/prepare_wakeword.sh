#!/usr/bin/env bash
# Fetch pinned, independently hash-verified build inputs; do not commit binaries.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs app/src/main/assets/pocketsphinx/ru
fetch() {
  local repo="$1" blob="$2" expected="$3" destination="$4" tmp="${4}.part"
  if [[ -s "$destination" ]] && echo "$expected  $destination" | sha256sum -c --status; then return; fi
  rm -f "$tmp"
  echo "Downloading $(basename "$destination")"
  gh api -H 'Accept: application/vnd.github.raw+json' "repos/$repo/git/blobs/$blob" > "$tmp"
  echo "$expected  $tmp" | sha256sum -c --status || { rm -f "$tmp"; echo "Checksum mismatch: $destination" >&2; exit 1; }
  mv "$tmp" "$destination"
}
fetch cmusphinx/pocketsphinx-android-demo 745ebd6f70ba101a80bd5c90d1094284e803044c 817fad50d1f29c202a3eb790c0005cca04484a39ad041aff9819086de8d9a69b app/libs/pocketsphinx.aar
model=app/src/main/assets/pocketsphinx/ru
repo=synesthesiam/ru_pocketsphinx-cmu
fetch "$repo" a72f39b72d921e0af8b8191c7767c9c9105fb4f0 1f2f06f7a7400bc3a890d1944b9c3b5d768c8925a5040f43ea550ad40daf9752 "$model/feat.params"
fetch "$repo" 87594c2fe769b2a93eefceedcdb695e80c29d01c 46fbfd07e2cc07c852021a86b4008afe42d5acd1f0b62ef06345f21130a5d9b9 "$model/feature_transform"
fetch "$repo" e158959c98213a3ee6903f039ba7d449c816d161 d86194aba8a4956efcb36d879f4dca24fc8e1aefb9f2735182b7b40453d441a4 "$model/mdef"
fetch "$repo" 292afbee06210df0db7e9c294e124040298bd8cb f747d6027430d59c185be21e6ef658da6e05d186c22221682e26e69a38d9ad65 "$model/means"
fetch "$repo" bbee1b44abb50635c3cf72cbde16d312e6a3f98a 9b73e3e6ba4ead3243b0970fea4ab4a947a9cc73365382c775c9fdcbfad25e5f "$model/mixture_weights"
fetch "$repo" eb36d33387bbacd7d33837584db995b7a467217f af281d8c53f0a24ace7193e014dcb876e4e517f96b34791d5cd3e71fc69fc83b "$model/noisedict"
fetch "$repo" c6d2281a34446300d8d9b6ccf7ab74c0a7b8fb07 b8d5dd0aa55e765f715ecf218060ab4c8f921220d0ba0f6ab851bc0d06725348 "$model/transition_matrices"
fetch "$repo" 7cbb035c6a53322920d640154e7ba697494096f5 294fa8bf88f6371d490cbcea3f6eef49e18d6f6cbb18db69e125e54054596926 "$model/variances"
echo 'Wake-word inputs verified.'

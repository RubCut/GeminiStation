#!/usr/bin/env bash
# Download pinned open-source tools and training-only voices; no credentials are stored.
set -euo pipefail
WORK="${GEMINI_MWW_WORK:-/home/user/.cache/gemini-mww}"
LIB="$WORK/lib"
mkdir -p "$LIB" "$WORK/trainer" "$WORK/voices"
export OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=1
python3 -m pip install --target "$LIB" tensorflow-cpu==2.16.2 numpy==1.26.4 scipy==1.12.0 pymicro-features==2.0.2 piper-tts==1.3.0 PyYAML==6.0.2 tqdm==4.67.1 'h5py>=3.10'
python3 -m pip install --target "$LIB" --no-deps 'git+https://github.com/kahrendt/microWakeWord.git@a70bd740d4e79ee8a8bb3db843fe862b88d5d6b0'
fetch_blob() {
  local repo="$1" sha="$2" dest="$3" tmp
  tmp="${dest}.part"
  gh api -H 'Accept: application/vnd.github.raw+json' "repos/$repo/git/blobs/$sha" > "$tmp"
  test "$(git hash-object "$tmp")" = "$sha" || { echo "Git blob mismatch: $dest" >&2; rm -f "$tmp"; return 1; }
  mv "$tmp" "$dest"
}
# The upstream wheel omits layers because those directories lack __init__.py.
mkdir -p "$LIB/microwakeword/layers"
while IFS=$'\t' read -r path sha; do
  fetch_blob kahrendt/microWakeWord "$sha" "$LIB/microwakeword/layers/$(basename "$path")"
done < <(gh api 'repos/kahrendt/microWakeWord/git/trees/a70bd740d4e79ee8a8bb3db843fe862b88d5d6b0?recursive=1' --jq '.tree[] | select(.path | startswith("microwakeword/layers/")) | [.path,.sha] | @tsv')
touch "$LIB/microwakeword/layers/__init__.py"
TRAINER_SHA=$(gh api 'repos/interkelstar/microwakeword-trainer/contents/train_mww.py?ref=8fdbadc3b969a8c1993108c0d655910440dfb762' --jq .sha)
fetch_blob interkelstar/microwakeword-trainer "$TRAINER_SHA" "$WORK/trainer/train_mww.py"
# Limit RAM and thread pressure. The upstream generator still alternates classes.
python3 - "$WORK/trainer/train_mww.py" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
for old, new in {'.shuffle(10_000)': '.shuffle(256)', '.map(spec_augment, num_parallel_calls=tf.data.AUTOTUNE)': '.map(spec_augment, num_parallel_calls=1)', '.prefetch(tf.data.AUTOTUNE)': '.prefetch(1)'}.items():
    if s.count(old) != 1:
        raise RuntimeError('Unexpected upstream trainer: ' + old)
    s = s.replace(old, new)
open(p, 'w', encoding='utf-8').write(s)
PY
fetch_blob LazyFoxGame/Den-AI-Assistant 850fd015f0c142cff0e0e5db191ef0aa88f6fb3e "$WORK/voices/ru_RU-dmitri-medium.onnx"
fetch_blob LazyFoxGame/Den-AI-Assistant 89ff93dea9003ae8a364d48460c660c4f2a1ef01 "$WORK/voices/ru_RU-dmitri-medium.onnx.json"
fetch_blob Samidjon/CareMate bff2b025e7e5b36a610fe54eeb0aac0e28bf71a0 "$WORK/voices/ru_RU-irina-medium.onnx"
fetch_blob Samidjon/CareMate 7b61766dc87a3b419855f7980e6ab14d61166412 "$WORK/voices/ru_RU-irina-medium.onnx.json"
printf '%s  %s\n' f073356ebc4bd0f80c5af58df2953a5988bd5bdab1eb38635ce960b071fbefcb "$WORK/voices/ru_RU-dmitri-medium.onnx" 8ff38212d23da300bbe3705c645e6e5b9475f0bfde01558eb17813e22acaaaaa "$WORK/voices/ru_RU-irina-medium.onnx" | sha256sum --check
printf 'Ready. Run with PYTHONPATH=%s python3 -u experiments/microwakeword/run_experiment.py\n' "$LIB"

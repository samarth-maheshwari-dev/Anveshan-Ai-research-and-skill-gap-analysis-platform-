#!/usr/bin/env bash
# Downloads the pinned embedding model (intfloat/multilingual-e5-small, MIT) for local
# runs and tests. Files are verified by SHA-256 and are NOT committed (gitignored).
# The Docker image fetches the same files itself (see Dockerfile, keep pins in sync).
#
#   bash apps/api/scripts/fetch-model.sh
set -euo pipefail

REV=614241f622f53c4eeff9890bdc4f31cfecc418b3
BASE="https://huggingface.co/intfloat/multilingual-e5-small/resolve/${REV}/onnx"
# Outside the repo on purpose (the repo may sit in a synced folder). Same default as
# `anveshan.embeddings.model-dir` in application.yml; override with ANV_EMBEDDINGS_MODEL_DIR.
DIR="${ANV_EMBEDDINGS_MODEL_DIR:-$HOME/.cache/anveshan/models/multilingual-e5-small}"

files=(
  "model.onnx ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665"
  "tokenizer.json 0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
)

mkdir -p "$DIR"
for entry in "${files[@]}"; do
  set -- $entry
  name=$1 sha=$2 target="$DIR/$1"
  if [[ -f "$target" ]] && echo "$sha  $target" | sha256sum -c --status; then
    echo "ok (cached)  $name"
    continue
  fi
  echo "downloading  $name"
  curl -fL --retry 3 -o "$target.part" "$BASE/$name"
  echo "$sha  $target.part" | sha256sum -c --status || { echo "SHA-256 mismatch for $name" >&2; rm -f "$target.part"; exit 1; }
  mv "$target.part" "$target"
  echo "ok           $name"
done

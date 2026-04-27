#!/usr/bin/env bash
set -e

ROOT="$(git rev-parse --show-toplevel)"
SRC="$ROOT/scripts/hooks"
DST="$ROOT/.git/hooks"

for hook in pre-commit pre-push; do
  install -m 0755 "$SRC/$hook" "$DST/$hook"
  echo "installed: $DST/$hook"
done

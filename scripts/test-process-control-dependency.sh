#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/test-owned-process-api.sh
mkdir -p build/test-classes
stock="$(cut -d: -f1 build/compile-classpath.txt)"
cp="build/classes:$stock:build/test-classes"
javac --release 8 -cp "$cp" -d build/test-classes tests/org/opensagetv/vibe/ffmpeg/StockDvdStreamServiceTest.java
temporary="$(mktemp -d /tmp/vibe-process-control.XXXXXX)"
resolved="$(realpath "$temporary")"
[[ "$resolved" == /tmp/vibe-process-control.?????? && "$resolved" == "$temporary" ]] || exit 2
trap 'rm -r -- "$resolved"' EXIT
java -cp "$cp" org.opensagetv.vibe.ffmpeg.StockDvdStreamServiceTest "$temporary/stock-only"

#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIM_ROOT="${MIM_ROOT:-/project}"
CP="$(cat "$ROOT/build/compile-classpath.txt")"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
javac --release 8 -cp "$ROOT/build/classes:$CP" -d "$ROOT/build/test-classes" "$ROOT/tests/org/opensagetv/vibe/ffmpeg/DirectOutputRuntimeTest.java"
java -cp "$ROOT/build/classes:$CP:$ROOT/build/test-classes" org.opensagetv.vibe.ffmpeg.DirectOutputRuntimeTest "$scratch" "$MIM_ROOT/output/linux-x64"

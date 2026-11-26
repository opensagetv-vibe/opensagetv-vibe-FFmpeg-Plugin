#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/process-api-test-classes
javac --release 8 -d build/process-api-test-classes \
  src/main/java/org/opensagetv/vibe/ffmpeg/OwnedProcessStop.java \
  src/main/java/org/opensagetv/vibe/ffmpeg/OwnedProcessControl.java \
  src/main/java/org/opensagetv/vibe/ffmpeg/WindowsJobProcessStop.java \
  src/main/java/org/opensagetv/vibe/ffmpeg/RuntimePaths.java \
  tests/processapi/ExternalOwnedProcessControlSmoke.java \
  tests/processapi/WindowsJobProcessStopTest.java
java -cp build/process-api-test-classes org.opensagetv.processapitest.ExternalOwnedProcessControlSmoke
java -cp build/process-api-test-classes org.opensagetv.vibe.ffmpeg.WindowsJobProcessStopTest

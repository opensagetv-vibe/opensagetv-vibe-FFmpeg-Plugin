# Common project workflow

SETTINGS-005: validate immutable hardwareBackend, negotiate the runtime revision
and verify actual matching hardware/encoder before returning a pinned session.
Never change authoritative INI to satisfy a single client. Run Direct output
and old-wrapper negative tests, native backend scope tests, then the explicit
`scripts/commission-hardware-backend.py --run-stock232` owned API gate while
independently idle. It checks QSV/VAAPI Legacy/responsive output, seek and quality
prepare/abort using real segments, not browser rendering or hardware menu intent.
Follow normal manager installation, protected-stock hashes and independent
activity guards; use affected Non-Pro playback gates for client certification.

FIXED-002 normal-manager232 update: FFmpeg is not in MCP's third-party
plugin.commission_install allowlist. Do not bypass that refusal with arbitrary
server expressions. Refresh metadata through existing companion.available
refresh=true, then use the actual stock UI: Setup / SageTV Plugins / Installed
Plugins / OpenSageTV Vibe FFmpeg Plugin / Update Plugin. Review the selected
row before activating it; never Reset/Uninstall. Choose Restart Later, exit
the owned installer client, independently wait for recording/UI/client idle,
then use the guarded232 restart helper. A retiring UI may exceed25s; wait for
real public idle proof rather than changing its count or bypassing the guard.
Verify installed wrapper/classes/stock hashes before affected consumer tests.

ADAPT-001 test control uses MCP-ADAPT-TEST-001, never a shared INI bitrate edit.
Match testSessionId from the owned client's buffer response to the provider's
session list, then use confirm + expected revision and a 15..300s lease. Preserve
min/max, pause/readiness and two-handoff safeguards. Readback overrideKbps is a
request, observedKbps is the latest encoder target reported by its owner; neither
is independent output measurement or video proof. Clear with fresh revision in
finally; expiry backs up cleanup. No automatic retry of an uncertain mutation.

The stock232 Java-only follow-up uses scripts/stage-adaptive-java.py through
the reusable container's /opt/opensagetv-vibe/web-python/bin/python (its
commissioning SSH dependency lives there, not in bare system python3). Stage
only after focused provider tests and idle/stock guards. It changes only the
normal developer-catalog JAR input, preserving native packages/dependencies;
SageTV's ordinary plugin manager still installs it. Close the owned installer
UI, independently observe actual retirement, then use the guarded stock232
restart helper; a still-retiring context is not idle proof.

## Current stock-only server boundary

`.232` now uses protected official stock9.2.17. Earlier non-stock/Core-change
exceptions below are superseded. Keep original Sage.jar/native Core/FFmpeg;
deliver separate MIM/runtime upgrades only through SageTV's plugin manager,
preserve INI/settings, and verify protected hashes plus fresh restart activity.

## Task fix and server-boundary policy

Apply this policy to every task workflow, including dependency fixes across
Vibe repositories. Fix and test necessary plugins and update the test-server
plugin without asking again solely for repository-boundary approval.

Prefer the Android client, then a stock-compatible plugin. Change non-stock
`.232` Core only for a proven production defect that neither can correct;
document the API gap and alternatives, keep optional negotiation and safe
stock/older-client fallback, and run affected compatibility tests. Never patch
Core merely to simplify testing.

Stock `.175` installation changes are limited to plugin installation/update.
Do not modify its stock Sage.jar, stock FFmpeg, Core binaries, or server
installation/configuration files. Preserve user settings, recordings and
unrelated clients; reversible supported SageTV playback APIs remain allowed.

Non-stock `.232` restarts are authorized for task updates without asking
again; coordinate them with active test guards and preserve data/settings.
  Always ask the user before restarting stock `.175`, even when it appears idle,
  unless an explicit user-granted bounded restart window is active. Record
  its UTC expiry in the task/handoff, and check expiry and revocation before
  every restart. After expiry or revocation, ask again; stock files stay protected.

Update owning TASKS.md, linked dependencies and the workspace suggested order
as work changes; move completed checkoffs into the checklist change ledger.
Test only affected gates, preserve unrelated completed matrices, and do not
stop independent authorized work for a status question or a dependency-only
permission request. Unrelated work, publication, destructive actions and
interruption of recordings/other users still require their own authority.

Run `dev.cmd` or `./dev.sh` with `test`, `validate`, `build`, `install`, or
`all`. Every command delegates to the one sibling
`opensagetv-vibe-build-env` image/container. `install` only reports the
artifact boundary; physical SageTV commissioning is an explicit guarded step.

Serialize build/test/package operations within this repository: each build
recreates the shared `build/` tree. Running package/build while Java tests
are executing can remove their unloaded classes and cause a false
NoClassDefFoundError. Independent projects may build concurrently; read-only
checks are safe. After an overlap, discard the invalid run and rerun serially.

When a gate proves a plugin defect needed for the active user task, fix and
test it, then update the test-server plugin as part of that task without asking
again solely for cross-repository permission. Preserve settings and unrelated
completed gates. On stock `.175`, only plugin installation/update is allowed;
never modify stock Core, Sage.jar, stock FFmpeg or server installation/config
files. This standing rule does not authorize unrelated publication, destructive
cleanup or interruption of recordings/other users.

The build requires an unmodified stock `Sage.jar` through `SAGETV_JAR` or the
ignored `.deps/stock/Sage.jar`. Compile-only API classes must never be packaged.
The plugin runtime is produced separately by `opensagetv-vibe-ffmpeg-mim` and
is installed under `plugins/SageTVFFmpegPlugin/runtime/`.

The reusable development container mounts that runtime repository at `/project`,
not `/workspace/opensagetv-vibe-ffmpeg-mim`. For packaging in that container,
set `MIM_REPO_ROOT=/project`; do not duplicate runtime archives to compensate
for a guessed sibling path. Check mounted paths before a resumed package build.

Do not publish a plugin manifest, runtime archive, GitHub repository, or
release until all stock-server and physical-device gates pass and the user
gives explicit final approval.

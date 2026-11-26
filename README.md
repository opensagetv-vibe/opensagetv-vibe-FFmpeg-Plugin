# OpenSageTV Vibe FFmpeg Plugin

Development SETTINGS-005 adds optional Direct `hardwareBackendRevision=1`,
advertised only with a supporting MIM runtime. Immutable `hardwareBackend`
requests preserve Auto/legacy defaults or pin one server accelerator without
editing shared INI. The service verifies the exact observed hardware backend
and encoder before accepting a pinned stream; seek and quality retain the same
options. Unsupported explicit choices refuse, never silently become another
backend or original Pull. Copy/Software-only conflicting API requests reject.
Android and Web use consistent accelerator names; their transports and Web's
existing explicit Allow Fallback control remain distinct.

Development0.1.36 shares one buffer-only adjustment controller between Android
and Web. Clients send startup min/max and report their buffer; encoder ownership,
readiness, cooldowns and handoff limits stay enforced. Optional MCP commissioning
can override one adjusted session within its range for 15..300 seconds. It holds
automatic decisions until clear/expiry and emits a target once through the
existing rate/handoff path. Accepted override, observed encoder target and
measured output/playback are separate evidence. Nothing changes shared INI or
stock Sage.jar/FFmpeg; missing capability retains ordinary Fixed playback.

Repository: https://github.com/opensagetv-vibe/opensagetv-vibe-FFmpeg-Plugin

Development MIM-OUTPUT-001 adds optional Direct outputSettingsRevision1 and
native encoderPolicyRevision1. A request may select server default, prefer
hardware, software only or hardware only, plus supported codec/size/FPS/audio
options. Explicit unavailable hardware never silently becomes software. Options
are session-owned, survive seek/quality replacement and do not edit the live
INI. Source audio is detected/validated; requested targets remain separate from
observed output. Current delivery is MPEG-TS segments; MKV/DVD refuse explicitly.
Per-client original-stock routing is deferred MIM-STOCK-001: existing legacy
launcher clients retain behavior, not a global engine switch. See TASKS.md and
artifacts/results/MIM-OUTPUT-001/RESULT.md for scoped qualification/limitations.

The repository was renamed; installed SageTVFFmpegPlugin IDs, JAR names and
configuration paths are unchanged. Existing releases remain available.

This repository owns the SageTV integration, settings and install manifests.
The separate [FFmpeg/MIM runtime project](https://github.com/opensagetv-vibe/opensagetv-vibe-ffmpeg-mim)
builds its runtime dependencies; packaging FFmpeg as a plugin does not replace
that build project. Their versions can advance independently.

OpenSageTV Vibe SageTV Standard plugin and companion STVi that utilizes the separate `opensagetv-vibe/opensagetv-vibe-ffmpeg-mim` FFmpeg runtime.

User-facing plugin name: **OpenSageTV Vibe FFmpeg Plugin**.

## Non-negotiable architecture

- No changes to stock `Sage.jar`.
- Never replace or patch SageTV's stock `ffmpeg`.
- MIM runtime stays owned/released by `opensagetv-vibe-ffmpeg-mim`.
- `ffmpeg.real.ini` remains the authoritative configuration and is user-editable.
- The plugin edits individual INI keys while preserving comments/order/unknown settings.
- The plugin installs/repairs a small `SageTVTranscoder` bridge because stock SageTV already prefers that executable before its `ffmpeg` fallback.
- The STVi talks to the server-side Standard plugin; it never executes MIM locally on a remote UI.
- Updated Core builds may discover the optional, provider-neutral DVD stream
  transform through Java `ServiceLoader`. The plugin, not Core, owns all MIM
  capability probing and process management. Stock Core never loads the absent
  SPI and continues to use the same Standard plugin normally.

## Current implementation status

Development0.1.35 pairs MIM0.4.20's Windows forced-stop caption lease and retains
MIM0.4.19's pipe/device corrections plus
optional Java8 Windows process ownership. The shared API registers only an
exact Process launched through the byte-verified native kill-on-close Job
launcher; unrelated Processes are refused. Java9 public-handle behavior is
unchanged. Stock Java/Core/FFmpeg and existing INI are preserved. See runtime
MIM-WIN-001 for actual Windows185 acceptance; no publication is implied.

Development0.1.32 pairs MIM0.4.17's ordinary Fixed Linux H.264 QSV
caption-safe encode queue. It corrects the reproduced Intel async-worker
crash without disabling captions/GPU or changing requested bitrate/VBV.
Normal-manager stock232 install and Non-Pro Media3/Legacy Exo repeated-seek,
pause/resume/EOF gates pass. Authoritative INI, engines and stock20 remain
unchanged; Auto remains the default preference. See sibling runtime
`artifacts/results/MIM-QSV-001/RESULT.md`; not universal vendor certification.

Development0.1.30 / MIM0.4.16 adds optional Linux-QSV same-process bitrate
control. Enable the plugin's **Allow In-Place Bitrate Control** and the client's
separate session preference; both default off. Unsupported encoders/platforms
keep existing playback/handoff. No rendition ladder or stock Core change.
See [the shared contract](docs/DIRECT_SESSION_CONTRACT.md).

**Hardware Test → BITRATE TEST** (also in generic plugin configuration) runs
a bounded synthetic encoder test and saves measured results under the plugin's
`reports/Vibe-Bitrate-Test-latest.json`. Actual stock232 test passed:
6.09→2.02→2.47Mbps for6→2→2.5Mbps targets, same process/clean60fps decode.
It refuses active playback/recordings/unknown activity and preserves user INI.
This tests software-source/QSV-encode only; Windows/NVENC are not qualified.
Web/Android use the same optional diagnostic rather than duplicating it.

Development0.1.28 pairs MIM0.4.15 for Android's optional responsive HLS
profile. New capability negotiation protects older wrappers/callers; only
explicit HLS Transcode changes segment cadence, bounded completed-source
startup read-ahead and QSV IDR handling. Existing Direct/Copy/Fixed, subtitles,
INI and stock Core remain unchanged. See [the contract](docs/DIRECT_SESSION_CONTRACT.md).
Candidate packages are local, not installed/published or physically certified.

The later HLS-BUFFER-001 development contract optionally negotiates a bounded
completed-file forward reserve (`hlsBufferRevision=1`, `bufferAheadMs`0..20000).
Old callers keep the six-second burst; live/unknown writers and steady pacing
are unchanged. The Java-only update is manager-installed on stock232 and
qualified with Android Non-Pro HLS delegates; reserve reaches19.806s against20s.
See `artifacts/results/HLS-BUFFER-001/RESULT.md` for exact scope and the shared
contract for request/response rules. No Core, native wrapper, INI or175 change;
Pro deployment remains user-deferred, and this is not a published release.

Implemented and commissioned:

- Java 8-compatible Standard plugin source.
- Line-preserving INI editor with `.bak` and atomic replacement.
- First-run creation of `ffmpeg.real.ini` from `ffmpeg.real.ini.default` without overwriting user config on upgrades.
- MIM `--mim-status` and `--mim-capabilities` query/cache layer.
- Virtual status/capability values for the STVi.
- ABI-neutral POSIX `SageTVTranscoder` bridge for Linux and a native Windows
  bridge source.
- Startup launcher health/repair logic.
- Linux/Windows plugin manifest templates.
- Stock SageTV7/SageTV9 STVi setup screen, status views, common settings,
  launcher repair, reload, and restore-default confirmation actions.
- Deterministic release packages, rendered Linux/Windows/STVi plugin manifests,
  SHA-256 checksums, and the common Vibe update/handoff workflow.
- Hardware capability/status reporting from MIM with safe software fallback.
- Explicit, synthetic hardware testing that independently reports decode,
  hardware filtering, encode, full-pipeline, and known compatibility-fallback
  results without reading user media or changing `ffmpeg.real.ini`.
- Credential-free JSON report export to
  `plugins/SageTVFFmpegPlugin/reports/`, including a timestamped report and
  `OpenSageTV-Vibe-Hardware-Test-latest.json`.
- Install, upgrade, user-INI preservation, root-launcher repair, uninstall, and
  stock-FFmpeg fallback validation on an unmodified SageTV server.
- Separate FFmpeg/MIM patch/merge instructions in the handoff package.
- Optional `dvd_mpegts_v1` DVD transform provider for updated Core, with
  native-DVD fallback when the provider is absent or fails.
- Tokenless LAN-scoped caption/session API with SageTV source authorization
  and opaque bounded handles; no API credential is stored in the STVi.
- Explicit MIM Direct Copy and Direct Transcode sessions. Copy performs no
  video/audio decode or encode. Transcode reports the actual full-GPU, mixed,
  or software fallback stage.
- Require MIM's explicit ownedDirectStreams feature before Direct negotiation.
  MPEG-2-less Android clients can additionally negotiate bounded exact-source
  stock-API fresh Watch/video/Seek recovery for startup failure. Older/missing
  support safely retains ordinary Fixed; MCP is not a playback dependency.
- Bounded live MPEG-TS segmentation that preserves CEA, Teletext, DVB bitmap,
  language, and timing metadata for Android-local caption rendering.

The `.232` commissioning server and non-Pro Fire TV validation passed recorded
and live Fixed/MIM playback through VAAPI (`h264_vaapi`), including real
video/audio output and HDMI continuity. Version0.1.5 is published as a beta,
paired with MIM0.4.11. Catalog update PR127 is submitted for upstream review;
submission is not immediate plugin-manager availability.

## Hardware test and report export

In the plugin configuration, select **Run Hardware Test**. The server creates
a one-second synthetic MPEG-2 fixture and tests the same MPEG-2-to-H.264 stages
used by Fixed/DVD transcoding. It removes the fixture after the bounded test,
does not inspect recordings, and does not change the selected backend.
The plugin refuses to start the test while a MIM transcode job is active.

Select **Export Hardware Report** after the test. The plugin writes a
credential-free JSON report under `plugins/SageTVFFmpegPlugin/reports` and
shows the exact timestamped path in **Exported Hardware Report**. The stable
`OpenSageTV-Vibe-Hardware-Test-latest.json` copy is overwritten only when the
user explicitly exports another completed result.

## Build

### Development bounded Direct handoff (0.1.23; stock232 qualification)

Candidate23 also advertises `mimDirect.sourceProbeCacheRevision=1`. Nonzero
Direct starts/seeks may reuse a duration only when the public server API says
the source is not recording and both source and ffprobe identities remain
unchanged (regular file, file key, size, modification and creation times).
The in-memory cache holds at most32 entries. Unknown/live writers, missing
identity and failed probes retain the original bounded probe path; mutation
during a probe cannot establish a seek bound. There is no disk cache, TTL,
background scan or change to encoder/segment/caption policy. Session status
reports only the closed `durationProbeCache` disposition, not source identity.
Local mutation, replacement, LRU and concurrent lifecycle tests pass.23 is
normal-manager loaded on stock Linux232; real API reuse/clamp and affected
Non-Pro Media3/legacy seek/pause/content/caption checks pass. This is not a
Windows/all-device qualification or public release. See
[the shared contract](docs/DIRECT_SESSION_CONTRACT.md).

Latest user clarification allows temporary overlap, not a permanent rendition
ladder. The existing FFmpeg/MIM session owner advertises
`mimDirect.qualityHandoffRevision=1` separately from legacy quality revision1.
Only an opting-in client uses these POST operations:

- `qualityStartRevision=1` accepts `videoKbps=250..50000` on ordinary start;
  omitted retains6000. This initial request becomes the immutable ceiling.
  `qualityRecoveryRevision=1` permits a higher target only on handoff prepare
  with `recovery=true`, at most25% above current and never above that ceiling.
  Both directions share60s cooldown/two total attempts; seek preserves budget
  and ceiling. Legacy `/quality` remains strictly reduction-only. This is not
  a certified WAN adaptation result. GPU execution requires exact wrapper PID;
  same-input/latest-job matching or missing PID is not hardware evidence.

- `/v1/direct/quality/prepare?token=<old>&startMs=<absolute-ms>&videoKbps=<lower>`
  prepares a replacement and returns its ordinary session/caption/media handles.
  The original remains active. At most one pending handoff across the service,
 45s including startup, within the existing four-session admission limit.
- `/v1/direct/quality/commit?token=<old>&replacementToken=<new>&presentedMs=<absolute-ms>`
  confirms the exact replacement was presented. Only then retire the old
  producer, keeping its published files for the normal15s grace. Exact commit
  retries are idempotent. Playlist readiness alone is not presentation.
- `/v1/direct/quality/abort?token=<old>&replacementToken=<new>` cancels only the
  still-pending candidate. Expiry/preparation failure also preserves original.
  User seek/STOP supersedes a pending handoff; stale acknowledgements cannot
  retire a new source. Failed attempts consume the existing cooldown/budget.
- Optional `qualitySeekRevision=1` (20+) adds POST
  `/v1/direct/quality/seek?token=<old>&replacementToken=<new>&startMs=<absolute-ms>`.
  Resolve pending/committed/expired exact lineage atomically for a user seek;
  never guess which token won or replay an ordered seek after ambiguity.

21 corrects a true MiniClient active-file hint only when public SageTV
`IsFileCurrentlyRecording` returns Boolean.FALSE for the authorized source.
Unknown/actual writers retain follow behavior. New adaptive handoff on a real/
unknown writer is refused safely until its nonzero source-content position is
qualified; original playback and provider change budget remain unchanged.
Completed-file qualification must compare rendered content, not merely an API
offset or a client clock. No stock Core, MIM engine or shared INI edit is needed.

This is session-only Transcode with optional bounded recovery, not Copy/DVD, global INI edits,
two-way ramp certification or a new encoder manager in either client. Admission
limits cannot guarantee driver/GPU headroom: an encoder startup failure must
roll back safely. Clients without the advertised optional contract retain
their established paths; `/v1/direct/quality` revision1 below stays compatible.
Candidate service/HTTP tests are not Android/browser/physical presentation
qualification. Stock232 now has manager21; eight provider API rows pass with
synthetic ACK only. Non-Pro115 completed-file manual handoff/correct content/
captions/seek/pause passes actual renderer ACK in Media3/legacy/both GSY.
Measured-WAN, two-way/shared quality and remaining failure/device scope are
separate; do not claim seamless/all-device or writer adaptation from those rows.

Legacy Placeshifter instead sent `videorateadapt <delta-kbps>` to one running
patched encoder after measuring Push throughput/client buffering. MIM already
has compatibility code, but actual output-rate response must be verified per
encoder before claiming live bitrate support. It does not change resolution.

### Development Direct quality contract (0.1.18)

Clients must discover `mimDirect.sessionQualityRevision == 1` from
`GET /v1/capabilities` before using `POST /v1/direct/quality` with the exact
owned `token`, absolute `startMs` and lower `videoKbps`. Revision1 allows
Transcode only, minimum250kbps,60s cooldown and two attempted reductions per
source load. Copy, upward changes and retired tokens are refused. The reply
uses the existing Direct session schema with a new media/caption ownership
token, effective bitrate and change counter. Subsequent ordinary restart keeps
that effective quality. Missing/old providers retain existing client behavior.

This is one encoder/output, not simultaneous quality renditions. The current
producer must exit before its replacement starts; published old segments remain
readable for the normal handoff. A failed stop refuses another encoder. A
quality change may briefly interrupt playback; clients must not retry in a
loop or claim an accepted response is a presented frame. Shared INI/defaults
remain unchanged. Two-way ramping and Android consumer qualification remain
unfinished; native Push/Pull/DVD are not automatically adapted.

Explicit provider-only stock232 gate:
`py -3.12 scripts/commission-direct-quality.py --run-stock232`.
It requires the existing canonical imported fixture/ignored commissioning
configuration and independently idle recordings/clients; never run it as CI or
claim it is physical Android/visible-caption proof. It has no Web Client runtime
dependency, never accepts arbitrary source paths and retires only its own tokens.

Linux development build:

```bash
./scripts/build.sh
./scripts/package-dev.sh
```

Use a real stock SageTV jar for release compilation:

```bash
SAGETV_JAR=/path/to/Sage.jar ./scripts/build.sh
```

Release builds require `SAGETV_JAR` or `.deps/stock/Sage.jar`. Isolated source
checks may explicitly set `ALLOW_SAGETV_STUBS=true` to use the compile-only API
stubs under `tools/sagetv-stubs`; those stubs are never included in the plugin
JAR.

Windows launcher from the common Linux builder:

```bash
./scripts/build-windows-launcher.sh
```

## Runtime layout

```text
SAGE_HOME/
  Sage.jar                         stock, untouched
  ffmpeg                           stock, untouched
  SageTVTranscoder[.exe]           tiny plugin bridge
  plugins/
    SageTVFFmpegPlugin/
      launcher/
        SageTVTranscoder[.exe]     canonical repair copy
      runtime/
        ffmpeg_MIM[.exe]
        ffmpeg.real[.exe]
        ffprobe[.exe]
        ffmpeg.real.ini.default
        ffmpeg.real.ini            user-owned live config
        cache/
```

See `HANDOFF.md` for the exact Codex continuation and merge order.

## Standard workflow

Use the same location-independent commands as the other Vibe projects:

```text
dev.cmd test|validate|build|install|all
update.cmd
create_ai_handoff_zip.cmd
```

`install` is intentionally artifact-only. Physical SageTV installation is an
explicit commissioning action through SageTV's plugin manager.

## Windows plugin visibility

The Windows x64 Standard plugin and its STVi are beta plugins. In the stock
STV plugin manager, enable **Beta Versions: Show** and refresh the available
plugin list.

SageTV lists plugins from the aggregate `SageTVPluginsV9.xml` catalog, not
directly from individual manifests merged under `plugins/` in the catalog
repository. If a newly accepted plugin is still absent, verify that the
aggregate catalog contains `SageTVFFmpegPluginWinx64`. A temporary
`SageTVPluginsDev.xml` containing the Windows manifests can commission an
isolated server while waiting for the official aggregate catalog to refresh;
remove that temporary override after the official catalog contains the same
version.

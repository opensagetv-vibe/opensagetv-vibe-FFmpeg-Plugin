# Optional Direct playback contract

Development24; stock Linux232 qualification only where explicitly recorded.
This is the existing FFmpeg Plugin/MIM owner, not a new Web/Android service.
Neither client requires the other client, Client Extension or MCP for normal
playback. Keep stock Sage.jar, original FFmpeg and native Core unchanged.

## Negotiation and ownership

Shared buffer-policy recovery requires ten spaced, non-buffering observations,
at least5s reserve, at least75% of the observed high-water mark and no more than
1500ms cumulative loss across that window. Normal segment refill oscillation
must not reset it on every adjacent sample. Responsive HLS is intentionally
paced about6s ahead; requiring8s would make recovery impossible. Preserve the
existing cooldown, min/max, producer-health and two-handoff limit. No client
buffer/startup-burst increase or measured-bandwidth input is required for that
recovery controller. Optional forward-reserve negotiation below is independent.

Development0.1.28 / MIM0.4.15 adds `mimDirect.responsiveHlsRevision=1`
only when the installed wrapper advertises the same numeric capability.
An explicit Transcode start may then send `hlsProfile=responsive`. Omitted
keeps legacy behavior; unknown profile, Copy/profile combinations and an old
wrapper refuse the request without leaking private flags to FFmpeg.

The accepted response exposes `hlsProfile`, `segmentTargetMs`,
`startupBurstSeconds` and `startupReadyMs` (publication/probe wait, not displayed
video latency). Responsive uses one-second MPEG-TS segments, an equal-time
180-segment window, original optional subtitle mappings and forced keyframes.
Only MIM's actual H.264 QSV mapping adds `-forced_idr 1`; other encoders,
Copy, ordinary Fixed and legacy Direct are unchanged. The segment muxer is
retained because FFmpeg's HLS/WebVTT path cannot preserve DVB/Teletext PIDs.

By default, only independently proven completed recordings get a six-second initial read
burst; `-re` still bounds steady production. Real/unknown writers stay paced,
including when the client claims completed. FFmpeg documents the bounded
[initial read burst](https://ffmpeg.org/ffmpeg.html#Advanced-options).
Seek and quality candidates inherit the profile and recheck writer truth.
Readiness checks run every20ms for at most the first2s, then the existing50ms;
header-only/undecodable segments are still rejected. No input/keyframe/CPU
change is imposed on older clients or existing sessions.

HLS-BUFFER-001 adds optional numeric `mimDirect.hlsBufferRevision=1`. After
negotiating it, responsive HLS clients may send `bufferAheadMs` from0..20000
(non-responsive positive values and out-of-range values are rejected). Omitted
or0 preserves the six-second default. For independently confirmed completed
files only, a positive request selects `max(6, ceil(bufferAheadMs/1000)+3)`
seconds of initial burst; steady production remains paced. Responses expose
`bufferAheadTargetMs`; owned seek and quality candidates retain the intent and
independently recheck writer status. This is a bounded production allowance,
not a promise that the decoder or network can fill that reserve immediately.
No shared INI, original FFmpeg, native wrapper or stock Core change is needed.

Android explicitly starts this profile at the representation origin (or its
existing quality join position), never the HLS library's default live edge.
It shortens only the startup reserve to at most250ms, preserving configured
lower reserves, min/max/rebuffer bounds and native play/pause decisions.
Missing support uses legacy HLS, not a false responsive acceptance. These are
development automated/CPU-qualified features; physical GPU/Android/WAN and
Windows runtime acceptance are not claimed by the capability flag.

Read `/v1/capabilities` before opting in. Missing, disabled, old, incompatible
or failed optional APIs retain the established native stock playback route.
Ignore unknown additive fields; do not infer support from a version label.
An opaque `sessionToken` identifies one authorized source/load, not a file path
that a client can reuse for another source. Do not log tokens/media URLs.
POST operations are explicit ordered mutations; no automatic replay of start,
prepare or seek after a lost response. Read-only publication requests may be
retried inside their original deadline. Exact commit retries are idempotent.

| Optional capability | Request/result obligation |
| --- | --- |
| `sessionQualityRevision=1` | Legacy `/v1/direct/quality` remains reduction-only; do not silently give old callers a presentation-ACK obligation. |
| `qualityStartRevision=1` | Ordinary start accepts session-local `videoKbps=250..50000`; omitted keeps6000. This is an immutable source ceiling, never a shared INI edit. |
| `qualityHandoffRevision=1` | `quality/prepare` reserves one candidate; playlist/API readiness does not retire the original. |
| `qualityRecoveryRevision=1` | Explicit `recovery=true` permits at most25% increase, never above the initial ceiling. Same60s cooldown/two-attempt total budget in either direction. |
| `qualitySeekRevision=1` | `quality/seek` atomically resolves exact old/candidate lineage for a superseding seek; a client must not guess the winning token or replay a seek. |
| `sourceProbeCacheRevision=1` | Server-side completed-source duration reuse only. No extra client request, client-supplied duration or cache lifetime assumption. |

Admission permits at most four Direct sessions and ONE globally pending
quality handoff. A candidate expires after45s including startup. Capacity,
hardware/preparation failure, explicit abort and expiry preserve the original;
superseding seek/STOP/source retirement cannot resurrect an old candidate.
Actual/unknown growing writers currently refuse adaptive handoff while keeping
ordinary playback available. Copy, DVD, native Push/Pull are not adaptive
transcodes. A submitted command or encoder name is not full-hardware evidence.

## Bounded replacement sequence

1. Prepare with exact old token, absolute `startMs` and target `videoKbps`.
2. Preserve old playback while verifying the candidate's publication and join
   point. Real-time Direct may require ahead preroll for a moving old stream;
   a truly stalled measured downshift may prepare at current position.
3. Attach only the current load's replacement. Commit exact old/new lineage
   only after a real current renderer-frame callback at/after candidate start.
   Do not manufacture an ACK from API readiness, a requested or held clock.
4. On commit, retire old producer; published files have ordinary15s grace.
   Abort only an uncommitted candidate. A lost commit response is ambiguous:
   reconcile exact lineage rather than blindly returning to an expired old URL.

This is temporary overlap, not a permanent rendition ladder. Client adaptation
is default off and uses existing body deliveries, not bandwidth-test encodes.
Hardware bitrate reconfiguration is not certified merely because MIM accepts
`videorateadapt`; bounded replacement is the qualified fallback.

## Completed-source duration bounds

For each nonzero start/seek, public writer truth must be strictly false before
reuse. Source AND ffprobe must remain regular files with stable file key, size,
modification/creation times. Before/after mismatch, unknown/live writer, missing
identity or probe failure never seeds a reusable duration.32entry in-memory LRU,
no TTL/disk/background scan; generation-clear fences in-flight probes. Unknown
inputs retain the original bounded probe. `durationProbeCache` is a closed
status (`hit`, `miss`, `not_needed`, or bounded bypass/unknown reason), never an
export of file identity. Seek clamping, source authorization, timeouts, caption
and producer policy remain owned by this service.

## Optional24 scrub-preview contract (consumer qualification in progress)

`sourcePreviewRevision=1` opts in to POST `/v1/direct/preview` with the current
Direct `token` and file-relative `positionMs`. It returns a bounded JPEG, not
another playback URL/stream. Headers `X-Vibe-Preview-Position-Ms` identify the
actual10s bucket and `X-Vibe-Preview-Cached` distinguishes reuse; never label
this bucket as an exact requested frame. GET cannot allocate thumbnail work.

An existing Web HLS owner must not create a Direct playback stream just to get
previews. Separately negotiate `sourcePreviewLeaseRevision=1`:

- POST `/v1/previews/reserve?source=<known-library-file>` returns an opaque
  `previewToken`,120000ms lifetime,10s bucket and32768byte bound. This lease
  cannot watch, seek, control, or produce an A/V stream. At most4 reservations.
- POST `/v1/previews/image?previewToken=<lease>&positionMs=<file-ms>` returns
  the same source-owned JPEG/header contract. Caller retains its current
  source/load guard; preview selection is NOT a seek or displayed-playback ACK.
- POST `/v1/previews/teardown?previewToken=<lease>` retires just that lease's
  image work/cache. Expiry/provider stop also retires it; stale leases refuse.

Both routes share ONE finite single-frame MIM job,3s process budget/32KiB
output/one decode thread,2s admission spacing and16JPEGs/512KiB memory. No
image files, work queue, background captures, new GPU playback stream or INI
changes. Source/MIM/FFmpeg/INI identities must remain unchanged and public
writer truth strictly false on every request. Playback handoff takes priority;
source retirement cancels outstanding image work. Windows, disabled MIM,
always-transcode or unisolated-child policies advertise revision0, not a false
capability. Missing/refused previews retain all normal native controls.

Manager24 is loaded on stock232. Java8/job/cache/HTTP boundaries plus actual
40s/10s image/cache/reference comparisons, teardown and120s lease expiry pass.
Android115/8f3e6c05 Media3 production menu/coexistence/seek-invalidation/reopen
also passes its affected Non-Pro25 row. Other consumers/fallbacks remain under
qualification; these sub-gates are not all-client feature closure. Previews
are optional navigation aids, never a requirement for playback/quality changes.

## Honest playback diagnostics

Android's on-demand `playbackExperience` schema1 retains up to64 lifecycle
events with source/operation generations. Requested/local acceptance and
owned-producer preparation are not renderer landing. Untagged first-frame
callbacks leave `presentedPositionMs` and `stockUiProcessedAtMs` null. Basic
stock Fixed can send only FLUSH: server file-relative time and decoder-relative
transcode epoch are different coordinates; never invent a client offset.
Web retains its own browser/native bridge state and actual presentation ACK;
Android must not depend on the Web API to obtain these diagnostics.

## Recorded validation boundaries

Provider22 API lifecycle/capacity/expiry/lineage tests and independent exact
VAAPI child decode/filter/encode overlap pass. Android115c90/Non-Pro25/stock232
measured constrained delivery down/up, both HTTP families, GSY delegates,
actual renderer commits, seeks/pause/captions, rollback/supersession and old/
missing capability/native/Copy fallbacks pass their scoped results. Capability
404 is not a whole-plugin uninstall test. Host-WebKit Web handoff/recovery is
separate from physical iPhone/WAN acceptance.23 cache API and affected Non-Pro
Media3/legacy metadata/seek/content/caption checks pass. Windows remains deferred, and none of
these receipts claims all-device, all-disc, audible sync or physical WAN parity.
Owning task ledgers/results remain authoritative; this is not publication.
# Optional in-place bitrate control (development)

FFmpeg Plugin 0.1.30 / MIM 0.4.16 initially qualify Linux H.264 QSV only.
Both the site's `stdinctrl.runtime_bitrate_control` and the client's session
opt-in must be enabled. Defaults remain off. Copy, raw pipe input, unsupported
encoders/platforms and older providers retain ordinary playback and the
existing bounded quality handoff. Normal playback never requires Core MCP.
Stock Sage.jar, bundled Core natives and stock FFmpeg remain unchanged.

`mimDirect.runtimeBitrateControlRevision=1` negotiates the optional start field
`runtimeRateControl=true`. Each owned status has `runtimeBitrateControl` with
revision, availability, state, requested/applied/ceiling kbps and request ID.
`POST /v1/direct/rate?token=...&requestId=...&videoKbps=...` sends one absolute
target; GET on the same route reads status. IDs are increasing safe integers;
same ID/target is idempotent, conflicting or older IDs are refused. Never log
bearer tokens or replay a relative encoder mutation after an ambiguous reply.

The shared controller owns the actual Process, not an externally supplied PID.
It requires a matching QSV reset receipt and subsequent produced video time;
request acceptance alone is not measured bitrate proof. One producer and fixed
codec/resolution remain. Minimum command spacing is 15 seconds; recovery steps
are at most 25% and cannot exceed the initial session ceiling. Buffered segments
retain their original bitrate. Unconfirmed application holds further adaptation
without restarting a healthy decoder or blindly switching using stale metadata.

The Web Client reuses this Java protocol for its own Process; Android uses the
Direct HTTP owner. Their opt-in policy uses actual completed body transfers,
fast/slow EWMA, sustained reserve pressure, hysteresis and gradual recovery.
Missing native Safari transfer timing abstains rather than inventing bandwidth;
explicit HLS/MSE supplies fragment-body measurements where supported.

Policy reference designs: hls.js's
[fast/slow EWMA bandwidth estimator](https://github.com/video-dev/hls.js/blob/master/src/utils/ewma-bandwidth-estimator.ts)
and [ABR controller](https://github.com/video-dev/hls.js/blob/master/src/controller/abr-controller.ts),
plus Media3's [buffer/hysteresis-aware adaptive selection](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.java).
Vibe implements its own equivalent Web/Android decision policy; it changes one
encoder's bitrate, not a multi-rendition track-selection ladder.

Plugin settings / STVI Hardware Test offer **BITRATE TEST**. It runs a bounded
synthetic 6 → 2 → 2.5 Mbps encode, measures packets/timestamps and decodes output.
It checks recording/client/transcode activity, uses a private copied INI,
refuses concurrent playback and stores `reports/Vibe-Bitrate-Test-latest.json`.
This proves software-source/QSV-encode reconfiguration, not audio/captions,
full-GPU decoding, Windows or NVENC. The same optional diagnostic is available
to clients; it is not a normal-playback dependency.

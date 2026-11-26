package org.opensagetv.vibe.ffmpeg;

import java.io.Closeable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Owns optional Android MIM Direct playback sessions.
 *
 * <p>The stock SageTV MiniClient connection remains the authority for the UI,
 * watched state, and commands.  This service owns only the bounded media
 * representation.  It invokes the separately installed MIM executable and
 * exposes completed MPEG-TS HLS artifacts through the LAN-scoped plugin
 * HTTP listener.  Source paths must resolve to a SageTV MediaFile through the
 * injected authorizer; callers cannot use this API as an arbitrary file
 * server or command launcher.</p>
 */
final class MimDirectSessionService implements Closeable {
    static final int CONTRACT_VERSION = 1;
    static final int MAX_SESSIONS = 4;
    static final int PLAYLIST_SEGMENTS = 90;
    static final long RESTART_HANDOFF_GRACE_MS = 15_000L;
    static final long QUALITY_HANDOFF_TIMEOUT_MS = 45_000L;
    static final long LIVE_EDGE_PREROLL_MS = 4_000L;
    private static final long UNLISTED_SEGMENT_GRACE_MS = 15_000L;
    private static final long ABANDONED_SESSION_MS = 6L * 60L * 60L * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RuntimePaths paths;
    private final SourceAuthorizer authorizer;
    private final CaptionSideChannelService captions;
    private final MimRuntime runtime;
    private final CompletedSourceProbeCache durationProbes;
    private final ScrubPreviewCache previews=new ScrubPreviewCache();
    // Read-only preview leases let Web reuse this owner without creating a
    // second Direct playback stream. They cannot play, seek, control or watch.
    private final Map<String,PreviewLease> previewLeases=new LinkedHashMap<String,PreviewLease>();
    static final long PREVIEW_LEASE_MS=120000L;
    private static final class PreviewLease {
        final String token;final Path source;final long began=System.nanoTime();
        PreviewLease(String token,Path source){this.token=token;this.source=source;}
        boolean expired(){return System.nanoTime()-began>=TimeUnit.MILLISECONDS.toNanos(PREVIEW_LEASE_MS);}
    }
    private final Map<String, Session> sessions = new LinkedHashMap<String, Session>();
    private final Map<String, Session> retiredSessions =
            new LinkedHashMap<String, Session>();
    private final ScheduledExecutorService cleanupExecutor =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "vibe-mim-direct-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private volatile boolean enabled;
    private volatile boolean responsiveHlsSupported;
    private final java.util.Set<String> replacing = new java.util.HashSet<String>();
    // At most ONE temporary second producer across this service. This is a
    // bounded handoff, not an always-running adaptive rendition ladder. The
    // pending slot is reserved before launch so concurrent HTTP requests cannot
    // evade the limit while create() waits for its first completed segment.
    private QualityHandoff qualityHandoff;

    private static final class QualityHandoff {
        final Session previous;
        final long beganNs = System.nanoTime();
        Session replacement;
        QualityHandoff(Session previous) { this.previous = previous; }
    }

    interface SourceAuthorizer {
        boolean isAllowed(Path source);
        /** Public SageTV writer truth; null preserves the existing hint when
         * the installation/API cannot establish recording state. */
        default Boolean isRecording(Path source) { return null; }
    }

    MimDirectSessionService(RuntimePaths paths, SourceAuthorizer authorizer) {
        this(paths, authorizer, null);
    }

    MimDirectSessionService(RuntimePaths paths, SourceAuthorizer authorizer,
                            CaptionSideChannelService captions) {
        this.paths = paths;
        this.authorizer = authorizer;
        this.captions = captions;
        this.runtime = new MimRuntime(paths);
        this.durationProbes = new CompletedSourceProbeCache(paths.ffprobeExecutable);
    }

    synchronized void start(boolean requested) throws IOException {
        stopAll();
        durationProbes.clear();
        previews.invalidate(null);
        previewLeases.clear();
        enabled = requested && Files.isRegularFile(paths.mimExecutable)
                && Boolean.TRUE.equals(runtime.capabilities(true).json.get("ownedDirectStreams"));
        Object responsive = enabled ? runtime.capabilities(false).json.get("responsiveHlsRevision") : null;
        responsiveHlsSupported = responsive instanceof Number && ((Number) responsive).doubleValue() == 1.0d;
        if (enabled) Files.createDirectories(paths.directMediaDir);
        cleanupStaleDirectories();
    }

    synchronized String capabilitiesJson() {
        boolean preview=enabled&&runtime.thumbnailSupported();
        return "{\"contractVersion\":" + CONTRACT_VERSION +
                ",\"available\":" + enabled +
                ",\"modes\":[\"copy\",\"transcode\"]" +
                ",\"deinterlacePolicies\":[\"auto\",\"on\",\"off\"]" +
                ",\"sessionQualityRevision\":1,\"sourceProbeCacheRevision\":1" +
                ",\"sourcePreviewRevision\":"+(preview?1:0) +
                ",\"sourcePreviewLeaseRevision\":"+(preview?1:0) +
                ",\"activePreviewLeases\":"+previewLeases.size() +
                ",\"qualityHandoffRevision\":1,\"qualitySeekRevision\":1,\"qualityStartRevision\":1,\"qualityRecoveryRevision\":1,\"qualityHandoffTimeoutMs\":" + QUALITY_HANDOFF_TIMEOUT_MS +
                ",\"maxPendingQualityHandoffs\":1" +
                ",\"responsiveHlsRevision\":" + (enabled && responsiveHlsSupported ? 1 : 0) +
                ",\"hlsBufferRevision\":" + (enabled && responsiveHlsSupported ? 1 : 0) +
                ",\"runtimeBitrateControlRevision\":" + (runtimeRateAvailable()?1:0) +
                ",\"bufferAdaptationRevision\":1" +
                ",\"outputSettingsRevision\":1,\"encoderPolicyRevision\":"+(encoderPolicyAvailable()?1:0) +
                ",\"hardwareBackendRevision\":"+(hardwareBackendAvailable()?1:0) +
                ",\"container\":\"hls-mpegts\"" +
                ",\"activeSessions\":" + sessions.size() + "}";
    }

    Session create(String sourceValue, String mode, long startMs) throws IOException {
        return create(sourceValue, mode, startMs, "auto");
    }
    synchronized String runtimeRate(String token,long requestId,int kbps)throws IOException{
        Session session=sessions.get(token);
        if(session==null||replacing.contains(token)||qualityHandoff!=null||!session.runtimeRateRequested||session.runtimeRate==null)
            throw new IllegalStateException("runtime_rate_owner_unavailable");
        session.runtimeRateJson();
        return session.runtimeRate.request(requestId,kbps,System.nanoTime()/1000000L);
    }
    synchronized String runtimeRateStatus(String token){
        Session session=sessions.get(token);
        if(session==null)throw new IllegalArgumentException("unknown_session");
        return session.runtimeRateJson();
    }

    Session create(String sourceValue, String mode, long startMs,
                   String deinterlaceValue) throws IOException {
        return create(sourceValue, mode, startMs, deinterlaceValue, false);
    }

    Session create(String sourceValue, String mode, long startMs,
                   String deinterlaceValue, boolean active) throws IOException {
        return create(sourceValue,mode,startMs,deinterlaceValue,active,6000);
    }

    Session create(String sourceValue,String mode,long startMs,
                           String deinterlaceValue,boolean active,int videoKbps)throws IOException {
        return create(sourceValue, mode, startMs, deinterlaceValue, active, videoKbps, "legacy", null);
    }

    Session create(String sourceValue,String mode,long startMs,
                   String deinterlaceValue,boolean active,int videoKbps,String hlsProfile)throws IOException {
        return create(sourceValue,mode,startMs,deinterlaceValue,active,videoKbps,hlsProfile,null);
    }

    private Session create(String sourceValue,String mode,long startMs,
                           String deinterlaceValue,boolean active,int videoKbps,
                           String hlsProfile,QualityHandoff pending)throws IOException {
        return create(sourceValue,mode,startMs,deinterlaceValue,active,videoKbps,hlsProfile,pending,false);
    }
    Session create(String sourceValue,String mode,long startMs,String deinterlaceValue,
                   boolean active,int videoKbps,String hlsProfile,boolean runtimeRate)throws IOException {
        return create(sourceValue,mode,startMs,deinterlaceValue,active,videoKbps,hlsProfile,null,runtimeRate);
    }
    private boolean runtimeRateAvailable(){
        try{return enabled&&!paths.windows&&Boolean.parseBoolean(IniDocument.load(paths.ini).get("stdinctrl","runtime_bitrate_control","false"))
            &&runtime.capabilities(false).json.get("runtimeBitrateControlRevision") instanceof Number
            &&((Number)runtime.capabilities(false).json.get("runtimeBitrateControlRevision")).doubleValue()==1.0;}
        catch(Exception unavailable){return false;}
    }
    private boolean encoderPolicyAvailable(){
        Object revision=runtime.capabilities(false).json.get("encoderPolicyRevision");
        return revision instanceof Number&&((Number)revision).doubleValue()==1;
    }
    private boolean hardwareBackendAvailable(){
        Object revision=runtime.capabilities(false).json.get("hardwareBackendRevision");
        return revision instanceof Number&&((Number)revision).doubleValue()==1;
    }
    Session create(String sourceValue,String mode,long startMs,String deinterlaceValue,boolean active,
                   int videoKbps,String hlsProfile,boolean runtimeRate,DirectOutputOptions output)throws IOException {
        return create(sourceValue,mode,startMs,deinterlaceValue,active,videoKbps,hlsProfile,null,runtimeRate,output);
    }
    private Session create(String sourceValue,String mode,long startMs,
                           String deinterlaceValue,boolean active,int videoKbps,
                           String hlsProfile,QualityHandoff pending,boolean runtimeRate)throws IOException {
        return create(sourceValue,mode,startMs,deinterlaceValue,active,videoKbps,hlsProfile,pending,runtimeRate,DirectOutputOptions.LEGACY);
    }
    private Session create(String sourceValue,String mode,long startMs,
                           String deinterlaceValue,boolean active,int videoKbps,
                           String hlsProfile,QualityHandoff pending,boolean runtimeRate,DirectOutputOptions output)throws IOException {
        return create(sourceValue,mode,startMs,deinterlaceValue,active,videoKbps,hlsProfile,pending,runtimeRate,output,0);
    }
    Session create(String sourceValue,String mode,long startMs,String deinterlaceValue,boolean active,
                   int videoKbps,String hlsProfile,boolean runtimeRate,DirectOutputOptions output,int bufferAheadMs)throws IOException {
        return create(sourceValue,mode,startMs,deinterlaceValue,active,videoKbps,hlsProfile,null,runtimeRate,output,bufferAheadMs);
    }
    private Session create(String sourceValue,String mode,long startMs,
                           String deinterlaceValue,boolean active,int videoKbps,
                           String hlsProfile,QualityHandoff pending,boolean runtimeRate,DirectOutputOptions output,int bufferAheadMs)throws IOException {
        if (!enabled) throw new IllegalStateException("mim_direct_disabled");
        String selected = mode == null ? "" : mode.trim().toLowerCase();
        if (!"copy".equals(selected) && !"transcode".equals(selected))
            throw new IllegalArgumentException("invalid_direct_mode");
        output.validate(selected);
        if(!"server".equals(output.encoder)&&!encoderPolicyAvailable())throw new IllegalStateException("encoder_policy_unavailable");
        if(!"auto".equals(output.hardwareBackend)&&!hardwareBackendAvailable())throw new IllegalStateException("hardware_backend_unavailable");
        String profile = hlsProfile == null || hlsProfile.isEmpty() ? "legacy" : hlsProfile;
        if (!"legacy".equals(profile) && !"responsive".equals(profile))
            throw new IllegalArgumentException("invalid_hls_profile");
        final boolean responsive = "responsive".equals(profile);
        if(bufferAheadMs<0||bufferAheadMs>20000||bufferAheadMs>0&&!responsive)
            throw new IllegalArgumentException("invalid_hls_buffer_target");
        if (responsive && !"transcode".equals(selected))
            throw new IllegalArgumentException("responsive_hls_requires_transcode");
        if (responsive && !responsiveHlsSupported)
            throw new IllegalStateException("responsive_hls_unavailable");
        String deinterlace = normalizeDeinterlace(deinterlaceValue);
        if (startMs < 0L || startMs > 14L * 86400000L)
            throw new IllegalArgumentException("invalid_start_ms");
        if (videoKbps < 250 || videoKbps > 50000)
            throw new IllegalArgumentException("invalid_video_kbps");
        Path source;
        try { source = new File(sourceValue == null ? "" : sourceValue).getCanonicalFile().toPath(); }
        catch (IOException invalid) { throw new IllegalArgumentException("invalid_source_path"); }
        if (!Files.isRegularFile(source) || !Files.isReadable(source))
            throw new IllegalArgumentException("source_not_readable");
        if (authorizer == null || !authorizer.isAllowed(source))
            throw new IllegalArgumentException("source_not_in_sagetv_library");
        Map<String,Object> sourceOutput=output==DirectOutputOptions.LEGACY||"copy".equals(selected)?
                java.util.Collections.<String,Object>emptyMap():DirectOutputOptions.probe(paths.ffprobeExecutable,source);
        // Validate source-dependent options before reserving a slot/producer.
        if("transcode".equals(selected))output.append(new ArrayList<String>(),sourceOutput);
        // MiniClient's timeshifted bit can describe Fixed transport semantics
        // even for a completed MediaFile. Treating that as a real writer adds
        // MIM stock-follow handling: on the commissioned input a nonzero -ss
        // then publishes frame zero while reporting the requested offset.
        // Only independently proven FALSE may correct a true hint. Unknown
        // and real recordings keep the established growing-file behavior.
        final Boolean recordingState = active || startMs > 0L || responsive ? authorizer.isRecording(source) : null;
        final boolean effectiveActive = responsive && Boolean.TRUE.equals(recordingState)
                || active && !Boolean.FALSE.equals(recordingState);

        String captionReservation = "";
        if ("transcode".equals(selected) && captions != null) {
            if (!captions.isOperational())
                throw new IllegalStateException("direct_caption_tap_unavailable");
            captionReservation = captions.reserve();
            if (captionReservation == null)
                throw new IllegalStateException("direct_caption_slot_unavailable");
        }
        final StartResolution resolvedStart = boundedStartMs(source, startMs, Boolean.FALSE.equals(recordingState));
        final long effectiveStartMs = resolvedStart.startMs;
        final Session session;
        try {
            synchronized (this) {
                reapFinished();
                if (pending != null && qualityHandoff != pending)
                    throw new IllegalStateException("direct_handoff_cancelled");
                if (sessions.size() >= MAX_SESSIONS)
                    throw new IllegalStateException("direct_session_limit");
                String token = randomToken();
                Path directory = paths.directMediaDir.resolve(token).toAbsolutePath().normalize();
                if (!directory.startsWith(paths.directMediaDir.toAbsolutePath().normalize()))
                    throw new IOException("unsafe_direct_session_path");
                Files.createDirectories(directory);
                session = new Session(token, source, selected, deinterlace,
                        effectiveActive, startMs, effectiveStartMs, directory,
                        captionReservation);
                session.videoKbps=videoKbps;
                session.output=output;session.sourceOutput=sourceOutput;
                session.responsiveHls=responsive;
                // Never read ahead of an actual or unknown writer. Even a
                // completed source keeps steady -re pacing after a bounded
                // explicitly negotiated reserve; old callers retain six seconds.
                session.bufferAheadMs=bufferAheadMs;
                session.startupBurstSeconds=responsive && Boolean.FALSE.equals(recordingState) && !effectiveActive
                        ? bufferAheadMs>0 ? Math.max(6,(bufferAheadMs+999)/1000+3) : 6 : 0;
                session.videoCeilingKbps=videoKbps;
                session.runtimeRateRequested=runtimeRate&&"transcode".equals(selected)&&runtimeRateAvailable();
                session.durationProbeCache=resolvedStart.probeCache;
                sessions.put(token, session);
                if (pending != null) pending.replacement = session;
            }
        } catch (IOException failure) {
            cancelCaptionReservation(captionReservation);
            throw failure;
        } catch (RuntimeException failure) {
            cancelCaptionReservation(captionReservation);
            throw failure;
        }
        try {
            synchronized (this) {
                // Register and launch against the SAME lease. Cancellation
                // during duration probing/startup must not orphan a late child.
                if (sessions.get(session.token) != session || pending != null && qualityHandoff != pending)
                    throw new IllegalStateException("direct_handoff_cancelled");
                session.launch();
            }
            try {
                session.awaitPlaylist(15000L);
            } catch (IOException firstStartupFailure) {
                // SageTV's timeshift bit is advisory. During a live-program
                // transition it can still describe the just-closed recording
                // as active. MIM intentionally exits an -activefile job with
                // no output in that state. Retry that one proven condition
                // once as a completed file instead of abandoning the owned
                // transport and falling all the way back to MiniClient Pull.
                if (!session.retryCompletedAfterStaleActiveHint())
                    throw firstStartupFailure;
                session.awaitPlaylist(15000L);
            }
            session.claimCaption(3000L);
            if("hardware_only".equals(output.encoder)||"software".equals(output.encoder)){
                Map<String,Object> observed=session.matchingJob(runtime.status(true).json);
                if(observed==null||"hardware_only".equals(output.encoder)!=Boolean.TRUE.equals(observed.get("hardwareEncode")))
                    throw new IOException("requested_encoder_not_confirmed");
            }
            if(!"auto".equals(output.hardwareBackend)){
                Map<String,Object> observed=session.matchingJob(runtime.status(true).json);
                String actualEncoder=observed==null?"":String.valueOf(observed.get("encoder"));
                if(observed==null||!Boolean.TRUE.equals(observed.get("hardwareEncode"))||!output.hardwareBackend.equals(observed.get("backend"))||
                   !("h264_"+output.hardwareBackend).equals(actualEncoder)&&!("hevc_"+output.hardwareBackend).equals(actualEncoder))
                    throw new IOException("requested_hardware_backend_not_confirmed");
            }
            return session;
        } catch (IOException failure) {
            release(session.token);
            throw failure;
        } catch (RuntimeException failure) {
            release(session.token);
            throw failure;
        }
    }

    /**
     * Bound an owned seek to a playable point in the media that currently
     * exists. This is especially important for growing recordings: MIM can
     * successfully recover from an out-of-range {@code -ss}, but the client
     * must be told the effective offset or SageTV's timeline remains at the
     * impossible requested value. Keep a small preroll so the replacement HLS
     * representation contains enough A/V to open and continue growing.
     */
    private static final class StartResolution {
        final long startMs;final String probeCache;
        StartResolution(long startMs,String probeCache){this.startMs=startMs;this.probeCache=probeCache;}
    }

    private StartResolution boundedStartMs(Path source,long requestedStartMs,boolean confirmedCompleted) {
        if (requestedStartMs <= 0L || !Files.isRegularFile(paths.ffprobeExecutable))
            return new StartResolution(requestedStartMs,"not_needed");
        // A client-supplied duration cannot replace the owner's seek bounds.
        // Reuse ONLY independently completed, unchanged source/tool metadata.
        CompletedSourceProbeCache.Result result=durationProbes.duration(source,confirmedCompleted,
                new CompletedSourceProbeCache.Probe(){public Long durationMs(Path value){return probeDurationMs(value);}});
        long effective=requestedStartMs;
        if(result.durationMs!=null&&requestedStartMs>=result.durationMs.longValue())
            effective=Math.max(0L,result.durationMs.longValue()-LIVE_EDGE_PREROLL_MS);
        return new StartResolution(effective,result.disposition);
    }

    private Long probeDurationMs(Path source) {
        Process probe = null;
        try {
            probe = new ProcessBuilder(paths.ffprobeExecutable.toString(),
                    "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    source.toString()).redirectErrorStream(true).start();
            // A duration-only probe emits only a few bytes. Wait first so the
            // timeout also bounds a stuck probe instead of blocking forever
            // while reading its still-open stdout pipe.
            if (!probe.waitFor(5000L, TimeUnit.MILLISECONDS)) {
                probe.destroyForcibly();
                return null;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InputStream input = probe.getInputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) >= 0 && output.size() < 4096)
                output.write(buffer, 0, Math.min(count, 4096 - output.size()));
            input.close();
            if (probe.exitValue() != 0) return null;
            String[] lines = new String(output.toByteArray(), StandardCharsets.UTF_8)
                    .trim().split("\\R");
            for (String line : lines) {
                try {
                    double seconds = Double.parseDouble(line.trim());
                    if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0.0)
                        continue;
                    long durationMs = Math.max(0L, Math.round(seconds * 1000.0));
                    return Long.valueOf(durationMs);
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (probe != null && probe.isAlive()) probe.destroyForcibly();
        }
        return null;
    }

    synchronized Session get(String token) { return sessions.get(token); }
    synchronized void configureAdaptive(String token,int minimum,int maximum){
        Session s=sessions.get(token);if(s==null||!"transcode".equals(s.mode)||maximum!=s.videoCeilingKbps)throw new IllegalStateException("adaptive_owner_unavailable");
        s.adaptive=new BufferAdaptiveController(minimum,maximum,s.runtimeRate!=null);
    }
    synchronized String adaptiveReport(String token,long report,long localPosition,long reserve,boolean buffering,boolean eligible)throws IOException{
        Session s=sessions.get(token);
        if(s==null||s.adaptive==null||replacing.contains(token)||qualityHandoff!=null)throw new IllegalStateException("adaptive_owner_unavailable");
        s.runtimeRateJson();
        boolean healthy=!s.stopped&&s.error.isEmpty()&&(s.runtimeRate==null||s.runtimeRate.available())&&(s.process!=null&&s.process.isAlive()||s.finishedAtMs>0&&s.exitCode==0);
        long produced=-1;
        if(healthy&&Files.isRegularFile(s.playlist)&&Files.size(s.playlist)<=4194304){
            byte[] bytes=Files.readAllBytes(s.playlist);HlsProductionWitness parsed=new HlsProductionWitness();parsed.read(bytes,0,bytes.length);
            produced=s.recordAdaptivePublished(parsed.finish());
        }
        boolean ready=healthy&&BufferAdaptiveController.producerReady(produced,localPosition,
            System.nanoTime()/1000000L-s.adaptivePublishedAt,s.finishedAtMs>0&&s.exitCode==0);
        int target=s.adaptive.observe(System.nanoTime()/1000000L,report,reserve,buffering,eligible,ready,s.videoKbps);
        return s.adaptive.json(report,target);
    }

    /** An opt-in thumbnail of an exact current completed source. This reuses
     * MIM, not stock's unbounded synchronous GenerateThumbnail call or another
     * playback producer. The caller cannot select a file/output/codec. All
     * source, tool and INI identities are rechecked before caching output. */
    ScrubPreviewCache.Preview preview(String token,long positionMs)throws IOException{
        final Session owner;
        synchronized(this){
            owner=sessions.get(token);
            if(owner==null)throw new IllegalArgumentException("unknown_or_finished_session");
            if(!enabled||qualityHandoff!=null)throw new IllegalStateException("preview_unavailable_or_handoff");
        }
        if(owner.active)throw new IllegalStateException("preview_requires_completed_source");
        return previewForOwner(owner,owner.source,positionMs,
                ()->{
                    synchronized(MimDirectSessionService.this){if(!enabled||sessions.get(token)!=owner||qualityHandoff!=null)return false;}
                    return Boolean.FALSE.equals(authorizer.isRecording(owner.source));
                });
    }

    /** A preview-only lease, never another media producer. Source authorization
     * is the same public library boundary as Direct; expiry and explicit source
     * retirement cancel only its thumbnail work, never the caller's playback. */
    String reservePreview(String sourceValue)throws IOException{
        final Path source;
        try{source=new File(sourceValue==null?"":sourceValue).getCanonicalFile().toPath();}
        catch(IOException invalid){throw new IllegalArgumentException("invalid_source_path");}
        if(!Files.isRegularFile(source)||!Files.isReadable(source)||authorizer==null||!authorizer.isAllowed(source))
            throw new IllegalArgumentException("source_not_in_sagetv_library");
        if(!Boolean.FALSE.equals(authorizer.isRecording(source)))throw new IllegalStateException("preview_requires_completed_source");
        if(!runtime.thumbnailSupported())throw new IllegalStateException("preview_runtime_unavailable");
        final PreviewLease lease=new PreviewLease(randomToken(),source);
        synchronized(this){
            if(!enabled||previewLeases.size()>=4)throw new IllegalStateException("preview_lease_capacity_or_disabled");
            previewLeases.put(lease.token,lease);
            cleanupExecutor.schedule(()->releasePreview(lease.token),PREVIEW_LEASE_MS,TimeUnit.MILLISECONDS);
        }
        return "{\"contractVersion\":1,\"previewToken\":\""+lease.token+"\",\"expiresInMs\":"+PREVIEW_LEASE_MS+
                ",\"method\":\"mim-single-frame\",\"bucketMs\":10000,\"maxImageBytes\":32768}";
    }

    ScrubPreviewCache.Preview previewFromLease(String token,long positionMs)throws IOException{
        final PreviewLease owner;
        synchronized(this){
            owner=previewLeases.get(token);
            if(owner==null||owner.expired())throw new IllegalArgumentException("unknown_or_expired_preview");
        }
        return previewForOwner(owner,owner.source,positionMs,()->{
            synchronized(MimDirectSessionService.this){if(!enabled||owner.expired()||previewLeases.get(token)!=owner||qualityHandoff!=null)return false;}
            return Boolean.FALSE.equals(authorizer.isRecording(owner.source));
        });
    }

    synchronized boolean releasePreview(String token){
        PreviewLease owner=previewLeases.remove(token);
        if(owner==null)return false;
        previews.invalidate(owner);return true;
    }

    private ScrubPreviewCache.Preview previewForOwner(Object owner,Path source,long positionMs,
            java.util.function.BooleanSupplier current)throws IOException{
        if(positionMs<0||positionMs>14L*86400000L)throw new IllegalArgumentException("invalid_preview_position");
        if(!authorizer.isAllowed(source)||!Boolean.FALSE.equals(authorizer.isRecording(source)))
            throw new IllegalStateException("preview_requires_completed_source");
        if(!runtime.thumbnailSupported())throw new IllegalStateException("preview_runtime_unavailable");
        final long target=boundedStartMs(source,positionMs,true).startMs;
        return previews.get(owner,source,new Path[]{paths.mimExecutable,paths.ffmpegExecutable,paths.ini},target,
                current,position->{
                    final MimRuntime.ThumbnailJob job=runtime.thumbnail(source,position);
                    return new ScrubPreviewCache.Work(){
                        public byte[] run()throws IOException{return job.run();}
                        public void cancel(){job.cancel();}
                    };
                });
    }

    synchronized Session getMedia(String token) {
        Session active = sessions.get(token);
        return active == null ? retiredSessions.get(token) : active;
    }

    synchronized boolean release(String token) {
        // Closing the source closes both producers. Closing only the pending
        // replacement aborts the handoff and MUST leave the original playing.
        QualityHandoff pending = qualityHandoff;
        if (pending != null && (pending.previous.token.equals(token) ||
                pending.replacement != null && pending.replacement.token.equals(token))) {
            boolean replacementOnly = !pending.previous.token.equals(token);
            cancelQualityHandoff(pending);
            if (replacementOnly) return true;
        }
        Session session = sessions.remove(token);
        if(session!=null)previews.invalidate(session);
        if (session == null) session = retiredSessions.remove(token);
        if (session == null) return false;
        session.stop();
        deleteTree(session.directory);
        return true;
    }

    Session restart(String token, long startMs) throws IOException {
        return restart(token, startMs, null);
    }

    Session restart(String token, long startMs, String deinterlaceValue) throws IOException {
        return replace(token,startMs,deinterlaceValue,null);
    }

    /** Optional revision1 operation for any client. Exact opaque custody,
     * immutable shared INI/defaults and bounded per-load reductions. */
    Session reduceQuality(String token,long startMs,int videoKbps)throws IOException {
        return replace(token,startMs,null,Integer.valueOf(videoKbps));
    }

    /** Optional two-phase contract. Legacy /quality keeps its existing revision1
     * semantics; only explicitly negotiating clients opt into the extra lease.
     * Readiness returns a new URL, NOT permission to retire the old producer.
     * The client commits only after presenting that exact replacement. No Core
     * or shared INI mutation, arbitrary source or client-side process manager. */
    Session prepareQuality(String token, long startMs, int videoKbps) throws IOException {
        return prepareQuality(token,startMs,videoKbps,false);
    }

    /** Recovery is explicitly negotiated and opt-in. It may regain at most
     * 25 percent per handoff, never exceed the original request, and uses the
     * same overlap, frame acknowledgement, cooldown and total launch budget.
     * The legacy quality endpoint remains reduction-only. */
    Session prepareQuality(String token, long startMs, int videoKbps, boolean recovery) throws IOException {
        final QualityHandoff pending;
        synchronized (this) {
            Session current = sessions.get(token);
            if (current == null) throw new IllegalArgumentException("unknown_or_finished_session");
            // The measured stock-follow path currently cannot establish the
            // requested nonzero content position. Never make a new adaptive
            // candidate for a real/unknown writer until that path is qualified.
            // Original playback survives this optional-feature refusal.
            if (current.active) throw new IllegalStateException("direct_handoff_growing_source_unqualified");
            if (qualityHandoff != null || replacing.contains(token))
                throw new IllegalStateException("direct_handoff_in_progress");
            // This is an admission limit, not a claim that a driver has spare
            // encoder sessions. A hardware launch failure still rolls back.
            if (sessions.size() >= MAX_SESSIONS)
                throw new IllegalStateException("direct_handoff_capacity");
            claimQualityAttempt(current, videoKbps, recovery);
            // Only an admitted, current playback operation may preempt optional
            // thumbnail work. A foreign/stale/refused request must not cancel
            // another client's preview or purge its cached images.
            previews.invalidate(null);
            pending = new QualityHandoff(current);
            qualityHandoff = pending;
            replacing.add(token);
            cleanupExecutor.schedule(new Runnable() {
                public void run() { expireQualityHandoffs(System.nanoTime()); }
            }, QUALITY_HANDOFF_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        Session replacement = null;
        try {
            Session previous = pending.previous;
            replacement = create(previous.source.toString(), previous.mode, startMs,
                    previous.deinterlace, previous.active, videoKbps, previous.responsiveHls ? "responsive" : "legacy", pending,false,previous.output,previous.bufferAheadMs);
            synchronized (this) {
                if (qualityHandoff != pending || sessions.get(token) != previous ||
                        System.nanoTime() - pending.beganNs >= TimeUnit.MILLISECONDS.toNanos(QUALITY_HANDOFF_TIMEOUT_MS))
                    throw new IllegalStateException("direct_handoff_cancelled");
                replacement.qualityReductions = previous.qualityReductions;
                replacement.qualityChangedAtMs = previous.qualityChangedAtMs;
                replacement.videoCeilingKbps = previous.videoCeilingKbps;
                replacement.adaptive=previous.adaptive;if(replacement.adaptive!=null)replacement.adaptive.epoch();
                previous.lastQualityCandidateToken=replacement.token;
                pending.replacement = replacement;
                return replacement;
            }
        } catch (IOException failure) {
            synchronized (this) { if (qualityHandoff == pending) cancelQualityHandoff(pending); }
            if (replacement != null) release(replacement.token);
            throw failure;
        } catch (RuntimeException failure) {
            synchronized (this) { if (qualityHandoff == pending) cancelQualityHandoff(pending); }
            if (replacement != null) release(replacement.token);
            throw failure;
        }
    }

    synchronized Session commitQuality(String previousToken, String replacementToken, long presentedMs) {
        expireQualityHandoffs(System.nanoTime());
        QualityHandoff pending = qualityHandoff;
        Session replacement = sessions.get(replacementToken);
        // A lost HTTP response may be retried. Exact committed lineage is
        // idempotent; unrelated/stale token pairs never stop another producer.
        if (replacement != null && previousToken.equals(replacement.committedFromToken)) return replacement;
        if (pending == null || pending.replacement != replacement || replacement == null ||
                !pending.previous.token.equals(previousToken) || sessions.get(previousToken) != pending.previous)
            throw new IllegalStateException("direct_handoff_not_current");
        if (presentedMs < replacement.startMs || presentedMs > 14L * 86400000L)
            throw new IllegalArgumentException("invalid_presented_ms");
        if ("failed".equals(replacement.state) || "released".equals(replacement.state)) {
            cancelQualityHandoff(pending);
            throw new IllegalStateException("direct_handoff_replacement_failed");
        }
        qualityHandoff = null;
        replacing.remove(previousToken);
        replacement.committedFromToken = previousToken;
        retireForHandoff(previousToken);
        return replacement;
    }

    synchronized boolean abortQuality(String previousToken, String replacementToken) {
        QualityHandoff pending = qualityHandoff;
        if (pending == null || pending.replacement == null ||
                !pending.previous.token.equals(previousToken) || !pending.replacement.token.equals(replacementToken))
            return false;
        cancelQualityHandoff(pending);
        return true;
    }

    /** Resolve an explicit seek against the SAME handoff lineage atomically.
     * Before commit, cancel only its candidate and seek the retained original;
     * after commit, seek the accepted candidate. A client cannot choose between
     * those outcomes safely by observing two HTTP replies or replaying a seek.
     * Process preparation remains outside the service monitor, and normal
     * replace() still rejects STOP/source retirement without resurrection. */
    Session seekQuality(String previousToken,String replacementToken,long startMs,String deinterlace)throws IOException {
        if(startMs<0||startMs>14L*86400000L)throw new IllegalArgumentException("invalid_start_ms");
        final String policy=deinterlace==null||deinterlace.trim().length()==0?null:normalizeDeinterlace(deinterlace);
        final String currentToken;
        synchronized(this){
            expireQualityHandoffs(System.nanoTime());
            Session previous=sessions.get(previousToken),replacement=sessions.get(replacementToken);
            if(qualityHandoff!=null&&qualityHandoff.previous.token.equals(previousToken)){
                if(qualityHandoff.replacement==null||!qualityHandoff.replacement.token.equals(replacementToken))
                    throw new IllegalStateException("direct_handoff_not_current");
                cancelQualityHandoff(qualityHandoff);currentToken=previousToken;
            }else if(replacement!=null&&previousToken.equals(replacement.committedFromToken)){
                currentToken=replacementToken;
            }else if(previous!=null&&!replacing.contains(previousToken)&&replacementToken.equals(previous.lastQualityCandidateToken)){
                // Expiry/abort may already have retired the candidate. The
                // original remains an authorized ordinary restart token.
                currentToken=previousToken;
            }else throw new IllegalStateException("direct_handoff_not_current");
        }
        return replace(currentToken,startMs,policy,null);
    }

    /** Monotonic expiry is also a deterministic seam for lifecycle tests. It
     * retires only a still-current replacement, never the playing original. */
    synchronized void expireQualityHandoffs(long nowNs) {
        QualityHandoff pending = qualityHandoff;
        if (pending != null && nowNs - pending.beganNs >= TimeUnit.MILLISECONDS.toNanos(QUALITY_HANDOFF_TIMEOUT_MS))
            cancelQualityHandoff(pending);
    }

    private void cancelQualityHandoff(QualityHandoff pending) {
        if (qualityHandoff != pending) return;
        qualityHandoff = null;
        replacing.remove(pending.previous.token);
        if (pending.replacement != null) release(pending.replacement.token);
    }

    private void claimQualityAttempt(Session current, int videoKbps) {
        claimQualityAttempt(current,videoKbps,false);
    }

    private void claimQualityAttempt(Session current, int videoKbps, boolean recovery) {
        if (!"transcode".equals(current.mode)) throw new IllegalArgumentException("quality_requires_transcode");
        if (videoKbps < 250 || videoKbps == current.videoKbps ||
                (!recovery && videoKbps > current.videoKbps))
            throw new IllegalArgumentException("quality_requires_lower_bitrate");
        if (videoKbps > current.videoCeilingKbps ||
                videoKbps > current.videoKbps && videoKbps > current.videoKbps * 5 / 4)
            throw new IllegalArgumentException("quality_recovery_exceeds_ceiling_or_step");
        long now = System.currentTimeMillis();
        if (current.qualityReductions >= 2 || current.qualityChangedAtMs > 0 && now - current.qualityChangedAtMs < 60000L)
            throw new IllegalStateException("quality_cooldown_or_budget");
        // Failure is not permission for an unbounded launch/retry loop.
        current.qualityReductions++;
        current.qualityChangedAtMs = now;
    }

    private Session replace(String token,long startMs,String deinterlaceValue,Integer reducedKbps)throws IOException {
        Session current;
        synchronized(this){
            current=sessions.get(token);
            if(current==null)throw new IllegalArgumentException("unknown_or_finished_session");
            // An explicit user seek supersedes a background quality handoff.
            // Cancel its candidate before starting the seek, not the source.
            if(reducedKbps==null&&qualityHandoff!=null&&qualityHandoff.previous==current)
                cancelQualityHandoff(qualityHandoff);
            if(replacing.contains(token)||qualityHandoff!=null&&qualityHandoff.replacement==current)
                throw new IllegalStateException("direct_replacement_in_progress");
            if(reducedKbps!=null){
                claimQualityAttempt(current,reducedKbps);
            }
            replacing.add(token);
        }
        try{
          // Adaptive quality is a single-producer operation, not a rendition
          // ladder or a make-before-break encoder handoff. Keep published
          // files available, but stop the current encoder before allocating
          // its replacement. If termination cannot be confirmed, fail closed;
          // never double GPU/CPU load to hide a failed stop. Ordinary seeks
          // retain their established handoff policy.
          if(reducedKbps!=null){
            current.stop();
            Process previous=current.process;
            if(previous!=null&&previous.isAlive()){
                try{previous.waitFor(1000L,TimeUnit.MILLISECONDS);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("quality_stop_interrupted",interrupted);}
                if(previous.isAlive())throw new IOException("quality_previous_producer_still_running");
            }
            synchronized(this){
                if(sessions.get(token)!=current)throw new IllegalStateException("direct_owner_retired");
            }
          }
          String replacementPolicy = deinterlaceValue == null ||
                deinterlaceValue.trim().length() == 0
                ? current.deinterlace : normalizeDeinterlace(deinterlaceValue);
          Session replacement = create(current.source.toString(), current.mode,
                startMs, replacementPolicy, current.active,reducedKbps==null?current.videoKbps:reducedKbps,
                current.responsiveHls ? "responsive" : "legacy",null,current.runtimeRateRequested,current.output,current.bufferAheadMs);
          synchronized(this){
            if(sessions.get(token)!=current){release(replacement.token);throw new IllegalStateException("direct_owner_retired");}
            replacement.qualityReductions=current.qualityReductions;replacement.qualityChangedAtMs=current.qualityChangedAtMs;
            replacement.videoCeilingKbps=current.videoCeilingKbps;
            replacement.adaptive=current.adaptive;if(replacement.adaptive!=null)replacement.adaptive.epoch();
            if(replacement.runtimeRate!=null)replacement.runtimeRate.restoreCeiling(current.videoCeilingKbps);
            retireForHandoff(token);
          }
          return replacement;
        }finally{synchronized(this){replacing.remove(token);}}
    }

    private void retireForHandoff(final String token) {
        final Session retired;
        synchronized (this) {
            retired = sessions.remove(token);
            if (retired == null) return;
            retired.stop();
            retired.state = "handoff";
            retiredSessions.put(token, retired);
        }
        cleanupExecutor.schedule(new Runnable() {
            public void run() {
                synchronized (MimDirectSessionService.this) {
                    if (retiredSessions.get(token) != retired) return;
                    retiredSessions.remove(token);
                }
                deleteTree(retired.directory);
            }
        }, RESTART_HANDOFF_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    synchronized void stopAll() {
        qualityHandoff = null;
        replacing.clear();
        for (Session session : new ArrayList<Session>(sessions.values())) {
            session.stop(); deleteTree(session.directory);
        }
        for (Session session : new ArrayList<Session>(retiredSessions.values())) {
            session.stop(); deleteTree(session.directory);
        }
        sessions.clear();
        retiredSessions.clear();
    }

    public synchronized void close() { stopAll(); previews.invalidate(null); previewLeases.clear(); enabled = false; }

    private synchronized void reapFinished() {
        List<String> finished = new ArrayList<String>();
        for (Session session : sessions.values())
            if (System.currentTimeMillis() - session.createdAtMs > ABANDONED_SESSION_MS)
                finished.add(session.token);
        for (String token : finished) release(token);
    }

    private void cleanupStaleDirectories() throws IOException {
        if (!Files.isDirectory(paths.directMediaDir)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(paths.directMediaDir)) {
            for (Path entry : entries) deleteTree(entry);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            if (Files.isDirectory(root)) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
                    for (Path child : children) deleteTree(child);
                }
            }
            Files.deleteIfExists(root);
        } catch (IOException ignored) { }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    static String normalizeDeinterlace(String value) {
        String selected = value == null ? "" : value.trim().toLowerCase();
        if (selected.length() == 0) return "auto";
        if (!"auto".equals(selected) && !"on".equals(selected) &&
                !"off".equals(selected))
            throw new IllegalArgumentException("invalid_deinterlace_policy");
        return selected;
    }

    private void cancelCaptionReservation(String token) {
        if (captions == null || token == null || token.length() == 0) return;
        try { captions.teardown(token); }
        catch (RuntimeException ignored) { }
    }

    final class Session {
        final String token;
        final Path source;
        final String mode;
        final String deinterlace;
        final boolean active;
        final long requestedStartMs;
        final long startMs;
        final Path directory;
        final Path playlist;
        final String captionReservationToken;
        final long createdAtMs = System.currentTimeMillis();
        volatile Process process;
        volatile long finishedAtMs;
        volatile int exitCode = Integer.MIN_VALUE;
        volatile String state = "starting";
        volatile String error = "";
        volatile String captionSessionToken = "";
        volatile String durationProbeCache = "not_needed";
        volatile boolean responsiveHls;
        int bufferAheadMs;
        volatile int startupBurstSeconds;
        volatile long startupReadyMs = -1L;
        volatile boolean activeFileApplied;
        volatile int videoKbps=6000,videoCeilingKbps=6000,qualityReductions;
        DirectOutputOptions output=DirectOutputOptions.LEGACY;
        Map<String,Object> sourceOutput=java.util.Collections.emptyMap();
        volatile String effectiveOutputJson="{\"observed\":false}";
        volatile String lastQualityCandidateToken="";
        volatile long qualityChangedAtMs;
        volatile boolean runtimeRateRequested;
        volatile RuntimeBitrateControl runtimeRate;
        volatile BufferAdaptiveController adaptive;
        final HlsProductionWitness.Timeline adaptivePublished=new HlsProductionWitness.Timeline();
        private volatile long adaptivePublishedAt;
        private long adaptiveHorizon=-1;
        private synchronized long recordAdaptivePublished(HlsProductionWitness.Snapshot snapshot){
            long produced=adaptivePublished.receive(snapshot);
            if(produced>adaptiveHorizon){adaptiveHorizon=produced;adaptivePublishedAt=System.nanoTime()/1000000L;}
            return produced;
        }
        private boolean stopped;
        // Internal lineage only: never add source/previous bearer tokens to
        // diagnostics. Used solely to make an exact commit retry idempotent.
        String committedFromToken = "";

        Session(String token, Path source, String mode, String deinterlace,
                boolean active, long requestedStartMs, long startMs, Path directory,
                String captionReservationToken) {
            this.token = token; this.source = source; this.mode = mode;
            this.deinterlace = deinterlace;
            this.active = active;
            this.requestedStartMs = requestedStartMs;
            this.startMs = startMs; this.directory = directory;
            this.captionReservationToken = captionReservationToken == null
                    ? "" : captionReservationToken;
            this.playlist = directory.resolve("stream.m3u8");
        }

        void launch() throws IOException {
            launch(active);
        }

        private synchronized void launch(boolean useActiveFile) throws IOException {
            if (stopped) throw new IOException("direct_session_stopped");
            List<String> command = command(useActiveFile);
            Path stderr = directory.resolve("mim-stderr.log");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(paths.sageHome.toFile());
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("mim-stdout.log").toFile()));
            if(!runtimeRateRequested)builder.redirectError(ProcessBuilder.Redirect.appendTo(stderr.toFile()));
            final Process launchedProcess = builder.start();
            process = launchedProcess;
            runtimeRate=runtimeRateRequested?new RuntimeBitrateControl(launchedProcess,videoKbps,Math.max(videoKbps,videoCeilingKbps)):null;
            if(runtimeRate!=null){
                final RuntimeBitrateControl control=runtimeRate;
                Thread reader=new Thread(()->{
                    try(java.io.BufferedReader in=new java.io.BufferedReader(new java.io.InputStreamReader(launchedProcess.getErrorStream(),StandardCharsets.UTF_8));
                        java.io.BufferedWriter out=Files.newBufferedWriter(stderr,StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)){
                        String line;while((line=in.readLine())!=null){control.observe(line);out.write(line);out.newLine();out.flush();}
                    }catch(IOException ignored){}
                },"vibe-mim-rate-stderr");reader.setDaemon(true);reader.start();
            }
            activeFileApplied = useActiveFile;
            exitCode = Integer.MIN_VALUE;
            finishedAtMs = 0L;
            error = "";
            state = "running";
            Thread monitor = new Thread(new Runnable() {
                public void run() {
                    int launchedExitCode = Integer.MIN_VALUE;
                    String launchedError = "";
                    try {
                        while (!launchedProcess.waitFor(5L, TimeUnit.SECONDS))
                            cleanupUnlistedSegments();
                        launchedExitCode = launchedProcess.exitValue();
                        cleanupUnlistedSegments();
                    }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        launchedError = "monitor_interrupted";
                    }
                    synchronized (Session.this) {
                        // A stale-active retry can install a replacement
                        // process before this monitor publishes its result.
                        // Never let the retired process overwrite the active
                        // attempt's observable lifecycle state.
                        if (process != launchedProcess || stopped) return;
                        exitCode = launchedExitCode;
                        if (launchedError.length() != 0) error = launchedError;
                        finishedAtMs = System.currentTimeMillis();
                        state = exitCode == 0 ? "complete" : "failed";
                        if (exitCode != 0 && error.length() == 0)
                            error = "mim_exit_" + exitCode;
                    }
                }
            }, "vibe-mim-direct-" + token.substring(0, 8));
            monitor.setDaemon(true);
            monitor.start();
        }

        private List<String> command(boolean useActiveFile) {
            List<String> command = new ArrayList<String>();
            Collections.addAll(command, paths.mimExecutable.toString(), "-hide_banner",
                    "-loglevel", "warning", "-sagetvdirect", "-re");
            if (responsiveHls) {
                command.add("-sagetvresponsivehls");
                if (startupBurstSeconds > 0)
                    Collections.addAll(command,"-readrate_initial_burst",Integer.toString(startupBurstSeconds));
            }
            if(runtimeRateRequested)Collections.addAll(command,"-sagetvruntimebitrate","-progress",directory.resolve("rate-progress.log").toString(),"-nostats");
            // MIM's active-file contract enables bounded probing, growing-file
            // following, and the safer live decode policy. Without it, a
            // newly tuned recording can reach its current edge during GPU
            // startup and leave behind a short, undecodable H.264 fragment.
            if (useActiveFile) command.add("-activefile");
            if ("transcode".equals(mode)) {
                command.add("-sagetvdeinterlace");
                command.add(deinterlace);
            }
            if (!captionReservationToken.isEmpty()) command.add("-stdinctrl");
            if (startMs > 0L) {
                command.add("-ss"); command.add(String.format(java.util.Locale.US, "%.3f", startMs / 1000.0));
            }
            Collections.addAll(command, "-i", source.toString(),
                    "-map", "0:v:0?", "-map", "0:a?", "-map", "0:s?");
            if ("copy".equals(mode)) {
                Collections.addAll(command, "-c:v", "copy", "-c:a", "copy", "-c:s", "copy");
            } else {
                Collections.addAll(command, "-c:v", "hevc".equals(output.videoCodec)?"libx265":"libx264", "-preset", "veryfast",
                        "-b:v", videoKbps+"k", "-maxrate", (videoKbps*4/3)+"k", "-bufsize", (videoKbps*2)+"k",
                        // Direct Transcode is an interactive HLS transport.
                        // Keep the GOP bounded so the first complete segment
                        // has a decodable keyframe within roughly one second
                        // for common 25/29.97/30 fps broadcast sources. MIM
                        // preserves -g when it maps this request to a hardware
                        // encoder; legacy Fixed and Direct Copy are untouched.
                        "-g", "30",
                        "-a53cc", "1", "-c:s", "copy");
                output.append(command,sourceOutput);
                if (responsiveHls)
                    Collections.addAll(command,"-force_key_frames","expr:gte(t,n_forced*1)");
            }
            /*
             * FFmpeg's HLS muxer always routes mapped subtitle streams through
             * its WebVTT child muxer. That rejects DVB bitmap and DVB Teletext
             * copy streams even when the requested HLS segment type is MPEG-TS.
             * The segment muxer writes the same live M3U8 plus real MPEG-TS
             * segments while retaining those broadcast subtitle PIDs. This is
             * intentionally a Direct-session-only change; established SageTV
             * Fixed commands and the MIM compatibility policy are untouched.
             */
            Collections.addAll(command, "-f", "segment", "-segment_time", responsiveHls ? "1" : "2",
                    "-segment_format", "mpegts", "-segment_list_type", "m3u8",
                    "-segment_list_flags", "+live", "-segment_list_size",
                    Integer.toString(PLAYLIST_SEGMENTS * (responsiveHls ? 2 : 1)), "-segment_list",
                    playlist.toString(), "-y",
                    directory.resolve("seg_%06d.ts").toString());
            return command;
        }

        /**
         * Retry only MIM's clean, media-less active-file exit. A running job,
         * a nonzero child failure, or a completed playable segment is not a
         * stale SageTV hint and must retain the original startup failure.
         */
        private boolean retryCompletedAfterStaleActiveHint() throws IOException {
            Process attempted = process;
            if (!active || !activeFileApplied || attempted == null ||
                    attempted.isAlive()) return false;
            int attemptedExit;
            try { attemptedExit = attempted.exitValue(); }
            catch (IllegalThreadStateException stillRunning) { return false; }
            if (attemptedExit != 0 || playlistReady()) return false;
            clearStartupArtifacts();
            state = "retrying_completed_after_stale_active_hint";
            launch(false);
            return true;
        }

        private void clearStartupArtifacts() throws IOException {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    Path name = entry.getFileName();
                    String value = name == null ? "" : name.toString();
                    if ("stream.m3u8".equals(value) ||
                            "mim-stdout.log".equals(value) ||
                            "mim-stderr.log".equals(value) ||
                            value.matches("seg_[0-9]{6}\\.ts"))
                        Files.deleteIfExists(entry);
                }
            }
        }

        /**
         * Retire only segments older than the published window. An unlisted
         * newer file can still be the segment muxer's current open output.
         * Copy input can pause longer than the retention grace (for example
         * around a seek/keyframe); age alone must never unlink that writer.
         * Use the monotonically numbered playlist boundary rather than OS
         * open-file inspection so the same rule holds on Linux and Windows.
         */
        void cleanupUnlistedSegments() {
            if (!Files.isRegularFile(playlist)) return;
            final Set<String> referenced = new HashSet<String>();
            try {
                if(Files.size(playlist)>4194304)return;
                // Reuse the existing five-second retention monitor to keep
                // an exact overlapping manifest horizon, including while a
                // client is paused. No new timer, HTTP request or guessed
                // segment-number-to-time conversion is introduced.
                HlsProductionWitness witness=adaptive==null?null:new HlsProductionWitness();
                String firstPublished = null;
                for (String line : Files.readAllLines(playlist, StandardCharsets.UTF_8)) {
                    if(witness!=null){byte[] bytes=(line+"\n").getBytes(StandardCharsets.UTF_8);witness.read(bytes,0,bytes.length);}
                    String value = line == null ? "" : line.trim();
                    if (value.matches("seg_[0-9]{6}\\.ts")) {
                        referenced.add(value);
                        if (firstPublished == null || value.compareTo(firstPublished) < 0)
                            firstPublished = value;
                    }
                }
                // A transient empty/partial playlist offers no safe retirement
                // boundary. Teardown still removes the entire owned directory.
                if(witness!=null)recordAdaptivePublished(witness.finish());
                if (firstPublished == null) return;
                long cutoff = System.currentTimeMillis() - UNLISTED_SEGMENT_GRACE_MS;
                try (DirectoryStream<Path> entries =
                             Files.newDirectoryStream(directory, "seg_*.ts")) {
                    for (Path entry : entries) {
                        Path name = entry.getFileName();
                        if (name == null || !name.toString().matches("seg_[0-9]{6}\\.ts") ||
                                referenced.contains(name.toString()) ||
                                name.toString().compareTo(firstPublished) >= 0) continue;
                        FileTime modified = Files.getLastModifiedTime(entry);
                        if (modified.toMillis() <= cutoff) Files.deleteIfExists(entry);
                    }
                }
            } catch (IOException ignored) {
                // Playback remains authoritative. A later monitor pass or the
                // session teardown will retry/complete cleanup.
            }
        }

        void awaitPlaylist(long timeoutMs) throws IOException {
            long beganNs = System.nanoTime();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                // FFmpeg creates the M3U8 before its first MPEG-TS segment is
                // publishable. A nonempty, header-only playlist is not a
                // playable session and Media3 correctly rejects it as a
                // malformed HLS container. Do not expose the media URL until
                // at least one safe listed segment exists and contains data.
                if (playlistReady()) {
                    startupReadyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beganNs);
                    state = "ready"; return;
                }
                Process active = process;
                if (active != null && !active.isAlive())
                    throw new IOException("MIM Direct ended before producing media (exit " + exitCode + ")");
                try { Thread.sleep(responsiveHls && System.nanoTime() - beganNs < TimeUnit.SECONDS.toNanos(2) ? 20L : 50L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IOException("MIM Direct startup interrupted");
                }
            }
            throw new IOException("MIM Direct playlist startup timed out");
        }

        private boolean playlistReady() {
            if (!Files.isRegularFile(playlist) || playlistSize() <= 0L) return false;
            try {
                for (String line : Files.readAllLines(playlist, StandardCharsets.UTF_8)) {
                    String value = line == null ? "" : line.trim();
                    if (!value.matches("seg_[0-9]{6}\\.ts")) continue;
                    Path segment = directory.resolve(value).normalize();
                    if (segment.startsWith(directory) && Files.isRegularFile(segment) &&
                            Files.size(segment) > 0L && segmentPlayable(segment))
                        return true;
                }
            } catch (IOException incompleteWrite) {
                // The writer may be replacing the live manifest. Retry until
                // the bounded startup deadline instead of exposing it early.
            }
            return false;
        }

        private boolean segmentPlayable(Path segment) {
            if(output!=DirectOutputOptions.LEGACY&&!effectiveOutputJson.contains("\"observed\":true"))try {
                Map<String,Object> observed=DirectOutputOptions.probe(paths.ffprobeExecutable,segment);
                if(DirectOutputOptions.number(observed.get("width"))>0)
                    effectiveOutputJson=DirectOutputOptions.observedJson(observed);
            }catch(IOException unavailable){/* Unknown is not requested/observed proof. */}
            // Copy sessions retain the source codecs and can include audio-only
            // media. For Transcode, however, the contract promises H.264 video.
            // Prove that FFprobe can read nonzero dimensions before exposing
            // the URL. This rejects the small TS fragment produced when a GPU
            // encoder fails during its first frame even if MIM contains the
            // child failure as a clean wrapper exit.
            if (!"transcode".equals(mode)) return true;
            if (!Files.isRegularFile(paths.ffprobeExecutable)) return false;
            Process probe = null;
            try {
                probe = new ProcessBuilder(paths.ffprobeExecutable.toString(),
                        "-v", "error", "-select_streams", "v:0",
                        "-show_entries", "stream=width,height",
                        "-of", "csv=p=0", segment.toString())
                        .redirectErrorStream(true).start();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                InputStream input = probe.getInputStream();
                byte[] buffer = new byte[512];
                int count;
                while ((count = input.read(buffer)) >= 0 && output.size() < 4096)
                    output.write(buffer, 0, Math.min(count, 4096 - output.size()));
                input.close();
                if (!probe.waitFor(2000L, TimeUnit.MILLISECONDS)) {
                    probe.destroyForcibly();
                    return false;
                }
                if (probe.exitValue() != 0) return false;
                String dimensions = new String(output.toByteArray(),
                        StandardCharsets.UTF_8).trim();
                // MPEG-TS FFprobe output can list the selected stream once
                // under its program and once in the global stream table,
                // separated by a blank line. Any proven nonzero-dimension
                // video line is sufficient; requiring the entire output to
                // be one record rejects valid broadcast TS segments.
                for (String line : dimensions.split("\\R")) {
                    if (line.trim().matches("[1-9][0-9]*,[1-9][0-9]*"))
                        return true;
                }
                return false;
            } catch (IOException invalidMedia) {
                return false;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                if (probe != null && probe.isAlive()) probe.destroyForcibly();
            }
        }

        void claimCaption(long timeoutMs) throws IOException {
            if (captionReservationToken.isEmpty()) return;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                String claimed = captions == null ? null
                        : captions.claim(captionReservationToken);
                if (claimed != null) {
                    captionSessionToken = claimed;
                    return;
                }
                try { Thread.sleep(50L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Direct caption claim interrupted");
                }
            }
            throw new IOException("Direct caption tap startup timed out");
        }

        private long playlistSize() {
            try { return Files.size(playlist); } catch (IOException ignored) { return 0L; }
        }

        synchronized void stop() {
            previews.invalidate(this);
            stopped = true;
            String captionToken = captionSessionToken.length() == 0
                    ? captionReservationToken : captionSessionToken;
            captionSessionToken = "";
            if (captions != null && captionToken.length() != 0) {
                try { captions.teardown(captionToken); }
                catch (RuntimeException ignored) { }
            }
            Process active = process;
            if (active != null && active.isAlive()) {
                try {
                    // MIM owns FFmpeg's control pipe. Ask it to stop normally
                    // first so it can terminate the child, publish final
                    // status, and release its caption-slot claim. Destroying
                    // the process first skips all three native cleanup paths.
                    active.getOutputStream().write("STOP\n".getBytes(StandardCharsets.US_ASCII));
                    active.getOutputStream().flush();
                    if (!active.waitFor(2000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        active.destroy();
                        if (!active.waitFor(1000L, java.util.concurrent.TimeUnit.MILLISECONDS))
                            active.destroyForcibly();
                    }
                } catch (IOException controlFailure) {
                    active.destroy();
                    try {
                        if (!active.waitFor(1000L, java.util.concurrent.TimeUnit.MILLISECONDS))
                            active.destroyForcibly();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        active.destroyForcibly();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); active.destroyForcibly();
                }
            }
            if (captions != null && captionReservationToken.length() != 0)
                captions.releaseTransport(captionReservationToken);
            state = "released";
        }

        String json() {
            String rate=runtimeRateJson();
            return "{\"contractVersion\":" + CONTRACT_VERSION +
                    ",\"bufferAdaptation\":"+(adaptive==null?"{\"revision\":0,\"available\":false}":adaptive.json(0,0))+
                    ",\"runtimeBitrateControl\":"+rate+
                    ",\"sessionToken\":\"" + jsonEscape(token) + "\"" +
                    ",\"mode\":\"" + jsonEscape(mode) + "\"" +
                    ",\"hlsProfile\":\"" + (responsiveHls ? "responsive" : "legacy") + "\"" +
                    ",\"segmentTargetMs\":" + (responsiveHls ? 1000 : 2000) +
                    ",\"startupBurstSeconds\":" + startupBurstSeconds +
                    ",\"bufferAheadTargetMs\":" + bufferAheadMs +
                    ",\"startupReadyMs\":" + startupReadyMs +
                    ",\"deinterlace\":\"" + jsonEscape(deinterlace) + "\"" +
                    ",\"sessionQualityRevision\":1,\"videoTargetKbps\":"+("transcode".equals(mode)?Integer.toString(videoKbps):"null")+
                    ",\"videoCeilingKbps\":"+("transcode".equals(mode)?Integer.toString(videoCeilingKbps):"null")+
                    ",\"qualityReductions\":"+qualityReductions+",\"qualityChangedAtMs\":"+qualityChangedAtMs+
                    ",\"active\":" + active +
                    ",\"activeFileApplied\":" + activeFileApplied +
                    ",\"state\":\"" + jsonEscape(state) + "\"" +
                    ",\"requestedStartMs\":" + requestedStartMs +
                    ",\"startMs\":" + startMs +
                    ",\"durationProbeCache\":\"" + jsonEscape(durationProbeCache) + "\"" +
                    ",\"mediaUrl\":\"/v1/direct/media/" + jsonEscape(token) + "/stream.m3u8\"" +
                    ",\"captionSessionToken\":\"" +
                    jsonEscape(captionSessionToken) + "\"" +
                    ",\"createdAtMs\":" + createdAtMs +
                    ",\"finishedAtMs\":" + finishedAtMs +
                    ",\"exitCode\":" + exitCode +
                    ",\"outputSettings\":"+output.json()+",\"effectiveOutput\":"+effectiveOutputJson+
                    ",\"outputFallbackReason\":\""+("source".equals(output.audioKbps)&&!DirectOutputOptions.validAudioRate("source".equals(output.audioCodec)?DirectOutputOptions.string(sourceOutput.get("audioCodec")):output.audioCodec,DirectOutputOptions.number(sourceOutput.get("audioBitrate")))?"source_audio_bitrate_unavailable_encoder_default":"none")+"\""+
                    ",\"execution\":" + executionJson() +
                    ",\"error\":\"" + jsonEscape(error) + "\"}";
        }

        String runtimeRateJson(){
            RuntimeBitrateControl control=runtimeRate;if(control==null)return "{\"revision\":0,\"available\":false}";
            long progress=RuntimeBitrateControl.progress(directory.resolve("rate-progress.log"));
            String value=control.snapshot(progress,System.nanoTime()/1000000L);videoKbps=control.currentKbps();return value;
        }

        private String executionJson() {
            Map<String, Object> job = matchingJob(runtime.status(false).json);
            if (job == null) {
                return "{\"state\":\"pending\",\"path\":\"unknown\"," +
                        "\"backend\":\"\",\"encoder\":\"\"," +
                        "\"hardwareDecode\":false,\"hardwareEncode\":false}";
            }
            boolean hardwareDecode = Boolean.TRUE.equals(job.get("hardwareDecode"));
            boolean hardwareEncode = Boolean.TRUE.equals(job.get("hardwareEncode"));
            String backend = string(job.get("backend"));
            String path;
            if ("copy".equals(backend)) path = "copy";
            else if (hardwareDecode && hardwareEncode) path = "full_gpu";
            else if (hardwareEncode) path = "mixed";
            else path = "software";
            return "{\"state\":\"" + jsonEscape(string(job.get("state"))) + "\"" +
                    ",\"path\":\"" + path + "\"" +
                    ",\"backend\":\"" + jsonEscape(backend) + "\"" +
                    ",\"encoder\":\"" + jsonEscape(string(job.get("encoder"))) + "\"" +
                    ",\"hardwareDecode\":" + hardwareDecode +
                    ",\"hardwareEncode\":" + hardwareEncode + "}";
        }

        Map<String, Object> matchingJob(Map<String, Object> status) {
            Map<String, Object> best = null;
            long bestStarted = Long.MIN_VALUE;
            Object active = status.get("activeJobs");
            if (active instanceof List) {
                for (Object candidate : (List<?>) active) {
                    Map<String, Object> match = matchingMap(candidate);
                    long started = number(match == null ? null : match.get("startedEpochMs"));
                    if (match != null && started > bestStarted) {
                        best = match; bestStarted = started;
                    }
                }
            }
            for (String name : new String[]{"lastJob", "lastTranscodeJob"}) {
                Map<String, Object> match = matchingMap(status.get(name));
                long started = number(match == null ? null : match.get("startedEpochMs"));
                if (match != null && started > bestStarted) {
                    best = match; bestStarted = started;
                }
            }
            return best;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> matchingMap(Object value) {
            if (!(value instanceof Map)) return null;
            Map<String, Object> job = (Map<String, Object>) value;
            // Two handoff producers deliberately share an input path and may
            // start within the same status-cache interval. Source/time/newest
            // alone would attribute the candidate's GPU result to the original.
            // Require exact wrapper PID custody; Java8/old MIM without that
            // witness reports unknown rather than inventing hardware proof.
            long expectedPid=processId(process);
            if(expectedPid<=0||number(job.get("mimPid"))!=expectedPid)return null;
            if (!source.toString().equals(string(job.get("input")))) return null;
            // Direct media is an HLS playlist backed by the FFmpeg segment
            // muxer. Older prototypes reported `hls`; the commissioned MIM
            // correctly reports its actual output muxer as `segment`. Accept
            // both so execution proof does not remain `pending` while a real
            // owned segment job is active.
            String outputFormat = string(job.get("outputFormat"));
            if (!"segment".equalsIgnoreCase(outputFormat) &&
                    !"hls".equalsIgnoreCase(outputFormat)) return null;
            long started = number(job.get("startedEpochMs"));
            return started >= createdAtMs - 5000L ? job : null;
        }

        private long number(Object value) {
            return value instanceof Number ? ((Number) value).longValue() : Long.MIN_VALUE;
        }

        private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    }

    static long processId(Process process) {
        if(process==null)return -1;
        try{return ((Number)Process.class.getMethod("pid").invoke(process)).longValue();}
        catch(ReflectiveOperationException|RuntimeException unavailable){return -1;}
    }

    static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}

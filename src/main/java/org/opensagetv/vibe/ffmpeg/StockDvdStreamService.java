package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FFmpeg-owned finite DVD stream service, loadable on unchanged SageTV Core.
 *
 * <p>No optional sage.DVDStreamTransform* types occur in this class or its
 * nested session. The legacy Core-SPI adapter delegates here, as can a
 * stock-compatible player plugin. All process management stays in this plugin;
 * callers supply only an already-authorized navigation segment, not paths or
 * arbitrary command arguments. This service does not itself navigate a DVD.</p>
 */
public class StockDvdStreamService {
    public static final int CONTRACT_VERSION = 1;
    public static final String TRANSPORT_ID = "dvd_mpegts_v1";
    public static final String COPY_TRANSPORT_ID = "dvd_mim_copy_v1";
    public static final String TRANSCODE_TRANSPORT_ID = "dvd_mim_transcode_v1";
    static final String MODE_COPY = "copy";
    static final String MODE_TRANSCODE = "transcode";
    private static final long PROBE_CACHE_MILLIS = 30000L;
    private static volatile long lastProbeTime;
    private static volatile boolean lastAvailable;
    private static final Object STATUS_LOCK = new Object();
    private static final AtomicLong STATUS_SEQUENCE = new AtomicLong();
    private static final Map<Long, SessionStatus> SESSION_STATUS =
            new java.util.LinkedHashMap<Long, SessionStatus>();
    private static final Map<Long, Session> ACTIVE = new java.util.LinkedHashMap<Long, Session>();
    private static volatile boolean pluginRunning;
    private final String transportId;
    private final String mode;
    private final String outputFormat;

    /** Legacy negotiated transport retained for already-deployed Vibe clients. */
    public StockDvdStreamService() {
        this(TRANSPORT_ID, MODE_TRANSCODE, "mpegts");
    }

    public StockDvdStreamService(String mode) {
        this(MODE_COPY.equals(mode) ? COPY_TRANSPORT_ID : TRANSCODE_TRANSPORT_ID,
                mode, MODE_COPY.equals(mode) ? "mpegps" : "mpegts");
    }

    protected StockDvdStreamService(String transportId, String mode,
                                             String outputFormat) {
        if (!MODE_COPY.equals(mode) && !MODE_TRANSCODE.equals(mode))
            throw new IllegalArgumentException("unsupported DVD stream mode");
        this.transportId = transportId;
        this.mode = mode;
        this.outputFormat = outputFormat;
    }

    public String getTransportId() { return transportId; }
    public String getOutputFormat() { return outputFormat; }

    public boolean isAvailable() {
        if (!pluginRunning || !OwnedProcessStop.available()) return false;
        long now = System.currentTimeMillis();
        if (now - lastProbeTime < PROBE_CACHE_MILLIS) return lastAvailable;
        synchronized (StockDvdStreamService.class) {
            now = System.currentTimeMillis();
            if (now - lastProbeTime < PROBE_CACHE_MILLIS) return lastAvailable;
            RuntimePaths paths = RuntimePaths.detect();
            MimRuntime runtime = new MimRuntime(paths);
            String health = runtime.health();
            if (!"healthy".equals(health)) {
                lastAvailable = false;
                lastProbeTime = now;
                log("DVD transform unavailable: " + health);
                return false;
            }
            MimRuntime.Snapshot capabilities = runtime.capabilities(true);
            Object advertised = capabilities.json.get("dvdStreamTransform");
            lastAvailable = capabilities.ok() && Boolean.TRUE.equals(advertised);
            lastProbeTime = now;
            if (!lastAvailable && capabilities.error != null)
                log("DVD transform unavailable: " + capabilities.error);
            return lastAvailable;
        }
    }

    public Session open(String videoBitrate) throws IOException {
        if (!OwnedProcessStop.available()) throw new IOException("Owned DVD process-stop capability unavailable");
        // Typed service boundary, never an arbitrary command-line argument.
        if (videoBitrate == null || !videoBitrate.matches("[1-9][0-9]{0,7}[kKmM]?"))
            throw new IOException("invalid DVD video bitrate");
        RuntimePaths paths = RuntimePaths.detect();
        if (!java.nio.file.Files.isRegularFile(paths.mimExecutable))
            throw new IOException("MIM executable is missing: " + paths.mimExecutable);

        List<String> command = new ArrayList<String>();
        command.add((paths.windows ? paths.rootLauncher : paths.mimExecutable).toString());
        command.add("-sagetvdiscstream");
        // Fixed input-header metadata, without per-frame progress statistics.
        // The session's bounded parser retains only physical DVD SPU IDs.
        command.add("-hide_banner"); command.add("-nostats");
        command.add("-loglevel"); command.add("info");
        command.add("-f"); command.add("mpeg");
        command.add("-i"); command.add("-");
        command.add("-map"); command.add("0:v:0");
        command.add("-map"); command.add("0:a?");
        appendDvdSubtitleMaps(command);
        if (MODE_COPY.equals(mode)) {
            command.add("-c:v"); command.add("copy");
            command.add("-c:a"); command.add("copy");
            command.add("-c:s"); command.add("copy");
            // Keep the authored MPEG-PS/SPU representation intact. The MIM
            // owns and remuxes the finite VM segment, but Direct Copy must not
            // decode or encode the DVD's video or audio.  FFmpeg's generic
            // "mpeg" muxer writes MPEG-1 PES headers; feeding those bytes to a
            // DVD/VOB extractor makes the MPEG-1 PTS bytes look like the
            // MPEG-2 PES flags/header length and produces false timestamps and
            // audio types after a menu-to-title transition.  The "vob" muxer
            // writes the DVD-compatible MPEG-2 program-stream representation
            // while still performing a pure stream copy.
            command.add("-f"); command.add("vob");
        } else {
            command.add("-vcodec"); command.add("mpeg4");
            command.add("-b:v"); command.add(videoBitrate);
            command.add("-acodec"); command.add("copy");
            command.add("-c:s"); command.add("dvbsub");
            command.add("-f"); command.add("mpegts");
        }
        command.add("-");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(paths.runtimeDir.toFile());
        log("starting optional DVD transform transport " + transportId +
                " mode=" + mode);
        synchronized (STATUS_LOCK) {
            // Serialize creation with Disable's snapshot. A probe/open racing
            // stop must not create a new untracked child after cleanup.
            if (!pluginRunning) throw new IOException("FFmpeg plugin is stopped");
            SessionStatus status = registerStatus(mode, transportId);
            DvdSessionIni policy = null;
            try {
                policy = DvdSessionIni.create(paths);
                builder.environment().put("SAGETV_FFMPEG_MIM_INI", policy.path.toString());
                return new Session(OwnedProcessStop.start(builder), status, policy);
            } catch (IOException failure) {
                if (policy != null) policy.close();
                status.state = "start_failed"; throw failure;
            }
        }
    }

    static void appendDvdSubtitleMaps(List<String> command) {
        // DVD VM selectors name physical SPUs (0x20..0x3f), not FFmpeg's
        // discovery order. Nested spumux passes can put Spanish0x21 before
        // English0x20 in the VOB. Generic 0:s? then reverses their output
        // ordinals and loses that original identity after DVB conversion.
        // Optional fixed IDs preserve authored ordinals without paths, probe
        // subprocesses, a copied demuxer or a changed client/Core protocol.
        for (int stream = 0x20; stream <= 0x3f; stream++) {
            command.add("-map");
            command.add("0:i:0x" + Integer.toHexString(stream) + "?");
        }
    }

    /** Bounded, credential-free proof for the LAN-scoped commissioning API. */
    public static String statusJson(String requestedMode) {
        String selected = requestedMode == null ? "" : requestedMode.trim().toLowerCase();
        SessionStatus status = null;
        synchronized (STATUS_LOCK) {
            for (SessionStatus candidate : SESSION_STATUS.values()) {
                if ((selected.length() == 0 || selected.equals(candidate.mode)) &&
                        (status == null || candidate.startedAtMs > status.startedAtMs))
                    status = candidate;
            }
        }
        if (status == null)
            return "{\"state\":\"idle\",\"mode\":\"" + json(selected) +
                    "\",\"executionPath\":\"unknown\",\"bytesIn\":0,\"bytesOut\":0}";
        String path = MODE_COPY.equals(status.mode) ? "copy"
                : resolveTranscodePath(status.startedAtMs);
        return "{\"state\":\"" + json(status.state) + "\"" +
                ",\"sessionId\":" + status.sequence +
                ",\"mode\":\"" + json(status.mode) + "\"" +
                ",\"transportId\":\"" + json(status.transportId) + "\"" +
                ",\"executionPath\":\"" + json(path) + "\"" +
                ",\"bytesIn\":" + status.bytesIn +
                ",\"bytesOut\":" + status.bytesOut +
                ",\"startedAtMs\":" + status.startedAtMs + "}";
    }

    /** Plugin Disable owns child cleanup too; caller/player can then use Native. */
    static void stopSessions() {
        Session[] sessions;
        synchronized (STATUS_LOCK) {
            pluginRunning = false;
            sessions = ACTIVE.values().toArray(new Session[0]);
        }
        for (Session session : sessions) session.close();
        lastProbeTime = 0; lastAvailable = false;
    }
    static void startSessions() { synchronized (STATUS_LOCK) { pluginRunning = true; } }

    /** Authenticated MCP commissioning only: terminate exactly one checkpointed child.
     * No PID, path, command or executable is accepted, and stale IDs fail closed. */
    static void failProcessForTest(long expected) throws IOException {
        synchronized (STATUS_LOCK) {
            Session session = ACTIVE.get(expected);
            if (expected < 1 || session == null || ACTIVE.size() != 1 || session.closed)
                throw new IOException("Expected unique active DVD transform session changed");
            OwnedProcessStop.kill(session.process);
        }
    }

    private static SessionStatus registerStatus(String mode, String transportId) {
        SessionStatus status = new SessionStatus(STATUS_SEQUENCE.incrementAndGet(),
                mode, transportId, System.currentTimeMillis());
        synchronized (STATUS_LOCK) {
            SESSION_STATUS.put(status.sequence, status);
            while (SESSION_STATUS.size() > 32)
                SESSION_STATUS.remove(SESSION_STATUS.keySet().iterator().next());
        }
        return status;
    }

    private static String resolveTranscodePath(long startedAtMs) {
        Map<String, Object> snapshot = new MimRuntime(RuntimePaths.detect())
                .status(false).json;
        Map<String, Object> best = null;
        long bestStarted = Long.MIN_VALUE;
        Object active = snapshot.get("activeJobs");
        if (active instanceof List) {
            for (Object value : (List<?>) active) {
                Map<String, Object> job = statusJob(value, startedAtMs);
                long jobStarted = number(job == null ? null : job.get("startedEpochMs"));
                if (job != null && jobStarted > bestStarted) {
                    best = job; bestStarted = jobStarted;
                }
            }
        }
        for (String name : new String[]{"lastJob", "lastTranscodeJob"}) {
            Map<String, Object> job = statusJob(snapshot.get(name), startedAtMs);
            long jobStarted = number(job == null ? null : job.get("startedEpochMs"));
            if (job != null && jobStarted > bestStarted) {
                best = job; bestStarted = jobStarted;
            }
        }
        if (best == null) return "unknown";
        boolean hardwareDecode = Boolean.TRUE.equals(best.get("hardwareDecode"));
        boolean hardwareEncode = Boolean.TRUE.equals(best.get("hardwareEncode"));
        String backend = String.valueOf(best.get("backend"));
        if ("copy".equals(backend)) return "copy";
        if (hardwareDecode && hardwareEncode) return "full_gpu";
        if (hardwareEncode) return "mixed";
        return "software";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> statusJob(Object value, long startedAtMs) {
        if (!(value instanceof Map)) return null;
        Map<String, Object> job = (Map<String, Object>) value;
        long jobStarted = number(job.get("startedEpochMs"));
        String format = String.valueOf(job.get("outputFormat"));
        if (jobStarted < startedAtMs - 5000L ||
                !("mpegts".equalsIgnoreCase(format) || "mpeg".equalsIgnoreCase(format)))
            return null;
        return job;
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : Long.MIN_VALUE;
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static final class SessionStatus {
        final long sequence;
        final String mode;
        final String transportId;
        final long startedAtMs;
        volatile String state = "active";
        volatile long bytesIn;
        volatile long bytesOut;

        SessionStatus(long sequence, String mode, String transportId, long startedAtMs) {
            this.sequence = sequence;
            this.mode = mode;
            this.transportId = transportId;
            this.startedAtMs = startedAtMs;
        }
    }

    static boolean capabilitiesAdvertiseTransform(Map<String, Object> capabilities) {
        return capabilities != null && Boolean.TRUE.equals(capabilities.get("dvdStreamTransform"));
    }

    private static void log(String message) {
        System.out.println("[SageTVFFmpegPlugin] " + message);
    }

    public static final class Session implements java.io.Closeable {
        private static final int OUTPUT_CHUNK_SIZE = 32768;
        private static final int OUTPUT_QUEUE_CHUNKS = 128;
        private final Process process;
        private final DvdSessionIni policy;
        private final SessionStatus status;
        private final OutputStream input;
        private final InputStream output;
        private final DvdSpuInventory sourceSubpictures = new DvdSpuInventory();
        private final ArrayBlockingQueue<byte[]> outputQueue =
                new ArrayBlockingQueue<byte[]>(OUTPUT_QUEUE_CHUNKS);
        private volatile IOException outputFailure;
        private volatile boolean outputEnded;
        private volatile boolean inputClosed;
        private volatile boolean closed;
        private Thread outputReader;

        Session(Process process, SessionStatus status, DvdSessionIni policy) {
            this.process = process;
            this.policy = policy;
            this.status = status;
            input = process.getOutputStream();
            output = process.getInputStream();
            synchronized (STATUS_LOCK) { ACTIVE.put(status.sequence, this); }
            startOutputReader();
            startErrorReader(process.getErrorStream());
        }

        public void write(byte[] data, int offset, int length) throws IOException {
            if (closed) throw new IOException("DVD transform session is closed");
            if (outputFailure != null) throw outputFailure;
            if (inputClosed) throw new IOException("DVD transform input is already closed");
            input.write(data, offset, length);
            status.bytesIn += length;
        }

        public byte[] pollOutput(long timeoutMillis) throws IOException {
            if (timeoutMillis < 0 || timeoutMillis > 30000)
                throw new IllegalArgumentException("DVD output wait exceeds bound");
            if (outputFailure != null) throw outputFailure;
            try {
                return timeoutMillis <= 0 ? outputQueue.poll()
                        : outputQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for DVD transform output", e);
            }
        }

        public boolean isOutputEnded() {
            return outputEnded && outputFailure == null && outputQueue.isEmpty();
        }

        /** Null until the process has completed its input-stream inventory. */
        public int[] getSourceSubpictureIds() throws IOException {
            return sourceSubpictures.snapshot();
        }

        public void closeInput() {
            if (inputClosed) return;
            inputClosed = true;
            try { input.close(); } catch (IOException ignored) {}
        }

        public void close() {
            if (closed) return;
            closed = true;
            synchronized (STATUS_LOCK) { ACTIVE.remove(status.sequence); }
            // Terminate the child before closing its buffered stdin: another
            // writer can hold that stream's monitor while the child is blocked
            // on a full output queue. Closing stdin first would deadlock Stop.
            try { OwnedProcessStop.kill(process); }
            catch (IOException failure) { log("DVD stop failed: " + failure.getMessage()); }
            if (outputReader != null) outputReader.interrupt();
            outputQueue.clear();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    try { OwnedProcessStop.kill(process); } catch (IOException ignored) { }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                try { OwnedProcessStop.kill(process); } catch (IOException ignored) { }
            }
            closeInput();
            try { output.close(); } catch (IOException ignored) {}
            outputQueue.clear();
            status.state = "released";
            policy.close();
        }

        private void startOutputReader() {
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    byte[] buffer = new byte[OUTPUT_CHUNK_SIZE];
                    try {
                        int read;
                        while (!closed && (read = output.read(buffer)) >= 0) {
                            if (read == 0) continue;
                            byte[] chunk = new byte[read];
                            System.arraycopy(buffer, 0, chunk, 0, read);
                            while (!closed && !outputQueue.offer(chunk, 50, TimeUnit.MILLISECONDS)) { }
                            status.bytesOut += read;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (!closed) outputFailure = new IOException("DVD output reader interrupted", e);
                    } catch (IOException e) {
                        if (!closed) outputFailure = e;
                    } finally {
                        // Closing stdout is not evidence of a successful
                        // encoder. A child may emit a header then exit with
                        // an error, or close stdout while remaining stuck.
                        // Bound that distinction so a stock caller can take
                        // its native fallback rather than accept clean EOS.
                        if (!closed && outputFailure == null) {
                            try {
                                if (!process.waitFor(2, TimeUnit.SECONDS))
                                    outputFailure = new IOException("DVD process did not exit after output EOF");
                                else if (process.exitValue() != 0)
                                    outputFailure = new IOException("DVD process exited with code " + process.exitValue());
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                outputFailure = new IOException("Interrupted checking DVD process exit", e);
                            }
                        }
                        outputEnded = true;
                        if (!closed)
                            status.state = outputFailure == null ? "complete" : "failed";
                    }
                }
            }, "DVD-Transform-Output");
            outputReader = reader;
            reader.setDaemon(true);
            reader.start();
        }

        private void startErrorReader(final InputStream errors) {
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    byte[] buffer = new byte[4096];
                    try {
                        int read;
                        while ((read = errors.read(buffer)) >= 0) {
                            if (read > 0) sourceSubpictures.accept(buffer, 0, read);
                            if (read > 0)
                                log(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        }
                    } catch (IOException ignored) {
                    } finally {
                        try { errors.close(); } catch (IOException ignored) {}
                    }
                }
            }, "DVD-Transform-Errors");
            reader.setDaemon(true);
            reader.start();
        }
    }
}

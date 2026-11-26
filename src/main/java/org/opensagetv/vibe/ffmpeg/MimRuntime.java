package org.opensagetv.vibe.ffmpeg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

final class MimRuntime {
    private final RuntimePaths paths;
    private volatile Snapshot status = Snapshot.empty();
    private volatile Snapshot capabilities = Snapshot.empty();
    private volatile Snapshot hardwareTest = Snapshot.empty();

    MimRuntime(RuntimePaths paths) { this.paths = paths; }

    /** A preview is a bounded ordinary MIM thumbnail, never a second playback
     * stream. Require the already qualified Linux child-isolation policy and
     * transparent mode; an administrator's always-transcode policy must not
     * silently turn a JPEG request into a hardware H.264 job. Windows remains
     * unadvertised until its distinct physical cancellation gate qualifies. */
    boolean thumbnailSupported() {
        if (paths.windows || !"healthy".equals(health())) return false;
        try {
            IniDocument ini=IniDocument.load(paths.ini);
            return ini.getBoolean("general","enabled",true)
                    && "passthrough".equalsIgnoreCase(ini.get("general","mode","passthrough").trim())
                    && ini.getBoolean("safety","isolate_child_process",true);
        } catch(IOException unavailable) { return false; }
    }

    ThumbnailJob thumbnail(Path source,long positionMs) {
        if(source==null||positionMs<0||positionMs>14L*86400000L)
            throw new IllegalArgumentException("invalid_preview_position");
        return new ThumbnailJob(source,positionMs);
    }

    /** The existing runtime owner retains the process handle through teardown.
     * No source/output path is supplied by HTTP callers and no image is written
     * to server files. Parent-death isolation (qualified above) also retires the
     * exact Linux child if a cancelled wrapper needs forcible termination. */
    final class ThumbnailJob {
        private final Path source;
        private final long positionMs;
        private volatile Process process;
        private volatile boolean cancelled;
        ThumbnailJob(Path source,long positionMs){this.source=source;this.positionMs=positionMs;}
        synchronized void cancel(){cancelled=true;if(process!=null)process.destroy();}
        byte[] run() throws IOException {
            if(!thumbnailSupported())throw new IOException("preview_runtime_unavailable");
            Process owned=null;FutureTask<byte[]> reader=null;
            try {
                ProcessBuilder builder=new ProcessBuilder(paths.mimExecutable.toString(),
                        "-hide_banner","-loglevel","error","-ss",
                        String.format(java.util.Locale.US,"%.3f",positionMs/1000.0),
                        "-i",source.toString(),"-map","0:v:0","-an","-sn",
                        "-threads","1","-frames:v","1","-vf","scale=192:108",
                        "-c:v","mjpeg","-q:v","5","-f","image2pipe","pipe:1");
                builder.directory(paths.runtimeDir.toFile());
                builder.environment().put("SAGETV_FFMPEG_MIM_INI",paths.ini.toString());
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);
                synchronized(this){
                    if(cancelled)throw new IOException("preview_cancelled");
                    process=owned=builder.start();
                }
                final InputStream input=owned.getInputStream();
                reader=new FutureTask<byte[]>(()->{
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    byte[] buffer=new byte[2048];int count;
                    while((count=input.read(buffer))!=-1){
                        if(bytes.size()+count>32768){cancel();throw new IOException("preview_image_too_large");}
                        bytes.write(buffer,0,count);
                    }
                    return bytes.toByteArray();
                });
                Thread output=new Thread(reader,"vibe-mim-preview-output");output.setDaemon(true);output.start();
                if(!owned.waitFor(3000,TimeUnit.MILLISECONDS))throw new IOException("preview_timeout");
                byte[] image=reader.get(500,TimeUnit.MILLISECONDS);
                if(cancelled||owned.exitValue()!=0)throw new IOException("preview_cancelled_or_failed");
                if(image.length<4||(image[0]&255)!=255||(image[1]&255)!=216
                        ||(image[image.length-2]&255)!=255||(image[image.length-1]&255)!=217)
                    throw new IOException("preview_invalid_jpeg");
                return image;
            } catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();throw new IOException("preview_interrupted");
            } catch(ExecutionException failed){throw new IOException("preview_output_failed");
            } catch(TimeoutException timedOut){throw new IOException("preview_output_timeout");
            } finally {
                if(owned!=null){
                    owned.destroy();
                    try {if(!owned.waitFor(500,TimeUnit.MILLISECONDS))owned.destroyForcibly();}
                    catch(InterruptedException interrupted){owned.destroyForcibly();Thread.currentThread().interrupt();}
                    try {owned.getInputStream().close();}catch(IOException ignored){}
                }
                if(reader!=null)reader.cancel(true);
                synchronized(this){process=null;}
            }
        }
    }

    Snapshot status(boolean force) {
        long now = System.currentTimeMillis();
        Snapshot current = status;
        if (!force && now - current.timeMs < 1000) return current;
        status = query("--mim-status");
        return status;
    }

    Snapshot capabilities(boolean force) {
        long now = System.currentTimeMillis();
        Snapshot current = capabilities;
        if (!force && now - current.timeMs < 30000) return current;
        capabilities = query("--mim-capabilities");
        return capabilities;
    }

    Snapshot hardwareTest() { return hardwareTest; }

    Snapshot runHardwareTest() {
        Snapshot live = status(true);
        Object jobs = live.json.get("activeJobs");
        if (live.ok() && jobs instanceof List && !((List<?>) jobs).isEmpty()) {
            hardwareTest = Snapshot.error(System.currentTimeMillis(),
                    "hardware test refused while MIM transcode jobs are active");
            return hardwareTest;
        }
        hardwareTest = query("--mim-hardware-test", 120, 524288);
        return hardwareTest;
    }

    String health() {
        if (!Files.isRegularFile(paths.mimExecutable)) return "missing MIM wrapper";
        if (!Files.isRegularFile(paths.ffmpegExecutable)) return "missing FFmpeg runtime";
        if (!Files.isRegularFile(paths.ffprobeExecutable)) return "missing FFprobe runtime";
        if (!paths.windows && !Files.isExecutable(paths.mimExecutable)) return "MIM wrapper is not executable";
        if (!paths.windows && !Files.isExecutable(paths.ffmpegExecutable)) return "FFmpeg runtime is not executable";
        if (!paths.windows && !Files.isExecutable(paths.ffprobeExecutable)) return "FFprobe runtime is not executable";
        if (!Files.isRegularFile(paths.defaultIni)) return "missing default INI";
        if (!Files.isRegularFile(paths.ini)) return "missing live INI";
        return "healthy";
    }

    String repairExecutablePermissions() {
        if (paths.windows) return "OK";
        String failure = makeExecutable(paths.mimExecutable, "MIM wrapper");
        if (failure != null) return failure;
        failure = makeExecutable(paths.ffmpegExecutable, "FFmpeg runtime");
        if (failure != null) return failure;
        failure = makeExecutable(paths.ffprobeExecutable, "FFprobe runtime");
        return failure == null ? "OK" : failure;
    }

    private static String makeExecutable(java.nio.file.Path path, String label) {
        if (!Files.isRegularFile(path)) return "FAILED: " + label + " missing: " + path;
        if (Files.isExecutable(path)) return null;
        return path.toFile().setExecutable(true, false)
                ? null : "FAILED: unable to make " + label + " executable: " + path;
    }

    private Snapshot query(String arg) { return query(arg, 7, 262144); }

    private Snapshot query(String arg, long timeoutSeconds, int maximumBytes) {
        long now = System.currentTimeMillis();
        if (!Files.isRegularFile(paths.mimExecutable)) return Snapshot.error(now, "MIM executable missing: " + paths.mimExecutable);
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(paths.mimExecutable.toString(), arg);
            pb.directory(paths.runtimeDir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("SAGETV_FFMPEG_MIM_INI", paths.ini.toString());
            process = pb.start();
            boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
                return Snapshot.error(now, arg + " timed out");
            }
            String raw = readAll(process.getInputStream(), maximumBytes).trim();
            if (process.exitValue() != 0) return Snapshot.error(now, arg + " exit=" + process.exitValue() + " output=" + raw);
            Map<String, Object> parsed = raw.startsWith("{") ? MiniJson.object(raw) : Collections.<String,Object>emptyMap();
            return new Snapshot(now, raw, parsed, null);
        } catch (Exception e) {
            return Snapshot.error(now, e.toString());
        } finally {
            if (process != null) process.destroy();
        }
    }

    static Object path(Map<String, Object> map, String dotted) {
        Object cur = map;
        String[] parts = dotted.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (!(cur instanceof Map)) return null;
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) cur;
            cur = m.get(parts[i]);
        }
        return cur;
    }

    static Object lastTranscode(Map<String, Object> map, String field) {
        Object job = map.get("lastTranscodeJob");
        if (!(job instanceof Map)) return null;
        @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>) job;
        return m.get(field);
    }

    static String string(Object value) { return value == null ? "" : String.valueOf(value); }

    private static String readAll(InputStream in, int maximumBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) >= 0) {
            if (out.size() + n > maximumBytes) throw new IOException("MIM query output exceeded " + maximumBytes + " bytes");
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static final class Snapshot {
        final long timeMs;
        final String raw;
        final Map<String, Object> json;
        final String error;
        Snapshot(long timeMs, String raw, Map<String,Object> json, String error) {
            this.timeMs = timeMs; this.raw = raw; this.json = json; this.error = error;
        }
        static Snapshot empty() { return new Snapshot(0, "", Collections.<String,Object>emptyMap(), "not queried"); }
        static Snapshot error(long timeMs, String error) { return new Snapshot(timeMs, "", Collections.<String,Object>emptyMap(), error); }
        boolean ok() { return error == null; }
    }
}

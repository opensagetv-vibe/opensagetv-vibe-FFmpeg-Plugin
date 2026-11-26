package org.opensagetv.vibe.ffmpeg;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One job's authoritative-policy snapshot with bounded DVD stream probing.
 * MIM's normal transcode log policy overrides CLI loglevel. Do not edit the
 * shared user INI, change GPU/codec policy, or require a new wrapper merely to
 * retain DVD input IDs. MIM resolves relative paths against its executable,
 * not this INI's directory. The caller owns and closes this generated file.
 */
final class DvdSessionIni implements Closeable {
    final Path path;
    private DvdSessionIni(Path path) { this.path = path; }

    static DvdSessionIni create(RuntimePaths paths) throws IOException {
        if (Files.size(paths.ini) > 1048576L) throw new IOException("DVD policy exceeds bound");
        IniDocument policy = IniDocument.load(paths.ini);
        policy.set("logging", "ffmpeg_transcode_loglevel", "info");
        // Read child input metadata directly, using the plugin's independent
        // stderr reader. MIM's Linux raw-input pump can block forwarding stdin
        // before it drains its mirrored stderr pipe, creating a circular wait
        // when the first output is held for identity. No engine change needed;
        // errors still reach SageTV through this session's error reader.
        policy.set("logging", "log_ffmpeg_stderr", "false");
        // Ordinary TV's half-second probe can miss an SPU after a DVD seek.
        // Bound read-ahead, not wall-clock playback: this pipe is fed as fast
        // as the reader permits. Never change the shared TV/live policy.
        policy.set("input", "probesize_mode", "override");
        policy.set("input", "probesize", "8000000");
        policy.set("input", "analyzeduration_mode", "override");
        policy.set("input", "analyzeduration", "5000000");
        Path directory = paths.runtimeDir.resolve("cache").resolve("dvd-sessions");
        Files.createDirectories(directory);
        Path path = Files.createTempFile(directory, "dvd-", ".ini");
        try { policy.saveTo(path); return new DvdSessionIni(path); }
        catch (IOException failure) { Files.deleteIfExists(path); throw failure; }
    }

    public void close() {
        try { Files.deleteIfExists(path); }
        catch (IOException failure) { path.toFile().deleteOnExit(); }
    }
}

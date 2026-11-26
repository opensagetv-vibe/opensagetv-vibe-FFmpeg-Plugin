package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Java8 Windows fallback ONLY for processes started through the verified
 * plugin launcher. That native launcher creates a fail-closed kill-on-close
 * Job Object; public Process.destroyForcibly terminates it before closing
 * Java's streams, releasing all downstream pipe holders. Never infer a PID,
 * kill by name, reflect into ProcessImpl or accept an unregistered Process.
 */
final class WindowsJobProcessStop {
    private static final Map<Process, Boolean> OWNED =
            Collections.synchronizedMap(new WeakHashMap<Process, Boolean>());
    private WindowsJobProcessStop() { }

    static boolean available() {
        try {
            RuntimePaths paths=RuntimePaths.detect();
            return paths.windows && launcherMatches(paths.rootLauncher, paths.canonicalLauncher);
        } catch (IOException | RuntimeException unavailable) { return false; }
    }

    static boolean launcherMatches(Path root, Path canonical) throws IOException {
        if (!Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(root)==0 || Files.size(root)!=Files.size(canonical)) return false;
        try { return MessageDigest.isEqual(digest(root),digest(canonical)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
    private static byte[] digest(Path path) throws IOException, java.security.NoSuchAlgorithmException {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(java.io.InputStream in=Files.newInputStream(path)) {
            byte[] bytes=new byte[32768];int n;
            while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);
        }
        return digest.digest();
    }
    static Process start(ProcessBuilder builder) throws IOException {
        RuntimePaths paths=RuntimePaths.detect();
        if (!available() || builder.command().isEmpty() ||
                !new java.io.File(builder.command().get(0)).getCanonicalFile().equals(paths.rootLauncher.toFile().getCanonicalFile()))
            throw new IOException("Verified Windows job launcher required");
        Process process=builder.start();
        OWNED.put(process,Boolean.TRUE);
        return process;
    }
    static void kill(Process process) throws IOException {
        if (process==null) throw new IOException("Owned Process required");
        if (!process.isAlive()) { OWNED.remove(process); return; }
        if (!OWNED.containsKey(process))
            throw new IOException("Process was not started by the Windows job owner");
        process.destroyForcibly();
        // Retain the weak identity while exit is unconfirmed, including repeat
        // requests. No worker, timeout, command line or PID registry is needed.
        if (!process.isAlive()) OWNED.remove(process);
    }
}

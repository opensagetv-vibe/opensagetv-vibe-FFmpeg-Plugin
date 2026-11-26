package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;

/** Versioned, optional process-lifecycle utility shared by Vibe plugins.
 *
 * The caller must supply the actual Process it created and retain ownership
 * until exit is verified. No PID, shell command, source path or Core object is
 * accepted. This reuses the DVD provider's existing pipe-lock-safe tree stop;
 * it does not introduce a second implementation or a remote control endpoint.
 * Java8 linkage is retained. Java9 uses public ProcessHandle; Java8 Windows
 * additionally supports only registered, verified native-job launcher roots.
 */
public final class OwnedProcessControl {
    private OwnedProcessControl() { }

    public static int protocolVersion() { return 1; }

    public static boolean available() { return OwnedProcessStop.available(); }

    /** Preserve ProcessBuilder environment, directory and redirects. On Java8
     * Windows only the byte-verified plugin root launcher is admissible. */
    public static Process start(ProcessBuilder builder) throws IOException {
        if (builder==null) throw new IOException("ProcessBuilder required");
        return OwnedProcessStop.start(builder);
    }

    /** Request termination; caller separately verifies exit before replacement. */
    public static void terminate(Process ownedProcess) throws IOException {
        if (ownedProcess == null) throw new IOException("Owned Process reference required");
        OwnedProcessStop.kill(ownedProcess);
    }
}

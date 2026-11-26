package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.stream.Stream;

/** Stop only this Process and its OS-owned descendants, without pipe locks.
 * Java Process.destroy() closes buffered stdin synchronously: a blocked writer
 * can hold that monitor forever while MIM waits on a full child-output pipe.
 * Public Java9 ProcessHandle avoids those stream monitors. Reflection keeps
 * stock Java8 linkage valid; Windows Java8 can instead use the verified,
 * registered native job launcher. Other unsupported runtimes still decline.
 * No Core internals, caller PID, shell, executable or arbitrary method is used.
 */
final class OwnedProcessStop {
    private OwnedProcessStop() { }
    static boolean available() {
        return processHandleAvailable() || WindowsJobProcessStop.available();
    }
    static boolean processHandleAvailable() {
        try {
            Process.class.getMethod("toHandle");
            Class.forName("java.lang.ProcessHandle").getMethod("destroyForcibly");
            return true;
        } catch (ReflectiveOperationException | LinkageError unsupported) { return false; }
    }
    static Process start(ProcessBuilder builder) throws IOException {
        return !processHandleAvailable() && WindowsJobProcessStop.available()
                ? WindowsJobProcessStop.start(builder) : builder.start();
    }
    static void kill(Process process) throws IOException {
        if (!processHandleAvailable()) { WindowsJobProcessStop.kill(process); return; }
        try {
            Class<?> type = Class.forName("java.lang.ProcessHandle");
            Object root = Process.class.getMethod("toHandle").invoke(process);
            Method kill = type.getMethod("destroyForcibly");
            Object[] children;
            try (Stream<?> stream = (Stream<?>)type.getMethod("descendants").invoke(root)) {
                children = stream.toArray();
            }
            // Release downstream pipe holders before terminating the wrapper.
            for (int i = children.length - 1; i >= 0; i--) kill.invoke(children[i]);
            kill.invoke(root);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("Owned DVD process-stop capability unavailable", failure);
        }
    }
}

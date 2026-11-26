# Shared owned-process control — protocol1

Development0.1.26 exposes `org.opensagetv.vibe.ffmpeg.OwnedProcessControl`.
It shares the existing DVD `OwnedProcessStop` implementation; it is not an
HTTP/MCP endpoint or an alternative process-ownership registry.

- `protocolVersion()` returns1.
- `available()` requires public Java9+ ProcessHandle support. The class links
  on Java8 but does not claim an unavailable safe tree-stop capability.
- `terminate(Process)` accepts only the actual Process reference the calling
  plugin created. It accepts no PID, command, executable or source/output path.
  It requests owned descendant/root termination without synchronously closing
  Java's buffered pipe monitors. The caller must independently verify exit.

The consumer retains the Process, session ownership and output directory until
termination completes; it must not start an overlapping replacement or claim
successful cleanup from the wrapper PID exiting alone. Close Java streams only
after the process tree has been terminated and blocked writers can retire.
An absent/older/unsupported provider must decline managed pipe startup safely,
not fall back to `Process.destroy()` while an input writer may hold its monitor.
Do not duplicate this implementation or reach into SageTV Core.

Evidence: Web WEB-097's actual stock232 JVM trace shows its feeder blocked in
writeBytes and its HTTP Stop blocked in Process.destroy/stdin.close. The browser
cannot release that server OS pipe through stock playback events. Independent
package tests exercise actual blocked duplex IO, bounded stop and preservation
of an unrelated process. Windows physical qualification remains deferred.

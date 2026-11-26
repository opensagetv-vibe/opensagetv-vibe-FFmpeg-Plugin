package org.opensagetv.processapitest;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.opensagetv.vibe.ffmpeg.OwnedProcessControl;

/** Independent package models an optional Web/plugin consumer, not Core. */
public final class ExternalOwnedProcessControlSmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        check(OwnedProcessControl.protocolVersion() == 1, "versioned public boundary");
        try { OwnedProcessControl.terminate(null); throw new AssertionError("null accepted"); }
        catch (IOException expected) { }
        if (!OwnedProcessControl.available()) {
            System.out.println("SKIP actual stop: public ProcessHandle unavailable; Java8 linkage passed");
            return;
        }
        final Process owned = OwnedProcessControl.start(new ProcessBuilder("/bin/sh", "-c",
                "dd if=/dev/zero bs=32768 count=4096 2>/dev/null & wait"));
        Process unrelated = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        Thread writer = new Thread(new Runnable() { public void run() {
            byte[] bytes = new byte[32768];
            try { for (int i=0; i<512; i++) owned.getOutputStream().write(bytes); }
            catch (IOException expected) { }
        }}, "process-api-blocked-writer");
        writer.setDaemon(true); writer.start();
        try {
            Thread.sleep(250);
            check(writer.isAlive(), "stdin writer actually blocked");
            check(owned.isAlive(), "owned duplex producer live before stop");
            long start = System.nanoTime();
            OwnedProcessControl.terminate(owned);
            check(owned.waitFor(2, TimeUnit.SECONDS), "owned root exited");
            writer.join(1000);
            check(!writer.isAlive(), "blocked stdin monitor released");
            check(System.nanoTime()-start < 3000000000L, "shared stop bounded");
            check(unrelated.isAlive(), "unrelated process preserved");
            OwnedProcessControl.terminate(owned); // repeat on same exited handle only
            System.out.println("PASS public versioned API, blocked duplex stop, writer exit, unrelated preservation");
        } finally {
            OwnedProcessControl.terminate(owned);
            OwnedProcessControl.terminate(unrelated);
            owned.waitFor(2, TimeUnit.SECONDS); unrelated.waitFor(2, TimeUnit.SECONDS);
        }
    }
}

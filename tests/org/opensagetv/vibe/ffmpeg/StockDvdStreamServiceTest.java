package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Runs WITHOUT the optional Core-SPI compile stubs on its runtime classpath. */
public final class StockDvdStreamServiceTest {
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static void assertInvalidInventory(String stream) throws Exception {
        DvdSpuInventory inventory = new DvdSpuInventory();
        byte[] bytes = ("Input #0, mpeg\n" + stream + "Stream mapping:\n").getBytes("UTF-8");
        inventory.accept(bytes, 0, bytes.length);
        try { inventory.snapshot(); throw new AssertionError("Unproven identity accepted"); }
        catch (IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        DvdSpuInventory inventory = new DvdSpuInventory();
        byte[] header = ("Input #0, mpeg, from 'pipe:':\n"
                + "  Stream #0:3[0x22]: Subtitle: dvd_subtitle\n"
                + "  Stream #0:4[0x20]: Subtitle: dvd_subtitle\n"
                + "Stream mapping:\n"
                + "Output #0, mpegts, to 'pipe:':\n"
                + "  Stream #0:3[0x120]: Subtitle: dvb_subtitle\n").getBytes("UTF-8");
        if (inventory.snapshot() != null) throw new AssertionError("No guessed pre-probe map");
        for (byte item : header) inventory.accept(new byte[]{item}, 0, 1);
        if (!java.util.Arrays.equals(inventory.snapshot(), new int[]{32,34}))
            throw new AssertionError("Physical sparse input IDs, not output/discovery ordinals");
        assertInvalidInventory("  Stream #0:1: Subtitle: dvd_subtitle\n");
        assertInvalidInventory("  Stream #0:1[0x120]: Subtitle: dvd_subtitle\n");
        assertInvalidInventory("  Stream #0:1[0x20]: Subtitle: unrecognized_codec\n");
        char[] oversized = new char[4097]; java.util.Arrays.fill(oversized, 'x');
        assertInvalidInventory(new String(oversized) + "\n");
        DvdSpuInventory unfinished = new DvdSpuInventory();
        byte[] incomplete = "Input #0, mpeg\n  Stream #0:1[0x20]: Subtitle: dvd_subtitle\n".getBytes("UTF-8");
        unfinished.accept(incomplete, 0, incomplete.length);
        check(unfinished.snapshot() == null, "partial inventory must not authorize transformed OPEN");
        try { Class.forName("sage.DVDStreamTransform"); throw new AssertionError("SPI unexpectedly present"); }
        catch (ClassNotFoundException expected) { }
        Path home = Paths.get(args[0]);
        Path runtime = home.resolve("plugins/SageTVFFmpegPlugin/runtime");
        Files.createDirectories(runtime);
        Path sharedIni = runtime.resolve("ffmpeg.real.ini");
        byte[] sharedPolicy = ("; keep comments\n[logging]\nffmpeg_transcode_loglevel=error\n"
                + "[hardware]\nbackend=vaapi\nunknown_user_key=retain\n").getBytes(StandardCharsets.UTF_8);
        Files.write(sharedIni, sharedPolicy);
        Path executable = runtime.resolve("ffmpeg_MIM");
        Files.write(executable, "#!/bin/sh\ncat\n".getBytes(StandardCharsets.UTF_8));
        check(executable.toFile().setExecutable(true), "test runtime executable");
        System.setProperty("vibe.ffmpeg.sageHome", home.toString());
        DvdSessionIni snapshot = DvdSessionIni.create(RuntimePaths.detect());
        check("info".equals(IniDocument.load(snapshot.path).get("logging", "ffmpeg_transcode_loglevel", "")), "job probe enabled");
        check("retain".equals(IniDocument.load(snapshot.path).get("hardware", "unknown_user_key", "")), "unknown user policy retained");
        check("5000000".equals(IniDocument.load(snapshot.path).get("input", "analyzeduration", "")), "DVD-only bounded sparse probe");
        check(!IniDocument.load(snapshot.path).getBoolean("logging", "log_ffmpeg_stderr", true), "independent stderr avoids raw-input circular wait");
        check(java.util.Arrays.equals(sharedPolicy, Files.readAllBytes(sharedIni)), "shared INI byte-identical");
        Path snapshotPath = snapshot.path; snapshot.close();
        check(!Files.exists(snapshotPath), "owned snapshot retired on close");
        StockDvdStreamService service = new StockDvdStreamService("copy");
        StockDvdStreamService.startSessions();
        java.util.List<String> subtitleMaps = new java.util.ArrayList<String>();
        StockDvdStreamService.appendDvdSubtitleMaps(subtitleMaps);
        check(subtitleMaps.size() == 64, "bounded32 physical DVD SPU maps");
        check("0:i:0x20?".equals(subtitleMaps.get(1)), "first authored SPU before probe-first Spanish");
        check("0:i:0x3f?".equals(subtitleMaps.get(63)), "last authored SPU optional");
        check(!subtitleMaps.contains("0:s?"), "never discovery-order subtitle ordinals");
        check(StockDvdStreamService.CONTRACT_VERSION == 1, "explicit stock service version");
        check("dvd_mim_copy_v1".equals(service.getTransportId()), "copy contract retained");
        // Resolving every public method/nested session type must not link an
        // absent sage.DVDStreamTransform* interface on an unchanged server.
        service.getClass().getMethods(); StockDvdStreamService.Session.class.getMethods();
        try { service.open("7M -i /private"); throw new AssertionError("invalid argument accepted"); }
        catch (IOException expected) { }
        try { new StockDvdStreamService("unexpected"); throw new AssertionError("invalid mode accepted"); }
        catch (IllegalArgumentException expected) { }
        StockDvdStreamService.Session session = service.open("7M");
        byte[] payload = "finite DVD segment".getBytes(StandardCharsets.UTF_8);
        try {
            session.write(payload, 0, payload.length); session.closeInput();
            byte[] output = session.pollOutput(2000);
            check(java.util.Arrays.equals(payload, output), "stock-only finite transform round trip");
        } finally { session.close(); }
        // Force the bounded output queue to fill. Closing must terminate the
        // child/read worker even when no consumer drains more than4MiB.
        Files.write(executable, "#!/bin/sh\nexec dd if=/dev/zero bs=32768 count=512 2>/dev/null\n".getBytes(StandardCharsets.UTF_8));
        StockDvdStreamService.Session blocked = service.open("7M");
        long deadline = System.nanoTime() + 5000000000L;
        while (!StockDvdStreamService.statusJson("copy").contains("\"bytesOut\":4194304")
                && System.nanoTime() < deadline) Thread.sleep(20);
        long start = System.nanoTime(); blocked.close();
        check(System.nanoTime() - start < 3000000000L, "full output queue close is bounded");
        blocked.close();
        // Both pipes blocked: a child never reads stdin while producing more
        // than the output queue. Java11 Process.destroy used to deadlock on
        // the writer's buffered-stream monitor (physical VCE-014 stack).
        Files.write(executable, ("#!/bin/sh\n(dd if=/dev/zero bs=32768 count=4096 2>/dev/null) &\nwait\n").getBytes(StandardCharsets.UTF_8));
        final StockDvdStreamService.Session duplex = service.open("7M");
        Thread writer = new Thread(() -> {
            byte[] large = new byte[32768];
            try { for (int i = 0; i < 512; i++) duplex.write(large, 0, large.length); }
            catch (IOException expectedClose) { }
        });
        writer.setDaemon(true); writer.start(); Thread.sleep(200);
        Thread closer = new Thread(() -> duplex.close());
        closer.setDaemon(true); closer.start(); closer.join(3000); writer.join(1000);
        check(!closer.isAlive() && !writer.isAlive(), "both blocked pipes close without Process.destroy monitor deadlock");
        try (java.util.stream.Stream<Path> snapshots = Files.list(runtime.resolve("cache/dvd-sessions"))) {
            check(!snapshots.findAny().isPresent(), "all owned session snapshots cleaned");
        }
        try { blocked.write(payload, 0, payload.length); throw new AssertionError("closed session accepted input"); }
        catch (IOException expected) { }
        // An encoder can emit a header and then fail. EOF alone must not
        // advertise successful completion or suppress the caller's fallback.
        Files.write(executable, "#!/bin/sh\nprintf bad\nexit 7\n".getBytes(StandardCharsets.UTF_8));
        StockDvdStreamService.Session failed = service.open("7M");
        try {
            boolean observedFailure = false;
            long failureDeadline = System.nanoTime() + 3000000000L;
            while (System.nanoTime() < failureDeadline && !observedFailure) {
                try { failed.pollOutput(50); }
                catch (IOException expected) { observedFailure = expected.getMessage().contains("7"); }
            }
            check(observedFailure, "nonzero process exit must be a failed output, not clean EOF");
            check(!failed.isOutputEnded(), "failed output cannot claim clean completion");
        } finally { failed.close(); }
        Files.write(executable, "#!/bin/sh\nexec cat\n".getBytes(StandardCharsets.UTF_8));
        StockDvdStreamService.Session disabled = service.open("7M");
        StockDvdStreamService.stopSessions();
        try { disabled.write(payload, 0, payload.length); throw new AssertionError("Disable retained child"); }
        catch (IOException expected) { }
        try { service.open("7M"); throw new AssertionError("stopped provider created a new child"); }
        catch (IOException expected) { }
        StockDvdStreamService.startSessions();
        StockDvdStreamService.Session killed = service.open("7M");
        try {
            String status = StockDvdStreamService.statusJson("copy");
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"sessionId\":([0-9]+)").matcher(status);
            check(id.find(), "status exposes bounded session identity, not PID/path");
            long sequence = Long.parseLong(id.group(1));
            try { StockDvdStreamService.failProcessForTest(sequence - 1); throw new AssertionError("stale session killed"); }
            catch (IOException expected) { }
            StockDvdStreamService.failProcessForTest(sequence);
            boolean failedChild = false;
            long until = System.nanoTime() + 3000000000L;
            while (!failedChild && System.nanoTime() < until) {
                try { killed.pollOutput(50); }
                catch (IOException expected) { failedChild = true; }
            }
            check(failedChild, "real terminated child is an output failure");
        } finally { killed.close(); }
        System.out.println("PASS: stock-only DVD service linkage, argument boundary, finite IO and full-queue close");
    }
}

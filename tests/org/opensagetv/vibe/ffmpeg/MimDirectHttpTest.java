package org.opensagetv.vibe.ffmpeg;

import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MimDirectHttpTest {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    private static int freePort() throws Exception {
        DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        int port = socket.getLocalPort();
        socket.close();
        return port;
    }

    private static Response http(String method, int port, String path,
                                 String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setRequestMethod(method);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if ("POST".equals(method)) connection.setDoOutput(true);
        int status = connection.getResponseCode();
        java.io.InputStream stream = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        if (stream != null) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            stream.close();
        }
        connection.disconnect();
        return new Response(status, new String(bytes.toByteArray(), "UTF-8"));
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) throw new AssertionError("Missing " + name + " in " + json);
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) throw new AssertionError("Unterminated " + name + " in " + json);
        return json.substring(start, end);
    }

    public static void main(String[] args) throws Exception {
        Path home = java.nio.file.Paths.get(args[0]).toAbsolutePath();
        System.setProperty("vibe.ffmpeg.sageHome", home.toString());
        RuntimePaths paths = RuntimePaths.detect();
        Files.createDirectories(paths.runtimeDir);
        String script = "#!/bin/sh\n" +
                "if [ \"$1\" = \"--mim-capabilities\" ]; then printf '{\"ownedDirectStreams\":true,\"responsiveHlsRevision\":1}\\n'; exit 0; fi\n" +
                "if [ \"$1\" = \"--mim-status\" ]; then printf '{\"activeJobs\":[]}\\n'; exit 0; fi\n" +
                "playlist=''\nprevious=''\nfor arg in \"$@\"; do " +
                "if [ \"$previous\" = \"-segment_list\" ]; then playlist=\"$arg\"; fi; " +
                "previous=\"$arg\"; done\n" +
                "dir=$(dirname \"$playlist\")\n" +
                "dd if=/dev/zero of=\"$dir/seg_000000.ts\" bs=188 count=8 2>/dev/null\n" +
                "printf '#EXTM3U\\n#EXT-X-VERSION:3\\n#EXTINF:2.0,\\nseg_000000.ts\\n' > \"$playlist\"\n" +
                // Match MIM's STOP control; do not sleep through teardown.
                "IFS= read -r control\n";
        Files.write(paths.mimExecutable, script.getBytes("UTF-8"));
        paths.mimExecutable.toFile().setExecutable(true, false);
        Files.write(paths.ffprobeExecutable,
                "#!/bin/sh\nprintf '1920,1080\\n'\n".getBytes("UTF-8"));
        paths.ffprobeExecutable.toFile().setExecutable(true, false);
        final Path source = home.resolve("fixture with spaces.ts");
        Files.write(source, new byte[]{0x47, 0x40, 0, 0x10});

        CaptionSideChannelService captions = new CaptionSideChannelService(paths);
        captions.start(false, freePort(), 1, 2, 64);
        MimDirectSessionService direct = new MimDirectSessionService(paths,
                new MimDirectSessionService.SourceAuthorizer() {
                    public boolean isAllowed(Path candidate) { return candidate.equals(source); }
                });
        direct.start(true);
        int apiPort = freePort();
        CaptionSideChannelHttpServer server = new CaptionSideChannelHttpServer(
                "127.0.0.1", apiPort, captions, direct);
        server.start();
        try {
            check(http("GET",apiPort,"/v1/diagnostics/bitrate-test",null).status==200,"Bitrate diagnostic status missing");
            check(http("POST",apiPort,"/v1/diagnostics/bitrate-test",null).status==409,"Unknown activity must refuse synthetic encode");
            check(http("DELETE",apiPort,"/v1/diagnostics/bitrate-test",null).status==405,"Diagnostic method guard missing");
            check(http("POST",apiPort,"/v1/diagnostics/bitrate-test/other",null).status==404,"Diagnostic exact path guard missing");
            check(http("POST",apiPort,"/v1/direct/rate?token=missing&requestId=0&videoKbps=2000",null).status==400,"Invalid rate request admitted");
            Response capabilities = http("GET", apiPort, "/v1/capabilities", null);
            check(capabilities.status == 200 && capabilities.body.contains("\"mimDirect\"") &&
                    capabilities.body.contains("\"available\":true"), capabilities.body);
            check(capabilities.body.contains("\"sessionQualityRevision\":1"),"Optional quality revision missing");
            check(capabilities.body.contains("\"responsiveHlsRevision\":1"),"Missing HTTP responsive capability");
            check(capabilities.body.contains("\"bufferAdaptationRevision\":1"),"Missing buffer controller capability");
            check(http("GET",apiPort,"/v1/direct/buffer",null).status==405,"Buffer mutation allowed GET");
            check(http("POST",apiPort,"/v1/direct/buffer/other",null).status==404,"Buffer exact-path guard missing");
            check(http("POST",apiPort,"/v1/direct/buffer?token=missing&reportId=1&positionMs=0&bufferMs=0",null).status==409,"Foreign buffer report accepted");
            String responsiveQuery="/v1/direct/start?source="+URLEncoder.encode(source.toString(),"UTF-8")+"&mode=transcode&hlsProfile=responsive";
            Response responsive=http("POST",apiPort,responsiveQuery,null);
            check(responsive.status==200&&responsive.body.contains("\"hlsProfile\":\"responsive\"")&&responsive.body.contains("\"startupBurstSeconds\":0"),responsive.body);
            check(http("POST",apiPort,"/v1/direct/teardown?token="+field(responsive.body,"sessionToken"),null).status==200,"Responsive HTTP cleanup failed");
            check(http("POST",apiPort,responsiveQuery.replace("hlsProfile=responsive","hlsProfile=invalid"),null).status==400,"Invalid HTTP profile accepted");
            check(http("POST",apiPort,responsiveQuery.replace("mode=transcode","mode=copy"),null).status==400,"Copy HTTP profile accepted");
            check(http("POST",apiPort,responsiveQuery+"&adaptiveMinimumKbps=7000",null).status==400,"Invalid startup bounds accepted");
            Response adjusted=http("POST",apiPort,responsiveQuery+"&videoKbps=4000&adaptiveMinimumKbps=1000",null);
            check(adjusted.status==200&&adjusted.body.contains("\"maximumKbps\":4000")&&adjusted.body.contains("\"minimumKbps\":1000"),adjusted.body);
            String adjustedToken=field(adjusted.body,"sessionToken");
            Response report=http("POST",apiPort,"/v1/direct/buffer?token="+adjustedToken+"&reportId=1&positionMs=0&bufferMs=0&buffering=true&eligible=true",null);
            check(report.status==200&&report.body.contains("\"targetKbps\":0")&&report.body.contains("producer-unavailable"),report.body);
            check(http("POST",apiPort,"/v1/direct/teardown?token="+adjustedToken,null).status==200,"Adjusted cleanup failed");
            check(http("GET",apiPort,"/v1/direct/quality",null).status==405,"Quality allowed mutating GET");
            Response dvdStatus = http("GET", apiPort,
                    "/v1/dvd/status?mode=transcode", null);
            check(dvdStatus.status == 200 && dvdStatus.body.contains("\"state\":\"idle\"") &&
                    dvdStatus.body.contains("\"executionPath\":\"unknown\""),
                    dvdStatus.body);
            check(http("GET", apiPort, "/v1/dvd/status?mode=invalid", null).status == 400,
                    "invalid DVD status mode was accepted");
            String query = "?source=" + URLEncoder.encode(source.toString(), "UTF-8") +
                    "&mode=copy&active=true&startMs=0";
            Response started = http("POST", apiPort, "/v1/direct/start" + query, null);
            check(started.status == 200 && started.body.contains("\"state\":\"ready\"") &&
                    started.body.contains("\"active\":true"),
                    started.body);
            String token = field(started.body, "sessionToken");
            check(http("POST",apiPort,"/v1/direct/quality?token="+token+"&startMs=0&videoKbps=1000",null).status==400,"Copy quality mutation accepted");
            String mediaUrl = field(started.body, "mediaUrl");
            Response playlist = http("GET", apiPort, mediaUrl, null);
            check(playlist.status == 200 && playlist.body.contains("seg_000000.ts"), playlist.body);
            check(http("GET", apiPort, "/v1/direct/media/" + token +
                    "/seg_000000.ts", null).status == 200, "direct segment was not served");
            check(http("GET", apiPort, "/v1/direct/status?token=" + token,
                    null).status == 200, "direct status was not available on the LAN API");
            Response restarted = http("POST", apiPort, "/v1/direct/restart?token=" + token +
                    "&startMs=42000", null);
            check(restarted.status == 200 && restarted.body.contains("\"startMs\":42000"),
                    restarted.body);
            String restartedToken = field(restarted.body, "sessionToken");
            check(http("GET", apiPort, mediaUrl, null).status == 200,
                    "restart removed the previous media URL before player handoff");
            check(!restartedToken.equals(token), "restart reused the exposed media token");
            check(http("GET", apiPort, "/v1/direct/status?token=" + token,
                    null).status == 404, "old direct session remained visible");
            check(http("POST", apiPort, "/v1/direct/teardown?token=" + restartedToken,
                    null).status == 200, "direct teardown failed");
            check(http("GET", apiPort, "/v1/direct/status?token=" + restartedToken,
                    null).status == 404, "released direct session remained visible");
            Response transcode=http("POST",apiPort,"/v1/direct/start?source="+URLEncoder.encode(source.toString(),"UTF-8")+"&mode=transcode&startMs=0&deinterlace=off",null);
            check(transcode.status==200,transcode.body);
            String beforeQuality=field(transcode.body,"sessionToken");
            Response reduced=http("POST",apiPort,"/v1/direct/quality?token="+beforeQuality+"&startMs=12000&videoKbps=1000",null);
            check(reduced.status==200&&reduced.body.contains("\"videoTargetKbps\":1000")&&reduced.body.contains("\"deinterlace\":\"off\""),reduced.body);
            String afterQuality=field(reduced.body,"sessionToken");
            check(http("POST",apiPort,"/v1/direct/quality?token="+afterQuality+"&startMs=13000&videoKbps=500",null).status==409,"Quality cooldown not enforced over HTTP");
            check(http("POST",apiPort,"/v1/direct/quality?token="+beforeQuality+"&startMs=13000&videoKbps=500",null).status==400,"Retired quality token accepted");
            http("POST",apiPort,"/v1/direct/teardown?token="+beforeQuality,null);
            http("POST",apiPort,"/v1/direct/teardown?token="+afterQuality,null);
            check(capabilities.body.contains("\"qualityHandoffRevision\":1"),"Handoff revision missing");
            check(capabilities.body.contains("\"qualitySeekRevision\":1"),"Atomic quality seek revision missing");
            check(http("GET",apiPort,"/v1/direct/quality/prepare",null).status==405,"Prepare allowed mutating GET");
            check(http("GET",apiPort,"/v1/direct/quality/seek",null).status==405,"Quality seek allowed mutating GET");
            check(http("POST",apiPort,"/v1/direct/quality/not-an-action",null).status==404,"Unknown suffix reached legacy mutation");
            check(capabilities.body.contains("\"qualityStartRevision\":1")&&capabilities.body.contains("\"qualityRecoveryRevision\":1"),"Start/recovery not negotiated");
            check(http("POST",apiPort,"/v1/direct/start?source="+URLEncoder.encode(source.toString(),"UTF-8")+"&mode=transcode&videoKbps=249",null).status==400,"Invalid initial bitrate admitted");
            Response handoffSource=http("POST",apiPort,"/v1/direct/start?source="+URLEncoder.encode(source.toString(),"UTF-8")+"&mode=transcode&startMs=0&deinterlace=off&videoKbps=1500",null);
            check(handoffSource.status==200&&handoffSource.body.contains("\"videoTargetKbps\":1500")&&handoffSource.body.contains("\"videoCeilingKbps\":1500"),"Initial session request ignored");
            String original=field(handoffSource.body,"sessionToken");
            Response candidate=http("POST",apiPort,"/v1/direct/quality/prepare?token="+original+"&startMs=12000&videoKbps=1000",null);
            check(candidate.status==200,candidate.body);
            check(candidate.body.contains("\"videoCeilingKbps\":1500"),"HTTP handoff reset ceiling");
            String replacement=field(candidate.body,"sessionToken");
            check(http("GET",apiPort,"/v1/direct/status?token="+original,null).status==200,"Readiness retired old owner");
            check(http("GET",apiPort,field(candidate.body,"mediaUrl"),null).status==200,"Candidate media unavailable");
            check(http("POST",apiPort,"/v1/direct/quality/commit?token="+original+"&replacementToken="+replacement,null).status==400,"Commit accepted missing presentation");
            String commit="/v1/direct/quality/commit?token="+original+"&replacementToken="+replacement+"&presentedMs=12001";
            check(http("POST",apiPort,commit,null).status==200,"Presented commit failed");
            check(http("POST",apiPort,commit,null).status==200,"Exact commit retry failed");
            check(http("GET",apiPort,"/v1/direct/status?token="+original,null).status==404,"Commit retained original active owner");
            check(http("POST",apiPort,"/v1/direct/quality/abort?token="+original+"&replacementToken="+replacement,null).status==409,"Late abort accepted");
            Response atomic=http("POST",apiPort,"/v1/direct/quality/seek?token="+original+"&replacementToken="+replacement+"&startMs=21000",null);
            check(atomic.status==200&&atomic.body.contains("\"startMs\":21000"),"Committed quality seek lost target");
            String atomicToken=field(atomic.body,"sessionToken");
            check(http("GET",apiPort,"/v1/direct/status?token="+replacement,null).status==404,"Atomic seek retained superseded candidate");
            http("POST",apiPort,"/v1/direct/teardown?token="+atomicToken,null);
            http("POST",apiPort,"/v1/direct/teardown?token="+original,null);
            http("POST",apiPort,"/v1/direct/teardown?token="+replacement,null);
        } finally {
            server.stop();
            direct.close();
            captions.close();
        }
        System.out.println("MimDirectHttpTest PASS");
    }

    private static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }
}

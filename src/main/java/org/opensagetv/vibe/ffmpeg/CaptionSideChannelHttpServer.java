package org.opensagetv.vibe.ffmpeg;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** LAN-scoped API for the bounded caption and owned-media services. */
final class CaptionSideChannelHttpServer {
    private final String bindAddress;
    private final int port;
    private final CaptionSideChannelService service;
    private final MimDirectSessionService direct;
    private final DirectWatchRecoveryService recovery;
    private HttpServer server;
    private ExecutorService executor;

    CaptionSideChannelHttpServer(String bindAddress, int port,
                                 CaptionSideChannelService service,
                                 MimDirectSessionService direct) {
        this(bindAddress,port,service,direct,null);
    }

    /** Recovery remains absent on existing production construction/negotiation. */
    CaptionSideChannelHttpServer(String bindAddress, int port,
                                 CaptionSideChannelService service,
                                 MimDirectSessionService direct,
                                 DirectWatchRecoveryService recovery) {
        if (bindAddress == null || bindAddress.trim().length() == 0)
            throw new IllegalArgumentException("Caption API bind address is empty");
        if (port < 1024 || port > 65535)
            throw new IllegalArgumentException("Caption API port is outside 1024-65535");
        this.bindAddress = bindAddress;
        this.port = port;
        this.service = service;
        this.direct = direct;
        this.recovery = recovery;
    }

    synchronized void start() throws IOException {
        if (server != null) return;
        InetAddress address = InetAddress.getByName(bindAddress);
        server = HttpServer.create(new InetSocketAddress(address, port), 16);
        server.createContext("/v1/capabilities", new CapabilitiesHandler());
        server.createContext("/v1/sessions/reserve", new ReserveHandler());
        server.createContext("/v1/sessions/claim", new ClaimHandler());
        server.createContext("/v1/sessions/status", new StatusHandler());
        server.createContext("/v1/sessions/teardown", new TeardownHandler());
        server.createContext("/v1/captions", new CaptionsHandler());
        server.createContext("/v1/direct/start", new DirectStartHandler());
        server.createContext("/v1/direct/status", new DirectStatusHandler());
        server.createContext("/v1/direct/preview", new DirectPreviewHandler());
        server.createContext("/v1/previews/reserve",new PreviewLeaseHandler(true));
        server.createContext("/v1/previews/teardown",new PreviewLeaseHandler(false));
        server.createContext("/v1/previews/image",new DirectPreviewHandler(true));
        server.createContext("/v1/direct/restart", new DirectRestartHandler());
        server.createContext("/v1/direct/quality", new DirectQualityHandler());
        server.createContext("/v1/direct/quality/prepare", new DirectQualityHandoffHandler("prepare"));
        server.createContext("/v1/direct/quality/commit", new DirectQualityHandoffHandler("commit"));
        server.createContext("/v1/direct/quality/abort", new DirectQualityHandoffHandler("abort"));
        server.createContext("/v1/direct/quality/seek", new DirectQualityHandoffHandler("seek"));
        server.createContext("/v1/direct/rate", new DirectRateHandler());
        server.createContext("/v1/direct/buffer", new DirectBufferHandler());
        server.createContext("/v1/diagnostics/bitrate-test", new BitrateTestHandler());
        server.createContext("/v1/direct/teardown", new DirectTeardownHandler());
        server.createContext("/v1/direct/media", new DirectMediaHandler());
        server.createContext("/v1/dvd/status", new DvdStatusHandler());
        if (recovery !=null) {
            server.createContext("/v1/direct/recovery/reserve",new RecoveryHandler("reserve"));
            server.createContext("/v1/direct/recovery/watch",new RecoveryHandler("watch"));
            server.createContext("/v1/direct/recovery/seek",new RecoveryHandler("seek"));
            server.createContext("/v1/direct/recovery/cancel",new RecoveryHandler("cancel"));
        }
        executor = Executors.newFixedThreadPool(8, new ThreadFactory() {
            private int sequence;
            public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "vibe-caption-http-" + (++sequence));
                thread.setDaemon(true);
                return thread;
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    private final class ReserveHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed"));
                return;
            }
            String reservationToken = service.reserve();
            if (reservationToken == null) {
                send(exchange, 409, error("no_caption_slot_available"));
                return;
            }
            send(exchange, 200, "{\"contractVersion\":1,\"reservationToken\":\"" +
                    json(reservationToken) + "\",\"expiresInMs\":60000}");
        }
    }

    synchronized void stop() {
        if (server != null) server.stop(1);
        server = null;
        if (executor != null) executor.shutdownNow();
        executor = null;
        if (recovery !=null) recovery.close();
    }

    /**
     * Closed response vocabulary: never return source paths, arbitrary API
     * error text or server objects. These candidate routes are not advertised
     * until production client integration and physical fallback gates pass.
     */
    private final class RecoveryHandler implements HttpHandler {
        private final String stage;
        RecoveryHandler(String stage) { this.stage=stage; }
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange,405,error("method_not_allowed")); return;
            }
            // HttpServer contexts are prefix matches; reject suffixes and
            // bound parser work before decoding caller-controlled query data.
            String expected="/v1/direct/recovery/"+stage;
            String raw=exchange.getRequestURI().getRawQuery();
            if (!expected.equals(exchange.getRequestURI().getPath())
                    || raw ==null || raw.length()>16384) {
                send(exchange,400,error("invalid_recovery_request")); return;
            }
            try {
                Map<String,String> query=parseQuery(raw);
                long intent=number(required(query,"intent"),-1,0,Long.MAX_VALUE);
                if ("reserve".equals(stage)) {
                    long offset=number(required(query,"startMs"),-1,0,14L*86400000L);
                    boolean playing=booleanValue(required(query,"playing"),true);
                    DirectWatchRecoveryService.Reservation reservation=recovery.reserve(
                            required(query,"clientId"),required(query,"source"),offset,playing,intent);
                    if (reservation ==null) {
                        send(exchange,409,error("recovery_snapshot_unavailable")); return;
                    }
                    send(exchange,200,"{\"contractVersion\":1,\"recoveryToken\":\""+
                            json(reservation.handle)+"\",\"context\":\""+json(reservation.context)+
                            "\",\"mediaFileId\":"+reservation.mediaFileId+",\"intent\":"+intent+
                            ",\"expiresInMs\":"+DirectWatchRecoveryTickets.TTL_MS+"}");
                    return;
                }
                String handle=required(query,"recoveryToken");
                String context=required(query,"context");
                long id=number(required(query,"mediaFileId"),-1,1,Long.MAX_VALUE);
                if (!handle.matches("[0-9a-f]{32}") || context.length()>64)
                    throw new IllegalArgumentException("invalid_recovery_binding");
                if ("cancel".equals(stage)) {
                    if (!recovery.cancel(handle,context,id,intent))
                        send(exchange,409,error("stale_recovery_ticket"));
                    else send(exchange,200,"{\"ok\":true,\"state\":\"canceled\"}");
                    return;
                }
                DirectWatchRestoreCoordinator.Result result="watch".equals(stage)
                        ? recovery.watch(handle,context,id,intent)
                        : recovery.seekAfterReady(handle,context,id,intent);
                boolean accepted=result ==DirectWatchRestoreCoordinator.Result.WATCH_REQUESTED
                        || result ==DirectWatchRestoreCoordinator.Result.SEEK_REQUESTED;
                send(exchange,accepted ? 200 : 409,"{\"ok\":"+accepted+",\"state\":\""+
                        result.name().toLowerCase(java.util.Locale.US)+"\"}");
            } catch (IllegalArgumentException invalid) {
                send(exchange,400,error("invalid_recovery_request"));
            }
        }
    }

    private final class CapabilitiesHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed"));
                return;
            }
            String captions = service.capabilitiesJson();
            String combined = captions.substring(0, captions.length() - 1) +
                    ",\"mimDirect\":" + direct.capabilitiesJson() +
                    ",\"directWatchRecovery\":{\"contractVersion\":1,\"available\":"+
                    (recovery !=null && recovery.available())+
                    ",\"sourceScope\":\"single-segment-non-DVD\""+
                    ",\"restoreStages\":\"fresh-watch-video-seek\"}}";
            send(exchange, 200, combined);
        }
    }

    private final class DirectStartHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed")); return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                int adaptiveMin=(int)number(query.get("adaptiveMinimumKbps"),0L,0L,50000L);
                int adaptiveMax=(int)number(query.get("videoKbps"),6000L,250L,50000L);
                if(adaptiveMin!=0&&(adaptiveMin<250||adaptiveMin>adaptiveMax))throw new IllegalArgumentException("invalid_bitrate_range");
                if(adaptiveMin>0&&!"transcode".equals(query.get("mode")))throw new IllegalArgumentException("adaptive_requires_transcode");
                MimDirectSessionService.Session session = direct.create(
                        required(query, "source"), required(query, "mode"),
                        number(query.get("startMs"), 0L, 0L, 14L * 86400000L),
                        query.get("deinterlace"), booleanValue(query.get("active"), false),
                        (int)number(query.get("videoKbps"),6000L,250L,50000L),query.get("hlsProfile"),booleanValue(query.get("runtimeRateControl"),false),DirectOutputOptions.from(query),
                        (int)number(query.get("bufferAheadMs"),0L,0L,20000L));
                if(adaptiveMin>0)direct.configureAdaptive(session.token,adaptiveMin,adaptiveMax);
                send(exchange, 200, session.json());
            } catch (IllegalArgumentException invalid) {
                send(exchange, 400, error(invalid.getMessage()));
            } catch (IllegalStateException unavailable) {
                send(exchange, 409, error(unavailable.getMessage()));
            } catch (IOException failure) {
                send(exchange, 502, error("mim_direct_start_failed"));
            }
        }
    }

    private final class BitrateTestHandler implements HttpHandler {
        public void handle(HttpExchange exchange)throws IOException{
            if(!"/v1/diagnostics/bitrate-test".equals(exchange.getRequestURI().getPath())){send(exchange,404,error("unknown_action"));return;}
            try{
                if("POST".equals(exchange.getRequestMethod()))send(exchange,200,RuntimeBitrateSelfTest.start());
                else if("GET".equals(exchange.getRequestMethod()))send(exchange,200,RuntimeBitrateSelfTest.status());
                else send(exchange,405,error("method_not_allowed"));
            }catch(Exception unavailable){send(exchange,409,error("bitrate_test_busy_or_unavailable"));}
        }
    }
    private final class DirectBufferHandler implements HttpHandler {
        public void handle(HttpExchange exchange)throws IOException{
            if(!"/v1/direct/buffer".equals(exchange.getRequestURI().getPath())){send(exchange,404,error("unknown_action"));return;}
            if(!"POST".equals(exchange.getRequestMethod())){send(exchange,405,error("method_not_allowed"));return;}
            try{Map<String,String> q=parseQuery(exchange.getRequestURI().getRawQuery());
                send(exchange,200,direct.adaptiveReport(required(q,"token"),number(required(q,"reportId"),0,1,9007199254740991L),
                    number(required(q,"positionMs"),0,0,14L*86400000L),number(required(q,"bufferMs"),0,-1,600000),
                    booleanValue(q.get("buffering"),false),booleanValue(q.get("eligible"),false)));
            }catch(IllegalArgumentException e){send(exchange,400,error(e.getMessage()));}
             catch(IllegalStateException e){send(exchange,409,error(e.getMessage()));}
             catch(IOException e){send(exchange,502,error("adaptive_report_unavailable"));}
        }
    }
    private final class DirectRateHandler implements HttpHandler {
        public void handle(HttpExchange exchange)throws IOException{
            if(!"/v1/direct/rate".equals(exchange.getRequestURI().getPath())){send(exchange,404,error("unknown_action"));return;}
            if(!"GET".equals(exchange.getRequestMethod())&&!"POST".equals(exchange.getRequestMethod())){send(exchange,405,error("method_not_allowed"));return;}
            try{Map<String,String> q=parseQuery(exchange.getRequestURI().getRawQuery());
                String result="POST".equals(exchange.getRequestMethod())?direct.runtimeRate(required(q,"token"),
                    number(required(q,"requestId"),0,1,9007199254740991L),
                    (int)number(required(q,"videoKbps"),0,250,50000)):direct.runtimeRateStatus(required(q,"token"));
                send(exchange,200,result);
            }catch(IllegalArgumentException e){send(exchange,400,error(e.getMessage()));}
             catch(IllegalStateException e){send(exchange,409,error(e.getMessage()));}
             catch(IOException e){send(exchange,502,error("runtime_rate_write_unconfirmed"));}
        }
    }
    private final class DirectStatusHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed")); return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                MimDirectSessionService.Session session = direct.get(required(query, "token"));
                if (session == null) send(exchange, 404, error("unknown_or_finished_session"));
                else send(exchange, 200, session.json());
            } catch (IllegalArgumentException invalid) {
                send(exchange, 400, error(invalid.getMessage()));
            }
        }
    }

    private final class DirectRestartHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed")); return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                MimDirectSessionService.Session session = direct.restart(
                        required(query, "token"),
                        number(query.get("startMs"), 0L, 0L, 14L * 86400000L),
                        query.get("deinterlace"));
                send(exchange, 200, session.json());
            } catch (IllegalArgumentException invalid) {
                send(exchange, 404, error(invalid.getMessage()));
            } catch (IllegalStateException unavailable) {
                send(exchange, 409, error(unavailable.getMessage()));
            } catch (IOException failure) {
                send(exchange, 502, error("mim_direct_restart_failed"));
            }
        }
    }

    private final class DirectQualityHandler implements HttpHandler {
        public void handle(HttpExchange exchange)throws IOException {
            if(!"/v1/direct/quality".equals(exchange.getRequestURI().getPath())){send(exchange,404,error("unknown_action"));return;}
            if(!"POST".equals(exchange.getRequestMethod())){send(exchange,405,error("method_not_allowed"));return;}
            try{
                Map<String,String> query=parseQuery(exchange.getRequestURI().getRawQuery());
                MimDirectSessionService.Session session=direct.reduceQuality(required(query,"token"),
                    number(required(query,"startMs"),0L,0L,14L*86400000L),
                    (int)number(required(query,"videoKbps"),0L,250L,50000L));
                send(exchange,200,session.json());
            }catch(IllegalArgumentException invalid){send(exchange,400,error(invalid.getMessage()));}
             catch(IllegalStateException unavailable){send(exchange,409,error(unavailable.getMessage()));}
             catch(IOException failure){send(exchange,502,error("mim_direct_quality_failed"));}
        }
    }

    /** Explicitly negotiated two-phase lease. A playlist-ready response is
     * not a presentation acknowledgement; old callers retain /quality rev1. */
    private final class DirectQualityHandoffHandler implements HttpHandler {
        private final String action;
        DirectQualityHandoffHandler(String action) { this.action = action; }
        public void handle(HttpExchange exchange) throws IOException {
            if (!("/v1/direct/quality/" + action).equals(exchange.getRequestURI().getPath())) {
                send(exchange,404,error("unknown_action")); return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) { send(exchange,405,error("method_not_allowed")); return; }
            try {
                Map<String,String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String previous = required(query,"token");
                if ("prepare".equals(action)) {
                    MimDirectSessionService.Session session = direct.prepareQuality(previous,
                            number(required(query,"startMs"),0L,0L,14L*86400000L),
                            (int)number(required(query,"videoKbps"),0L,250L,50000L),
                            booleanValue(query.get("recovery"),false));
                    send(exchange,200,session.json());
                } else if ("commit".equals(action)) {
                    MimDirectSessionService.Session session = direct.commitQuality(previous,
                            required(query,"replacementToken"),
                            number(required(query,"presentedMs"),0L,0L,14L*86400000L));
                    send(exchange,200,session.json());
                } else if ("seek".equals(action)) {
                    MimDirectSessionService.Session session=direct.seekQuality(previous,required(query,"replacementToken"),
                        number(required(query,"startMs"),0L,0L,14L*86400000L),query.get("deinterlace"));
                    send(exchange,200,session.json());
                } else {
                    boolean aborted = direct.abortQuality(previous,required(query,"replacementToken"));
                    send(exchange,aborted?200:409,aborted?"{\"ok\":true,\"state\":\"aborted\"}":error("direct_handoff_not_current"));
                }
            } catch (IllegalArgumentException invalid) { send(exchange,400,error(invalid.getMessage())); }
              catch (IllegalStateException unavailable) { send(exchange,409,error(unavailable.getMessage())); }
              catch (IOException failure) { send(exchange,502,error("mim_direct_handoff_failed")); }
        }
    }

    /** Explicit bounded image generation, not an implicitly expensive GET.
     * No arbitrary source/output arguments; exact Direct token custody only.
     * A missing optional route/revision leaves ordinary playback unchanged. */
    private final class DirectPreviewHandler implements HttpHandler {
        private final boolean lease;
        DirectPreviewHandler(){this(false);}
        DirectPreviewHandler(boolean lease){this.lease=lease;}
        public void handle(HttpExchange exchange)throws IOException{
            if(!"POST".equals(exchange.getRequestMethod())){send(exchange,405,error("method_not_allowed"));return;}
            if(direct==null){send(exchange,409,error("preview_unavailable"));return;}
            try {
                Map<String,String> query=parseQuery(exchange.getRequestURI().getRawQuery());
                String token=required(query,lease?"previewToken":"token");
                long position=number(required(query,"positionMs"),0L,0L,14L*86400000L);
                ScrubPreviewCache.Preview preview=lease?direct.previewFromLease(token,position):direct.preview(token,position);
                exchange.getResponseHeaders().set("X-Vibe-Preview-Position-Ms",String.valueOf(preview.positionMs));
                exchange.getResponseHeaders().set("X-Vibe-Preview-Cached",String.valueOf(preview.cached));
                exchange.getResponseHeaders().set("Content-Type","image/jpeg");
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                exchange.sendResponseHeaders(200,preview.jpeg.length);
                try(OutputStream output=exchange.getResponseBody()){output.write(preview.jpeg);}
            }catch(IllegalArgumentException invalid){send(exchange,400,error(invalid.getMessage()));}
             catch(IllegalStateException unavailable){send(exchange,409,error(unavailable.getMessage()));}
             catch(IOException unavailable){send(exchange,502,error("preview_unavailable"));}
        }
    }

    /** A bounded preview-only reservation lets an existing Web HLS producer
     * reuse MIM thumbnails without creating a parallel Direct media stream. */
    private final class PreviewLeaseHandler implements HttpHandler {
        private final boolean reserve;
        PreviewLeaseHandler(boolean reserve){this.reserve=reserve;}
        public void handle(HttpExchange exchange)throws IOException{
            if(!"POST".equals(exchange.getRequestMethod())){send(exchange,405,error("method_not_allowed"));return;}
            if(direct==null){send(exchange,409,error("preview_unavailable"));return;}
            try{
                Map<String,String> query=parseQuery(exchange.getRequestURI().getRawQuery());
                if(reserve)send(exchange,200,direct.reservePreview(required(query,"source")));
                else{boolean released=direct.releasePreview(required(query,"previewToken"));
                    send(exchange,released?200:404,released?"{\"released\":true}":error("unknown_or_expired_preview"));}
            }catch(IllegalArgumentException invalid){send(exchange,400,error(invalid.getMessage()));}
             catch(IllegalStateException unavailable){send(exchange,409,error(unavailable.getMessage()));}
             catch(IOException unavailable){send(exchange,502,error("preview_unavailable"));}
        }
    }

    private final class DirectTeardownHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed")); return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                if (!direct.release(required(query, "token")))
                    send(exchange, 404, error("unknown_or_finished_session"));
                else send(exchange, 200, "{\"ok\":true,\"state\":\"released\"}");
            } catch (IllegalArgumentException invalid) {
                send(exchange, 400, error(invalid.getMessage()));
            }
        }
    }

    private final class DirectMediaHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed")); return;
            }
            String prefix = "/v1/direct/media/";
            String requestPath = exchange.getRequestURI().getPath();
            String suffix = requestPath.startsWith(prefix)
                    ? requestPath.substring(prefix.length()) : "";
            String[] parts = suffix.split("/", -1);
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{64}") ||
                    !("stream.m3u8".equals(parts[1]) ||
                            parts[1].matches("seg_[0-9]{6}\\.ts"))) {
                send(exchange, 404, error("unknown_media")); return;
            }
            MimDirectSessionService.Session session = direct.getMedia(parts[0]);
            if (session == null) { send(exchange, 404, error("unknown_media")); return; }
            Path file = session.directory.resolve(parts[1]).normalize();
            if (!file.startsWith(session.directory) || !Files.isRegularFile(file)) {
                send(exchange, 404, error("media_not_ready")); return;
            }
            sendFile(exchange, file, parts[1].endsWith(".m3u8")
                    ? "application/vnd.apple.mpegurl" : "video/mp2t");
        }
    }

    private final class DvdStatusHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed")); return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String mode = query.get("mode");
                if (mode != null && !("copy".equals(mode) || "transcode".equals(mode))) {
                    send(exchange, 400, error("invalid_dvd_mode")); return;
                }
                send(exchange, 200, StockDvdStreamService.statusJson(mode));
            } catch (IllegalArgumentException invalid) {
                send(exchange, 400, error(invalid.getMessage()));
            }
        }
    }

    private final class ClaimHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed"));
                return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String sessionToken = service.claim(required(query, "reservationToken"));
                if (sessionToken == null) {
                    send(exchange, 409, error("fixed_session_not_ready_or_already_claimed"));
                    return;
                }
                send(exchange, 200, "{\"contractVersion\":1,\"sessionToken\":\"" +
                        json(sessionToken) + "\"}");
            } catch (IllegalArgumentException invalid) {
                send(exchange, 400, error(invalid.getMessage()));
            }
        }
    }

    private final class CaptionsHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed"));
                return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String token = required(query, "token");
                long cursor = number(query.get("cursor"), 0L, 0L, Long.MAX_VALUE);
                long untilMs = number(query.get("untilMs"), 0L, 0L, 7L * 86400000L);
                int limit = (int) number(query.get("limit"), 256L, 1L, 1024L);
                String result = service.read(token, cursor, untilMs, limit);
                if (result == null) send(exchange, 404, error("unknown_or_finished_session"));
                else send(exchange, 200, result);
            } catch (IllegalArgumentException invalid) {
                send(exchange, 400, error(invalid.getMessage()));
            }
        }
    }

    private final class StatusHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed"));
                return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String result = service.sessionStatus(required(query, "token"));
                if (result == null) send(exchange, 404, error("unknown_or_finished_session"));
                else send(exchange, 200, result);
            } catch (IllegalArgumentException invalid) {
                send(exchange, 400, error(invalid.getMessage()));
            }
        }
    }

    private final class TeardownHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, error("method_not_allowed"));
                return;
            }
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                if (!service.teardown(required(query, "token")))
                    send(exchange, 404, error("unknown_or_finished_session"));
                else send(exchange, 200, "{\"ok\":true,\"state\":\"released\"}");
            } catch (IllegalArgumentException invalid) {
                send(exchange, 400, error(invalid.getMessage()));
            }
        }
    }

    private static Map<String, String> parseQuery(String raw) throws IOException {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (raw == null || raw.length() == 0) return values;
        for (String part : raw.split("&")) {
            int separator = part.indexOf('=');
            String key = URLDecoder.decode(separator < 0 ? part : part.substring(0, separator), "UTF-8");
            String value = URLDecoder.decode(separator < 0 ? "" : part.substring(separator + 1), "UTF-8");
            if (key.length() == 0 || values.containsKey(key))
                throw new IllegalArgumentException("invalid_or_duplicate_parameter");
            values.put(key, value);
        }
        return values;
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.length() == 0) throw new IllegalArgumentException("missing_" + name);
        return value;
    }

    private static long number(String value, long fallback, long minimum, long maximum) {
        if (value == null || value.length() == 0) return fallback;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException("number_out_of_range");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid_number");
        }
    }

    private static boolean booleanValue(String value, boolean fallback) {
        if (value == null || value.length() == 0) return fallback;
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        throw new IllegalArgumentException("invalid_boolean");
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":\"" + json(message == null ? "request_failed" : message) + "\"}";
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = (json + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        OutputStream output = exchange.getResponseBody();
        try { output.write(payload); }
        finally { output.close(); }
    }

    private static void sendFile(HttpExchange exchange, Path file,
                                 String contentType) throws IOException {
        long length = Files.size(file);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, length);
        InputStream input = Files.newInputStream(file, StandardOpenOption.READ);
        OutputStream output = exchange.getResponseBody();
        try {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        } finally {
            try { input.close(); } finally { output.close(); }
        }
    }

    private static String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}

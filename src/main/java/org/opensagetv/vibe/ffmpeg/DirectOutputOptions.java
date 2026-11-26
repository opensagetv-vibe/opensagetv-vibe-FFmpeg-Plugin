package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Validated, immutable request values. Never edits the shared MIM INI. */
final class DirectOutputOptions {
    final String encoder,hardwareBackend,videoCodec,resolution,fps,audioCodec,audioKbps,channels,container,remux;
    static final DirectOutputOptions LEGACY=new DirectOutputOptions("server","h264","SOURCE","SOURCE","ac3","192","","mpegts","needed");
    DirectOutputOptions(String encoder,String videoCodec,String resolution,String fps,String audioCodec,
                        String audioKbps,String channels,String container,String remux) {
        this(encoder,videoCodec,resolution,fps,audioCodec,audioKbps,channels,container,remux,"auto");
    }
    DirectOutputOptions(String encoder,String videoCodec,String resolution,String fps,String audioCodec,
                        String audioKbps,String channels,String container,String remux,String hardwareBackend) {
        this.encoder=choice(encoder,"server","server","prefer_hardware","software","hardware_only");
        this.hardwareBackend=choice(hardwareBackend,"auto","auto","qsv","vaapi","nvenc","amf","videotoolbox","d3d12va");
        this.videoCodec=choice(videoCodec,"auto","auto","h264","hevc");
        this.resolution=choice(resolution,"SOURCE","SOURCE","CIF","D1","720","1080");
        this.fps=choice(fps,"SOURCE","SOURCE","24","29.97","59.94");
        this.audioCodec=choice(audioCodec,"aac","source","aac","ac3","mp2");
        this.audioKbps=choice(audioKbps,"128","source","96","112","128","160","192");
        this.channels=choice(channels,"","","1","2","6");
        this.container=choice(container,"mpegts","mpegts","matroska","dvd");
        this.remux=choice(remux,"needed","needed","always","off");
    }
    static String choice(String value,String fallback,String... choices) {
        String selected=value==null?fallback:value;
        for(String allowed:choices)if(allowed.equals(selected))return selected;
        throw new IllegalArgumentException("invalid_output_option");
    }
    static DirectOutputOptions from(Map<String,String> q) {
        if(!q.containsKey("outputRevision")) {
            for(String key:new String[]{"encoderPolicy","hardwareBackend","videoCodec","resolution","frameRate","audioCodec","audioKbps","audioChannels","container","remux"})
                if(q.containsKey(key))throw new IllegalArgumentException("output_revision_required");
            return LEGACY;
        }
        if(!"1".equals(q.get("outputRevision")))throw new IllegalArgumentException("unsupported_output_revision");
        return new DirectOutputOptions(q.get("encoderPolicy"),q.get("videoCodec"),q.get("resolution"),q.get("frameRate"),
                q.get("audioCodec"),q.get("audioKbps"),q.get("audioChannels"),q.get("container"),q.get("remux"),q.get("hardwareBackend"));
    }
    void validate(String mode) {
        // This owner publishes TS segments, not standalone MKV/PS files.
        // Never silently claim the requested container was applied.
        if(!"mpegts".equals(container))throw new IllegalArgumentException("container_not_supported_by_segment_transport");
        if("copy".equals(mode)&&!"server".equals(encoder))throw new IllegalArgumentException("copy_has_no_video_encoder");
        if(!"auto".equals(hardwareBackend)&&("copy".equals(mode)||"software".equals(encoder)))
            throw new IllegalArgumentException("hardware_backend_requires_hardware_transcode");
        if("copy".equals(mode)&&"off".equals(remux))throw new IllegalArgumentException("copy_without_remux_requires_original_source_playback");
    }
    String json() {
        return "{\"revision\":1,\"encoderPolicy\":\""+encoder+"\",\"hardwareBackend\":\""+hardwareBackend+"\",\"videoCodec\":\""+videoCodec+
                "\",\"resolution\":\""+resolution+"\",\"frameRate\":\""+fps+"\",\"audioCodec\":\""+audioCodec+
                "\",\"audioKbps\":\""+audioKbps+"\",\"audioChannels\":\""+channels+
                "\",\"container\":\""+container+"\",\"remux\":\""+remux+"\"}";
    }
    void append(List<String> command,Map<String,Object> source) {
        if(!"server".equals(encoder))java.util.Collections.addAll(command,"-sagetvencoderpolicy",encoder);
        if(!"auto".equals(hardwareBackend))java.util.Collections.addAll(command,"-sagetvhardwarebackend",hardwareBackend);
        String audio=audioCodec;
        if("source".equals(audio)) {
            audio=string(source.get("audioCodec"));
            if(!"aac".equals(audio)&&!"ac3".equals(audio)&&!"mp2".equals(audio))
                throw new IllegalArgumentException("source_audio_codec_not_supported");
        }
        java.util.Collections.addAll(command,"-c:a",audio);
        if(!channels.isEmpty())java.util.Collections.addAll(command,"-ac",channels);
        if(!"source".equals(audioKbps))java.util.Collections.addAll(command,"-b:a",audioKbps+"k");
        else {
            long bps=number(source.get("audioBitrate"));
            // Unknown or illegal rate deliberately leaves the encoder default.
            if(validAudioRate(audio,bps))java.util.Collections.addAll(command,"-b:a",Long.toString(bps));
        }
        if(!"SOURCE".equals(fps))java.util.Collections.addAll(command,"-r",fps);
        if(!"SOURCE".equals(resolution)) {
            long w=number(source.get("width")),h=number(source.get("height"));
            if(w<=0||h<=0)throw new IllegalArgumentException("source_dimensions_unavailable");
            int maxW="CIF".equals(resolution)?352:"D1".equals(resolution)?720:"720".equals(resolution)?1280:1920;
            int maxH="CIF".equals(resolution)?(pal(source)?288:240):"D1".equals(resolution)?(pal(source)?576:480):"720".equals(resolution)?720:1080;
            double scale=Math.min(1d,Math.min((double)maxW/w,(double)maxH/h));
            int outW=Math.max(2,((int)(w*scale)/2)*2),outH=Math.max(2,((int)(h*scale)/2)*2);
            java.util.Collections.addAll(command,"-s",outW+"x"+outH);
        }
    }
    static boolean validAudioRate(String codec,long bps) {
        if("ac3".equals(codec))return bps==32000||bps==40000||bps==48000||bps==56000||bps==64000||bps==80000||bps==96000||bps==112000||bps==128000||bps==160000||bps==192000||bps==224000||bps==256000||bps==320000||bps==384000||bps==448000||bps==512000||bps==576000||bps==640000;
        if("mp2".equals(codec))return bps==32000||bps==48000||bps==56000||bps==64000||bps==80000||bps==96000||bps==112000||bps==128000||bps==160000||bps==192000||bps==224000||bps==256000||bps==320000||bps==384000;
        return "aac".equals(codec)&&bps>=16000&&bps<=512000;
    }
    static boolean pal(Map<String,Object> source){String rate=string(source.get("fps"));return "25/1".equals(rate)||"50/1".equals(rate);}
    static long number(Object value){if(value instanceof Number){double n=((Number)value).doubleValue();return !Double.isNaN(n)&&!Double.isInfinite(n)&&n==Math.rint(n)?((Number)value).longValue():-1;}try{return Long.parseLong(String.valueOf(value));}catch(RuntimeException e){return -1;}}
    static String string(Object value){return value==null?"":String.valueOf(value);}
    @SuppressWarnings("unchecked")
    static Map<String,Object> probe(Path executable,Path input)throws IOException {
        final Process process=new ProcessBuilder(executable.toString(),"-v","error","-show_entries",
                "stream=codec_type,codec_name,width,height,avg_frame_rate,bit_rate,channels,sample_rate", "-of","json",input.toString()).redirectErrorStream(true).start();
        FutureTask<String> read=new FutureTask<String>(() -> {
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=process.getInputStream().read(buffer))!=-1){if(bytes.size()+n>65536)throw new IOException("output_probe_too_large");bytes.write(buffer,0,n);}
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        });
        Thread thread=new Thread(read,"vibe-output-probe");thread.setDaemon(true);thread.start();
        try {
            String body=read.get(5,TimeUnit.SECONDS);
            if(!process.waitFor(500,TimeUnit.MILLISECONDS)||process.exitValue()!=0)throw new IOException("output_probe_failed");
            Object parsed=MiniJson.parse(body);Map<String,Object> result=new java.util.LinkedHashMap<String,Object>();
            Object streams=parsed instanceof Map?((Map<?,?>)parsed).get("streams"):null;
            if(streams instanceof List)for(Object row:(List<?>)streams)if(row instanceof Map){Map<?,?> s=(Map<?,?>)row;
                if("video".equals(s.get("codec_type"))&&!result.containsKey("videoCodec")){
                    result.put("videoCodec",s.get("codec_name"));result.put("width",s.get("width"));result.put("height",s.get("height"));result.put("fps",s.get("avg_frame_rate"));result.put("videoBitrate",s.get("bit_rate"));
                }else if("audio".equals(s.get("codec_type"))&&!result.containsKey("audioCodec")){
                    result.put("audioCodec",s.get("codec_name"));result.put("audioBitrate",s.get("bit_rate"));result.put("audioChannels",s.get("channels"));
                }
            }
            return result;
        }catch(Exception error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();throw new IOException("output_probe_unavailable",error);}
        finally{process.destroyForcibly();read.cancel(true);try{process.getInputStream().close();}catch(IOException ignored){}}
    }
    static String observedJson(Map<String,Object> output) {
        StringBuilder json=new StringBuilder("{\"container\":\"mpegts\",\"observed\":true");
        for(String key:new String[]{"videoCodec","width","height","fps","videoBitrate","audioCodec","audioBitrate","audioChannels"}) {
            Object value=output.get(key);json.append(",\"").append(key).append("\":");
            if(value==null)json.append("null");else json.append('"').append(MimDirectSessionService.jsonEscape(value instanceof Number?Long.toString(number(value)):String.valueOf(value))).append('"');
        }
        return json.append('}').toString();
    }
}

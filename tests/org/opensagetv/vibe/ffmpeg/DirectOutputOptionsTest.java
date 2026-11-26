package org.opensagetv.vibe.ffmpeg;
import java.util.*;
public final class DirectOutputOptionsTest {
    static void check(boolean pass,String reason){if(!pass)throw new AssertionError(reason);}
    static void refuses(Runnable action){try{action.run();throw new AssertionError("request accepted");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        Map<String,String> query=new HashMap<String,String>();
        Map<String,String> backend=new HashMap<String,String>();backend.put("hardwareBackend","qsv");
        refuses(()->DirectOutputOptions.from(backend));backend.put("outputRevision","1");
        for(String name:new String[]{"qsv","vaapi","nvenc","amf","videotoolbox","d3d12va"}){
            backend.put("hardwareBackend",name);DirectOutputOptions pinned=DirectOutputOptions.from(backend);
            check(name.equals(pinned.hardwareBackend),"backend missing");pinned.validate("transcode");
            List<String> argv=new ArrayList<String>();pinned.append(argv,Collections.emptyMap());
            check(argv.contains("-sagetvhardwarebackend")&&argv.contains(name),"pin not forwarded");
            check(pinned.json().contains("\"hardwareBackend\":\""+name+"\""),"pin not retained in status");
            refuses(()->pinned.validate("copy"));
        }
        backend.put("encoderPolicy","software");refuses(()->DirectOutputOptions.from(backend).validate("transcode"));
        backend.put("hardwareBackend","qsv;evil");refuses(()->DirectOutputOptions.from(backend));
        check("auto".equals(DirectOutputOptions.LEGACY.hardwareBackend),"legacy default changed");
        check(DirectOutputOptions.from(query)==DirectOutputOptions.LEGACY,"old defaults changed");
        query.put("encoderPolicy","hardware_only");refuses(()->DirectOutputOptions.from(query));
        query.put("outputRevision","1");DirectOutputOptions hardware=DirectOutputOptions.from(query);
        check("hardware_only".equals(hardware.encoder),"policy missing");refuses(()->hardware.validate("copy"));
        query.put("encoderPolicy","server");query.put("container","matroska");refuses(()->DirectOutputOptions.from(query).validate("transcode"));
        query.put("container","mpegts");query.put("audioCodec","source");query.put("audioKbps","source");query.put("resolution","1080");
        Map<String,Object> source=new HashMap<String,Object>();source.put("audioCodec","ac3");source.put("audioBitrate","384000");source.put("width",640);source.put("height",480);
        List<String> cmd=new ArrayList<String>();DirectOutputOptions.from(query).append(cmd,source);
        check(cmd.contains("384000")&&cmd.contains("ac3"),"source audio lost");check(cmd.contains("640x480"),"unexpected upscale");
        source.put("width",1920);source.put("height",1080);query.put("resolution","720");cmd.clear();DirectOutputOptions.from(query).append(cmd,source);
        check(cmd.contains("1280x720"),"aspect/dimension bound lost");
        source.remove("audioBitrate");cmd.clear();DirectOutputOptions.from(query).append(cmd,source);check(!cmd.contains("-b:a"),"invented source rate");
        source.put("audioCodec","dts");refuses(()->DirectOutputOptions.from(query).append(new ArrayList<String>(),source));
        query.put("resolution","720;evil");refuses(()->DirectOutputOptions.from(query));
        check(DirectOutputOptions.validAudioRate("ac3",384000)&&!DirectOutputOptions.validAudioRate("ac3",123456),"invalid AC3 bitrate");
        check(!DirectOutputOptions.validAudioRate("mp2",640000),"invalid MP2 bitrate");
        check(DirectOutputOptions.number(Double.valueOf(640))==640&&DirectOutputOptions.number(Double.NaN)==-1,"JSON numeric dimensions lost");
        check(!DirectOutputOptions.observedJson(Collections.<String,Object>emptyMap()).contains("384000"),"requested output substituted for observation");
        System.out.println("PASS output revision/policy/copy/container/source/bitrate/scaling/injection/unknown guards");
    }
}

package org.opensagetv.vibe.ffmpeg;
import java.nio.file.*;
import java.util.*;
/** Real CPU encoder test in owned scratch, not a server/device qualification. */
public final class DirectOutputRuntimeTest {
    static void check(boolean pass,String reason){if(!pass)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        Path home=Paths.get(args[0]).toAbsolutePath(),binaries=Paths.get(args[1]);
        System.setProperty("vibe.ffmpeg.sageHome",home.toString());RuntimePaths paths=RuntimePaths.detect();
        Files.createDirectories(paths.runtimeDir);
        Files.copy(binaries.resolve("ffmpeg_MIM"),paths.mimExecutable);
        Files.createSymbolicLink(paths.ffmpegExecutable,binaries.resolve("ffmpeg.real"));
        Files.createSymbolicLink(paths.ffprobeExecutable,binaries.resolve("ffprobe"));paths.mimExecutable.toFile().setExecutable(true);
        Files.write(paths.ini,("[general]\nenabled=true\n[hardware]\nbackend=software\n[logging]\nffmpeg_transcode_loglevel=error\n").getBytes("UTF-8"));
        byte[] originalIni=Files.readAllBytes(paths.ini);
        Path source=home.resolve("fixture.ts");
        Process fixture=new ProcessBuilder(paths.ffmpegExecutable.toString(),"-v","error","-f","lavfi","-i","testsrc2=size=640x360:rate=30","-f","lavfi","-i","sine=frequency=1000:sample_rate=48000","-t","8","-c:v","mpeg2video","-c:a","ac3","-b:a","192k","-ac","2","-y",source.toString()).inheritIO().start();
        check(fixture.waitFor()==0,"fixture generation failed");
        MimDirectSessionService service=new MimDirectSessionService(paths,new MimDirectSessionService.SourceAuthorizer(){public boolean isAllowed(Path file){return file.equals(source);}public Boolean isRecording(Path file){return Boolean.FALSE;}});
        try{
            service.start(true);
            check(service.capabilitiesJson().contains("\"encoderPolicyRevision\":1"),"new native capability missing");
            DirectOutputOptions options=new DirectOutputOptions("software","h264","CIF","24","aac","96","2","mpegts","off");
            MimDirectSessionService.Session first=service.create(source.toString(),"transcode",0,"off",false,1500,"responsive",false,options);
            check(first.json().contains("\"observed\":true"),"actual output metadata unavailable");
            check(first.effectiveOutputJson.contains("\"width\":\"352\"")&&first.effectiveOutputJson.contains("\"videoCodec\":\"h264\""),"scale/codec not applied");
            check(first.effectiveOutputJson.contains("\"fps\":\"24/1\""),"actual frame rate not applied");
            check(first.effectiveOutputJson.contains("\"audioCodec\":\"aac\"")&&first.effectiveOutputJson.contains("\"audioChannels\":\"2\""),"audio not applied");
            MimDirectSessionService.Session seek=service.restart(first.token,1000,"off");
            check(seek.output==options&&seek.json().contains("\"width\":\"352\""),"seek lost output policy");
            MimDirectSessionService.Session quality=service.prepareQuality(seek.token,1000,1000,false);
            check(quality.output==options&&quality.effectiveOutputJson.contains("\"fps\":\"24/1\"")&&quality.effectiveOutputJson.contains("\"width\":\"352\""),"quality replacement lost output policy");
            check(service.abortQuality(seek.token,quality.token),"quality abort failed");service.release(seek.token);
            DirectOutputOptions sourceOptions=new DirectOutputOptions("software","auto","SOURCE","SOURCE","source","source","","mpegts","needed");
            MimDirectSessionService.Session original=service.create(source.toString(),"transcode",0,"off",false,1500,"legacy",false,sourceOptions);
            check(original.json().contains("\"audioCodec\":\"ac3\"")&&original.json().contains("\"audioBitrate\":\"192000\""),"Use Source audio not retained");
            service.release(original.token);
            check(Arrays.equals(originalIni,Files.readAllBytes(paths.ini)),"shared INI changed");
            System.out.println("PASS real CPU H.264/AAC/CIF/24fps/source AC3-rate, seek/quality-policy preservation and INI isolation; no device/server certification");
        }finally{service.close();}
    }
}

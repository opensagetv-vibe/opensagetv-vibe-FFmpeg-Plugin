package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded fake-runtime tests, not actual JPEG rendering or Windows proof. */
public final class MimThumbnailJobTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void text(Path file,String value)throws Exception{Files.write(file,value.getBytes(StandardCharsets.UTF_8));}
    private static void refused(MimRuntime.ThumbnailJob job)throws Exception{
        try{job.run();throw new AssertionError("Invalid preview accepted");}catch(IOException expected){}
    }
    public static void main(String[] args)throws Exception{
        Path home=Paths.get(args[0]).toAbsolutePath();System.setProperty("vibe.ffmpeg.sageHome",home.toString());
        RuntimePaths paths=RuntimePaths.detect();Files.createDirectories(paths.runtimeDir);
        for(Path file:new Path[]{paths.ffmpegExecutable,paths.ffprobeExecutable}){text(file,"stub");file.toFile().setExecutable(true);}
        text(paths.defaultIni,"");
        String policy="[general]\nmode=passthrough\n[safety]\nisolate_child_process=true\n";
        text(paths.ini,policy);
        text(paths.mimExecutable,"#!/bin/sh\ncd \"$(dirname \"$0\")\"\nprintf '%s\\n' \"$@\" >preview.args\n"
                +"case \"$*\" in *slow.ts*) touch preview.started; exec sleep 20;; "
                +"*large.ts*) head -c 40000 /dev/zero; exit 0;; *bad.ts*) printf bad; exit 0;; esac\n"
                +"printf '\\377\\330\\377\\331'\n");
        paths.mimExecutable.toFile().setExecutable(true);
        MimRuntime runtime=new MimRuntime(paths);check(runtime.thumbnailSupported(),"Qualified passthrough should support preview");
        Path source=home.resolve("source with spaces.ts");text(source,"fixture");
        check(runtime.thumbnail(source,42000).run().length==4,"Bounded JPEG output not returned");
        String argv=new String(Files.readAllBytes(paths.runtimeDir.resolve("preview.args")),StandardCharsets.UTF_8);
        check(argv.contains("42.000\n")&&argv.contains(source.toString()+"\n"),"Exact source/time argument boundary lost");
        check(argv.contains("-frames:v\n1\n")&&argv.contains("-c:v\nmjpeg\n")&&argv.contains("-threads\n1\n"),"Single bounded thumbnail contract lost");
        check(!argv.contains("-stdinctrl")&&!argv.contains("-sagetvdirect"),"Thumbnail must not be classified as playback transcoding");
        refused(runtime.thumbnail(home.resolve("bad.ts"),0));
        refused(runtime.thumbnail(home.resolve("large.ts"),0));
        long began=System.nanoTime();refused(runtime.thumbnail(home.resolve("slow.ts"),0));
        check((System.nanoTime()-began)/1000000<4500,"Hung thumbnail exceeded finite budget");
        MimRuntime.ThumbnailJob cancelled=runtime.thumbnail(source,0);cancelled.cancel();refused(cancelled);
        Files.deleteIfExists(paths.runtimeDir.resolve("preview.started"));
        MimRuntime.ThumbnailJob running=runtime.thumbnail(home.resolve("slow.ts"),0);
        AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
        Thread thread=new Thread(()->{try{running.run();failure.set(new AssertionError("Cancelled output returned"));}
            catch(IOException expected){}catch(Throwable unexpected){failure.set(unexpected);}});
        thread.start();long deadline=System.nanoTime()+2000000000L;
        while(!Files.exists(paths.runtimeDir.resolve("preview.started"))&&System.nanoTime()<deadline)Thread.sleep(10);
        check(Files.exists(paths.runtimeDir.resolve("preview.started")),"Owned preview did not start");
        running.cancel();thread.join(2000);check(!thread.isAlive()&&failure.get()==null,"Owned cancel did not finish cleanly");
        text(paths.ini,policy.replace("mode=passthrough","mode=always"));
        check(!runtime.thumbnailSupported(),"Always-transcode policy must not produce a false JPEG capability");
        refused(runtime.thumbnail(source,0));
        text(paths.ini,policy.replace("isolate_child_process=true","isolate_child_process=false"));
        check(!runtime.thumbnailSupported(),"Unqualified child cancellation must remain unavailable");
        System.out.println("MimThumbnailJobTest PASS: finite single-frame output/limits/cancel/policy");
    }
}

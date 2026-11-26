package org.opensagetv.vibe.ffmpeg;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit bounded settings diagnostic. Synthetic media only; independent of
 * clients and MCP. Reuses MIM and RuntimeBitrateControl, never edits live INI. */
public final class RuntimeBitrateSelfTest {
    private static volatile String result="{\"state\":\"not_run\"}";
    private static volatile boolean running;
    private static volatile String reportPath="";
    private static boolean restored;
    private RuntimeBitrateSelfTest(){}
    public static synchronized String status(){
        if(!restored){
            restored=true;
            try{Path saved=RuntimePaths.detect().reportDir.resolve("Vibe-Bitrate-Test-latest.json");
                if(Files.isRegularFile(saved,LinkOption.NOFOLLOW_LINKS)&&Files.size(saved)<=16384){
                    String previous=new String(Files.readAllBytes(saved),StandardCharsets.UTF_8).trim();
                    if(previous.startsWith("{")&&(previous.contains("\"state\":\"PASS\"")||previous.contains("\"state\":\"FAIL\""))){result=previous;reportPath=saved.toString();}
                }
            }catch(Exception unavailable){}
        }
        return result;
    }
    public static String summary(){
        status();
        if(result.contains("\"state\":\"PASS\""))return "PASS - measured down/up; see report";
        if(result.contains("\"state\":\"FAIL\""))return "FAIL - see report";
        return running?"Running synthetic QSV test":"Not run";
    }
    public static String reportPath(){status();return reportPath;}
    public static synchronized String start()throws Exception{
        status();
        if(running)throw new IllegalStateException("bitrate_test_already_running");
        final RuntimePaths paths=RuntimePaths.detect();
        if(paths.windows)throw new IllegalStateException("bitrate_test_linux_qsv_only");
        Object capability=new MimRuntime(paths).capabilities(false).json.get("runtimeBitrateControlRevision");
        if(!(capability instanceof Number)||((Number)capability).doubleValue()!=1.0)throw new IllegalStateException("runtime_test_requires_new_mim");
        requireIdle(paths);
        running=true;result="{\"state\":\"running\",\"encoder\":\"h264_qsv\",\"maximumSeconds\":125}";
        Thread worker=new Thread(()->run(paths),"vibe-runtime-bitrate-self-test");worker.setDaemon(true);worker.start();return result;
    }
    private static void run(RuntimePaths paths){
        Path dir=null;Process owned=null;long began=System.nanoTime();
        final Deque<String> logTail=new ArrayDeque<String>();
        try{
            Files.createDirectories(paths.runtimeDir.resolve("cache/runtime-rate-tests"));
            dir=Files.createTempDirectory(paths.runtimeDir.resolve("cache/runtime-rate-tests"),"owned-");
            Path ini=dir.resolve("test.ini");Files.copy(paths.ini,ini);
            IniDocument settings=IniDocument.load(ini);
            settings.set("general","enabled","true");settings.set("hardware","backend","qsv");
            // Test encoder reconfiguration, not inherited deinterlacing or
            // decoder policy. QSV's automatic VPP can double even progressive
            // synthetic60fps to120fps and invalidate the measurement oracle.
            settings.set("hardware","direct_hardware_decode","false");
            settings.set("filters","deinterlace","off");
            settings.set("stdinctrl","runtime_bitrate_control","true");settings.set("stdinctrl","enabled","true");
            settings.set("stdinctrl","videorateadapt_mode","forward");
            settings.set("safety","clean_exit_on_ffmpeg_failure","false");
            settings.set("logging","file",dir.resolve("mim.log").toString());
            settings.set("status","directory",dir.resolve("status").toString());
            settings.set("closed_captions","preserve_a53cc","false");settings.saveTo(ini);
            capture(Arrays.asList(paths.ffmpegExecutable.toString(),"-hide_banner","-loglevel","error","-nostdin",
                "-f","lavfi","-i","testsrc2=size=1280x720:rate=60","-t","6","-an","-c:v","mpeg2video","-q:v","2",
                "-y",dir.resolve("fixture.ts").toString()),20000);
            Path progress=dir.resolve("progress.log");Path playlist=dir.resolve("stream.m3u8");
            List<String> command=Arrays.asList(paths.mimExecutable.toString(),"-hide_banner","-sagetvdirect","-sagetvdeinterlace","off","-sagetvruntimebitrate",
                "-re","-stream_loop","-1","-i",dir.resolve("fixture.ts").toString(),"-t","54","-an",
                "-c:v","libx264","-b:v","6000k","-r","60","-g","120","-bf","0","-forced_idr","1",
                "-progress",progress.toString(),"-nostats","-f","segment","-segment_time","2","-segment_format","mpegts",
                "-segment_list_type","m3u8","-segment_list",playlist.toString(),"-y",dir.resolve("part-%03d.ts").toString());
            ProcessBuilder pb=new ProcessBuilder(command);pb.directory(paths.sageHome.toFile());
            pb.environment().put("SAGETV_FFMPEG_MIM_INI",ini.toString());
            pb.redirectOutput(ProcessBuilder.Redirect.to(dir.resolve("stdout.log").toFile()));
            requireIdle(paths);
            owned=pb.start();final Process active=owned;
            final RuntimeBitrateControl control=new RuntimeBitrateControl(active,6000,6000);
            Thread stderr=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(active.getErrorStream(),StandardCharsets.UTF_8))){
                String line;while((line=r.readLine())!=null){control.observe(line);
                    synchronized(logTail){if(logTail.size()==20)logTail.removeFirst();logTail.addLast(line.substring(0,Math.min(240,line.length())));}
                }
            }catch(IOException ignored){}},"vibe-rate-test-stderr");stderr.setDaemon(true);stderr.start();
            int changes=0;boolean reduced=false,recovered=false;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(75),nextGuard=0;
            while(active.isAlive()){
                if(System.nanoTime()>deadline)throw new IOException("bitrate_test_timeout");
                long ms=RuntimeBitrateControl.progress(progress),now=System.nanoTime()/1000000L;
                if(now>=nextGuard){requireIdle(paths);nextGuard=now+3000;}
                String state=control.snapshot(ms,now);
                if(changes==0&&ms>=18000){control.request(1,2000,now);changes++;}
                else if(changes==1&&state.contains("\"state\":\"applied\""))reduced=true;
                if(changes==1&&reduced&&ms>=36000){control.request(2,2500,now);changes++;}
                else if(changes==2&&state.contains("\"state\":\"applied\""))recovered=true;
                if(state.contains("unconfirmed"))throw new IOException("encoder_rate_change_unconfirmed");
                Thread.sleep(100);
            }
            stderr.join(2000);
            if(active.exitValue()!=0||!reduced||!recovered)throw new IOException("bitrate_test_encode_or_control_failed");
            String packets=capture(Arrays.asList(paths.ffprobeExecutable.toString(),"-v","error","-select_streams","v:0",
                "-show_packets","-show_entries","packet=pts_time,dts_time,size","-of","csv=p=0",playlist.toString()),10000);
            double[] rates=measure(packets);
            String decode=capture(Arrays.asList(paths.ffmpegExecutable.toString(),"-hide_banner","-v","error","-nostdin","-i",playlist.toString(),"-map","0:v:0","-f","null","-"),20000);
            if(!decode.trim().isEmpty())throw new IOException("bitrate_test_decode_errors");
            boolean pass=rates[0]>4500&&rates[0]<7500&&rates[1]>1400&&rates[1]<2600&&rates[2]>2000&&rates[2]<3300&&rates[2]>rates[1]*1.10;
            if(!pass)throw new IOException("measured_bitrate_did_not_follow_targets");
            result="{\"state\":\"PASS\",\"encoder\":\"h264_qsv\",\"targetsKbps\":[6000,2000,2500],\"measuredKbps\":["+
                rates[0]+","+rates[1]+","+rates[2]+"],\"sameProcess\":true,\"decode\":true,\"synthetic\":true,\"pipeline\":\"Software source decode, QSV hardware encode\",\"fps\":60,\"audioCaptionsTested\":false}";
        }catch(Exception failed){String tail;synchronized(logTail){tail=String.join("\n",logTail);}
            if(dir!=null)tail=tail.replace(dir.toString(),"<test>");
            result="{\"state\":\"FAIL\",\"synthetic\":true,\"error\":\""+MimDirectSessionService.jsonEscape(failed.getMessage()==null?failed.getClass().getSimpleName():failed.getMessage())+
                "\",\"encoderLogTail\":\""+MimDirectSessionService.jsonEscape(tail)+"\"}";}
        finally{
            if(owned!=null&&owned.isAlive())try{OwnedProcessControl.terminate(owned);}catch(Exception ignored){}
            result=result.substring(0,result.length()-1)+",\"completedAtMs\":"+System.currentTimeMillis()+",\"elapsedMs\":"+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)+
                ",\"pluginVersion\":\""+SageTVFFmpegPlugin.VERSION+"\"}";
            try{Files.createDirectories(paths.reportDir);Path report=paths.reportDir.resolve("Vibe-Bitrate-Test-latest.json");
                Files.write(report,(result+"\n").getBytes(StandardCharsets.UTF_8));reportPath=report.toString();
            }catch(IOException ignored){}
            // Only this newly created canonical scratch, no followed symlinks.
            if(dir!=null&&(owned==null||!owned.isAlive()))try(java.util.stream.Stream<Path> entries=Files.walk(dir)){
                entries.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});
            }catch(IOException ignored){}
            running=false;
        }
    }
    private static void requireIdle(RuntimePaths paths)throws Exception{
        try{
        requireEmptyArray(sage.SageTV.api("GetCurrentlyRecordingMediaFiles",new Object[0]),"recording");
        Object contexts=sage.SageTV.api("GetUIContextNames",new Object[0]);
        if(!(contexts instanceof Object[]))throw new IllegalStateException("ui_activity_unknown");
        for(Object context:(Object[])contexts){
            // A settings menu is allowed. Refusing every UI context would make
            // this action impossible to use from the STV's own settings page.
            Object playing=sage.SageTV.apiUI(String.valueOf(context),"IsPlaying",new Object[0]);
            Object paused=sage.SageTV.apiUI(String.valueOf(context),"IsPaused",new Object[0]);
            if(!(playing instanceof Boolean)||!(paused instanceof Boolean))throw new IllegalStateException("playback_activity_unknown");
            if(Boolean.TRUE.equals(playing)||Boolean.TRUE.equals(paused))throw new IllegalStateException("playback_active");
        }
        requireEmptyArray(sage.SageTV.api("GetConnectedClients",new Object[0]),"client");
        // The diagnostic writes its own MIM status to private scratch. Ordinary
        // runtime jobs remain visible here; a newly started viewer wins and the
        // diagnostic terminates only its own process.
        Object jobs=new MimRuntime(paths).status(true).json.get("activeJobs");
        if(!(jobs instanceof List)||!((List<?>)jobs).isEmpty())throw new IllegalStateException("transcode_active_or_unknown");
        }catch(LinkageError unavailable){throw new IllegalStateException("stock_activity_api_unavailable");}
    }
    static void requireEmptyArray(Object value,String kind){
        if(value==null||!value.getClass().isArray())throw new IllegalStateException(kind+"_activity_unknown");
        if(java.lang.reflect.Array.getLength(value)>0)throw new IllegalStateException(kind+"_active");
    }
    static double[] measure(String csv)throws IOException{
        double[] bytes=new double[3];int[] count=new int[3];double first=Double.NaN,last=-1,lastDts=Double.NaN;
        for(String line:csv.split("\\r?\\n")){
            String[] fields=line.split(",");if(fields.length<3)continue;
            double pts,dts;long size;try{pts=Double.parseDouble(fields[0]);dts=Double.parseDouble(fields[1]);size=Long.parseLong(fields[2]);}catch(NumberFormatException e){continue;}
            if(!Double.isFinite(pts)||!Double.isFinite(dts)||size<0)throw new IOException("invalid_test_packet");
            if(Double.isNaN(first))first=pts;double time=pts-first;
            if(last>=0&&(time<=last||time-last>.05))throw new IOException("test_video_clock_discontinuity");last=time;
            if(!Double.isNaN(lastDts)&&(dts<=lastDts||dts-lastDts>.05))throw new IOException("test_decode_clock_discontinuity");lastDts=dts;
            for(int i=0;i<3;i++)if(time>=6+i*18&&time<14+i*18){bytes[i]+=size;count[i]++;}
        }
        for(int i=0;i<3;i++){if(count[i]<450||count[i]>500)throw new IOException("incomplete_test_window");bytes[i]=Math.round(bytes[i]*8/8/1000*1000)/1000.0;}
        return bytes;
    }
    private static String capture(List<String> command,long timeout)throws Exception{
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{try(InputStream in=p.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0){
            if(data.size()+n>2097152){p.destroy();break;}data.write(b,0,n);
        }}catch(IOException ignored){}},"vibe-rate-test-capture");reader.setDaemon(true);reader.start();
        if(!p.waitFor(timeout,TimeUnit.MILLISECONDS)){OwnedProcessControl.terminate(p);throw new IOException("test_tool_timeout");}
        reader.join(2000);if(p.exitValue()!=0)throw new IOException("test_tool_exit_"+p.exitValue());
        return new String(data.toByteArray(),StandardCharsets.UTF_8);
    }
}

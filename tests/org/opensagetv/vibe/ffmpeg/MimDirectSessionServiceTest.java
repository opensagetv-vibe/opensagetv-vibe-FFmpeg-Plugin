package org.opensagetv.vibe.ffmpeg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

public final class MimDirectSessionServiceTest {
    /** No OS child: verifies refusal when even forced termination is unproven. */
    private static final class UnstoppableProducer extends Process {
        public java.io.OutputStream getOutputStream(){return new java.io.ByteArrayOutputStream();}
        public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(new byte[0]);}
        public java.io.InputStream getErrorStream(){return getInputStream();}
        public int waitFor(){throw new IllegalStateException("No real child");}
        public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit){return false;}
        public int exitValue(){throw new IllegalThreadStateException();}
        public void destroy(){}
        public Process destroyForcibly(){return this;}
        public boolean isAlive(){return true;}
    }
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        check("auto".equals(MimDirectSessionService.normalizeDeinterlace(null)),
                "missing deinterlace policy defaults to auto");
        check("on".equals(MimDirectSessionService.normalizeDeinterlace(" ON ")),
                "on deinterlace policy normalized");
        boolean invalidDeinterlace = false;
        try { MimDirectSessionService.normalizeDeinterlace("sometimes"); }
        catch (IllegalArgumentException expected) { invalidDeinterlace = true; }
        check(invalidDeinterlace, "invalid deinterlace policy rejected");
        Path home = java.nio.file.Paths.get(args[0]).toAbsolutePath();
        System.setProperty("vibe.ffmpeg.sageHome", home.toString());
        RuntimePaths paths = RuntimePaths.detect();
        Files.createDirectories(paths.runtimeDir);
        String script = "#!/bin/sh\n" +
                "if [ \"$1\" = \"--mim-capabilities\" ]; then printf '{\"ownedDirectStreams\":true}\\n'; exit 0; fi\n" +
                "status=\"$(dirname \"$0\")/fake-direct-status.json\"\n" +
                "if [ \"$1\" = \"--mim-status\" ]; then if [ -f \"$status\" ]; then cat \"$status\"; else printf '{\"activeJobs\":[]}\\n'; fi; exit 0; fi\n" +
                "if [ -f \"$(dirname \"$0\")/fail-direct-start\" ]; then exit 19; fi\n" +
                "printf '%s\\n' \"$@\" > \"$(dirname \"$0\")/last-direct-args.txt\"\n" +
                "if [ -f \"$(dirname \"$0\")/expect-quality-stop\" ] && [ ! -f \"$(dirname \"$0\")/last-direct-control.txt\" ]; then " +
                "touch \"$(dirname \"$0\")/quality-encoder-overlap\"; fi\n" +
                "if [ -f \"$(dirname \"$0\")/fail-active-once\" ]; then " +
                "for arg in \"$@\"; do if [ \"$arg\" = \"-activefile\" ]; then " +
                "rm -f \"$(dirname \"$0\")/fail-active-once\"; exit 0; fi; done; fi\n" +
                "playlist=''\ninput=''\nprevious=''\nfor arg in \"$@\"; do " +
                "if [ \"$previous\" = \"-segment_list\" ]; then playlist=\"$arg\"; fi; " +
                "if [ \"$previous\" = \"-i\" ]; then input=\"$arg\"; fi; " +
                "previous=\"$arg\"; done\n" +
                "dir=$(dirname \"$playlist\")\n" +
                "now=$(date +%s)000\n" +
                "printf '{\"activeJobs\":[{\"state\":\"running\",\"backend\":\"copy\",\"encoder\":\"copy\",\"hardwareEncode\":false,\"hardwareDecode\":false,\"mimPid\":%s,\"input\":\"%s\",\"outputFormat\":\"segment\",\"startedEpochMs\":%s}]}\\n' \"$$\" \"$input\" \"$now\" > \"$status\"\n" +
                "printf '#EXTM3U\\n#EXT-X-VERSION:3\\n' > \"$playlist\"\n" +
                "sleep 1\n" +
                "dd if=/dev/zero of=\"$dir/seg_000000.ts\" bs=188 count=8 2>/dev/null\n" +
                "printf '#EXTM3U\\n#EXT-X-VERSION:3\\n#EXTINF:2.0,\\nseg_000000.ts\\n#EXT-X-ENDLIST\\n' > \"$playlist\"\n" +
                "if IFS= read -r control; then printf '%s\\n' \"$control\" > \"$(dirname \"$0\")/last-direct-control.txt\"; fi\n";
        Files.write(paths.mimExecutable, script.getBytes("UTF-8"));
        paths.mimExecutable.toFile().setExecutable(true, false);
        Files.write(paths.ffprobeExecutable,
                // Real MPEG-TS FFprobe output repeats the selected video
                // stream in the program and global stream tables.
                ("#!/bin/sh\n" +
                "for arg in \"$@\"; do if [ \"$arg\" = \"format=duration\" ]; then " +
                "printf '60.000\\n'; exit 0; fi; done\n" +
                "printf '1920,1080\\n\\n1920,1080\\n'\n").getBytes("UTF-8"));
        paths.ffprobeExecutable.toFile().setExecutable(true, false);
        Path source = home.resolve("fixture.ts");
        Files.write(source, new byte[]{0x47, 0x40, 0, 0x10});

        // Only new wrappers advertise the private responsive encoder flag.
        String responsiveScript=script.replace("{\"ownedDirectStreams\":true}",
                "{\"ownedDirectStreams\":true,\"responsiveHlsRevision\":1}");
        Files.write(paths.mimExecutable,responsiveScript.getBytes("UTF-8"));
        for (final Boolean writer : new Boolean[]{Boolean.FALSE,Boolean.TRUE,null}) {
            MimDirectSessionService responsive=new MimDirectSessionService(paths,
                    new MimDirectSessionService.SourceAuthorizer(){
                        public boolean isAllowed(Path candidate){return candidate.equals(source);}
                        public Boolean isRecording(Path candidate){return writer;}
                    });
            try {
                responsive.start(true);
                check(responsive.capabilitiesJson().contains("\"responsiveHlsRevision\":1"),"Missing responsive capability");
                MimDirectSessionService.Session initial=responsive.create(source.toString(),"transcode",0,"off",true,4000,"responsive");
                String argsText=new String(Files.readAllBytes(paths.runtimeDir.resolve("last-direct-args.txt")),"UTF-8");
                check(argsText.contains("-sagetvresponsivehls\n")&&argsText.contains("-segment_time\n1\n"),"Responsive segment policy lost");
                check(argsText.contains("-segment_list_size\n180\n")&&argsText.contains("-map\n0:s?\n"),"Window/subtitle preservation lost");
                check(argsText.contains("-force_key_frames\nexpr:gte(t,n_forced*1)\n"),"Missing forced keyframes");
                check(argsText.contains("-readrate_initial_burst\n6\n")==Boolean.FALSE.equals(writer),"Burst must require independent completed truth");
                check(argsText.contains("-re\n"),"Burst removed steady pacing");
                check(initial.json().contains("\"hlsProfile\":\"responsive\"")&&initial.startupReadyMs>=0,"Missing accepted policy/timing");
                if(Boolean.TRUE.equals(writer)) {
                    MimDirectSessionService.Session limited=responsive.create(source.toString(),"transcode",0,"off",false,4000,"responsive",false,DirectOutputOptions.LEGACY,20000);
                    check(limited.startupBurstSeconds==0,"Live source accepted completed-file reserve");responsive.release(limited.token);
                    MimDirectSessionService.Session falseHint=responsive.create(source.toString(),"transcode",0,"off",false,4000,"responsive");
                    check(falseHint.active&&falseHint.activeFileApplied&&falseHint.startupBurstSeconds==0,"Client completed hint overrode known writer");
                    responsive.release(falseHint.token);
                }
                if(Boolean.FALSE.equals(writer)) {
                    MimDirectSessionService.Session buffered=responsive.create(source.toString(),"transcode",0,"off",false,4000,"responsive",false,DirectOutputOptions.LEGACY,20000);
                    check(buffered.startupBurstSeconds==23&&buffered.bufferAheadMs==20000,"Negotiated reserve lost");
                    MimDirectSessionService.Session bufferedSeek=responsive.restart(buffered.token,1000,"off");
                    check(bufferedSeek.startupBurstSeconds==23&&bufferedSeek.bufferAheadMs==20000,"Seek lost negotiated reserve");
                    MimDirectSessionService.Session bufferedQuality=responsive.prepareQuality(bufferedSeek.token,2000,2800);
                    check(bufferedQuality.startupBurstSeconds==23&&bufferedQuality.bufferAheadMs==20000,"Quality lost negotiated reserve");
                    responsive.abortQuality(bufferedSeek.token,bufferedQuality.token);responsive.release(bufferedSeek.token);
                    MimDirectSessionService.Session seek=responsive.restart(initial.token,1000,"off");
                    check(seek.responsiveHls&&seek.startupBurstSeconds==6,"Seek lost responsive policy");
                    MimDirectSessionService.Session candidate=responsive.prepareQuality(seek.token,2000,2800);
                    check(candidate.responsiveHls&&candidate.startupBurstSeconds==6,"Quality lost responsive policy");
                    responsive.abortQuality(seek.token,candidate.token);
                }
                try {responsive.create(source.toString(),"copy",0,"off",false,4000,"responsive");throw new AssertionError("Copy accepted responsive profile");}
                catch(IllegalArgumentException expected) {check(expected.getMessage().equals("responsive_hls_requires_transcode"),"Wrong Copy refusal");}
                try {responsive.create(source.toString(),"transcode",0,"off",false,4000,"unknown");throw new AssertionError("Unknown profile accepted");}
                catch(IllegalArgumentException expected) {check(expected.getMessage().equals("invalid_hls_profile"),"Wrong profile refusal");}
            } finally {responsive.close();}
        }
        Files.write(paths.mimExecutable,script.getBytes("UTF-8"));

        for (final Boolean recording : new Boolean[]{Boolean.FALSE,Boolean.TRUE,null}) {
            MimDirectSessionService truth = new MimDirectSessionService(paths,
                new MimDirectSessionService.SourceAuthorizer(){
                    public boolean isAllowed(Path candidate){return candidate.equals(source);}
                    public Boolean isRecording(Path candidate){return recording;}
                });
            try {
                truth.start(true);
                MimDirectSessionService.Session corrected=truth.create(source.toString(),"transcode",42000L,"on",true);
                String launch=new String(Files.readAllBytes(paths.runtimeDir.resolve("last-direct-args.txt")),"UTF-8");
                check(corrected.active==!Boolean.FALSE.equals(recording),"Writer truth handling failed: "+recording);
                check(launch.contains("-activefile\n")==!Boolean.FALSE.equals(recording),"Writer hint command changed incorrectly: "+recording);
                check(corrected.startMs==42000L&&launch.contains("-ss\n42.000\n"),"Writer correction lost requested seek");
                MimDirectSessionService.Session repeated=truth.create(source.toString(),"transcode",42000L,"on",true);
                check((Boolean.FALSE.equals(recording)?"hit":"bypass_writer").equals(repeated.durationProbeCache),
                        "Only proven completed source/tool identity can reuse seek duration");
                check(repeated.json().contains("\"durationProbeCache\":\""+repeated.durationProbeCache+"\""),
                        "Expose only the closed per-session cache disposition");
                if(corrected.active){
                    try {truth.prepareQuality(corrected.token,43000L,1000);throw new AssertionError("Unqualified writer handoff accepted");}
                    catch(IllegalStateException expected){check("direct_handoff_growing_source_unqualified".equals(expected.getMessage()),"Wrong writer refusal");}
                    check(truth.get(corrected.token)==corrected&&corrected.qualityReductions==0,"Writer refusal changed original/budget");
                }
                truth.release(corrected.token);
            } finally { truth.close(); }
        }

        MimDirectSessionService service = new MimDirectSessionService(paths,
                new MimDirectSessionService.SourceAuthorizer() {
                    public boolean isAllowed(Path candidate) { return candidate.equals(source); }
                });
        // File presence and a successful unrelated capability must not enable
        // private Direct options on an older MIM wrapper. It would pass them
        // through to stock FFmpeg and silently end the producer before video.
        for (String unsupported : new String[]{"{}", "{\"ownedDirectStreams\":false}",
                "{\"ownedDirectStreams\":\"true\"}", "{\"dvdVideoDemux\":true}"}) {
            Files.write(paths.mimExecutable,
                    script.replace("{\"ownedDirectStreams\":true}", unsupported).getBytes("UTF-8"));
            service.start(true);
            check(service.capabilitiesJson().contains("\"available\":false"),
                    "old/untyped MIM unexpectedly enabled Direct: " + unsupported);
        }
        Files.write(paths.mimExecutable, script.getBytes("UTF-8"));
        service.start(true);
        check(service.capabilitiesJson().contains("\"available\":true"),
                service.capabilitiesJson());
        check(service.capabilitiesJson().contains("\"responsiveHlsRevision\":0"),"Old wrapper opted into private flags");
        check(service.capabilitiesJson().contains("\"hardwareBackendRevision\":0"),"Old wrapper advertised hardware backend pin");
        java.util.Map<String,String> backendRequest=new java.util.HashMap<String,String>();
        backendRequest.put("outputRevision","1");backendRequest.put("hardwareBackend","qsv");
        try {service.create(source.toString(),"transcode",0,"off",false,4000,"legacy",false,DirectOutputOptions.from(backendRequest));throw new AssertionError("Old wrapper accepted backend pin");}
        catch(IllegalStateException expected){check(expected.getMessage().contains("hardware_backend_unavailable"),"Wrong pin refusal");}
        try {service.create(source.toString(),"transcode",0,"off",false,4000,"responsive");throw new AssertionError("Old wrapper accepted new profile");}
        catch(IllegalStateException expected) {check("responsive_hls_unavailable".equals(expected.getMessage()),"Wrong old-wrapper refusal");}
        MimDirectSessionService.Session session = service.create(
                source.toString(), "copy", 0L);
        check(Files.isRegularFile(session.playlist), "playlist not created");
        check(Files.isRegularFile(session.directory.resolve("seg_000000.ts")),
                "segment not created");
        String command = new String(Files.readAllBytes(
                paths.runtimeDir.resolve("last-direct-args.txt")), "UTF-8");
        check(command.contains("-f\nsegment\n"),
                "Direct output is not segment M3U8: " + command);
        check(command.contains("-segment_format\nmpegts\n"),
                "Direct output is not MPEG-TS segmented: " + command);
        check(command.contains("-map\n0:s?\n"),
                "Direct output does not preserve broadcast subtitles: " + command);
        byte[] publishedPlaylist = Files.readAllBytes(session.playlist);
        Path unfinished = session.directory.resolve("seg_000009.ts");
        Files.write(unfinished, new byte[]{0x47, 0, 0, 0});
        Files.setLastModifiedTime(unfinished,
                FileTime.fromMillis(System.currentTimeMillis() - 60_000L));
        session.cleanupUnlistedSegments();
        check(Files.exists(unfinished), "cleanup removed unfinished future segment");
        Path retired = session.directory.resolve("seg_000000.ts");
        Path recentRetired = session.directory.resolve("seg_000001.ts");
        Path referenced = session.directory.resolve("seg_000002.ts");
        Files.write(recentRetired, new byte[]{0x47});
        Files.write(referenced, new byte[]{0x47});
        Files.setLastModifiedTime(retired,
                FileTime.fromMillis(System.currentTimeMillis() - 60_000L));
        Files.setLastModifiedTime(referenced,
                FileTime.fromMillis(System.currentTimeMillis() - 60_000L));
        Files.write(session.playlist, "#EXTM3U\n#EXTINF:2,\nseg_000002.ts\n".getBytes("UTF-8"));
        session.cleanupUnlistedSegments();
        check(!Files.exists(retired), "retired segment below published window remained");
        check(Files.exists(recentRetired), "retired segment grace was lost");
        check(Files.exists(referenced), "referenced old segment was deleted");
        check(Files.exists(unfinished), "future writer was deleted after window advance");
        Files.setLastModifiedTime(recentRetired,
                FileTime.fromMillis(System.currentTimeMillis() - 60_000L));
        Files.write(session.playlist, "#EXTM3U\n".getBytes("UTF-8"));
        session.cleanupUnlistedSegments();
        check(Files.exists(recentRetired), "empty playlist allowed unsafe cleanup");
        Files.write(retired, new byte[]{0x47, 0, 0, 0});
        Files.write(session.playlist, publishedPlaylist);
        check(session.json().contains("/v1/direct/media/" + session.token + "/stream.m3u8"),
                session.json());
        check(session.json().contains(MimDirectSessionService.processId(session.process)>0?"\"path\":\"copy\"":"\"path\":\"unknown\""),
                "segment-muxer execution proof was not matched: " + session.json());
        java.util.Map<String,Object> ownJob=new java.util.HashMap<String,Object>();
        ownJob.put("mimPid",MimDirectSessionService.processId(session.process));
        ownJob.put("input",source.toString());ownJob.put("outputFormat","segment");ownJob.put("startedEpochMs",session.createdAtMs);
        java.util.Map<String,Object> foreignJob=new java.util.HashMap<String,Object>(ownJob);
        foreignJob.put("mimPid",MimDirectSessionService.processId(session.process)+1);
        foreignJob.put("startedEpochMs",session.createdAtMs+1000);
        java.util.Map<String,Object> proof=new java.util.HashMap<String,Object>();
        proof.put("activeJobs",java.util.Arrays.asList(ownJob,foreignJob));proof.put("lastJob",foreignJob);
        check(session.matchingJob(proof)==(MimDirectSessionService.processId(session.process)>0?ownJob:null),"Overlapping producer stole GPU attribution");
        ownJob.remove("mimPid");check(session.matchingJob(proof)==null,"Unknown PID guessed execution proof");
        check(service.get(session.token) == session, "session not registered");
        try{service.reduceQuality(session.token,1000L,1000);throw new AssertionError("Copy accepted quality mutation");}
        catch(IllegalArgumentException expected){check("quality_requires_transcode".equals(expected.getMessage()),"Wrong Copy rejection");}
        MimDirectSessionService.Session restarted = service.restart(session.token, 42000L);
        check(restarted != session, "restart reused the old session");
        check(restarted.startMs == 42000L, "restart did not preserve requested position");
        check(service.get(session.token) == null, "restart retained old session");
        check(service.getMedia(session.token) == session,
                "restart did not retain old media for player handoff");
        check(Files.exists(session.directory),
                "restart removed old session files before player handoff");
        check(service.release(session.token), "retired handoff teardown failed");
        check(!Files.exists(session.directory), "retired handoff files remained");
        check(service.release(restarted.token), "session teardown failed");
        MimDirectSessionService.Session edgeClamped = service.create(
                source.toString(), "copy", 86_400_000L, "auto", true);
        check(edgeClamped.requestedStartMs == 86_400_000L,
                "requested live-edge position was not retained for diagnostics");
        check(edgeClamped.startMs == 56_000L,
                "out-of-range live seek was not bounded with preroll: " + edgeClamped.startMs);
        check(edgeClamped.json().contains("\"requestedStartMs\":86400000"),
                edgeClamped.json());
        check(edgeClamped.json().contains("\"startMs\":56000"), edgeClamped.json());
        service.release(edgeClamped.token);
        check("STOP".equals(new String(Files.readAllBytes(
                        paths.runtimeDir.resolve("last-direct-control.txt")), "UTF-8").trim()),
                "Direct teardown did not use MIM's graceful STOP control");
        check(service.get(session.token) == null, "released session remained visible");
        check(!Files.exists(restarted.directory), "released session files remained");
        MimDirectSessionService.Session noDeinterlace = service.create(
                source.toString(), "transcode", 0L, "off", true);
        command = new String(Files.readAllBytes(
                paths.runtimeDir.resolve("last-direct-args.txt")), "UTF-8");
        check(command.contains("-sagetvdeinterlace\noff\n"),
                "Direct deinterlace policy was not passed to MIM: " + command);
        check(command.contains("-activefile\n"),
                "growing Direct source was not declared active to MIM: " + command);
        check(noDeinterlace.json().contains("\"deinterlace\":\"off\""),
                noDeinterlace.json());
        check(noDeinterlace.json().contains("\"active\":true"),
                noDeinterlace.json());
        MimDirectSessionService.Session noDeinterlaceRestart =
                service.restart(noDeinterlace.token, 12000L);
        check("off".equals(noDeinterlaceRestart.deinterlace),
                "restart did not preserve deinterlace policy");
        service.release(noDeinterlace.token);
        service.release(noDeinterlaceRestart.token);
        Files.write(paths.runtimeDir.resolve("fail-active-once"),
                new byte[]{1});
        MimDirectSessionService.Session staleActiveRecovered = service.create(
                source.toString(), "transcode", 0L, "off", true);
        command = new String(Files.readAllBytes(
                paths.runtimeDir.resolve("last-direct-args.txt")), "UTF-8");
        check(!command.contains("-activefile\n"),
                "stale active hint did not retry as completed media: " + command);
        check(staleActiveRecovered.json().contains("\"active\":true"),
                staleActiveRecovered.json());
        check(staleActiveRecovered.json().contains("\"activeFileApplied\":false"),
                "stale-active recovery was not observable: " +
                        staleActiveRecovered.json());
        service.release(staleActiveRecovered.token);
        MimDirectSessionService.Session quality=service.create(source.toString(),"transcode",0L,"off",true);
        check(service.capabilitiesJson().contains("\"sessionQualityRevision\":1"),"Optional quality contract not advertised");
        try{service.reduceQuality(quality.token,1000L,7000);throw new AssertionError("Upshift accepted");}
        catch(IllegalArgumentException expected){check(quality.qualityReductions==0,"Rejected upshift consumed budget");}
        Files.deleteIfExists(paths.runtimeDir.resolve("last-direct-control.txt"));
        Files.write(paths.runtimeDir.resolve("expect-quality-stop"),new byte[]{1});
        MimDirectSessionService.Session lower=service.reduceQuality(quality.token,12000L,1000);
        Files.delete(paths.runtimeDir.resolve("expect-quality-stop"));
        check(!Files.exists(paths.runtimeDir.resolve("quality-encoder-overlap")),"Quality started replacement before old producer STOP");
        check(!quality.process.isAlive(),"Previous quality encoder is still alive");
        check(service.getMedia(quality.token)==quality&&Files.exists(quality.playlist),"Published old media was not retained for handoff");
        check(lower.videoKbps==1000&&lower.startMs==12000L&&"off".equals(lower.deinterlace)&&lower.active,"Reduction lost source/position/policy");
        check(lower.qualityReductions==1&&service.get(quality.token)==null,"Replacement/budget custody incorrect");
        command=new String(Files.readAllBytes(paths.runtimeDir.resolve("last-direct-args.txt")),"UTF-8");
        check(command.contains("-b:v\n1000k\n"),"Actual command did not reduce bitrate: "+command);
        try{service.reduceQuality(lower.token,13000L,500);throw new AssertionError("Cooldown bypassed");}
        catch(IllegalStateException expected){check("quality_cooldown_or_budget".equals(expected.getMessage()),"Wrong cooldown rejection");}
        lower.qualityChangedAtMs=System.currentTimeMillis()-61000L;
        MimDirectSessionService.Session lowest=service.reduceQuality(lower.token,13000L,500);
        lowest.qualityChangedAtMs=System.currentTimeMillis()-61000L;
        try{service.reduceQuality(lowest.token,14000L,250);throw new AssertionError("Per-load budget bypassed");}
        catch(IllegalStateException expected){check(lowest.qualityReductions==2,"Budget changed on rejection");}
        MimDirectSessionService.Session retained=service.restart(lowest.token,15000L);
        check(retained.videoKbps==500&&retained.qualityReductions==2,"Ordinary seek reset adapted session quality/budget");
        service.release(quality.token);service.release(lower.token);service.release(lowest.token);service.release(retained.token);
        MimDirectSessionService.Session stuck=service.create(source.toString(),"transcode",0L,"off",true);
        stuck.stop(); // Retire the real fixture process before injecting the no-OS-child double.
        stuck.process=new UnstoppableProducer();
        long directoriesBefore;
        try(java.util.stream.Stream<Path> entries=Files.list(paths.directMediaDir)){directoriesBefore=entries.count();}
        try{service.reduceQuality(stuck.token,1000L,1000);throw new AssertionError("Unconfirmed stop created another encoder");}
        catch(java.io.IOException expected){check("quality_previous_producer_still_running".equals(expected.getMessage()),"Wrong stop failure");}
        try(java.util.stream.Stream<Path> entries=Files.list(paths.directMediaDir)){
            check(entries.count()==directoriesBefore,"Stop failure allocated a replacement session");
        }
        check(stuck.qualityReductions==1,"Failed stop did not consume bounded attempt budget");
        stuck.process=null;service.release(stuck.token);

        // The NEW optional handoff is make-before-break. Legacy rev1 tests
        // above remain deliberate compatibility evidence, not its contract.
        check(service.capabilitiesJson().contains("\"qualityHandoffRevision\":1"),"Handoff not explicitly advertised");
        MimDirectSessionService.Session original=service.create(source.toString(),"transcode",0L,"off",false);
        MimDirectSessionService.Session prepared=service.prepareQuality(original.token,12000L,1000);
        check(original.process.isAlive()&&prepared.process.isAlive(),"Readiness stopped original before client presentation");
        check(service.get(original.token)==original&&service.get(prepared.token)==prepared,"Both leases not retained");
        check(prepared.startMs==12000L&&prepared.videoKbps==1000&&!prepared.active&&"off".equals(prepared.deinterlace),"Preparation lost completed-source policy");
        try{service.prepareQuality(original.token,13000L,500);throw new AssertionError("Duplicate handoff admitted");}
        catch(IllegalStateException expected){check(original.qualityReductions==1,"Duplicate preparation consumed budget");}
        try{service.commitQuality(original.token,original.token,12000L);throw new AssertionError("Wrong pair committed");}
        catch(IllegalStateException expected){check(original.process.isAlive()&&prepared.process.isAlive(),"Wrong pair changed producers");}
        try{service.commitQuality(original.token,prepared.token,0L);throw new AssertionError("Pre-anchor frame committed");}
        catch(IllegalArgumentException expected){check(original.process.isAlive(),"Invalid presentation stopped original");}
        check(!service.abortQuality(original.token,original.token),"Wrong pair aborted handoff");
        check(service.abortQuality(original.token,prepared.token),"Abort did not cancel replacement");
        check(original.process.isAlive()&&!prepared.process.isAlive()&&service.get(prepared.token)==null,"Abort did not preserve original only");
        try{service.commitQuality(original.token,prepared.token,12000L);throw new AssertionError("Aborted pair committed");}
        catch(IllegalStateException expected){}
        original.qualityChangedAtMs=System.currentTimeMillis()-61000L;
        MimDirectSessionService.Session confirmed=service.prepareQuality(original.token,14000L,500);
        check(service.commitQuality(original.token,confirmed.token,14001L)==confirmed,"Presented commit failed");
        check(!original.process.isAlive()&&confirmed.process.isAlive()&&service.get(original.token)==null,"Commit failed to stop only original");
        check(service.getMedia(original.token)==original&&Files.exists(original.playlist),"Commit lost published-media grace");
        check(service.commitQuality(original.token,confirmed.token,14001L)==confirmed,"Lost commit response cannot safely retry");
        check(!service.abortQuality(original.token,confirmed.token)&&confirmed.process.isAlive(),"Late abort killed committed producer");
        service.release(original.token);service.release(confirmed.token);

        // Session-local ceiling comes from start, not a mutable global INI or
        // a later client-supplied ceiling. Recovery remains explicitly opted
        // in, step-bounded and subject to the same total two-attempt budget.
        check(service.capabilitiesJson().contains("\"qualityRecoveryRevision\":1"),"Recovery not negotiated");
        MimDirectSessionService.Session ceiling=service.create(source.toString(),"transcode",0L,"off",false,1000);
        MimDirectSessionService.Session reduced=service.prepareQuality(ceiling.token,1000L,700);
        service.commitQuality(ceiling.token,reduced.token,1001L);
        check(reduced.videoCeilingKbps==1000,"Downshift lost original ceiling");
        reduced.qualityChangedAtMs=System.currentTimeMillis()-61000L;
        try{service.prepareQuality(reduced.token,2000L,875);throw new AssertionError("Implicit upshift accepted");}
        catch(IllegalArgumentException expected){check(reduced.qualityReductions==1,"Refused recovery consumed budget");}
        for(int target:new int[]{1001,900}){
            try{service.prepareQuality(reduced.token,2000L,target,true);throw new AssertionError("Unbounded recovery accepted");}
            catch(IllegalArgumentException expected){check(reduced.process.isAlive()&&reduced.qualityReductions==1,"Bad recovery disturbed source");}
        }
        MimDirectSessionService.Session recovered=service.prepareQuality(reduced.token,2000L,875,true);
        check(reduced.process.isAlive()&&recovered.process.isAlive(),"Recovery retired old before frame");
        service.commitQuality(reduced.token,recovered.token,2001L);
        check(recovered.videoCeilingKbps==1000&&recovered.videoKbps==875&&recovered.qualityReductions==2,"Recovery lost ceiling/budget");
        MimDirectSessionService.Session recoveredSeek=service.restart(recovered.token,3000L);
        check(recoveredSeek.videoCeilingKbps==1000&&recoveredSeek.videoKbps==875,"Seek reset recovery ceiling");
        recoveredSeek.qualityChangedAtMs=System.currentTimeMillis()-61000L;
        try{service.prepareQuality(recoveredSeek.token,4000L,1000,true);throw new AssertionError("Third launch accepted");}
        catch(IllegalStateException expected){}
        service.release(recoveredSeek.token);

        MimDirectSessionService.Session timeoutSource=service.create(source.toString(),"transcode",0L,"on",false);
        MimDirectSessionService.Session expired=service.prepareQuality(timeoutSource.token,1000L,1000);
        service.expireQualityHandoffs(System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(MimDirectSessionService.QUALITY_HANDOFF_TIMEOUT_MS+1));
        check(timeoutSource.process.isAlive()&&!expired.process.isAlive()&&service.get(expired.token)==null,"Timeout did not roll back only replacement");
        service.release(timeoutSource.token);

        MimDirectSessionService.Session failedSource=service.create(source.toString(),"transcode",0L,"off",false);
        Path failMarker=paths.runtimeDir.resolve("fail-direct-start");
        Files.write(failMarker,new byte[]{1});
        try {
            try{service.prepareQuality(failedSource.token,1000L,1000);throw new AssertionError("Failed replacement published");}
            catch(java.io.IOException expected){}
        } finally {Files.delete(failMarker);}
        check(failedSource.process.isAlive()&&service.capabilitiesJson().contains("\"activeSessions\":1"),"Startup failure did not preserve original/clean candidate");
        check(failedSource.qualityReductions==1,"Failed prepare did not consume bounded budget");
        service.release(failedSource.token);

        MimDirectSessionService.Session seekSource=service.create(source.toString(),"transcode",0L,"off",false);
        MimDirectSessionService.Session superseded=service.prepareQuality(seekSource.token,1000L,1000);
        MimDirectSessionService.Session sought=service.restart(seekSource.token,9000L);
        check(sought.startMs==9000L&&!superseded.process.isAlive()&&service.get(superseded.token)==null,"User seek failed to supersede quality candidate");
        check(sought.videoKbps==6000&&sought.qualityReductions==1,"Seek adopted uncommitted quality/reset attempt budget");
        service.release(seekSource.token);service.release(sought.token);

        check(service.capabilitiesJson().contains("\"qualitySeekRevision\":1"),"Atomic handoff seek capability missing");
        for(String phase:new String[]{"pending","committed","expired"}){
            MimDirectSessionService.Session before=service.create(source.toString(),"transcode",0L,"off",false);
            MimDirectSessionService.Session candidate=service.prepareQuality(before.token,1000L,1000);
            if("committed".equals(phase))service.commitQuality(before.token,candidate.token,1001L);
            if("expired".equals(phase))service.expireQualityHandoffs(System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(MimDirectSessionService.QUALITY_HANDOFF_TIMEOUT_MS+1));
            try{service.seekQuality(before.token,"foreign",19000L,"on");throw new AssertionError("Foreign seek lineage accepted");}catch(IllegalStateException expected){}
            MimDirectSessionService.Session result=service.seekQuality(before.token,candidate.token,19000L,"on");
            check(result.startMs==19000L&&"on".equals(result.deinterlace),"Atomic seek lost explicit target/policy: "+phase);
            check(result.videoKbps==("committed".equals(phase)?1000:6000),"Atomic seek guessed wrong producer policy: "+phase);
            check(!candidate.process.isAlive()&&!before.process.isAlive()&&service.get(result.token)==result,"Atomic seek leaked/revived old lineage: "+phase);
            try{service.commitQuality(before.token,candidate.token,1001L);throw new AssertionError("Late commit resurrected superseded lineage");}catch(IllegalStateException expected){}
            service.release(result.token);
        }

        MimDirectSessionService.Session stopped=service.create(source.toString(),"transcode",0L,"off",false);
        MimDirectSessionService.Session stopCandidate=service.prepareQuality(stopped.token,1000L,1000);
        service.release(stopped.token);
        check(!stopped.process.isAlive()&&!stopCandidate.process.isAlive()&&service.get(stopCandidate.token)==null,"Source STOP leaked candidate");

        MimDirectSessionService.Session capped=service.create(source.toString(),"transcode",0L,"off",false);
        MimDirectSessionService.Session[] others=new MimDirectSessionService.Session[3];
        for(int i=0;i<others.length;i++)others[i]=service.create(source.toString(),"copy",0L);
        try{service.prepareQuality(capped.token,1000L,1000);throw new AssertionError("Full service admitted overlapping producer");}
        catch(IllegalStateException expected){check("direct_handoff_capacity".equals(expected.getMessage())&&capped.qualityReductions==0&&capped.process.isAlive(),"Capacity refusal disturbed source/budget");}
        for(MimDirectSessionService.Session other:others)service.release(other.token);
        service.release(capped.token);

        // Cancellation while first-segment creation is still blocked must
        // close the registered candidate, not allow a late orphan launch.
        final MimDirectSessionService.Session racing=service.create(source.toString(),"transcode",0L,"off",false);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> pending=worker.submit(new Runnable(){public void run(){
                try{service.prepareQuality(racing.token,1000L,1000);throw new AssertionError("Cancelled preparation published");}
                catch(java.io.IOException|IllegalStateException expected){}
            }});
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(!service.capabilitiesJson().contains("\"activeSessions\":2")&&System.nanoTime()<deadline)Thread.sleep(10L);
            check(service.capabilitiesJson().contains("\"activeSessions\":2"),"Preparation never registered candidate");
            service.release(racing.token);
            pending.get(5,java.util.concurrent.TimeUnit.SECONDS);
            check(service.capabilitiesJson().contains("\"activeSessions\":0"),"Cancelled preparation leaked registered sessions");
        } finally {worker.shutdownNow();}
        boolean rejected = false;
        try { service.create(home.resolve("not-library.ts").toString(), "copy", 0L); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "non-library source was accepted");
        service.close();
        System.out.println("MimDirectSessionServiceTest PASS");
    }
}

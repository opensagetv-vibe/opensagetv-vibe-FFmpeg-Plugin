package org.opensagetv.vibe.ffmpeg;

import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/** Real local HTTP/fake MIM test, never a physical image-accuracy claim. */
public final class ScrubPreviewHttpTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void text(Path p,String value)throws Exception{Files.write(p,value.getBytes(StandardCharsets.UTF_8));}
    private static int port()throws Exception{try(ServerSocket s=new ServerSocket(0)){return s.getLocalPort();}}
    private static final class Reply {int status;byte[] body;String type,position,cached;}
    private static Reply http(int port,String method,String query)throws Exception{
        String path=query.startsWith("/v1/")?query:"/v1/direct/preview"+query;
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();
        c.setConnectTimeout(2000);c.setReadTimeout(5000);c.setRequestMethod(method);
        if("POST".equals(method))c.setDoOutput(true);
        try {
            Reply r=new Reply();r.status=c.getResponseCode();r.type=c.getHeaderField("Content-Type");
            r.position=c.getHeaderField("X-Vibe-Preview-Position-Ms");r.cached=c.getHeaderField("X-Vibe-Preview-Cached");
            java.io.InputStream input=r.status>=400?c.getErrorStream():c.getInputStream();
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            if(input!=null)try{byte[] b=new byte[2048];int n;while((n=input.read(b))!=-1){bytes.write(b,0,n);check(bytes.size()<=65536,"HTTP output unbounded");}}finally{input.close();}
            r.body=bytes.toByteArray();return r;
        }finally{c.disconnect();}
    }
    public static void main(String[] args)throws Exception{
        Path home=Paths.get(args[0]).toAbsolutePath();System.setProperty("vibe.ffmpeg.sageHome",home.toString());
        RuntimePaths paths=RuntimePaths.detect();Files.createDirectories(paths.runtimeDir);
        text(paths.ini,"[general]\nmode=passthrough\n[safety]\nisolate_child_process=true\n");text(paths.defaultIni,"");
        text(paths.ffmpegExecutable,"stub");paths.ffmpegExecutable.toFile().setExecutable(true);
        text(paths.ffprobeExecutable,"#!/bin/sh\nprintf '60.000\\n'\n");paths.ffprobeExecutable.toFile().setExecutable(true);
        text(paths.mimExecutable,"#!/bin/sh\ncd \"$(dirname \"$0\")\"\n"
                +"if [ \"$1\" = --mim-capabilities ]; then printf '{\"ownedDirectStreams\":true}'; exit 0; fi\n"
                +"case \"$*\" in *image2pipe*) printf '%s\\n' \"$@\" >preview.args; "
                +"if [ -f preview.slow ]; then touch preview.started; exec sleep 20; fi; printf '\\377\\330\\377\\331'; exit 0;; esac\n"
                +"playlist=''; previous=''; for arg in \"$@\"; do if [ \"$previous\" = -segment_list ]; then playlist=\"$arg\"; fi; previous=\"$arg\"; done\n"
                +"dir=$(dirname \"$playlist\"); dd if=/dev/zero of=\"$dir/seg_000000.ts\" bs=188 count=8 2>/dev/null\n"
                +"printf '#EXTM3U\\n#EXTINF:2.0,\\nseg_000000.ts\\n' >\"$playlist\"\nIFS= read -r control\n");
        paths.mimExecutable.toFile().setExecutable(true);
        Path source=home.resolve("authorized fixture.ts");text(source,"fixture");
        AtomicReference<Boolean> writer=new AtomicReference<Boolean>(Boolean.FALSE);
        MimDirectSessionService direct=new MimDirectSessionService(paths,new MimDirectSessionService.SourceAuthorizer(){
            public boolean isAllowed(Path value){return source.equals(value);}
            public Boolean isRecording(Path value){return writer.get();}
        });
        CaptionSideChannelService captions=new CaptionSideChannelService(paths);
        int port=port();CaptionSideChannelHttpServer server=new CaptionSideChannelHttpServer("127.0.0.1",port,captions,direct);
        try {
            direct.start(true);server.start();
            check(direct.capabilitiesJson().contains("\"sourcePreviewRevision\":1"),"Qualified preview not advertised");
            MimDirectSessionService.Session session=direct.create(source.toString(),"copy",0);
            String token=session.token,query="?token="+token+"&positionMs=42000";
            check(http(port,"GET",query).status==405,"GET must not allocate work");
            check(http(port,"POST","?positionMs=42000").status==400,"Missing custody accepted");
            check(http(port,"POST","?token=unknown&positionMs=42000").status==400,"Unknown source accepted");
            Reply first=http(port,"POST",query);
            check(first.status==200&&"image/jpeg".equals(first.type)&&first.body.length==4,"JPEG response contract lost");
            check("40000".equals(first.position)&&"false".equals(first.cached),"Actual preview bucket not identified");
            Reply hit=http(port,"POST",query);check(hit.status==200&&"true".equals(hit.cached),"Completed source did not reuse preview");
            for(String refused:new String[]{"foreign-token",token}){
                try{direct.prepareQuality(refused,42000,1000);throw new AssertionError("Invalid/Copy quality admitted");}
                catch(IllegalArgumentException expected){}
                check("true".equals(http(port,"POST",query).cached),"Refused quality request purged another owner's preview");
            }
            for(Boolean value:new Boolean[]{Boolean.TRUE,null}){writer.set(value);check(http(port,"POST",query).status==409,"Real/unknown writer reused cached image");}
            writer.set(Boolean.FALSE);
            Thread.sleep(2100);text(paths.runtimeDir.resolve("preview.slow"),"");
            AtomicReference<Reply> slow=new AtomicReference<Reply>();AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
            Thread request=new Thread(()->{try{slow.set(http(port,"POST","?token="+token+"&positionMs=10000"));}catch(Throwable t){failure.set(t);}});
            request.start();long deadline=System.nanoTime()+2000000000L;
            while(!Files.exists(paths.runtimeDir.resolve("preview.started"))&&System.nanoTime()<deadline)Thread.sleep(10);
            check(Files.exists(paths.runtimeDir.resolve("preview.started")),"Preview job did not start");
            long began=System.nanoTime();direct.release(token);request.join(2500);
            check(!request.isAlive()&&failure.get()==null,"Source release left blocked HTTP/image work");
            check(slow.get().status!=200&&(System.nanoTime()-began)/1000000<2500,"Cancelled image resurrected after source release");
            check(http(port,"POST",query).status==400,"Retired token reused preview");
            check(direct.capabilitiesJson().contains("\"activeSessions\":0"),"Preview created another playback session");
            Files.deleteIfExists(paths.runtimeDir.resolve("preview.slow"));Thread.sleep(2100);
            String reserve="/v1/previews/reserve?source="+java.net.URLEncoder.encode(source.toString(),"UTF-8");
            check(http(port,"GET",reserve).status==405,"GET allocated a preview reservation");
            check(http(port,"POST","/v1/previews/reserve?source=/unrelated/file").status==400,"Arbitrary source was reserved");
            java.util.List<String> leases=new java.util.ArrayList<String>();
            for(int i=0;i<4;i++){
                Reply reserved=http(port,"POST",reserve);check(reserved.status==200,"Preview reservation failed");
                java.util.Map<String,Object> value=MiniJson.object(new String(reserved.body,StandardCharsets.UTF_8));
                check(((Number)value.get("expiresInMs")).longValue()==120000,"Preview-only lease lost finite lifetime");
                leases.add((String)value.get("previewToken"));
            }
            check(http(port,"POST",reserve).status==409,"Preview-only lease capacity was not enforced");
            String image="/v1/previews/image?previewToken="+leases.get(0)+"&positionMs=42000";
            check(http(port,"POST",image).status==200,"Shared preview lease requires an unwanted Direct playback producer");
            check(direct.capabilitiesJson().contains("\"activeSessions\":0"),"Read-only preview reserved a playback producer");
            for(String lease:leases)check(http(port,"POST","/v1/previews/teardown?previewToken="+lease).status==200,"Preview-only teardown failed");
            check(http(port,"POST",image).status==400,"Retired preview-only lease was revived");
            check(direct.capabilitiesJson().contains("\"activePreviewLeases\":0"),"Preview-only lease leaked");
        }finally{server.stop();direct.close();captions.close();}
        System.out.println("ScrubPreviewHttpTest PASS: binary POST/cache/writer/retired source/cancel/zero producers");
    }
}

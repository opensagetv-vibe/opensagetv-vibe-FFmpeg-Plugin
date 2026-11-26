package org.opensagetv.vibe.ffmpeg;
import java.io.*;
public final class RuntimeBitrateControlTest {
    static final class Child extends Process {
        final ByteArrayOutputStream commands=new ByteArrayOutputStream();boolean alive=true;
        public OutputStream getOutputStream(){return commands;}
        public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
        public InputStream getErrorStream(){return getInputStream();}
        public int waitFor(){alive=false;return 0;}public int exitValue(){return 0;}
        public void destroy(){alive=false;}public boolean isAlive(){return alive;}
    }
    static void check(boolean pass){if(!pass)throw new AssertionError();}
    static void reject(Runnable action){try{action.run();throw new AssertionError("accepted invalid request");}catch(IllegalArgumentException|IllegalStateException expected){}}
    static void ask(RuntimeBitrateControl c,long id,int target,long now){try{c.request(id,target,now);}catch(IOException e){throw new AssertionError(e);}}
    public static void main(String[] args)throws Exception{
        Child p=new Child();RuntimeBitrateControl c=new RuntimeBitrateControl(p,6000,6000);
        reject(()->ask(c,1,2000,1000));
        c.observe("VIBE_RATE_CONTROL revision=1 encoder=h264_qsv");
        c.snapshot(20000,1000);ask(c,1,2000,1000);ask(c,1,2000,1001);
        check(p.commands.toString("US-ASCII").equals("videorateadapt -4000\n"));
        reject(()->ask(c,1,2500,1002));reject(()->ask(c,2,1500,1002));
        c.observe("[h264_qsv @ 1] SageTV videorateadapt encoder=h264_qsv old=6000000 delta=-4000000 new=2000000");
        check(c.snapshot(25000,1003).contains("\"pending\""));
        c.observe("[h264_qsv @ 1] Reset BufferSizeInKB: 750; TargetKbps: 2000; MaxKbps: 2000; BRCParamMultiplier: 1");
        check(c.snapshot(26000,1004).contains("\"pending\""));
        check(c.snapshot(28001,1005).contains("\"applied\""));check(c.currentKbps()==2000);
        reject(()->ask(c,2,2500,1006));reject(()->ask(c,2,6001,20000));reject(()->ask(c,2,4000,20000));
        ask(c,2,2500,20000);reject(()->ask(c,1,2000,20001));
        check(c.snapshot(40000,36000).contains("\"unconfirmed\""));
        check(!c.available());check(p.alive);
        Child failed=new Child();RuntimeBitrateControl d=new RuntimeBitrateControl(failed,6000,6000);
        d.observe("VIBE_RATE_CONTROL revision=1 encoder=h264_qsv");ask(d,1,2000,1000);
        d.observe("Error during resetting: -9");check(d.snapshot(10000,2000).contains("unconfirmed"));
        check(failed.alive);
        System.out.println("PASS runtime control ready/reset/progress/idempotence/conflict/pending/ceiling/recovery/cooldown/stale/timeout/error/retained-process guards");
    }
}

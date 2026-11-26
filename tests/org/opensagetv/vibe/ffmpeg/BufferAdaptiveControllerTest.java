package org.opensagetv.vibe.ffmpeg;

public final class BufferAdaptiveControllerTest {
    private static void eq(long expected,long actual){if(expected!=actual)throw new AssertionError(expected+" != "+actual);}
    private static HlsProductionWitness.Snapshot playlist(String text){HlsProductionWitness p=new HlsProductionWitness();byte[] b=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);for(int i=0;i<b.length;i++)p.read(b,i,1);return p.finish();}
    public static void main(String[] args){
        if(!BufferAdaptiveController.producerReady(20000,10000,1000,false))throw new AssertionError("fully fetched but freshly published output is healthy");
        if(BufferAdaptiveController.producerReady(20000,10000,10001,false)||BufferAdaptiveController.producerReady(14000,10000,0,false)
            ||BufferAdaptiveController.producerReady(20000,-1,0,false))throw new AssertionError("stale/near-tail/unknown producer accepted");
        HlsProductionWitness.Timeline output=new HlsProductionWitness.Timeline();
        HlsProductionWitness.Snapshot first=playlist("#EXTM3U\n#EXTINF:1.5,\na.ts\n#EXTINF:2,\nb.ts\n");
        eq(3500,output.receive(first));
        eq(6500,output.receive(playlist("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2,\nb.ts\n#EXTINF:3,\nc.ts\n")));
        eq(6500,output.receive(first)); // concurrent older snapshot cannot regress
        eq(-1,output.receive(playlist("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:1,\nf.ts\n")));
        eq(-1,output.receive(playlist("#EXTM3U\n#EXTINF:NaN,\na.ts\n")));
        BufferAdaptiveController p=new BufferAdaptiveController(1000,4000,true);
        eq(0,p.observe(0,1,7000,false,true,true,4000));eq(0,p.observe(3000,2,5500,false,true,true,4000));
        eq(0,p.observe(6000,3,4000,false,true,true,4000));eq(3000,p.observe(9000,4,2500,false,true,true,4000));
        eq(0,p.observe(12000,4,0,true,true,true,3000)); // duplicate report
        p=new BufferAdaptiveController(3500,4000,true);
        eq(0,p.observe(0,1,0,true,true,true,4000));eq(0,p.observe(3000,2,0,true,true,true,4000));eq(3500,p.observe(6000,3,0,true,true,true,4000));
        for(int i=3;i<30;i++)eq(0,p.observe(i*3000,i+1,0,true,true,true,3500));
        for(boolean health:new boolean[]{false,true}){
            p=new BufferAdaptiveController(250,4000,true);
            for(int i=0;i<20;i++)eq(0,p.observe(i*3000,i+1,1000,false,true,health,4000)); // stable low is not pressure
        }
        p=new BufferAdaptiveController(1000,4000,true);
        for(int i=0;i<20;i++)eq(0,p.observe(i*3000,i+1,0,true,true,false,4000));
        p=new BufferAdaptiveController(1000,2300,true);
        for(int i=0;i<9;i++)eq(0,p.observe(i*3000,i+1,12000,false,true,true,2000));
        eq(2300,p.observe(27000,10,12000,false,true,true,2000));
        for(int i=10;i<20;i++)eq(0,p.observe(i*3000,i+1,12000,false,true,true,2300));
        p=new BufferAdaptiveController(1000,4000,false);
        p.observe(0,1,0,true,true,true,4000);p.observe(3000,2,0,true,true,true,4000);
        eq(3000,p.observe(6000,3,0,true,true,true,4000));p.epoch();
        long[] refill={29997,30251,29504,30508,29761,29764,30017,30271,29494,29747};
        for(int i=0;i<9;i++)eq(0,p.observe(39000+i*3000,4+i,refill[i],false,true,true,3000));
        eq(3750,p.observe(66000,13,refill[9],false,true,true,3000));
        p=new BufferAdaptiveController(1000,4000,false);
        p.observe(0,1,0,true,true,true,4000);p.observe(3000,2,0,true,true,true,4000);
        eq(3000,p.observe(6000,3,0,true,true,true,4000));p.epoch();
        long[] paced={6169,6420,5680,6190,6440,5700,6210,6460,5720,6230};
        for(int i=0;i<9;i++)eq(0,p.observe(39000+i*3000,4+i,paced[i],false,true,true,3000));
        eq(3750,p.observe(66000,13,paced[9],false,true,true,3000));
        p=new BufferAdaptiveController(1000,4000,true);
        for(int i=0;i<25;i++)eq(0,p.observe(i*3000,i+1,4999,false,true,true,3000));
        p=new BufferAdaptiveController(1000,4000,true);
        for(int i=0;i<25;i++)eq(0,p.observe(i*3000,i+1,30000-i*400,false,true,true,3000));
        p=new BufferAdaptiveController(250,4000,false);
        for(int i=0;i<2;i++)eq(0,p.observe(i*3000,i+1,0,true,true,true,4000));
        eq(3000,p.observe(6000,3,0,true,true,true,4000));p.epoch();
        eq(0,p.observe(10000,4,0,true,true,true,3000));
        eq(0,p.observe(13000,5,0,true,true,true,3000));eq(0,p.observe(16000,6,0,true,true,true,3000));
        eq(0,p.observe(66000,7,0,true,true,true,3000));eq(0,p.observe(69000,8,0,true,true,true,3000));
        eq(2250,p.observe(72000,9,0,true,true,true,3000));p.epoch();
        for(int i=0;i<30;i++)eq(0,p.observe(140000+i*3000,10+i,0,true,true,true,2250));
        if(!p.json(39,0).contains("handoff-budget"))throw new AssertionError("budget absent");
        p=new BufferAdaptiveController(250,4000,true);
        p.observe(0,1,0,true,true,true,4000);p.observe(3000,2,0,true,true,true,4000);
        eq(0,p.observe(6000,3,0,true,false,true,4000));eq(0,p.observe(9000,4,0,true,true,true,4000));
        eq(0,p.observe(20000,5,0,true,true,true,4000)); // stale report gap resets pressure
        testOverride();
        System.out.println("PASS shared MIM buffer policy: drain/rebuffer/floor/ceiling/replay/pause/producer/gap/cooldown/handoff budget");
    }
    private static void reject(Runnable r){try{r.run();throw new AssertionError("expected refusal");}catch(IllegalArgumentException|IllegalStateException expected){}}
    private static String id(BufferAdaptiveController p){return p.json(0,0).replaceAll(".*\"testSessionId\":\"([0-9a-f]+)\".*","$1");}
    private static void testOverride(){
        BufferAdaptiveController a=new BufferAdaptiveController(1000,4000,true),b=new BufferAdaptiveController(1000,4000,true);
        a.observe(0,1,12000,false,true,true,4000);b.observe(0,1,12000,false,true,true,4000);
        String handle=id(a);String other=id(b);
        if(handle.equals(other)||!BufferAdaptiveController.testSessions().contains(handle))throw new AssertionError("isolated handles");
        reject(()->BufferAdaptiveController.testOverride(handle,0,999,120));
        reject(()->BufferAdaptiveController.testOverride(handle,0,4001,120));
        reject(()->BufferAdaptiveController.testOverride(handle,0,2000,301));
        reject(()->BufferAdaptiveController.testOverride("bad",0,2000,120));
        BufferAdaptiveController.testOverride(handle,0,2000,120);
        reject(()->BufferAdaptiveController.testOverride(handle,0,2000,120));
        eq(2000,a.observe(3000,2,12000,false,true,true,4000));
        eq(0,b.observe(3000,2,12000,false,true,true,4000));
        eq(0,a.observe(6000,3,12000,false,true,true,4000)); // never replay uncertain application
        eq(0,a.observe(9000,4,12000,false,true,true,2000));
        if(!a.json(4,0).contains("test-override-observed"))throw new AssertionError("separate observed acknowledgement");
        a.epoch();eq(0,a.observe(12000,5,12000,false,true,true,2000)); // handoff does not resend
        a.observe(15000,6,12000,false,false,true,2000);
        reject(()->BufferAdaptiveController.testOverride(handle,1,3000,120));
        BufferAdaptiveController.testOverride(handle,1,0,0); // clear works while paused
        a.observe(18000,7,12000,false,true,true,2000);
        BufferAdaptiveController.testOverride(handle,2,3000,15);
        try{java.lang.reflect.Field until=BufferAdaptiveController.class.getDeclaredField("testUntil");until.setAccessible(true);until.setLong(a,0);}
        catch(Exception fail){throw new AssertionError(fail);}
        eq(0,a.observe(21000,8,12000,false,true,true,2000));
        if(BufferAdaptiveController.testSessions().contains("\"overrideKbps\":3000"))throw new AssertionError("lease failed to clear");
        BufferAdaptiveController c=new BufferAdaptiveController(1000,4000,false);
        c.observe(0,1,12000,false,true,true,4000);String cid=id(c);
        BufferAdaptiveController.testOverride(cid,0,3000,120);eq(3000,c.observe(3000,2,12000,false,true,true,4000));
        BufferAdaptiveController.testOverride(cid,1,2000,120);eq(2000,c.observe(6000,3,12000,false,true,true,3000));
        reject(()->BufferAdaptiveController.testOverride(cid,2,1000,120));
        BufferAdaptiveController.testOverride(cid,2,0,0);
        System.out.println("PASS exact-session test override: range/CAS/lease/once/observed/pause/clear/isolation/handoff budget");
    }
}

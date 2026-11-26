package org.opensagetv.vibe.ffmpeg;

/** Shared MIM control-layer policy. Clients report reserve, never a bandwidth
 * estimate. No timers, threads, files, player calls or mutations live here.
 * Exact producer owners validate health and execute the existing rate/handoff
 * protocol; only their client may acknowledge actual replacement frames. */
public final class BufferAdaptiveController {
    // Weak, bounded commissioning directory. Handles are NOT media/session
    // credentials. No filesystem, encoder or global INI mutation is exposed.
    private static final java.util.Map<BufferAdaptiveController,String> TESTS=new java.util.WeakHashMap<BufferAdaptiveController,String>();
    private final String testId=java.util.UUID.randomUUID().toString().replace("-","");
    private long testSeen,testUntil,testRevision;
    private int testTarget,testCurrent;
    private boolean testEligible,testIssued;
    private final int minimum,maximum;
    private final boolean inPlace;
    private long last=-1,reserve=-1,highWater,cooldown,lastReport,recoveryAnchor=-1;
    private int low,high,samples,attempts;
    private String reason="warming-up";
    public static int protocolVersion(){return 1;}
    public static boolean producerReady(long published,long position,long progressAge,boolean complete){
        // The browser may already have fetched EVERY published segment. That
        // does not make a healthy producer unavailable. Require output ahead
        // of playback plus actual recent publication, not extra un-fetched data.
        return position>=0&&published-position>5000&&(complete||progressAge>=0&&progressAge<=10000);
    }
    public BufferAdaptiveController(int min,int max,boolean sameEncoder){
        if(min<250||max>50000||min>max)throw new IllegalArgumentException("invalid_bitrate_range");
        minimum=min;maximum=max;inPlace=sameEncoder;
    }
    public synchronized void epoch(){last=reserve=recoveryAnchor=-1;highWater=0;low=high=0;reason="warming-up";}
    public synchronized int observe(long now,long report,long bufferMs,boolean buffering,boolean eligible,boolean producerReady,int current){
        if(report<=lastReport){reason="stale-report";return 0;}lastReport=report;
        long clock=System.nanoTime()/1000000L;
        testSeen=clock;testCurrent=current;testEligible=eligible&&producerReady&&bufferMs>=0&&bufferMs<=600000;
        synchronized(TESTS){if(TESTS.containsKey(this)||TESTS.size()<32)TESTS.put(this,testId);}
        expireTest(clock);
        if(!eligible||!producerReady||bufferMs<0||bufferMs>600000||current<250||current>maximum){epoch();reason=!eligible?"ineligible":!producerReady?"producer-unavailable":"unknown-buffer";return 0;}
        if(testTarget>0){
            low=high=0;
            if(testIssued||current==testTarget){reason=current==testTarget?"test-override-observed":"test-override-awaiting-result";return 0;}
            if(!inPlace&&attempts>=2){reason="handoff-budget";return 0;}
            testIssued=true;attempts++;reason="test-override-requested";return testTarget;
        }
        if(!inPlace&&attempts>=2){epoch();reason="handoff-budget";return 0;}
        if(last>=0&&now-last<3000)return 0;
        if(last>=0&&now-last>10000)epoch();
        boolean falling=reserve>=0&&bufferMs<reserve-250;
        highWater=Math.max(highWater,bufferMs);reserve=bufferMs;last=now;samples++;
        low=(buffering&&bufferMs<5000||falling&&bufferMs<8000)?low+1:0;
        int next=Math.min(maximum,Math.max(minimum,current*5/4/50*50));
        // Segment-sized refill oscillation is not sustained drain. Comparing
        // adjacent reports with a250ms threshold reset recovery forever on a
        // healthy30s buffer. Require ten high-reserve samples and bound the
        // cumulative loss across that window instead (not just each delta).
        // Responsive Direct HLS is deliberately paced about6s ahead. An8s
        // absolute requirement can never qualify that healthy stream. Keep
        // at least5s reserve plus relative high-water/window stability instead
        // of inflating the producer burst or the client's startup buffer.
        boolean recovery=!buffering&&bufferMs>=5000&&bufferMs>=highWater*3/4&&next>current;
        if(!recovery){high=0;recoveryAnchor=-1;}
        else if(high==0||bufferMs<recoveryAnchor-1500){high=1;recoveryAnchor=bufferMs;}
        else high++;
        reason=now<cooldown?"cooldown":low>0?"buffer-draining":high>0?"buffer-recovering":"buffer-stable";
        if(now<cooldown)return 0;
        int target=low>=3?Math.max(minimum,current*3/4/50*50):high>=10?next:0;
        if(target<=0||target==current||low>=3&&target>current)return 0;
        cooldown=now+(target>current?60000:inPlace?20000:60000);low=high=0;attempts++;
        reason=target>current?"stable-buffer-recovery":"buffer-pressure";return target;
    }
    public synchronized String json(long report,int target){return "{\"revision\":1,\"available\":true,\"reportId\":"+report+",\"targetKbps\":"+target+
        ",\"minimumKbps\":"+minimum+",\"maximumKbps\":"+maximum+",\"method\":\"buffer-only\",\"reason\":\""+reason+"\",\"samples\":"+samples+",\"attempts\":"+attempts+",\"reserveMs\":"+reserve+",\"testSessionId\":\""+testId+"\"}";}
    private void expireTest(long clock){if(testTarget>0&&clock>=testUntil){testTarget=0;testUntil=0;testIssued=false;testRevision++;epoch();}}
    private synchronized String testStatus(){
        long clock=System.nanoTime()/1000000L;expireTest(clock);
        return "{\"sessionId\":\""+testId+"\",\"revision\":"+testRevision+",\"minimumKbps\":"+minimum+",\"maximumKbps\":"+maximum+
            ",\"observedKbps\":"+testCurrent+",\"overrideKbps\":"+testTarget+",\"remainingMs\":"+Math.max(0,testUntil-clock)+
            ",\"eligible\":"+(testEligible&&clock-testSeen<=10000)+",\"issued\":"+testIssued+",\"inPlace\":"+inPlace+
            ",\"reason\":\""+reason+"\",\"reportAgeMs\":"+Math.max(0,clock-testSeen)+"}";
    }
    public static String testSessions(){
        java.util.List<BufferAdaptiveController> list;
        synchronized(TESTS){list=new java.util.ArrayList<BufferAdaptiveController>(TESTS.keySet());}
        StringBuilder out=new StringBuilder("[");
        for(BufferAdaptiveController p:list){if(out.length()>1)out.append(',');out.append(p.testStatus());}return out.append(']').toString();
    }
    /** Exact handle + compare-and-set revision; target zero clears. Leases
     * auto-clear even if a test runner disconnects. Advice is sent ONCE through
     * the existing client rate/frame-ACK path, never by creating a producer. */
    public static String testOverride(String id,long expected,int target,int seconds){
        if(id==null||!id.matches("[0-9a-f]{32}")||expected<0||target<0||seconds<0||seconds>300||target>0&&seconds<15||target==0&&seconds!=0)
            throw new IllegalArgumentException("invalid_test_override");
        BufferAdaptiveController selected=null;
        synchronized(TESTS){for(java.util.Map.Entry<BufferAdaptiveController,String> e:TESTS.entrySet())if(id.equals(e.getValue()))selected=e.getKey();}
        if(selected==null)throw new IllegalStateException("test_session_unavailable");
        synchronized(selected){
            long clock=System.nanoTime()/1000000L;selected.expireTest(clock);
            if(expected!=selected.testRevision)throw new IllegalStateException("test_revision_changed");
            if(target>0&&(target<selected.minimum||target>selected.maximum))throw new IllegalArgumentException("test_target_outside_session_range");
            if(target>0&&(!selected.testEligible||clock-selected.testSeen>10000))throw new IllegalStateException("test_session_ineligible");
            if(target>0&&!selected.inPlace&&selected.attempts>=2)throw new IllegalStateException("test_handoff_budget");
            selected.testTarget=target;selected.testUntil=target==0?0:clock+seconds*1000L;selected.testIssued=false;selected.testRevision++;selected.epoch();
            return selected.testStatus();
        }
    }
}

package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional per-owned-process protocol. Absolute HTTP targets are translated
 * ONCE to the existing engine delta command. An encoder setting log is not an
 * acknowledgement: require the QSV reset AND subsequent produced video time.
 * No process launch, seek, shared INI edit, or client clock is owned here. */
public final class RuntimeBitrateControl {
    private static final Pattern TARGET=Pattern.compile("TargetKbps: (\\d+)");
    private final Process process;
    private int ceiling;
    private int current,requested;
    private long lastId,requestAt,lastChange,progress,resetProgress;
    private boolean ready,reset,mutation;
    private String state="unavailable";
    public static int protocolVersion(){return 1;}
    public static boolean enabled(){
        try{RuntimePaths p=RuntimePaths.detect();
            if(p.windows||!Boolean.parseBoolean(IniDocument.load(p.ini).get("stdinctrl","runtime_bitrate_control","false")))return false;
            Object revision=new MimRuntime(p).capabilities(false).json.get("runtimeBitrateControlRevision");
            return revision instanceof Number&&((Number)revision).doubleValue()==1.0;
        }catch(Exception unavailable){return false;}
    }
    public synchronized void restoreCeiling(int value){if(value<current||value>50000)throw new IllegalArgumentException("invalid_ceiling");ceiling=value;}
    public RuntimeBitrateControl(Process process,int initial,int ceiling){
        if(process==null||initial<250||initial>50000||ceiling<initial||ceiling>50000)
            throw new IllegalArgumentException("invalid_runtime_rate_owner");
        this.process=process;this.current=initial;this.ceiling=ceiling;
    }
    public synchronized void observe(String line){
        if(line==null)return;
        if(line.equals("VIBE_RATE_CONTROL revision=1 encoder=h264_qsv")){ready=true;if(lastId==0)state="ready";}
        if(line.startsWith("VIBE_RATE_CONTROL unavailable")||line.contains("Error during resetting")
                ||line.contains("Error during encoding")){ready=false;state=lastId==0?"unavailable":"unconfirmed";}
        if(!"pending".equals(state))return;
        if(line.contains("SageTV videorateadapt encoder=h264_qsv ")&&line.endsWith("new="+(requested*1000L)))mutation=true;
        if(mutation&&line.contains("[h264_qsv @")&&line.contains("Reset ")){
            Matcher m=TARGET.matcher(line);
            Matcher multiplier=Pattern.compile("BRCParamMultiplier: (\\d+)").matcher(line);
            if(m.find()&&Long.parseLong(m.group(1))*(multiplier.find()?Math.max(1,Long.parseLong(multiplier.group(1))):1)==requested){
                reset=true;resetProgress=progress;
            }
        }
    }
    public synchronized String request(long id,int target,long now)throws IOException{
        update(progress,now);
        if(id<1||id>9007199254740991L||target<250||target>ceiling)
            throw new IllegalArgumentException("invalid_runtime_rate_request");
        if(id==lastId){if(target!=requested)throw new IllegalArgumentException("runtime_rate_id_conflict");return json();}
        if(id<lastId)throw new IllegalStateException("stale_runtime_rate_request");
        if(!ready||!process.isAlive()||"pending".equals(state)||"unconfirmed".equals(state))
            throw new IllegalStateException("runtime_rate_unavailable_or_pending");
        if(target==current)throw new IllegalArgumentException("runtime_rate_unchanged");
        if(target>current&&target>current*5/4)throw new IllegalArgumentException("runtime_rate_recovery_step");
        if(lastChange>0&&now-lastChange<15000)throw new IllegalStateException("runtime_rate_cooldown");
        lastId=id;requested=target;requestAt=now;lastChange=now;reset=mutation=false;state="pending";
        try{
            process.getOutputStream().write(("videorateadapt "+(target-current)+"\n").getBytes(StandardCharsets.US_ASCII));
            process.getOutputStream().flush();
        }catch(IOException failed){ready=false;state="unconfirmed";throw failed;}
        return json();
    }
    public synchronized String snapshot(long producedVideoMs,long now){update(producedVideoMs,now);return json();}
    private void update(long producedVideoMs,long now){
        progress=Math.max(progress,producedVideoMs);
        if("pending".equals(state)){
            if(!process.isAlive()||now-requestAt>15000){ready=false;state="unconfirmed";}
            else if(reset&&progress>resetProgress+2000){current=requested;state="applied";}
        }
    }
    public synchronized int currentKbps(){return current;}
    public static long progress(java.nio.file.Path path){
        try(java.io.RandomAccessFile f=new java.io.RandomAccessFile(path.toFile(),"r")){
            f.seek(Math.max(0,f.length()-16384));long value=0;String line;
            while((line=f.readLine())!=null)if(line.startsWith("out_time_us="))
                try{value=Math.max(value,Long.parseLong(line.substring(12))/1000);}catch(NumberFormatException ignored){}
            return value;
        }catch(IOException unavailable){return 0;}
    }
    public synchronized boolean available(){return ready&&process.isAlive()&&!"unconfirmed".equals(state);}
    private String json(){return "{\"revision\":1,\"available\":"+available()+",\"state\":\""+state+
        "\",\"requestId\":"+lastId+",\"requestedKbps\":"+requested+",\"appliedKbps\":"+current+
        ",\"ceilingKbps\":"+ceiling+",\"sameProcess\":true}";}
}

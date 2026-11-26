package org.opensagetv.vibe.ffmpeg;

/** Passive, bounded parser of an already-fetched owned EVENT/VOD playlist.
 * Never fetches anything, retains media URLs or guesses duration from names.
 * Partial, oversized and malformed manifests provide no evidence. Rolling
 * playlists require a continuously overlapping sequence, anchored at zero. */
public final class HlsProductionWitness {
    public static final class Snapshot {
        public final long publishedMs;
        public final boolean ended;
        final long firstSequence;
        final long[] durations;
        Snapshot(long ms,boolean end,long first,long[] parts){publishedMs=ms;ended=end;firstSequence=first;durations=parts;}
    }
    public static final class Timeline {
        private long next=-1,total;
        public synchronized long receive(Snapshot s){
            if(s==null){next=-1;total=0;return -1;}
            if(next<0){if(s.firstSequence!=0)return -1;next=total=0;}
            long end=s.firstSequence+s.durations.length;
            if(end<next)return total; // reordered already-published snapshot
            if(s.firstSequence>next){next=-1;total=0;return -1;}
            for(int i=(int)(next-s.firstSequence);i<s.durations.length;i++)total+=s.durations[i];
            next=end;return total;
        }
    }
    private final StringBuilder line=new StringBuilder();
    private int bytes,segments;
    private boolean header,invalid,ended;
    private double duration,pending=-1;
    private long firstSequence;
    private final long[] durations=new long[512];
    public void read(byte[] data,int offset,int length){
        if(invalid)return;
        bytes+=length;if(bytes>4194304){invalid=true;return;}
        for(int i=offset;i<offset+length;i++){
            int c=data[i]&255;
            if(c==10){consume();line.setLength(0);}
            else if(c!=13){if(line.length()>=4096){invalid=true;return;}line.append((char)c);}
        }
    }
    private void consume(){
        String s=line.toString().trim();if(s.isEmpty())return;
        if(!header){header="#EXTM3U".equals(s);invalid=!header;return;}
        try{
            if(s.startsWith("#EXT-X-MEDIA-SEQUENCE:")){firstSequence=Long.parseLong(s.substring(22));if(firstSequence<0||firstSequence>1000000000L||segments>0)invalid=true;}
            else if(s.startsWith("#EXTINF:")){
                if(pending>=0){invalid=true;return;}
                pending=Double.parseDouble(s.substring(8).split(",",2)[0]);
                if(Double.isNaN(pending)||Double.isInfinite(pending)||pending<=0||pending>60)invalid=true;
            }else if(s.equals("#EXT-X-ENDLIST"))ended=true;
            else if(!s.startsWith("#")){
                if(pending<0){invalid=true;return;}
                if(segments>=durations.length){invalid=true;return;}
                durations[segments++]=Math.round(pending*1000);duration+=pending;pending=-1;
            }
        }catch(RuntimeException malformed){invalid=true;}
    }
    public Snapshot finish(){
        if(line.length()>0){consume();line.setLength(0);}
        return !invalid&&header&&segments>0&&pending<0?new Snapshot((long)(duration*1000),ended,firstSequence,java.util.Arrays.copyOf(durations,segments)):null;
    }
}

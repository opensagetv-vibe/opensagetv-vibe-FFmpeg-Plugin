package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Optional single-frame preview custody, not a playback/thumbnail navigator.
 * At most one finite job,16 JPEGs/512KiB in memory,10s source buckets. No file
 * outputs, background worker, waiting queue, negative cache or source paths in
 * results. The caller independently authorizes its current completed source.
 */
final class ScrubPreviewCache {
    interface Work { byte[] run() throws IOException; void cancel(); }
    interface Factory { Work create(long positionMs); }
    static final class Preview {
        final long positionMs;
        final byte[] jpeg;
        final boolean cached;
        Preview(long positionMs,byte[] jpeg,boolean cached){this.positionMs=positionMs;this.jpeg=jpeg.clone();this.cached=cached;}
    }
    private static final class Stamp {
        final List<Object> values;
        Stamp(List<Object> values){this.values=values;}
        static Stamp read(Path source,Path[] dependencies){
            List<Path> paths=new ArrayList<Path>();paths.add(source);paths.addAll(Arrays.asList(dependencies));
            List<Object> values=new ArrayList<Object>();
            try {
                for(Path path:paths){
                    BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(!a.isRegularFile()||a.fileKey()==null)return null;
                    values.add(a.fileKey());values.add(a.size());values.add(a.lastModifiedTime());values.add(a.creationTime());
                }
                return new Stamp(values);
            }catch(IOException unavailable){return null;}catch(SecurityException unavailable){return null;}
        }
        boolean same(Stamp other){return other!=null&&values.equals(other.values);}
    }
    private static final class Key {
        final Object owner;final long position;
        Key(Object owner,long position){this.owner=owner;this.position=position;}
        @Override public int hashCode(){return System.identityHashCode(owner)*31+Long.valueOf(position).hashCode();}
        @Override public boolean equals(Object value){return value instanceof Key&&((Key)value).owner==owner&&((Key)value).position==position;}
    }
    private static final class Entry {
        final Stamp stamp;final byte[] jpeg;
        Entry(Stamp stamp,byte[] jpeg){this.stamp=stamp;this.jpeg=jpeg.clone();}
    }
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<Key,Entry>(16,.75f,true);
    private final LongSupplier clock;
    private Work active;
    private Object activeOwner;
    private long epoch,nextAdmission;
    private int bytes;
    ScrubPreviewCache(){this(()->System.nanoTime());}
    ScrubPreviewCache(LongSupplier clock){this.clock=clock;}

    Preview get(Object owner,Path source,Path[] dependencies,long requestedMs,BooleanSupplier current,Factory factory)throws IOException{
        if(owner==null||source==null||requestedMs<0||requestedMs>14L*86400000L)
            throw new IllegalArgumentException("invalid_preview_request");
        if(!current.getAsBoolean())throw new IllegalStateException("preview_source_not_current");
        final Stamp before=Stamp.read(source,dependencies);
        if(before==null)throw new IllegalStateException("preview_identity_unavailable");
        final long position=requestedMs/10000L*10000L;
        final Key key=new Key(owner,position);
        final Work job;
        final long generation;
        synchronized(this){
            Entry cached=entries.get(key);
            if(cached!=null&&cached.stamp.same(before))return new Preview(position,cached.jpeg,true);
            if(cached!=null){bytes-=cached.jpeg.length;entries.remove(key);}
            long now=clock.getAsLong();
            if(active!=null||now<nextAdmission)throw new IllegalStateException("preview_busy_or_cooldown");
            job=factory.create(position);active=job;activeOwner=owner;generation=epoch;
            nextAdmission=now+2000000000L;
        }
        try {
            byte[] image=job.run();
            if(image==null||image.length<4||image.length>32768)throw new IOException("preview_image_size");
            if(!current.getAsBoolean()||!before.same(Stamp.read(source,dependencies)))
                throw new IllegalStateException("preview_source_changed");
            synchronized(this){
                if(epoch!=generation||active!=job)throw new IllegalStateException("preview_cancelled");
                while(entries.size()>=16||bytes+image.length>524288){
                    Iterator<Map.Entry<Key,Entry>> iterator=entries.entrySet().iterator();
                    Map.Entry<Key,Entry> oldest=iterator.next();bytes-=oldest.getValue().jpeg.length;iterator.remove();
                }
                entries.put(key,new Entry(before,image));bytes+=image.length;
            }
            return new Preview(position,image,false);
        } finally {
            synchronized(this){if(active==job){active=null;activeOwner=null;}}
        }
    }

    /** Invalidate before releasing a source or prioritizing a playback handoff.
     * Keep the slot until the cancelled job actually leaves finally; otherwise
     * fast teardown/start could accumulate multiple still-terminating children.
     */
    synchronized void invalidate(Object owner){
        Iterator<Map.Entry<Key,Entry>> iterator=entries.entrySet().iterator();
        while(iterator.hasNext()){
            Map.Entry<Key,Entry> entry=iterator.next();
            if(owner==null||entry.getKey().owner==owner){bytes-=entry.getValue().jpeg.length;iterator.remove();}
        }
        if(active!=null&&(owner==null||activeOwner==owner)){epoch++;active.cancel();}
    }
    synchronized int count(){return entries.size();}
    synchronized int bytes(){return bytes;}
}

package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small, nonpersistent duration cache for independently proven completed
 * sources. No source path or file identity is exported. This is not a writer
 * detector: the caller must query public SageTV writer truth for EACH request.
 * Unknown/active writers and identity-less files always use the bounded probe.
 * Probe I/O runs outside the cache lock so one slow file cannot block another.
 */
final class CompletedSourceProbeCache {
    interface Probe { Long durationMs(Path source); }
    static final class Result {
        final Long durationMs;
        final String disposition;
        Result(Long durationMs,String disposition){this.durationMs=durationMs;this.disposition=disposition;}
    }
    private static final class Identity {
        final Object key;
        final long size;
        final FileTime modified,created;
        Identity(BasicFileAttributes a){key=a.fileKey();size=a.size();modified=a.lastModifiedTime();created=a.creationTime();}
        static Identity read(Path source){
            try {
                BasicFileAttributes a=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                return a.isRegularFile()&&a.fileKey()!=null?new Identity(a):null;
            } catch(IOException unavailable){return null;}
            catch(SecurityException unavailable){return null;}
        }
        boolean same(Identity other){return other!=null&&key.equals(other.key)&&size==other.size&&modified.equals(other.modified)&&created.equals(other.created);}
    }
    private static final class Entry {
        final Identity identity,tool;final long durationMs;
        Entry(Identity identity,Identity tool,long durationMs){this.identity=identity;this.tool=tool;this.durationMs=durationMs;}
    }
    private final Path probeTool;
    private final Map<Path,Entry> entries;
    private long epoch;
    CompletedSourceProbeCache(Path probeTool){this(32,probeTool);}
    CompletedSourceProbeCache(final int capacity,Path probeTool){
        if(capacity<1||capacity>64)throw new IllegalArgumentException("Invalid probe cache bound");
        if(probeTool==null)throw new IllegalArgumentException("Probe identity required");
        this.probeTool=probeTool;
        entries=new LinkedHashMap<Path,Entry>(capacity,0.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<Path,CompletedSourceProbeCache.Entry> eldest){return size()>capacity;}
        };
    }
    Result duration(Path source,boolean confirmedCompleted,Probe probe){
        Path key=source.toAbsolutePath().normalize();
        if(!confirmedCompleted)return new Result(probe.durationMs(source),"bypass_writer");
        Identity before=Identity.read(source),tool=Identity.read(probeTool);
        if(before==null||tool==null)return new Result(probe.durationMs(source),"bypass_identity");
        final long observedEpoch;
        synchronized(entries){
            observedEpoch=epoch;
            Entry entry=entries.get(key);
            if(entry!=null&&entry.identity.same(before)&&entry.tool.same(tool))return new Result(entry.durationMs,"hit");
            entries.remove(key);
        }
        Long duration=probe.durationMs(source);
        Identity after=Identity.read(source);
        // A source that changed during the probe cannot supply a trustworthy
        // cached seek boundary. Preserve the existing unknown-duration path.
        if(!before.same(after)||!tool.same(Identity.read(probeTool)))return new Result(null,"input_changed");
        if(duration==null||duration.longValue()<=0)return new Result(duration,"unknown");
        synchronized(entries){
            if(epoch!=observedEpoch)return new Result(duration,"bypass_lifecycle");
            entries.put(key,new Entry(after,tool,duration));
        }
        return new Result(duration,"miss");
    }
    void clear(){synchronized(entries){epoch++;entries.clear();}}
}

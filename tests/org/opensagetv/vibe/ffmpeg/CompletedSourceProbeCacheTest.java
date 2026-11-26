package org.opensagetv.vibe.ffmpeg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Filesystem/ownership tests only, not a physical seek or speed-up receipt. */
public final class CompletedSourceProbeCacheTest {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(root);
        Path source=root.resolve("completed source.ts");Files.write(source,new byte[]{1,2,3});
        AtomicInteger calls=new AtomicInteger();
        CompletedSourceProbeCache.Probe probe=p->{calls.incrementAndGet();return Long.valueOf(90000);};
        Path tool=root.resolve("fake probe");Files.write(tool,new byte[]{9});
        CompletedSourceProbeCache cache=new CompletedSourceProbeCache(2,tool);
        check("miss".equals(cache.duration(source,true,probe).disposition),"First completed probe must run");
        check("hit".equals(cache.duration(source,true,probe).disposition)&&calls.get()==1,"Unchanged completed identity must reuse result");
        cache.duration(source,false,probe);cache.duration(source,false,probe);
        check(calls.get()==3,"Unknown/active writer must always probe even if a completed value exists");
        Files.write(source,new byte[]{1,2,3,4});
        check("miss".equals(cache.duration(source,true,probe).disposition)&&calls.get()==4,"Size mutation must invalidate");
        Files.setLastModifiedTime(source,FileTime.fromMillis(Files.getLastModifiedTime(source).toMillis()+10000));
        check("miss".equals(cache.duration(source,true,probe).disposition),"Metadata correction must invalidate");
        Path replacement=root.resolve("replacement.ts");Files.write(replacement,new byte[]{1,2,3,4});
        Files.setLastModifiedTime(replacement,Files.getLastModifiedTime(source));
        Files.move(replacement,source,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        check("miss".equals(cache.duration(source,true,probe).disposition),"New file identity cannot inherit the old duration");
        cache.clear();AtomicInteger unknown=new AtomicInteger();
        CompletedSourceProbeCache.Probe missing=p->{unknown.incrementAndGet();return null;};
        cache.duration(source,true,missing);cache.duration(source,true,missing);
        check(unknown.get()==2,"Failed/unknown probe must not be cached");
        CompletedSourceProbeCache.Result changed=cache.duration(source,true,p->{
            try{Files.write(p,new byte[]{4,3,2,1,0});}catch(java.io.IOException failure){throw new RuntimeException(failure);}
            return Long.valueOf(90000);
        });
        check(changed.durationMs==null&&"input_changed".equals(changed.disposition),"Changed input cannot establish seek bounds");
        check("miss".equals(cache.duration(source,true,probe).disposition),"Changed input was not cached");
        Path second=root.resolve("second.ts"),third=root.resolve("third.ts");Files.write(second,new byte[]{1});Files.write(third,new byte[]{2});
        cache.duration(second,true,probe);cache.duration(source,true,probe);cache.duration(third,true,probe);
        check("miss".equals(cache.duration(second,true,probe).disposition),"Least-recent entry must be evicted at capacity");
        Files.write(tool,new byte[]{8,9});
        check("miss".equals(cache.duration(second,true,probe).disposition),"Probe executable replacement invalidates metadata");
        check("bypass_identity".equals(cache.duration(root,true,probe).disposition),"Non-regular/unknown identity must not be cached");
        // Slow probe I/O never holds the shared cache monitor. A different
        // source must complete while the first deliberately remains blocked.
        cache.clear();CountDownLatch began=new CountDownLatch(1),release=new CountDownLatch(1);
        Thread slow=new Thread(()->cache.duration(source,true,p->{began.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return Long.valueOf(90000);}));
        slow.start();check(began.await(1,TimeUnit.SECONDS),"Slow probe did not begin");
        long started=System.nanoTime();cache.duration(second,true,probe);
        check(System.nanoTime()-started<TimeUnit.SECONDS.toNanos(1),"Slow source blocked an independent lookup");
        cache.clear();release.countDown();slow.join(3000);check(!slow.isAlive(),"Owned probe thread did not finish");
        check("miss".equals(cache.duration(source,true,probe).disposition),"A pre-clear probe must not revive a retired cache generation");
        System.out.println("CompletedSourceProbeCacheTest PASS: identity/mutation/writer/unknown/LRU/independent I/O");
    }
}

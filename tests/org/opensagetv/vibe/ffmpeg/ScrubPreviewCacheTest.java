package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Identity/capacity/ownership tests, not source-frame accuracy receipts. */
public final class ScrubPreviewCacheTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static byte[] image(){byte[] b=new byte[32768];b[0]=(byte)255;b[1]=(byte)216;b[b.length-2]=(byte)255;b[b.length-1]=(byte)217;return b;}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(root);
        Path source=root.resolve("source.ts"),tool=root.resolve("tool");
        Files.write(source,new byte[]{1});Files.write(tool,new byte[]{2});Path[] dependencies={tool};
        AtomicLong clock=new AtomicLong();AtomicInteger generated=new AtomicInteger();
        ScrubPreviewCache cache=new ScrubPreviewCache(()->clock.get());Object owner=new Object();
        ScrubPreviewCache.Factory factory=position->new ScrubPreviewCache.Work(){
            public byte[] run(){generated.incrementAndGet();return image();}public void cancel(){}
        };
        ScrubPreviewCache.Preview first=cache.get(owner,source,dependencies,42000,()->true,factory);
        check(first.positionMs==40000&&!first.cached,"Explicit bucket timestamp must not pretend exact requested frame");
        first.jpeg[0]=0;
        ScrubPreviewCache.Preview hit=cache.get(owner,source,dependencies,44000,()->true,factory);
        check(hit.cached&&generated.get()==1&&(hit.jpeg[0]&255)==255,"Cached bytes/custody must not alias caller memory");
        try{cache.get(owner,source,dependencies,60000,()->true,factory);throw new AssertionError("Cooldown not enforced");}
        catch(IllegalStateException expected){}
        clock.addAndGet(3000000000L);Files.write(source,new byte[]{1,2});
        check(!cache.get(owner,source,dependencies,42000,()->true,factory).cached,"Changed source reused stale frame");
        clock.addAndGet(3000000000L);Files.write(tool,new byte[]{3,4});
        check(!cache.get(owner,source,dependencies,42000,()->true,factory).cached,"Changed tool/policy reused stale frame");
        try{cache.get(owner,source,dependencies,42000,()->false,factory);throw new AssertionError("Retired source hit cache");}
        catch(IllegalStateException expected){}
        for(int i=0;i<18;i++){clock.addAndGet(3000000000L);cache.get(owner,source,dependencies,i*10000L,()->true,factory);}
        check(cache.count()==16&&cache.bytes()==524288,"Memory or entry bound exceeded");
        cache.invalidate(owner);check(cache.count()==0&&cache.bytes()==0,"Source retirement retained images");
        clock.addAndGet(3000000000L);
        try{cache.get(owner,source,dependencies,42000,()->true,position->new ScrubPreviewCache.Work(){
            public byte[] run()throws IOException{throw new IOException("fixture failure");}public void cancel(){}
        });throw new AssertionError("Failure accepted");}catch(IOException expected){}
        check(cache.count()==0,"Negative result was cached");
        clock.addAndGet(3000000000L);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
        Thread blocked=new Thread(()->{
            try{cache.get(owner,source,dependencies,42000,()->true,position->new ScrubPreviewCache.Work(){
                public byte[] run(){started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return image();}
                public void cancel(){release.countDown();}
            });failure.set(new AssertionError("Cancelled work revived cache"));}
            catch(IllegalStateException expected){}catch(Throwable unexpected){failure.set(unexpected);}
        });
        blocked.start();check(started.await(1,TimeUnit.SECONDS),"Bounded work did not start");
        try{cache.get(new Object(),source,dependencies,0,()->true,factory);throw new AssertionError("Second job admitted");}
        catch(IllegalStateException expected){}
        cache.invalidate(owner);blocked.join(2000);
        check(!blocked.isAlive()&&failure.get()==null&&cache.count()==0,"Stale generation/cancel cleanup failed");
        clock.addAndGet(3000000000L);
        try{cache.get(owner,root,dependencies,0,()->true,factory);throw new AssertionError("Unknown identity accepted");}
        catch(IllegalStateException expected){}
        System.out.println("ScrubPreviewCacheTest PASS: owner/bucket/identity/clone/LRU/bytes/cancel/capacity");
    }
}

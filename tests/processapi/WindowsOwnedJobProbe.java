import java.io.*;
import java.util.concurrent.TimeUnit;
import org.opensagetv.vibe.ffmpeg.OwnedProcessControl;

/** Actual Java8 probe, not a Core/plugin modification. */
public final class WindowsOwnedJobProbe {
    public static void main(String[] args) throws Exception {
        if(!OwnedProcessControl.available())throw new AssertionError("Owned process API unavailable");
        final Process root=OwnedProcessControl.start(new ProcessBuilder(args[0],"-f","mpeg","-i","pipe:0","--duplex-probe"));
        final Process unrelated=new ProcessBuilder(args[1],"--wait-probe").start();
        try{OwnedProcessControl.terminate(unrelated);throw new AssertionError("Unregistered process accepted");}catch(IOException expected){}
        Thread writer=new Thread(new Runnable(){public void run(){
            try {byte[] data=new byte[32768];for(int i=0;i<1024;i++)root.getOutputStream().write(data);}
            catch(IOException expected){}
        }});
        writer.setDaemon(true);writer.start();Thread.sleep(500);
        if(!root.isAlive()||!writer.isAlive())throw new AssertionError("Duplex not actually blocked");
        long started=System.nanoTime();
        Thread stop=new Thread(new Runnable(){public void run(){try{OwnedProcessControl.terminate(root);}catch(IOException e){throw new RuntimeException(e);}}});
        stop.setDaemon(true);stop.start();stop.join(4000);writer.join(2000);
        boolean pass=!stop.isAlive()&&!writer.isAlive()&&root.waitFor(2,TimeUnit.SECONDS)&&unrelated.isAlive();
        System.out.println("{\"java\":\""+System.getProperty("java.version")+"\",\"blockedWriter\":true,\"stopReturned\":"+!stop.isAlive()+",\"writerReleased\":"+!writer.isAlive()+",\"rootExited\":"+!root.isAlive()+",\"unrelatedPreserved\":"+unrelated.isAlive()+",\"elapsedMs\":"+((System.nanoTime()-started)/1000000)+",\"passed\":"+pass+"}");
        unrelated.destroyForcibly();unrelated.waitFor(2,TimeUnit.SECONDS);
        OwnedProcessControl.terminate(root);
        if(!pass)throw new AssertionError("Windows owned-job stop failed");
    }
}

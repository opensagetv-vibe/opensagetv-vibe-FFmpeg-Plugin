package org.opensagetv.vibe.ffmpeg;
import java.io.IOException;
import java.nio.file.*;
/** Policy negatives need no fake Windows OS setting or executable launch. */
public final class WindowsJobProcessStopTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("vibe-windows-job-policy-");
        Path a=root.resolve("root.exe"),b=root.resolve("canonical.exe");
        try {
            check(!WindowsJobProcessStop.launcherMatches(a,b),"missing rejected");
            Files.write(a,new byte[0]);Files.write(b,new byte[0]);
            check(!WindowsJobProcessStop.launcherMatches(a,b),"empty rejected");
            Files.write(a,new byte[]{1,2});Files.write(b,new byte[]{1,3});
            check(!WindowsJobProcessStop.launcherMatches(a,b),"different bytes rejected");
            Files.write(b,new byte[]{1,2});
            check(WindowsJobProcessStop.launcherMatches(a,b),"same bytes accepted");
            Files.delete(b);Files.createDirectory(b);
            check(!WindowsJobProcessStop.launcherMatches(a,b),"directory rejected");
            try{WindowsJobProcessStop.kill(null);throw new AssertionError("null accepted");}catch(IOException expected){}
            check(!WindowsJobProcessStop.available(),"non-Windows does not advertise native job fallback");
            System.out.println("PASS Windows job missing/empty/mismatch/directory/null/non-Windows guards");
        } finally {Files.deleteIfExists(a);Files.deleteIfExists(b);Files.deleteIfExists(root);}
    }
}

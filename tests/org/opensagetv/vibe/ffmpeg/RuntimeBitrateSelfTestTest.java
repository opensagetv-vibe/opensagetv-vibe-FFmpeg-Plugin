package org.opensagetv.vibe.ffmpeg;
public final class RuntimeBitrateSelfTestTest {
    public static void main(String[] args)throws Exception{
        StringBuilder csv=new StringBuilder();
        for(int i=0;i<54*60;i++){
            int bytes=i<18*60?12500:i<36*60?4167:5208;
            csv.append(i/60.0).append(',').append(i/60.0).append(',').append(bytes).append('\n');
        }
        double[] rates=RuntimeBitrateSelfTest.measure(csv.toString());
        if(Math.abs(rates[0]-6000)>1||Math.abs(rates[1]-2000)>1||Math.abs(rates[2]-2500)>1)throw new AssertionError();
        try{RuntimeBitrateSelfTest.measure("0,0,100\n1,1,100\n");throw new AssertionError();}catch(java.io.IOException expected){}
        try{RuntimeBitrateSelfTest.measure("NaN,0,100\n");throw new AssertionError();}catch(java.io.IOException expected){}
        try{RuntimeBitrateSelfTest.measure(csv.toString().replaceFirst("0.016666666666666666,0.016666666666666666", "0.016666666666666666,0.0"));throw new AssertionError();}catch(java.io.IOException expected){}
        RuntimeBitrateSelfTest.requireEmptyArray(new Object[0],"recording");
        for(Object value:new Object[]{null,"unknown",new Object[]{"active"}}){
            try{RuntimeBitrateSelfTest.requireEmptyArray(value,"recording");throw new AssertionError();}catch(IllegalStateException expected){}
        }
        System.out.println("PASS settings diagnostic packet-rate/clock/incomplete/nonfinite measurement guards");
    }
}

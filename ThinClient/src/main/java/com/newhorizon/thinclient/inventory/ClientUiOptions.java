package com.newhorizon.thinclient.inventory;

/** Validated cross-runtime payload. Invalid settings leave all previous values intact. */
public final class ClientUiOptions {
    public float sensitivity=1,volume=1;
    public boolean invertY,doubleSprint=true;
    public boolean apply(String payload){
        try {
            String[] values=payload.split(",",-1);if(values.length!=4)return false;
            int s=Integer.parseInt(values[0]),i=Integer.parseInt(values[1]),d=Integer.parseInt(values[2]),v=Integer.parseInt(values[3]);
            if(s<25||s>200||i<0||i>1||d<0||d>1||v<0||v>100)return false;
            sensitivity=s/100f;invertY=i==1;doubleSprint=d==1;volume=v/100f;return true;
        }catch(RuntimeException invalid){return false;}
    }
}

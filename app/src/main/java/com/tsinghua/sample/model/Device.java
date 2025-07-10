package com.tsinghua.sample.model;

import java.io.Serializable;
import java.util.List;

public class Device implements Serializable {
    public static final int TYPE_FRONT_CAMERA = 0;
    public static final int TYPE_BACK_CAMERA = 1;
    public static final int TYPE_MICROPHONE = 2;
    public static final int TYPE_IMU = 3;
    public static final int TYPE_RING = 4;
    public static final int TYPE_ECG = 5;
    public static final int TYPE_OXIMETER = 6;

    private int type;
    private String name;
    private Boolean isRunning;
    // 在 Device.java 中添加：
    private boolean infoExpanded = false;
    private List<com.vivalnk.sdk.model.Device> ecgSubDevices; // 注意这里是viva sdk的Device，不是你的Device

    public boolean isInfoExpanded() {
        return infoExpanded;
    }

    public void setInfoExpanded(boolean infoExpanded) {
        this.infoExpanded = infoExpanded;
    }

    public Device(int type, String name) {
        this.type = type;
        this.name = name;
        this.isRunning = false;
    }
    public void setRunning(Boolean isRunning){
        this.isRunning = isRunning;
    }
    public int getType() { return type; }
    public String getName() { return name; }
    public Boolean isRunning(){
        return isRunning;
    }
    public List<com.vivalnk.sdk.model.Device> getEcgSubDevices() {
        return ecgSubDevices;
    }

    public void setEcgSubDevices(List<com.vivalnk.sdk.model.Device> ecgSubDevices) {
        this.ecgSubDevices = ecgSubDevices;
    }
}

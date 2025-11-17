package com.tsinghua.openring.utils;

import java.io.Serializable;

/**
 * Single vital signs measurement record
 */
public class VitalSignsRecord implements Serializable {
    private String timestamp;
    private int hr;        // Heart Rate (BPM)
    private int bp_sys;    // Systolic Blood Pressure (mmHg)
    private int bp_dia;    // Diastolic Blood Pressure (mmHg)
    private int spo2;      // Blood Oxygen (%)
    private int rr;        // Respiration Rate (brpm)

    public VitalSignsRecord() {
    }

    public VitalSignsRecord(String timestamp, int hr, int bp_sys, int bp_dia, int spo2, int rr) {
        this.timestamp = timestamp;
        this.hr = hr;
        this.bp_sys = bp_sys;
        this.bp_dia = bp_dia;
        this.spo2 = spo2;
        this.rr = rr;
    }

    // Getters and Setters
    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public int getHr() {
        return hr;
    }

    public void setHr(int hr) {
        this.hr = hr;
    }

    public int getBp_sys() {
        return bp_sys;
    }

    public void setBp_sys(int bp_sys) {
        this.bp_sys = bp_sys;
    }

    public int getBp_dia() {
        return bp_dia;
    }

    public void setBp_dia(int bp_dia) {
        this.bp_dia = bp_dia;
    }

    public int getSpo2() {
        return spo2;
    }

    public void setSpo2(int spo2) {
        this.spo2 = spo2;
    }

    public int getRr() {
        return rr;
    }

    public void setRr(int rr) {
        this.rr = rr;
    }
}

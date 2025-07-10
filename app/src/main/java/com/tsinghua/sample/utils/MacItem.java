package com.tsinghua.sample.utils;

import java.util.Objects;

public class MacItem {
    private String macAddress;
    private String description;
    private boolean selected; // 新增字段

    public MacItem(String macAddress, String description) {
        this.macAddress = macAddress;
        this.description = description;
        this.selected = false; // 默认为未选中
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        MacItem macItem = (MacItem) obj;
        return macAddress.equals(macItem.macAddress);
    }

    @Override
    public int hashCode() {
        return macAddress.hashCode();
    }

    @Override
    public String toString() {
        return macAddress + " - " + description;
    }
}

package com.tsinghua.openring.inference;

/**
 * Model source type enumeration
 * Defines where models can be loaded from
 */
public enum ModelSource {
    BUILT_IN("Built-in", "Models bundled with the app"),
    EXTERNAL_STORAGE("External Storage", "Models from device storage"),
    DOWNLOAD_SERVER("Download Server", "Models downloaded from server");
    
    private final String displayName;
    private final String description;
    
    ModelSource(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
}

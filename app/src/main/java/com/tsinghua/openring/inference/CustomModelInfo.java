package com.tsinghua.openring.inference;

import java.io.File;
import java.io.Serializable;
import java.util.Date;

/**
 * Custom Model Information
 * Stores metadata about user-loaded models
 */
public class CustomModelInfo implements Serializable {
    private String modelName;
    private String modelPath;
    private ModelArchitecture architecture;
    private ModelInferenceManager.Mission mission;
    private ModelSource source;
    private Date loadedDate;
    private long fileSize;
    private String description;
    private String version;
    
    // Model validation info
    private boolean isValidated;
    private String validationMessage;
    private float testAccuracy; // Optional: test accuracy if provided
    
    public CustomModelInfo(String modelName, String modelPath, 
                          ModelArchitecture architecture, 
                          ModelInferenceManager.Mission mission,
                          ModelSource source) {
        this.modelName = modelName;
        this.modelPath = modelPath;
        this.architecture = architecture;
        this.mission = mission;
        this.source = source;
        this.loadedDate = new Date();
        
        // Get file size if path exists
        File file = new File(modelPath);
        if (file.exists()) {
            this.fileSize = file.length();
        }
    }
    
    // Getters and Setters
    public String getModelName() {
        return modelName;
    }
    
    public void setModelName(String modelName) {
        this.modelName = modelName;
    }
    
    public String getModelPath() {
        return modelPath;
    }
    
    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }
    
    public ModelArchitecture getArchitecture() {
        return architecture;
    }
    
    public void setArchitecture(ModelArchitecture architecture) {
        this.architecture = architecture;
    }
    
    public ModelInferenceManager.Mission getMission() {
        return mission;
    }
    
    public void setMission(ModelInferenceManager.Mission mission) {
        this.mission = mission;
    }
    
    public ModelSource getSource() {
        return source;
    }
    
    public void setSource(ModelSource source) {
        this.source = source;
    }
    
    public Date getLoadedDate() {
        return loadedDate;
    }
    
    public void setLoadedDate(Date loadedDate) {
        this.loadedDate = loadedDate;
    }
    
    public long getFileSize() {
        return fileSize;
    }
    
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public boolean isValidated() {
        return isValidated;
    }
    
    public void setValidated(boolean validated) {
        isValidated = validated;
    }
    
    public String getValidationMessage() {
        return validationMessage;
    }
    
    public void setValidationMessage(String validationMessage) {
        this.validationMessage = validationMessage;
    }
    
    public float getTestAccuracy() {
        return testAccuracy;
    }
    
    public void setTestAccuracy(float testAccuracy) {
        this.testAccuracy = testAccuracy;
    }
    
    public String getFileSizeString() {
        if (fileSize < 1024) {
            return fileSize + " B";
        } else if (fileSize < 1024 * 1024) {
            return String.format("%.2f KB", fileSize / 1024.0);
        } else {
            return String.format("%.2f MB", fileSize / (1024.0 * 1024.0));
        }
    }
}

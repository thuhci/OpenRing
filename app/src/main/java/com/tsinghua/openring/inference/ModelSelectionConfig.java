package com.tsinghua.openring.inference;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Model Selection Configuration
 * Manages the model architecture used for each task (HR, BP, SpO2, etc.)
 */
public class ModelSelectionConfig implements Serializable {
    private Map<ModelInferenceManager.Mission, ModelArchitecture> missionArchitectures;
    private Map<ModelInferenceManager.Mission, String> customModelPaths;
    private boolean useCustomModels = false;

    public ModelSelectionConfig() {
        this.missionArchitectures = new HashMap<>();
        this.customModelPaths = new HashMap<>();
        // Set default architectures
        setDefaults();
    }

    /**
     * Set default model architecture configuration
     */
    private void setDefaults() {
        missionArchitectures.put(ModelInferenceManager.Mission.HR, ModelArchitecture.INCEPTION);
        missionArchitectures.put(ModelInferenceManager.Mission.BP_SYS, ModelArchitecture.INCEPTION);
        missionArchitectures.put(ModelInferenceManager.Mission.BP_DIA, ModelArchitecture.INCEPTION);
        missionArchitectures.put(ModelInferenceManager.Mission.SPO2, ModelArchitecture.INCEPTION);
        missionArchitectures.put(ModelInferenceManager.Mission.RR, ModelArchitecture.INCEPTION);
    }

    /**
     * Get the model architecture for the specified task
     */
    public ModelArchitecture getArchitecture(ModelInferenceManager.Mission mission) {
        return missionArchitectures.getOrDefault(mission, ModelArchitecture.INCEPTION);
    }

    /**
     * Set the model architecture for the specified task
     */
    public void setArchitecture(ModelInferenceManager.Mission mission, ModelArchitecture architecture) {
        if (architecture == null) {
            return;
        }
        if (architecture.isClassicAlgorithm()) {
            ModelArchitecture.ClassicAlgorithmType type = architecture.getClassicType();
            boolean supported =
                ((type == ModelArchitecture.ClassicAlgorithmType.HR_PEAK ||
                  type == ModelArchitecture.ClassicAlgorithmType.HR_FFT) && mission == ModelInferenceManager.Mission.HR) ||
                ((type == ModelArchitecture.ClassicAlgorithmType.RR_FFT ||
                  type == ModelArchitecture.ClassicAlgorithmType.RR_PEAK) && mission == ModelInferenceManager.Mission.RR);
            if (!supported) {
                throw new IllegalArgumentException("Architecture " + architecture.getDisplayName() +
                    " is not compatible with mission " + mission);
            }
        }
        missionArchitectures.put(mission, architecture);
    }

    /**
     * Get architecture configuration for all tasks
     */
    public Map<ModelInferenceManager.Mission, ModelArchitecture> getAllArchitectures() {
        return new HashMap<>(missionArchitectures);
    }

    /**
     * Set all tasks to use the same architecture
     */
    public void setAllArchitectures(ModelArchitecture architecture) {
        for (ModelInferenceManager.Mission mission : ModelInferenceManager.Mission.values()) {
            setArchitecture(mission, architecture);
        }
    }

    /**
     * Set custom model path for a specific mission
     */
    public void setCustomModelPath(ModelInferenceManager.Mission mission, String path) {
        customModelPaths.put(mission, path);
    }

    /**
     * Get custom model path for a specific mission
     */
    public String getCustomModelPath(ModelInferenceManager.Mission mission) {
        return customModelPaths.get(mission);
    }

    /**
     * Check if a mission has a custom model
     */
    public boolean hasCustomModel(ModelInferenceManager.Mission mission) {
        return customModelPaths.containsKey(mission) && customModelPaths.get(mission) != null;
    }

    /**
     * Enable or disable custom models
     */
    public void setUseCustomModels(boolean useCustomModels) {
        this.useCustomModels = useCustomModels;
    }

    /**
     * Check if custom models are enabled
     */
    public boolean isUseCustomModels() {
        return useCustomModels;
    }

    /**
     * Clear all custom model paths
     */
    public void clearCustomModels() {
        customModelPaths.clear();
    }
}

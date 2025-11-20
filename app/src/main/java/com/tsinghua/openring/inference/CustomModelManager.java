package com.tsinghua.openring.inference;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.pytorch.Module;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom Model Manager
 * Manages user-loaded models from external storage
 */
public class CustomModelManager {
    private static final String TAG = "CustomModelManager";
    private static final String PREFS_NAME = "CustomModels";
    private static final String KEY_MODEL_LIST = "model_list";
    
    // Default model directory on external storage
    public static final String MODEL_DIR = "OpenRing/Models";
    
    private final Context context;
    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final Map<String, CustomModelInfo> modelRegistry = new HashMap<>();
    
    public interface ModelLoadListener {
        void onModelLoaded(CustomModelInfo modelInfo);
        void onModelLoadFailed(String error);
        void onModelValidated(CustomModelInfo modelInfo, boolean isValid);
    }
    
    public CustomModelManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadModelRegistry();
    }
    
    /**
     * Get the default model directory path
     */
    public File getModelDirectory() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS), MODEL_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
    
    /**
     * Scan for model files in the default directory
     */
    public List<File> scanForModelFiles() {
        List<File> modelFiles = new ArrayList<>();
        File modelDir = getModelDirectory();
        
        if (modelDir.exists() && modelDir.isDirectory()) {
            File[] files = modelDir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    String name = file.getName().toLowerCase();
                    return name.endsWith(".pt") || name.endsWith(".pth") || 
                           name.endsWith(".onnx") || name.endsWith(".tflite");
                }
            });
            
            if (files != null) {
                for (File file : files) {
                    modelFiles.add(file);
                }
            }
        }
        
        return modelFiles;
    }
    
    /**
     * Load a custom model from file
     */
    public void loadCustomModel(File modelFile, ModelArchitecture architecture, 
                               ModelInferenceManager.Mission mission,
                               ModelLoadListener listener) {
        new Thread(() -> {
            try {
                // Create model info
                CustomModelInfo modelInfo = new CustomModelInfo(
                    modelFile.getName(),
                    modelFile.getAbsolutePath(),
                    architecture,
                    mission,
                    ModelSource.EXTERNAL_STORAGE
                );
                
                // Try to load the model
                Module module = Module.load(modelFile.getAbsolutePath());
                
                // If successful, validate the model
                boolean isValid = validateModel(module, mission);
                modelInfo.setValidated(isValid);
                
                if (isValid) {
                    // Register the model
                    registerModel(modelInfo);
                    
                    if (listener != null) {
                        listener.onModelLoaded(modelInfo);
                        listener.onModelValidated(modelInfo, true);
                    }
                } else {
                    modelInfo.setValidationMessage("Model validation failed");
                    if (listener != null) {
                        listener.onModelValidated(modelInfo, false);
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to load custom model: " + modelFile.getName(), e);
                if (listener != null) {
                    listener.onModelLoadFailed("Failed to load model: " + e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * Validate a loaded model for the specified mission
     */
    private boolean validateModel(Module module, ModelInferenceManager.Mission mission) {
        try {
            // Create test input based on mission
            // Default: 30 seconds at 100Hz = 3000 samples
            int testLength = 3000;
            int channels = (mission == ModelInferenceManager.Mission.HR || 
                           mission == ModelInferenceManager.Mission.RR) ? 1 : 2;
            
            float[] testInput = new float[testLength * channels];
            // Fill with dummy data
            for (int i = 0; i < testInput.length; i++) {
                testInput[i] = (float) Math.sin(i * 0.1);
            }
            
            long[] shape = new long[]{1, testLength, channels};
            org.pytorch.Tensor tensor = org.pytorch.Tensor.fromBlob(testInput, shape);
            
            // Try forward pass
            org.pytorch.IValue output = module.forward(org.pytorch.IValue.from(tensor));
            org.pytorch.Tensor outTensor = output.toTensor();
            
            // Check output shape and values
            float[] outData = outTensor.getDataAsFloatArray();
            if (outData != null && outData.length > 0) {
                // Basic validation passed
                return true;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Model validation failed", e);
        }
        
        return false;
    }
    
    /**
     * Register a validated model
     */
    private void registerModel(CustomModelInfo modelInfo) {
        modelRegistry.put(modelInfo.getModelPath(), modelInfo);
        saveModelRegistry();
    }
    
    /**
     * Get all registered custom models
     */
    public List<CustomModelInfo> getRegisteredModels() {
        return new ArrayList<>(modelRegistry.values());
    }
    
    /**
     * Get custom models for a specific mission
     */
    public List<CustomModelInfo> getModelsForMission(ModelInferenceManager.Mission mission) {
        List<CustomModelInfo> models = new ArrayList<>();
        for (CustomModelInfo model : modelRegistry.values()) {
            if (model.getMission() == mission) {
                models.add(model);
            }
        }
        return models;
    }
    
    /**
     * Remove a custom model
     */
    public void removeModel(CustomModelInfo modelInfo) {
        modelRegistry.remove(modelInfo.getModelPath());
        saveModelRegistry();
        
        // Optionally delete the file
        File file = new File(modelInfo.getModelPath());
        if (file.exists() && file.getAbsolutePath().contains(MODEL_DIR)) {
            file.delete();
        }
    }
    
    /**
     * Save model registry to SharedPreferences
     */
    private void saveModelRegistry() {
        List<CustomModelInfo> models = new ArrayList<>(modelRegistry.values());
        String json = gson.toJson(models);
        prefs.edit().putString(KEY_MODEL_LIST, json).apply();
    }
    
    /**
     * Load model registry from SharedPreferences
     */
    private void loadModelRegistry() {
        String json = prefs.getString(KEY_MODEL_LIST, "[]");
        Type type = new TypeToken<List<CustomModelInfo>>(){}.getType();
        List<CustomModelInfo> models = gson.fromJson(json, type);
        
        modelRegistry.clear();
        for (CustomModelInfo model : models) {
            // Verify file still exists
            File file = new File(model.getModelPath());
            if (file.exists()) {
                modelRegistry.put(model.getModelPath(), model);
            }
        }
    }
    
    /**
     * Create example model structure for users
     */
    public void createExampleStructure() {
        File modelDir = getModelDirectory();
        
        // Create subdirectories for organization
        new File(modelDir, "ResNet").mkdirs();
        new File(modelDir, "Transformer").mkdirs();
        new File(modelDir, "Inception").mkdirs();
        
        // Create a README file
        File readme = new File(modelDir, "README.txt");
        try {
            String content = "OpenRing Custom Models Directory\n" +
                "================================\n\n" +
                "Place your custom .pt (PyTorch) model files in this directory.\n\n" +
                "Naming Convention:\n" +
                "- HR models: *_hr_*.pt\n" +
                "- BP models: *_bp_sys_*.pt, *_bp_dia_*.pt\n" +
                "- SpO2 models: *_spo2_*.pt\n" +
                "- RR models: *_rr_*.pt\n\n" +
                "Model Requirements:\n" +
                "- Input: [batch_size, sequence_length, channels]\n" +
                "- Channels: 1 for HR/RR, 2 for BP/SpO2\n" +
                "- Output: Single prediction value\n\n" +
                "Organize models in subdirectories by architecture type.";
            
            java.io.FileWriter writer = new java.io.FileWriter(readme);
            writer.write(content);
            writer.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to create README", e);
        }
    }
}

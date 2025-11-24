package com.tsinghua.openring.inference;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import android.os.Environment;
import com.tsinghua.openring.utils.VitalSignsProcessor;
import com.tsinghua.openring.utils.SignalFilters;

public class ModelInferenceManager {
    public enum Mission { HR, BP_SYS, BP_DIA, SPO2, RR }
    
    // Physiological value ranges
    private static final int HR_MIN = 50;
    private static final int HR_MAX = 140;
    private static final int BP_SYS_MIN = 60;
    private static final int BP_SYS_MAX = 200;
    private static final int BP_DIA_MIN = 40;
    private static final int BP_DIA_MAX = 120;
    private static final int SPO2_MIN = 70;
    private static final int SPO2_MAX = 100;
    private static final int RR_MIN = 6;
    private static final int RR_MAX = 30;
    private static final double RR_MIN_HZ = 0.067; // ~4 bpm
    private static final double RR_MAX_HZ = 0.5;   // ~30 bpm
    private static final double HR_MIN_HZ = 0.5;
    private static final double HR_MAX_HZ = 4.0;

    public interface Listener {
        void onHrPredicted(int bpm);
        void onBpSysPredicted(int mmHg);
        void onBpDiaPredicted(int mmHg);
        void onSpo2Predicted(int percent);
        void onRrPredicted(int brpm);  // 呼吸率 breaths per minute
        default void onDebugLog(String message) {}
    }

    private static final String TAG = "ModelInference";
    private final Context appContext;
    private final Listener listener;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<Mission, List<Module>> missionModules = new HashMap<>();
    private final Map<Mission, JsonNode> missionConfigs = new HashMap<>();
    
    // Model selection configuration
    private ModelSelectionConfig modelSelectionConfig;

    private final int sampleRateHz = 25;
    private int windowSeconds = 30;  // 所有模型统一使用 30 秒窗口
    private static final int RR_WINDOW_SECONDS_OVERRIDE = 30;
    private int windowSecondsRR = RR_WINDOW_SECONDS_OVERRIDE;  // RR 也使用 30 秒窗口
    private int targetFs = 100;

    private final ArrayDeque<Float> greenBuf = new ArrayDeque<>();
    private final ArrayDeque<Float> redBuf = new ArrayDeque<>();
    private final ArrayDeque<Float> irBuf = new ArrayDeque<>();
    // RR 专用缓冲区（需要更长的数据窗口）
    private final ArrayDeque<Float> irBufRR = new ArrayDeque<>();
    
    // 推理间隔控制：每2秒推理一次，避免过于频繁
    private static final long INFERENCE_INTERVAL_MS = 2000; // 2秒 (HR/BP/SpO2)
    private static final long INFERENCE_INTERVAL_MS_RR = 5000; // 5秒 (RR)
    private long lastInferenceTimeMs = 0;
    private long lastInferenceTimeMsRR = 0;
    
    // 最小数据要求：至少需要5秒的数据才开始推理（避免过少数据影响精度）
    private static final int MIN_SECONDS_FOR_INFERENCE = 5;
    private static final int MIN_SECONDS_FOR_RR_INFERENCE = 10;  // 降低到10秒以加快响应
    
    // 平滑滤波器：存储最近N次预测结果
    private static final int SMOOTHING_WINDOW_SIZE = 5; // 使用最近5次结果
    // 深度学习 HR 低值回退阈值（BPM）：当 DL 结果低于该值时尝试使用 Peak 回退
    private static final int DL_HR_FALLBACK_THRESHOLD_BPM = 50;
    private static final int MIN_HR_BPM = 40;
    private static final int MAX_HR_BPM = 200;
    private final ArrayDeque<Integer> hrHistory = new ArrayDeque<>();
    private final ArrayDeque<Integer> bpSysHistory = new ArrayDeque<>();
    private final ArrayDeque<Integer> bpDiaHistory = new ArrayDeque<>();
    private final ArrayDeque<Integer> spo2History = new ArrayDeque<>();
    private final ArrayDeque<Integer> rrHistory = new ArrayDeque<>();
    
    // 文件日志相关
    private static BufferedWriter fileLogWriter = null;
    private static File logFile = null;
    private static final String LOG_DIR = "OpenRingLogs";
    private static final String LOG_FILE_PREFIX = "ModelInference_";
    
    // Signal quality tracking
    private VitalSignsProcessor.SignalQuality currentSignalQuality = VitalSignsProcessor.SignalQuality.NO_SIGNAL;

    public ModelInferenceManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.modelSelectionConfig = new ModelSelectionConfig();
        initializeFileLogging();
    }
    
    /**
     * 初始化文件日志系统
     */
    private void initializeFileLogging() {
        try {
            // 创建日志目录：/storage/emulated/0/Documents/OpenRingLogs/
            File logDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), LOG_DIR);
            if (!logDir.exists()) {
                if (!logDir.mkdirs()) {
                    Log.w(TAG, "Failed to create log directory: " + logDir.getAbsolutePath());
                    return;
                }
            }
            
            // 创建日志文件：ModelInference_YYYYMMDD_HHMMSS.txt
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String fileName = LOG_FILE_PREFIX + sdf.format(new Date()) + ".txt";
            logFile = new File(logDir, fileName);
            
            fileLogWriter = new BufferedWriter(new FileWriter(logFile, true));
            writeToFile("=".repeat(80));
            writeToFile("Model Inference Log Started: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            writeToFile("Log File: " + logFile.getAbsolutePath());
            writeToFile("=".repeat(80));
            writeToFile("");
            
            Log.i(TAG, "File logging initialized: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize file logging", e);
        }
    }
    
    /**
     * 写入日志到文件
     */
    private static void writeToFile(String message) {
        if (fileLogWriter != null) {
            try {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
                fileLogWriter.write("[" + timestamp + "] " + message + "\n");
                fileLogWriter.flush();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write to log file", e);
            }
        }
    }
    
    /**
     * 关闭文件日志
     */
    public static void closeFileLogging() {
        if (fileLogWriter != null) {
            try {
                writeToFile("=".repeat(80));
                writeToFile("Model Inference Log Ended: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
                writeToFile("=".repeat(80));
                fileLogWriter.close();
                fileLogWriter = null;
                if (logFile != null) {
                    Log.i(TAG, "File logging closed. Log saved to: " + logFile.getAbsolutePath());
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to close log file", e);
            }
        }
    }
    
    /**
     * 获取当前日志文件路径（用于显示给用户）
     */
    public static String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : null;
    }
    
    /**
     * Get current model selection configuration
     */
    public ModelSelectionConfig getModelSelectionConfig() {
        return modelSelectionConfig;
    }
    
    /**
     * Update model selection configuration (must be called before init)
     */
    public void setModelSelectionConfig(ModelSelectionConfig config) {
        if (config != null) {
            this.modelSelectionConfig = config;
            logDebug("Model selection config updated");
        }
    }
    
    /**
     * Reload models (called when configuration changes)
     */
    public void reloadModels() {
        logDebug("Reloading models with new configuration");

        // Clear existing models
        missionModules.clear();
        missionConfigs.clear();

        // Re-initialize
        init();
    }

    public void init() {
        logDebug("ModelInferenceManager.init() called");
        logDebug("Listener is " + (listener == null ? "null" : listener.getClass().getName()));
        
        // List all top-level assets
        try {
            String[] rootAssets = appContext.getAssets().list("");
            logDebug("=== Root Assets (total " + (rootAssets == null ? 0 : rootAssets.length) + ") ===");
            if (rootAssets != null) {
                for (String asset : rootAssets) {
                    logDebug("  - " + asset);
                    // For directories starting with "transformer", list subdirs
                    if (asset.startsWith("transformer")) {
                        try {
                            String[] subDirs = appContext.getAssets().list(asset);
                            if (subDirs != null && subDirs.length > 0) {
                                for (String subDir : subDirs) {
                                    logDebug("    - " + asset + "/" + subDir);
                                    // List contents of each mission folder (e.g., hr, BP_sys)
                                    try {
                                        String[] foldDirs = appContext.getAssets().list(asset + "/" + subDir);
                                        if (foldDirs != null && foldDirs.length > 0) {
                                            for (String foldDir : foldDirs) {
                                                logDebug("      - " + asset + "/" + subDir + "/" + foldDir);
                                                // List files in each Fold-X directory
                                                try {
                                                    String[] files = appContext.getAssets().list(asset + "/" + subDir + "/" + foldDir);
                                                    if (files != null && files.length > 0) {
                                                        for (String file : files) {
                                                            logDebug("        * " + file);
                                                        }
                                                    }
                                                } catch (IOException ignored) {}
                                            }
                                        }
                                    } catch (IOException ignored) {}
                                }
                            }
                        } catch (IOException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            logDebug("Error listing root assets: " + e.getMessage());
        }

        // Try load five missions. Assets are mapped to app/models/** by Gradle.
        ModelArchitecture.ClassicAlgorithmType hrClassicMode = getClassicAlgorithm(Mission.HR);
        if (hrClassicMode != ModelArchitecture.ClassicAlgorithmType.NONE) {
            logDebug("Mission HR configured for classic algorithm (" + hrClassicMode + ") - skipping model loading.");
        } else {
            loadMission(Mission.HR, findFirstMissionRoot("hr"));
        }
        loadMission(Mission.BP_SYS, findFirstMissionRoot("BP_sys"));
        loadMission(Mission.BP_DIA, findFirstMissionRoot("BP_dia"));
        loadMission(Mission.SPO2, findFirstMissionRoot("spo2"));
        ModelArchitecture.ClassicAlgorithmType rrClassicMode = getClassicAlgorithm(Mission.RR);
        if (rrClassicMode != ModelArchitecture.ClassicAlgorithmType.NONE) {
            logDebug("Mission RR configured for classic algorithm (" + rrClassicMode + ") - skipping model loading.");
        } else {
            loadMission(Mission.RR, findFirstMissionRoot("rr"));
        }

        inferWindowAndTargetFs();
        logDebug("Initialized. WindowSeconds=" + windowSeconds + ", targetFs=" + targetFs);
    }

    private void inferWindowAndTargetFs() {
        for (Map.Entry<Mission, JsonNode> entry : missionConfigs.entrySet()) {
            Mission mission = entry.getKey();
            JsonNode cfg = entry.getValue();
            if (cfg == null) continue;
            JsonNode dataset = cfg.get("dataset");
            if (dataset == null) continue;

            if (mission == Mission.RR) {
                // 即便配置文件为 30s，这里固定使用 15s，兼顾响应速度与周期覆盖
                windowSecondsRR = RR_WINDOW_SECONDS_OVERRIDE;
            } else {
                if (dataset.has("window_duration")) {
                    try {
                        int w = dataset.get("window_duration").asInt();
                        int normalized = Math.max(5, w);
                        windowSeconds = Math.max(windowSeconds, normalized);
                    } catch (Exception ignored) {}
                }
            }

            if (dataset.has("target_fs")) {
                try {
                    int fs = dataset.get("target_fs").asInt();
                    targetFs = Math.max(sampleRateHz, fs);
                } catch (Exception ignored) {}
            }
        }
        logDebug("Window seconds (all models)=" + windowSeconds + "s, targetFs=" + targetFs + "Hz");
    }

    private String findFirstMissionRoot(String missionKey) {
        // Get Mission enum for this task
        Mission mission = getMissionFromKey(missionKey);
        if (mission == null) {
            logDebug("Unknown mission key: " + missionKey);
            return null;
        }
        
        // Get selected architecture for this task
        ModelArchitecture selectedArch = modelSelectionConfig.getArchitecture(mission);
        logDebug("Mission " + mission + " uses architecture: " + selectedArch);
        if (selectedArch != null && selectedArch.isClassicAlgorithm()) {
            logDebug("Mission " + mission + " uses classic algorithm (" + selectedArch.getDisplayName() + ") - no assets required");
            return null;
        }
        
        // Build possible paths based on architecture and task
        List<String> roots = new ArrayList<>();
        String archPrefix = selectedArch.getPathPrefix();
        
        switch (selectedArch) {
            case TRANSFORMER:
                if ("hr".equals(missionKey)) {
                    roots.add("transformer-ring1-hr-all-ir/hr");
                    roots.add("transformer-ring1-hr-all-irred/hr");
                } else if ("BP_sys".equals(missionKey) || "BP_dia".equals(missionKey)) {
                    roots.add("transformer-ring1-bp-all-irred/" + missionKey);
                } else if ("spo2".equals(missionKey)) {
                    roots.add("transformer-ring1-spo2-all-irred/spo2");
                } else if ("rr".equals(missionKey)) {
                    roots.add("transformer-ring1-rr-all-ir/resp_rr");
                    roots.add("transformer-ring1-rr-all-irred/resp_rr");
                }
                break;
                
            case RESNET:
                if ("hr".equals(missionKey)) {
                    roots.add("resnet-ring1-hr-all-ir/hr");
                    roots.add("resnet-ring1-hr-all-irred/hr");
                } else if ("BP_sys".equals(missionKey) || "BP_dia".equals(missionKey)) {
                    roots.add("resnet-ring1-bp-all-irred/" + missionKey);
                } else if ("spo2".equals(missionKey)) {
                    roots.add("resnet-ring1-spo2-all-irred/spo2");
                } else if ("rr".equals(missionKey)) {
                    roots.add("resnet-ring1-rr-all-ir/resp_rr");
                }
                break;
                
            case INCEPTION:
                if ("hr".equals(missionKey)) {
                    roots.add("inception-ring1-hr-all-ir/hr");
                    roots.add("inception-ring1-hr-all-irred/hr");
                } else if ("BP_sys".equals(missionKey) || "BP_dia".equals(missionKey)) {
                    roots.add("inception-ring1-bp-all-irred/" + missionKey);
                } else if ("spo2".equals(missionKey)) {
                    roots.add("inception-ring1-spo2-all-irred/spo2");
                } else if ("rr".equals(missionKey)) {
                    roots.add("inception-ring1-rr-all-ir/resp_rr");
                }
                break;
        }
        
        logDebug("Searching for mission root: " + missionKey + " with architecture: " + selectedArch);
        for (String r : roots) {
            try {
                logDebug("Trying root path: " + r);
                String[] files = appContext.getAssets().list(r);
                if (files != null && files.length > 0) {
                    logDebug("Mission root candidate success: " + r + " (" + files.length + " entries)");
                    return r;
                } else {
                    logDebug("Mission root candidate empty or not found: " + r);
                }
            } catch (IOException e) {
                logDebug("IOException checking root " + r + ": " + e.getMessage());
            }
        }
        logDebug("Mission root not found for key: " + missionKey + " with architecture: " + selectedArch);
        return null;
    }
    
    private Mission getMissionFromKey(String missionKey) {
        if ("hr".equals(missionKey)) return Mission.HR;
        if ("BP_sys".equals(missionKey)) return Mission.BP_SYS;
        if ("BP_dia".equals(missionKey)) return Mission.BP_DIA;
        if ("spo2".equals(missionKey)) return Mission.SPO2;
        if ("rr".equals(missionKey)) return Mission.RR;
        return null;
    }

    public String reportStatus() {
        logDebug("reportStatus() invoked");
        StringBuilder sb = new StringBuilder();
        for (Mission m : Mission.values()) {
            List<Module> list = missionModules.get(m);
            int n = (list == null) ? 0 : list.size();
            sb.append("[Model] ").append(m.name()).append(": folds=").append(n).append('\n');
        }
        return sb.toString();
    }

    private void loadMission(Mission mission, String missionRoot) {
        logDebug("loadMission called: " + mission + " root=" + missionRoot);
        if (missionRoot == null) {
            Log.w(TAG, "Mission root not found: " + mission);
            logDebug("Mission root not found for " + mission);
            return;
        }
        try {
            String[] subDirs = appContext.getAssets().list(missionRoot);
            if (subDirs == null) {
                logDebug("Mission root has no subdirs: " + missionRoot);
                logDebug("Mission root has no subdirs: " + missionRoot);
                return;
            }

            // Each Fold-X contains a json and a pt file
            List<Module> modules = new ArrayList<>();
            logDebug("Found " + subDirs.length + " subdirs in " + missionRoot);
            for (String sub : subDirs) {
                String foldDir = missionRoot + "/" + sub;
                logDebug("Checking fold directory: " + foldDir);
                String[] foldFiles = appContext.getAssets().list(foldDir);
                if (foldFiles == null || foldFiles.length == 0) {
                    logDebug("Fold directory empty or not found: " + foldDir);
                    continue;
                }
                
                logDebug("Fold " + sub + " contains " + foldFiles.length + " files:");
                for (String f : foldFiles) {
                    logDebug("  - " + f);
                }

                String jsonPath = null;
                String ptPath = null;
                // Prefer TorchScript files with _ts suffix if available
                for (String f : foldFiles) {
                    if (f.endsWith(".json")) {
                        jsonPath = foldDir + "/" + f;
                        logDebug("Found JSON: " + jsonPath);
                    }
                    if (f.endsWith("_ts.pt")) {
                        ptPath = foldDir + "/" + f;
                        logDebug("Found PT (_ts): " + ptPath);
                    }
                }
                if (ptPath == null) {
                    for (String f : foldFiles) {
                        if (f.endsWith(".pt")) {
                            ptPath = foldDir + "/" + f;
                            logDebug("Found PT: " + ptPath);
                            break;
                        }
                    }
                }
                
                if (jsonPath == null) {
                    logDebug("WARNING: No JSON file found in " + foldDir);
                }
                if (ptPath == null) {
                    logDebug("WARNING: No PT file found in " + foldDir);
                }
                
                if (jsonPath != null && ptPath != null) {
                    logDebug("Both JSON and PT files found, attempting to load...");
                    // Load config (only once per mission, prefer first)
                    if (!missionConfigs.containsKey(mission)) {
                        logDebug("Loading config from: " + jsonPath);
                        try (InputStream is = appContext.getAssets().open(jsonPath)) {
                            missionConfigs.put(mission, mapper.readTree(is));
                            logDebug("Config loaded successfully for " + mission);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed reading config: " + jsonPath, e);
                            logDebug("Failed reading config: " + jsonPath + " - " + e.getMessage());
                            logDebug("Exception type: " + e.getClass().getName());
                            if (e.getCause() != null) {
                                logDebug("Cause: " + e.getCause().getMessage());
                            }
                        }
                    } else {
                        logDebug("Config already loaded for " + mission + ", skipping");
                    }
                    
                    logDebug("Loading model from: " + ptPath);
                    try {
                        String localPath = AssetsUtils.assetFilePath(appContext, ptPath);
                        logDebug("Model file copied to: " + localPath);
                        Module m = Module.load(localPath);
                        modules.add(m);
                        Log.i(TAG, "Loaded module: " + ptPath);
                        logDebug("Model loaded successfully: " + ptPath);
                    } catch (Throwable t) {
                        Log.w(TAG, "Skip module (load failed): " + ptPath, t);
                        logDebug("Model load failed for " + ptPath);
                        logDebug("Error message: " + t.getMessage());
                        logDebug("Error type: " + t.getClass().getName());
                        if (t.getCause() != null) {
                            logDebug("Cause: " + t.getCause().getMessage());
                        }
                        // 打印完整的堆栈跟踪（仅前几行）
                        StackTraceElement[] stack = t.getStackTrace();
                        for (int i = 0; i < Math.min(5, stack.length); i++) {
                            logDebug("  at " + stack[i].toString());
                        }
                    }
                } else {
                    logDebug("Skipping fold " + sub + " - missing files (jsonPath=" + (jsonPath != null) + ", ptPath=" + (ptPath != null) + ")");
                }
            }
            if (!modules.isEmpty()) {
                missionModules.put(mission, modules);
                Log.i(TAG, "Mission " + mission + " folds loaded: " + modules.size());
                logDebug("Mission " + mission + " folds loaded: " + modules.size());
            } else {
                logDebug("Mission " + mission + " loaded 0 modules");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading mission: " + mission, e);
            logDebug("Error loading mission " + mission + ": " + e.getMessage());
        }
    }
    
    /**
     * Update the current signal quality
     * This should be called whenever signal quality changes
     */
    public void updateSignalQuality(VitalSignsProcessor.SignalQuality quality) {
        VitalSignsProcessor.SignalQuality previousQuality = this.currentSignalQuality;
        this.currentSignalQuality = quality;
        if (quality == VitalSignsProcessor.SignalQuality.NO_SIGNAL &&
            previousQuality != VitalSignsProcessor.SignalQuality.NO_SIGNAL) {
            logDebug("Signal quality dropped to NO_SIGNAL, clearing buffers");
            reset();
        }
        if (quality == VitalSignsProcessor.SignalQuality.NO_SIGNAL || 
            quality == VitalSignsProcessor.SignalQuality.POOR) {
            logDebug("Signal quality is " + quality + ", data buffering will be paused");
        }
    }

    public synchronized void onSensorData(long green, long red, long ir, short accX, short accY, short accZ, long timestampMs) {
        // Only buffer data if signal quality is acceptable
        if (currentSignalQuality == VitalSignsProcessor.SignalQuality.GOOD ||
            currentSignalQuality == VitalSignsProcessor.SignalQuality.EXCELLENT ||
            currentSignalQuality == VitalSignsProcessor.SignalQuality.FAIR) {
            
            // Buffer for HR/BP/SpO2 (30 seconds)
        greenBuf.addLast((float) green);
        redBuf.addLast((float) red);
        irBuf.addLast((float) ir);
            int maxSize = windowSeconds * sampleRateHz;
        while (greenBuf.size() > maxSize) greenBuf.removeFirst();
        while (redBuf.size() > maxSize) redBuf.removeFirst();
        while (irBuf.size() > maxSize) irBuf.removeFirst();

            // Buffer for RR (30 seconds)
            irBufRR.addLast((float) ir);
            int maxSizeRR = windowSecondsRR * sampleRateHz;
            while (irBufRR.size() > maxSizeRR) irBufRR.removeFirst();

            // 调试：每100个样本记录一次缓冲区状态
            if (greenBuf.size() % 100 == 0 && greenBuf.size() > 0) {
                logDebug("Buffer: HR/BP/SpO2=" + greenBuf.size() + "/" + maxSize + 
                        ", RR=" + irBufRR.size() + "/" + maxSizeRR + " samples" +
                        " (RR needs " + (MIN_SECONDS_FOR_RR_INFERENCE * sampleRateHz) + " samples to start)");
            }

            // HR/BP/SpO2 推理：至少需要5秒数据，每2秒推理一次
            int minSize = MIN_SECONDS_FOR_INFERENCE * sampleRateHz;  // 5秒 × 25Hz = 125个样本
            if (greenBuf.size() >= minSize && irBuf.size() >= minSize && redBuf.size() >= minSize) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastInferenceTimeMs >= INFERENCE_INTERVAL_MS) {
                    lastInferenceTimeMs = currentTime;
                    int actualSeconds = Math.min(Math.min(greenBuf.size(), redBuf.size()), irBuf.size()) / sampleRateHz;
                    logDebug("HR/BP/SpO2 inference with " + actualSeconds + "s data (target: " + windowSeconds + "s)");
                    runHrBpSpo2Missions();
                }
            }
            
            // RR 推理：至少需要15秒数据，每5秒推理一次
            int minSizeRR = MIN_SECONDS_FOR_RR_INFERENCE * sampleRateHz;  // 15秒 × 25Hz = 375个样本
            if (irBufRR.size() >= minSizeRR) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastInferenceTimeMsRR >= INFERENCE_INTERVAL_MS_RR) {
                    lastInferenceTimeMsRR = currentTime;
                    int actualSeconds = irBufRR.size() / sampleRateHz;
                    logDebug("RR inference with " + actualSeconds + "s data (target: " + windowSecondsRR + "s)");
                    runRRMission();
                }
            }
        } else {
            // Poor signal - skip buffering entirely
            logDebug("Skipping data buffering due to poor signal quality: " + 
                    currentSignalQuality);
        }
    }

    /**
     * 重置所有缓冲区、历史记录和定时器，通常在开始/停止测量时调用，防止残留数据影响下一次推理。
     */
    public synchronized void reset() {
        greenBuf.clear();
        redBuf.clear();
        irBuf.clear();
        irBufRR.clear();

        hrHistory.clear();
        bpSysHistory.clear();
        bpDiaHistory.clear();
        spo2History.clear();
        rrHistory.clear();

        lastInferenceTimeMs = 0;
        lastInferenceTimeMsRR = 0;

        logDebug("Buffers, histories, and timers reset.");
    }

    private void runHrBpSpo2Missions() {
        long startTime = System.currentTimeMillis();

        int availableSamples = Math.min(Math.min(greenBuf.size(), redBuf.size()), irBuf.size());
        int requiredSamples = windowSeconds * sampleRateHz;
        boolean hasFullHrWindow = availableSamples >= requiredSamples;

        // Prepare input tensor [1, T, C] with C=2 (red, ir)
        // Model expects (batch, length, channels) format as per training
        int targetLength = windowSeconds * targetFs;
        if (targetLength <= 0) {
            logDebug("Target length invalid: " + targetLength);
            return;
        }

        float[] resampledRed = resampleBufferWithPadding(redBuf, targetLength);
        float[] resampledIr = resampleBufferWithPadding(irBuf, targetLength);
        if (resampledRed == null || resampledIr == null) {
            logDebug("Resample failed due to insufficient data");
            return;
        }
        
        // Apply bandpass filter (0.5-3 Hz) to match training preprocessing
        logDebug("Applying physiological signal bandpass filter");
        float[] filteredRed = SignalFilters.PhysiologicalSignalFilter.filter(resampledRed);
        float[] filteredIr = SignalFilters.PhysiologicalSignalFilter.filter(resampledIr);

        ModelArchitecture.ClassicAlgorithmType hrClassicMode = getClassicAlgorithm(Mission.HR);
        if (hrClassicMode == ModelArchitecture.ClassicAlgorithmType.HR_PEAK ||
            hrClassicMode == ModelArchitecture.ClassicAlgorithmType.HR_FFT) {
            emitClassicHrResult(filteredIr, targetFs, hrClassicMode);
        }

        // Interleave channels: [t0_green, t0_ir, t1_green, t1_ir, ...]
        // to match (batch=1, length=T, channels=2) layout
        float[] input = new float[targetLength * 2];
        for (int i = 0; i < targetLength; i++) {
            input[i * 2] = filteredIr[i];        // channel 0 (IR)
            input[i * 2 + 1] = filteredRed[i];   // channel 1 (Red)
        }

        // normalize per-channel (zero mean, unit var) for numerical stability
        normalizeInPlace(input, targetLength);

        long[] shape = new long[]{1, targetLength, 2};
        Tensor tensor = Tensor.fromBlob(input, shape);
        
        long prepTime = System.currentTimeMillis() - startTime;
        logDebug("Inference started: targetLength=" + targetLength + ", prep=" + prepTime + "ms");

        // HR - Check for classic algorithm first, then deep learning model
        long hrStart = System.currentTimeMillis();
        ModelArchitecture.ClassicAlgorithmType hrClassicType = getClassicAlgorithm(Mission.HR);
        boolean useClassicHr = hrClassicType != ModelArchitecture.ClassicAlgorithmType.NONE;
        boolean classicHrFallback = false;
        if (!useClassicHr && !hasFullHrWindow) {
            hrClassicType = ModelArchitecture.ClassicAlgorithmType.HR_PEAK;
            useClassicHr = true;
            classicHrFallback = true;
            logDebug("HR fallback: insufficient data for DL, using classic PEAK temporarily");
        }
        if (useClassicHr) {
            // Use classic algorithm (FFT or Peak) for HR estimation
            logDebug("Using classic algorithm for HR: " + hrClassicType +
                    (classicHrFallback ? " (fallback)" : ""));

            // Call classic algorithm using the same filtered 100Hz IR as DL models
            float hrValue = Float.NaN;
            try {
                if (hrClassicType == ModelArchitecture.ClassicAlgorithmType.HR_FFT) {
                    hrValue = ClassicAlgorithmProcessor.estimateHrByFFT(filteredIr, targetFs);
                    logDebug("Classic HR FFT raw estimation: " + hrValue + " bpm");
                } else if (hrClassicType == ModelArchitecture.ClassicAlgorithmType.HR_PEAK) {
                    hrValue = ClassicAlgorithmProcessor.estimateHrByPeak(filteredIr, targetFs);
                    logDebug("Classic HR Peak raw estimation: " + hrValue + " bpm");
                }
            } catch (Exception e) {
                Log.e(TAG, "Classic HR algorithm failed", e);
                logDebug("Classic HR algorithm error: " + e.getMessage());
            }

            // Return result with smoothing
            long hrTime = System.currentTimeMillis() - hrStart;
            if (listener != null && !Float.isNaN(hrValue) && hrValue > 0) {
                int rawValue = Math.round(hrValue);
                rawValue = Math.max(MIN_HR_BPM, Math.min(MAX_HR_BPM, rawValue));
                int smoothedValue = smoothValue(hrHistory, rawValue);
                listener.onHrPredicted(smoothedValue);
                logDebug("HR=" + smoothedValue + " bpm (raw=" + rawValue + ", classic, " + hrTime + "ms)");
            } else {
                logDebug("Classic HR estimation failed or invalid (" + hrTime + "ms)");
            }
        } else if (hasMission(Mission.HR)) {
            // Use deep learning model for HR estimation
            // Extract IR channel only for HR model: [1, T, 1]
            float[] hrInput = new float[targetLength];
            for (int i = 0; i < targetLength; i++) {
                hrInput[i] = input[i * 2];  // Extract IR channel (index 0)
            }
            // Normalize single channel
            double mean = 0;
            for (int i = 0; i < targetLength; i++) {
                mean += hrInput[i];
            }
            mean /= targetLength;
            double var = 0;
            for (int i = 0; i < targetLength; i++) {
                double v = hrInput[i] - mean;
                var += v * v;
            }
            double std = Math.sqrt(var / Math.max(1, targetLength - 1));
            if (std < 1e-6) std = 1.0;
            for (int i = 0; i < targetLength; i++) {
                hrInput[i] = (float) ((hrInput[i] - mean) / std);
            }
            
            long[] hrShape = new long[]{1, targetLength, 1};
            Tensor hrTensor = Tensor.fromBlob(hrInput, hrShape);
            float pred = averagePrediction(missionModules.get(Mission.HR), hrTensor);
            long hrTime = System.currentTimeMillis() - hrStart;
            if (listener != null && !Float.isNaN(pred) && pred > 0) {
                int dlRawValue = Math.round(pred);
                int finalRawValue = dlRawValue;

                // 当深度学习 HR 结果偏低时，尝试使用 Peak 回退，以提升低值区间的稳定性
                if (dlRawValue < DL_HR_FALLBACK_THRESHOLD_BPM) {
                    float peakHr = ClassicAlgorithmProcessor.estimateHrByPeak(filteredIr, targetFs);
                    if (!Float.isNaN(peakHr) && peakHr > 0) {
                        int peakRaw = Math.round(peakHr);
                        peakRaw = Math.max(MIN_HR_BPM, Math.min(MAX_HR_BPM, peakRaw));
                        finalRawValue = peakRaw;
                        logDebug("HR DL fallback to Peak: dl_raw=" + dlRawValue + " bpm, peak_raw=" + peakRaw + " bpm");
                    } else {
                        logDebug("HR DL value " + dlRawValue + " bpm < " + DL_HR_FALLBACK_THRESHOLD_BPM +
                                " bpm, but Peak fallback invalid (NaN or <=0) - keep DL value");
                    }
                }

                finalRawValue = Math.max(MIN_HR_BPM, Math.min(MAX_HR_BPM, finalRawValue));
                int smoothedValue = smoothValue(hrHistory, finalRawValue);
                listener.onHrPredicted(smoothedValue);
                logDebug("HR=" + smoothedValue + " bpm (raw=" + finalRawValue + ", dl_raw=" + dlRawValue +
                        ", " + hrTime + "ms)");
            }
            if (Float.isNaN(pred)) logDebug("HR prediction NaN (" + hrTime + "ms)");
        } 
        
        // BP SYS
        long bpSysStart = System.currentTimeMillis();
        if (hasMission(Mission.BP_SYS)) {
            float pred = averagePrediction(missionModules.get(Mission.BP_SYS), tensor);
            long bpSysTime = System.currentTimeMillis() - bpSysStart;
            if (listener != null && !Float.isNaN(pred) && pred > 0) {
                int rawValue = Math.round(pred);
                // Check physiological range
                if (rawValue >= BP_SYS_MIN && rawValue <= BP_SYS_MAX) {
                    int smoothedValue = smoothValue(bpSysHistory, rawValue);
                    listener.onBpSysPredicted(smoothedValue);
                    logDebug("BP_SYS=" + smoothedValue + " mmHg (raw=" + rawValue + ", " + bpSysTime + "ms)");
                } else {
                    logDebug("BP_SYS out of range: " + rawValue + " mmHg (valid: " + BP_SYS_MIN + "-" + BP_SYS_MAX + ")");
                }
            }
            if (Float.isNaN(pred)) logDebug("BP_SYS prediction NaN (" + bpSysTime + "ms)");
        }
        
        // BP DIA
        long bpDiaStart = System.currentTimeMillis();
        if (hasMission(Mission.BP_DIA)) {
            float pred = averagePrediction(missionModules.get(Mission.BP_DIA), tensor);
            long bpDiaTime = System.currentTimeMillis() - bpDiaStart;
            if (listener != null && !Float.isNaN(pred) && pred > 0) {
                int rawValue = Math.round(pred);
                // Check physiological range
                if (rawValue >= BP_DIA_MIN && rawValue <= BP_DIA_MAX) {
                    int smoothedValue = smoothValue(bpDiaHistory, rawValue);
                    listener.onBpDiaPredicted(smoothedValue);
                    logDebug("BP_DIA=" + smoothedValue + " mmHg (raw=" + rawValue + ", " + bpDiaTime + "ms)");
                } else {
                    logDebug("BP_DIA out of range: " + rawValue + " mmHg (valid: " + BP_DIA_MIN + "-" + BP_DIA_MAX + ")");
                }
            }
            if (Float.isNaN(pred)) logDebug("BP_DIA prediction NaN (" + bpDiaTime + "ms)");
        }
        
        // SpO2
        long spo2Start = System.currentTimeMillis();
        if (hasMission(Mission.SPO2)) {
            float pred = averagePrediction(missionModules.get(Mission.SPO2), tensor);
            long spo2Time = System.currentTimeMillis() - spo2Start;
            if (listener != null && !Float.isNaN(pred) && pred > 0) {
                int rawValue = Math.round(pred);
                // Check physiological range
                if (rawValue >= SPO2_MIN && rawValue <= SPO2_MAX) {
                    int smoothedValue = smoothValue(spo2History, rawValue);
                    listener.onSpo2Predicted(smoothedValue);
                    logDebug("SPO2=" + smoothedValue + "% (raw=" + rawValue + ", " + spo2Time + "ms)");
                } else {
                    logDebug("SPO2 out of range: " + rawValue + "% (valid: " + SPO2_MIN + "-" + SPO2_MAX + ")");
                }
            }
            if (Float.isNaN(pred)) logDebug("SpO2 prediction NaN (" + spo2Time + "ms)");
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        logDebug("HR/BP/SpO2 total inference time: " + totalTime + "ms");
    }
    
    private void runRRMission() {
        long startTime = System.currentTimeMillis();
        
        // Prepare input tensor for RR: [1, T, 1] with 30-second window
        int targetLength = windowSecondsRR * targetFs;  // 30秒 × 100Hz = 3000 samples
        if (targetLength <= 0) {
            logDebug("RR target length invalid: " + targetLength);
            return;
        }

        int requiredSamples = windowSecondsRR * sampleRateHz;
        boolean hasFullRrWindow = irBufRR.size() >= requiredSamples;

        float[] resampledIr = resampleBufferWithPadding(irBufRR, targetLength);
        if (resampledIr == null) {
            logDebug("RR resample failed due to insufficient data");
            return;
        }
        
        // Apply respiratory rate specific filtering (0.067-0.5 Hz bandpass)
        // This matches the "ir-filtered-rr" preprocessing used in training
        logDebug("Applying respiratory rate bandpass filter");
        float[] filteredIr = SignalFilters.RespiratoryRateFilter.filter(resampledIr);

        ModelArchitecture.ClassicAlgorithmType rrClassicMode = getClassicAlgorithm(Mission.RR);
        if (rrClassicMode == ModelArchitecture.ClassicAlgorithmType.RR_FFT ||
            rrClassicMode == ModelArchitecture.ClassicAlgorithmType.RR_PEAK) {
            emitClassicRrResult(filteredIr, targetFs, rrClassicMode);
            long totalTime = System.currentTimeMillis() - startTime;
            logDebug("RR classic " + rrClassicMode + " total time: " + totalTime + "ms");
            return;
        }

        // Normalize single channel (filtered IR)
        double mean = 0;
        for (int i = 0; i < targetLength; i++) {
            mean += filteredIr[i];
        }
        mean /= targetLength;
        double var = 0;
        for (int i = 0; i < targetLength; i++) {
            double v = filteredIr[i] - mean;
            var += v * v;
        }
        double std = Math.sqrt(var / Math.max(1, targetLength - 1));
        if (std < 1e-6) std = 1.0;
        for (int i = 0; i < targetLength; i++) {
            filteredIr[i] = (float) ((filteredIr[i] - mean) / std);
        }
        
        long prepTime = System.currentTimeMillis() - startTime;
        logDebug("RR inference started: targetLength=" + targetLength + ", prep=" + prepTime + "ms");

        // RR inference - Check for classic algorithm first, then deep learning model
        long rrStart = System.currentTimeMillis();
        ModelArchitecture.ClassicAlgorithmType rrClassicType = getClassicAlgorithm(Mission.RR);
        boolean useClassicRr = rrClassicType != ModelArchitecture.ClassicAlgorithmType.NONE;
        boolean classicRrFallback = false;
        if (!useClassicRr && !hasFullRrWindow) {
            rrClassicType = ModelArchitecture.ClassicAlgorithmType.RR_PEAK;
            useClassicRr = true;
            classicRrFallback = true;
            logDebug("RR fallback: insufficient data for DL, using classic PEAK temporarily");
        }
        if (useClassicRr) {
            // Use classic algorithm (FFT or Peak) for RR estimation
            logDebug("Using classic algorithm for RR: " + rrClassicType +
                    (classicRrFallback ? " (fallback)" : ""));

            // filteredIr is already prepared and filtered with respiratory rate filter
            // Call classic algorithm
            float rrValue = Float.NaN;
            try {
                if (rrClassicType == ModelArchitecture.ClassicAlgorithmType.RR_FFT) {
                    rrValue = ClassicAlgorithmProcessor.estimateRrByFFT(filteredIr, targetFs);
                    logDebug("Classic RR FFT raw estimation: " + rrValue + " brpm");
                } else if (rrClassicType == ModelArchitecture.ClassicAlgorithmType.RR_PEAK) {
                    rrValue = ClassicAlgorithmProcessor.estimateRrByPeak(filteredIr, targetFs);
                    logDebug("Classic RR Peak raw estimation: " + rrValue + " brpm");
                }
            } catch (Exception e) {
                Log.e(TAG, "Classic RR algorithm failed", e);
                logDebug("Classic RR algorithm error: " + e.getMessage());
            }

            // Return result with smoothing and clamping
            long rrTime = System.currentTimeMillis() - rrStart;
            if (listener != null && !Float.isNaN(rrValue) && rrValue > 0) {
                int rawValue = Math.max(8, Math.min(30, Math.round(rrValue)));  // 正常呼吸率范围 8-30 brpm
                int smoothedValue = smoothValue(rrHistory, rawValue);
                listener.onRrPredicted(smoothedValue);
                logDebug("RR=" + smoothedValue + " brpm (raw=" + rawValue + ", classic, " + rrTime + "ms)");
            } else {
                logDebug("Classic RR estimation failed or invalid (" + rrTime + "ms)");
            }
        } else if (hasMission(Mission.RR)) {
            // Use deep learning model for RR estimation
            float pred = Float.NaN;
            try {
                Tensor rrTensor = Tensor.fromBlob(filteredIr, new long[]{1, targetLength, 1}); // (B, T, C) with filtered data
                pred = averagePrediction(missionModules.get(Mission.RR), rrTensor);
            } catch (Throwable e) {
                Log.e(TAG, "RR inference failed", e);
                logDebug("RR inference failed: " + e.getMessage());
            }

            long rrTime = System.currentTimeMillis() - rrStart;
            if (listener != null && !Float.isNaN(pred) && pred > 0) {
                // Direct use of model output
                int rawValue = Math.round(pred);
                // Check physiological range
                if (rawValue >= RR_MIN && rawValue <= RR_MAX) {
                    int smoothedValue = smoothValue(rrHistory, rawValue);
                    listener.onRrPredicted(smoothedValue);
                    logDebug("RR=" + smoothedValue + " brpm (raw=" + rawValue + ", " + rrTime + "ms)");
                } else {
                    logDebug("RR out of range: " + rawValue + " brpm (valid: " + RR_MIN + "-" + RR_MAX + ")");
                }
            } else {
                logDebug("RR prediction invalid (" + rrTime + "ms)");
            }
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        logDebug("RR total inference time: " + totalTime + "ms");
    }

    private boolean hasMission(Mission m) {
        List<Module> list = missionModules.get(m);
        return list != null && !list.isEmpty();
    }

    private ModelArchitecture.ClassicAlgorithmType getClassicAlgorithm(Mission mission) {
        if (modelSelectionConfig == null) {
            return ModelArchitecture.ClassicAlgorithmType.NONE;
        }
        ModelArchitecture arch = modelSelectionConfig.getArchitecture(mission);
        if (arch != null && arch.isClassicAlgorithm()) {
            return arch.getClassicType();
        }
        return ModelArchitecture.ClassicAlgorithmType.NONE;
    }

    private float averagePrediction(List<Module> modules, Tensor input) {
        if (modules == null || modules.isEmpty()) return Float.NaN;
        float sum = 0f;
        int n = 0;
        for (Module m : modules) {
            try {
                IValue out = m.forward(IValue.from(input));

                // Handle both single tensor and tuple outputs
                Tensor t;
                if (out.isTuple()) {
                    // For tuple output, use the first element (prediction)
                    IValue[] elements = out.toTuple();
                    if (elements.length > 0 && elements[0].isTensor()) {
                        t = elements[0].toTensor();
                    } else {
                        logDebug("Tuple output but first element is not a tensor");
                        continue;
                    }
                } else if (out.isTensor()) {
                    t = out.toTensor();
                } else {
                    logDebug("Output is neither tensor nor tuple");
                    continue;
                }

                float[] arr = t.getDataAsFloatArray();
                if (arr.length > 0) {
                    sum += arr[arr.length - 1]; // support either scalar or last-step output
                    n++;
                }
                if (arr.length == 0) logDebug("Empty output tensor from module");
            } catch (Throwable e) {
                Log.w(TAG, "Forward failed on one fold", e);
                 logDebug("Forward failed: " + e.getMessage());
            }
        }
        return n > 0 ? sum / n : Float.NaN;
    }

    private void emitClassicHrResult(float[] irSignal, int sampleRate, ModelArchitecture.ClassicAlgorithmType mode) {
        Integer hrValue = null;
        if (mode == ModelArchitecture.ClassicAlgorithmType.HR_PEAK) {
            hrValue = computeClassicHrPeak(irSignal, sampleRate);
        } else if (mode == ModelArchitecture.ClassicAlgorithmType.HR_FFT) {
            hrValue = computeClassicHrFft(irSignal, sampleRate);
        }
        if (hrValue == null) {
            logDebug("Classic HR (" + mode + ") failed to generate a value");
            return;
        }
        int output = smoothValue(hrHistory, hrValue);
        if (listener != null) {
            listener.onHrPredicted(output);
        }
        logDebug("HR (classic " + mode + ")=" + output + " bpm (raw=" + hrValue + ")");
    }

    private void emitClassicRrResult(float[] rrSignal, int sampleRate, ModelArchitecture.ClassicAlgorithmType mode) {
        Integer rrValue = null;
        if (mode == ModelArchitecture.ClassicAlgorithmType.RR_FFT) {
            rrValue = computeClassicRrFft(rrSignal, sampleRate);
        } else if (mode == ModelArchitecture.ClassicAlgorithmType.RR_PEAK) {
            rrValue = computeClassicRrPeak(rrSignal, sampleRate);
        }
        if (rrValue == null) {
            logDebug("Classic RR (" + mode + ") failed to generate a value");
            return;
        }
        int output = smoothValue(rrHistory, rrValue);
        if (listener != null) {
            listener.onRrPredicted(output);
        }
        logDebug("RR (classic " + mode + ")=" + output + " brpm (raw=" + rrValue + ")");
    }

    private Integer computeClassicHrPeak(float[] signal, int sampleRate) {
        if (signal == null || sampleRate <= 0 || signal.length < sampleRate) {
            return null;
        }
        int n = signal.length;
        double mean = 0.0;
        for (float v : signal) {
            mean += v;
        }
        mean /= n;
        int peakCount = 0;
        for (int i = 1; i < n - 1; i++) {
            double prev = signal[i - 1] - mean;
            double curr = signal[i] - mean;
            double next = signal[i + 1] - mean;
            if (curr > prev && curr > next) {
                peakCount++;
            }
        }
        if (peakCount == 0) {
            return null;
        }
        double durationSec = n / (double) sampleRate;
        double bpm = (peakCount / Math.max(durationSec, 1e-6)) * 60.0;
        if (!Double.isFinite(bpm)) {
            return null;
        }
        int rounded = (int) Math.round(bpm);
        if (rounded < HR_MIN || rounded > HR_MAX) {
            return null;
        }
        return rounded;
    }

    private Integer computeClassicHrFft(float[] signal, int sampleRate) {
        if (signal == null || sampleRate <= 0 || signal.length < sampleRate) {
            return null;
        }
        int n = signal.length;
        double mean = 0.0;
        for (float v : signal) {
            mean += v;
        }
        mean /= n;
        double bestMagnitude = 0.0;
        double bestFreq = 0.0;
        for (int k = 1; k <= n / 2; k++) {
            double real = 0.0;
            double imag = 0.0;
            double angleFactor = -2.0 * Math.PI * k / n;
            for (int i = 0; i < n; i++) {
                double sample = signal[i] - mean;
                double angle = angleFactor * i;
                real += sample * Math.cos(angle);
                imag += sample * Math.sin(angle);
            }
            double magnitude = Math.hypot(real, imag);
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude;
                bestFreq = (double) k * sampleRate / n;
            }
        }
        if (bestFreq <= 0) {
            return null;
        }
        double hrBpm = bestFreq * 60.0;
        if (!Double.isFinite(hrBpm)) {
            return null;
        }
        int rounded = (int) Math.round(hrBpm);
        if (rounded < HR_MIN || rounded > HR_MAX) {
            return null;
        }
        return rounded;
    }

    private Integer computeClassicRrFft(float[] signal, int sampleRate) {
        float rr = ClassicAlgorithmProcessor.estimateRrByFFT(signal, sampleRate);
        if (Float.isNaN(rr) || rr <= 0) {
            return null;
        }
        int rounded = Math.round(rr);
        if (rounded < RR_MIN || rounded > RR_MAX) {
            return null;
        }
        return rounded;
    }

    private Integer computeClassicRrPeak(float[] signal, int sampleRate) {
        float rr = ClassicAlgorithmProcessor.estimateRrByPeak(signal, sampleRate);
        if (Float.isNaN(rr) || rr <= 0) {
            return null;
        }
        int rounded = Math.round(rr);
        if (rounded < RR_MIN || rounded > RR_MAX) {
            return null;
        }
        return rounded;
    }

    private float[] resampleBuffer(ArrayDeque<Float> buffer, int targetLength) {
        int srcSize = buffer.size();
        if (srcSize < 2 || targetLength < 2) {
            return null;
        }
        float[] src = new float[srcSize];
        int idx = 0;
        for (Float v : buffer) {
            src[idx++] = v;
        }

        float[] out = new float[targetLength];
        float ratio = (float) sampleRateHz / targetFs;
        for (int i = 0; i < targetLength; i++) {
            float srcPos = i * ratio;
            int idx0 = (int) Math.floor(srcPos);
            int idx1 = Math.min(srcSize - 1, idx0 + 1);
            float frac = srcPos - idx0;
            if (idx0 >= srcSize) {
                out[i] = src[srcSize - 1];
            } else {
                float v0 = src[idx0];
                float v1 = src[idx1];
                out[i] = v0 + (v1 - v0) * frac;
            }
        }
        return out;
    }
    
    /**
     * 重采样缓冲区数据并在数据不足时进行填充
     * 使用镜像填充策略：如果数据不足30秒，则循环重复现有数据
     */
    private float[] resampleBufferWithPadding(ArrayDeque<Float> buffer, int targetLength) {
        int srcSize = buffer.size();
        if (srcSize < 2 || targetLength < 2) {
            return null;
        }
        
        // 将缓冲区数据转换为数组
        float[] src = new float[srcSize];
        int idx = 0;
        for (Float v : buffer) {
            src[idx++] = v;
        }
        
        // 计算原始数据对应的目标长度（重采样后）
        float ratio = (float) sampleRateHz / targetFs;
        int srcTargetLength = (int) (srcSize / ratio);
        
        float[] out = new float[targetLength];
        
        if (srcTargetLength >= targetLength) {
            // 数据足够，正常重采样
            for (int i = 0; i < targetLength; i++) {
                float srcPos = i * ratio;
                int idx0 = (int) Math.floor(srcPos);
                int idx1 = Math.min(srcSize - 1, idx0 + 1);
                float frac = srcPos - idx0;
                if (idx0 >= srcSize) {
                    out[i] = src[srcSize - 1];
                } else {
                    float v0 = src[idx0];
                    float v1 = src[idx1];
                    out[i] = v0 + (v1 - v0) * frac;
                }
            }
        } else {
            // 数据不足，先重采样现有数据，然后循环填充
            // 第1步：重采样现有数据
            float[] resampled = new float[srcTargetLength];
            for (int i = 0; i < srcTargetLength; i++) {
                float srcPos = i * ratio;
                int idx0 = (int) Math.floor(srcPos);
                int idx1 = Math.min(srcSize - 1, idx0 + 1);
                float frac = srcPos - idx0;
                if (idx0 >= srcSize) {
                    resampled[i] = src[srcSize - 1];
                } else {
                    float v0 = src[idx0];
                    float v1 = src[idx1];
                    resampled[i] = v0 + (v1 - v0) * frac;
                }
            }
            
            // 第2步：循环填充到目标长度
            for (int i = 0; i < targetLength; i++) {
                out[i] = resampled[i % srcTargetLength];
            }
            
            logDebug("Data padded: " + srcTargetLength + " -> " + targetLength + " samples");
        }
        
        return out;
    }

    private void normalizeInPlace(float[] data, int lengthPerChannel) {
        int channels = 2;
        // Data is interleaved: [t0_c0, t0_c1, t1_c0, t1_c1, ...]
        // Normalize each channel separately
        for (int c = 0; c < channels; c++) {
            // Calculate mean for channel c
            double mean = 0;
            for (int t = 0; t < lengthPerChannel; t++) {
                mean += data[t * channels + c];
            }
            mean /= lengthPerChannel;
            
            // Calculate variance for channel c
            double var = 0;
            for (int t = 0; t < lengthPerChannel; t++) {
                double v = data[t * channels + c] - mean;
                var += v * v;
            }
            double std = Math.sqrt(var / Math.max(1, lengthPerChannel - 1));
            if (std < 1e-6) std = 1.0;
            
            // Normalize channel c
            for (int t = 0; t < lengthPerChannel; t++) {
                int idx = t * channels + c;
                data[idx] = (float) ((data[idx] - mean) / std);
            }
        }
    }

    /**
     * 平滑滤波：计算历史值的移动平均
     */
    private int smoothValue(ArrayDeque<Integer> history, int newValue) {
        history.addLast(newValue);
        while (history.size() > SMOOTHING_WINDOW_SIZE) {
            history.removeFirst();
        }
        
        // 计算移动平均（加权：最近的值权重更高）
        int sum = 0;
        int weightSum = 0;
        int weight = 1;
        for (Integer value : history) {
            sum += value * weight;
            weightSum += weight;
            weight++; // 线性增加权重，最新值权重最大
        }
        return Math.round((float) sum / weightSum);
    }
    
    private void logDebug(String message) {
        // 输出到Logcat
        Log.d(TAG, message);
        
        // 输出到UI（通过listener）
        if (listener != null) {
            listener.onDebugLog(message);
        }
        
        // 写入文件
        writeToFile(message);
    }

    private void logAssetListing(String path, String label) {
        try {
            String[] entries = appContext.getAssets().list(path);
            if (entries == null) {
                logDebug("Assets list null for " + label + " (" + path + ")");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Assets[" + label + "]=");
            for (int i = 0; i < entries.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(entries[i]);
            }
            logDebug(sb.toString());
        } catch (IOException e) {
            logDebug("Assets list error for " + label + ": " + e.getMessage());
        }
    }
}

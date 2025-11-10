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

public class ModelInferenceManager {
    public enum Mission { HR, BP_SYS, BP_DIA, SPO2, RR }

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

    private final int sampleRateHz = 25;
    private int windowSeconds = 5;  // HR/BP/SpO2 使用 5 秒窗口
    private static final int RR_WINDOW_SECONDS_OVERRIDE = 15;
    private int windowSecondsRR = RR_WINDOW_SECONDS_OVERRIDE;  // 默认 15 秒窗口，可根据需求覆盖
    private int targetFs = 100;

    private final ArrayDeque<Float> greenBuf = new ArrayDeque<>();
    private final ArrayDeque<Float> irBuf = new ArrayDeque<>();
    // RR 专用缓冲区（需要更长的数据窗口）
    private final ArrayDeque<Float> irBufRR = new ArrayDeque<>();
    
    // 推理间隔控制：每2秒推理一次，避免过于频繁
    private static final long INFERENCE_INTERVAL_MS = 2000; // 2秒 (HR/BP/SpO2)
    private static final long INFERENCE_INTERVAL_MS_RR = 5000; // 5秒 (RR)
    private long lastInferenceTimeMs = 0;
    private long lastInferenceTimeMsRR = 0;
    
    // 平滑滤波器：存储最近N次预测结果
    private static final int SMOOTHING_WINDOW_SIZE = 5; // 使用最近5次结果
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

    public ModelInferenceManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
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
        loadMission(Mission.HR, findFirstMissionRoot("hr"));
        loadMission(Mission.BP_SYS, findFirstMissionRoot("BP_sys"));
        loadMission(Mission.BP_DIA, findFirstMissionRoot("BP_dia"));
        loadMission(Mission.SPO2, findFirstMissionRoot("spo2"));
        loadMission(Mission.RR, findFirstMissionRoot("rr"));

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
                        windowSeconds = Math.max(5, w);
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
        logDebug("Window seconds (HR/BP/SpO2)=" + windowSeconds + ", RR=" + windowSecondsRR + " (override), targetFs=" + targetFs);
    }

    private String findFirstMissionRoot(String missionKey) {
        // Known roots under assets (mapped from app/models): try common patterns
        // Assets are packaged with 'models' as a source root, but the AssetManager paths are root-relative.
        String[] roots;
        
        // 根据missionKey选择不同的路径模式
        if ("hr".equals(missionKey)) {
            roots = new String[] {
                    "transformer-ring1-hr-all-ir/hr",
                    "transformer-ring1-hr-all-irred/hr"
            };
        } else if ("BP_sys".equals(missionKey) || "BP_dia".equals(missionKey)) {
            roots = new String[] {
                    "transformer-ring1-bp-all-irred/" + missionKey
            };
        } else if ("spo2".equals(missionKey)) {
            roots = new String[] {
                    "transformer-ring1-spo2-all-irred/spo2"
            };
        } else if ("rr".equals(missionKey)) {
            roots = new String[] {
                    "resnet-ring1-rr-all-ir/resp_rr"
            };
        } else {
            roots = new String[] {};
        }
        
        logDebug("Searching for mission root: " + missionKey);
        for (String r : roots) {
            try {
                logDebug("Trying root path: " + r);
                String[] files = appContext.getAssets().list(r);
                if (files != null && files.length > 0) {
                    logDebug("Root path exists: " + r + " (" + files.length + " entries)");
                    // 验证路径匹配
                    boolean matches = r.endsWith("/" + missionKey) || 
                                     (missionKey.equals("hr") && r.endsWith("/hr")) ||
                                     (missionKey.equals("spo2") && r.endsWith("/spo2")) ||
                                     (missionKey.equals("rr") && r.contains("rr-all-ir"));
                    if (matches) {
                        logDebug("Mission root candidate success: " + r + " (" + files.length + " entries)");
                        return r;
                    } else {
                        logDebug("Root path doesn't match missionKey: " + r + " (expected ends with /" + missionKey + ")");
                    }
                } else {
                    logDebug("Mission root candidate empty or not found: " + r);
                }
            } catch (IOException e) {
                logDebug("IOException checking root " + r + ": " + e.getMessage());
            }
        }
        logDebug("Mission root not found for key: " + missionKey);
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

    public synchronized void onSensorData(long green, long red, long ir, short accX, short accY, short accZ, long timestampMs) {
        // Buffer for HR/BP/SpO2 (5 seconds)
        greenBuf.addLast((float) green);
        irBuf.addLast((float) ir);
        int maxSize = windowSeconds * sampleRateHz;
        while (greenBuf.size() > maxSize) greenBuf.removeFirst();
        while (irBuf.size() > maxSize) irBuf.removeFirst();

        // Buffer for RR (15 seconds)
        irBufRR.addLast((float) ir);
        int maxSizeRR = windowSecondsRR * sampleRateHz;
        while (irBufRR.size() > maxSizeRR) irBufRR.removeFirst();

        // 调试：每100个样本记录一次缓冲区状态
        if (greenBuf.size() % 100 == 0 && greenBuf.size() > 0) {
            logDebug("Buffer: HR/BP/SpO2=" + greenBuf.size() + "/" + maxSize + 
                    ", RR=" + irBufRR.size() + "/" + maxSizeRR + " samples");
        }

        // HR/BP/SpO2 推理：5秒窗口，每2秒推理一次
        if (greenBuf.size() >= maxSize && irBuf.size() >= maxSize) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastInferenceTimeMs >= INFERENCE_INTERVAL_MS) {
                lastInferenceTimeMs = currentTime;
                logDebug("HR/BP/SpO2 buffer full, starting inference.");
                runHrBpSpo2Missions();
            }
        }
        
        // RR 推理：15秒窗口，每5秒推理一次
        if (irBufRR.size() >= maxSizeRR) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastInferenceTimeMsRR >= INFERENCE_INTERVAL_MS_RR) {
                lastInferenceTimeMsRR = currentTime;
                logDebug("RR buffer full, starting inference.");
                runRRMission();
            }
        }
    }

    /**
     * 重置所有缓冲区、历史记录和定时器，通常在开始/停止测量时调用，防止残留数据影响下一次推理。
     */
    public synchronized void reset() {
        greenBuf.clear();
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
        
        // Prepare input tensor [1, T, C] with C=2 (green, ir)
        // Model expects (batch, length, channels) format as per training
        int targetLength = windowSeconds * targetFs;
        if (targetLength <= 0) {
            logDebug("Target length invalid: " + targetLength);
            return;
        }

        float[] resampledGreen = resampleBuffer(greenBuf, targetLength);
        float[] resampledIr = resampleBuffer(irBuf, targetLength);
        if (resampledGreen == null || resampledIr == null) {
            logDebug("Resample failed due to insufficient data");
            return;
        }

        // Interleave channels: [t0_green, t0_ir, t1_green, t1_ir, ...]
        // to match (batch=1, length=T, channels=2) layout
        float[] input = new float[targetLength * 2];
        for (int i = 0; i < targetLength; i++) {
            input[i * 2] = resampledGreen[i];     // time step i, channel 0 (green)
            input[i * 2 + 1] = resampledIr[i];    // time step i, channel 1 (ir)
        }

        // normalize per-channel (zero mean, unit var) for numerical stability
        normalizeInPlace(input, targetLength);

        long[] shape = new long[]{1, targetLength, 2};
        Tensor tensor = Tensor.fromBlob(input, shape);
        
        long prepTime = System.currentTimeMillis() - startTime;
        logDebug("Inference started: targetLength=" + targetLength + ", prep=" + prepTime + "ms");

        // HR - uses single channel (IR only)
        long hrStart = System.currentTimeMillis();
        if (hasMission(Mission.HR)) {
            // Extract IR channel only for HR model: [1, T, 1]
            float[] hrInput = new float[targetLength];
            for (int i = 0; i < targetLength; i++) {
                hrInput[i] = input[i * 2 + 1];  // Extract IR channel (index 1)
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
                int rawValue = Math.round(pred);
                int smoothedValue = smoothValue(hrHistory, rawValue);
                listener.onHrPredicted(smoothedValue);
                logDebug("HR=" + smoothedValue + " bpm (raw=" + rawValue + ", " + hrTime + "ms)");
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
                int smoothedValue = smoothValue(bpSysHistory, rawValue);
                listener.onBpSysPredicted(smoothedValue);
                logDebug("BP_SYS=" + smoothedValue + " mmHg (raw=" + rawValue + ", " + bpSysTime + "ms)");
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
                int smoothedValue = smoothValue(bpDiaHistory, rawValue);
                listener.onBpDiaPredicted(smoothedValue);
                logDebug("BP_DIA=" + smoothedValue + " mmHg (raw=" + rawValue + ", " + bpDiaTime + "ms)");
            }
            if (Float.isNaN(pred)) logDebug("BP_DIA prediction NaN (" + bpDiaTime + "ms)");
        }
        
        // SpO2
        long spo2Start = System.currentTimeMillis();
        if (hasMission(Mission.SPO2)) {
            float pred = averagePrediction(missionModules.get(Mission.SPO2), tensor);
            long spo2Time = System.currentTimeMillis() - spo2Start;
            if (listener != null && !Float.isNaN(pred) && pred > 0) {
                int rawValue = Math.max(70, Math.min(100, Math.round(pred)));
                int smoothedValue = smoothValue(spo2History, rawValue);
                listener.onSpo2Predicted(smoothedValue);
                logDebug("SPO2=" + smoothedValue + "% (raw=" + rawValue + ", " + spo2Time + "ms)");
            }
            if (Float.isNaN(pred)) logDebug("SpO2 prediction NaN (" + spo2Time + "ms)");
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        logDebug("HR/BP/SpO2 total inference time: " + totalTime + "ms");
    }
    
    private void runRRMission() {
        long startTime = System.currentTimeMillis();
        
        // Prepare input tensor for RR: [1, T, 1] with 15-second window
        int targetLength = windowSecondsRR * targetFs;  // 15秒 × 100Hz = 1500 samples
        if (targetLength <= 0) {
            logDebug("RR target length invalid: " + targetLength);
            return;
        }

        float[] resampledIr = resampleBuffer(irBufRR, targetLength);
        if (resampledIr == null) {
            logDebug("RR resample failed due to insufficient data");
            return;
        }

        // Normalize single channel (IR only)
        double mean = 0;
        for (int i = 0; i < targetLength; i++) {
            mean += resampledIr[i];
        }
        mean /= targetLength;
        double var = 0;
        for (int i = 0; i < targetLength; i++) {
            double v = resampledIr[i] - mean;
            var += v * v;
        }
        double std = Math.sqrt(var / Math.max(1, targetLength - 1));
        if (std < 1e-6) std = 1.0;
        for (int i = 0; i < targetLength; i++) {
            resampledIr[i] = (float) ((resampledIr[i] - mean) / std);
        }
        
        long prepTime = System.currentTimeMillis() - startTime;
        logDebug("RR inference started: targetLength=" + targetLength + ", prep=" + prepTime + "ms");
        
        // RR inference
        long rrStart = System.currentTimeMillis();
        if (hasMission(Mission.RR)) {
            float pred = Float.NaN;
            try {
                Tensor rrTensor = Tensor.fromBlob(resampledIr, new long[]{1, targetLength, 1}); // (B, T, C) as per training
                pred = averagePrediction(missionModules.get(Mission.RR), rrTensor);
            } catch (Throwable e) {
                Log.e(TAG, "RR inference failed", e);
                logDebug("RR inference failed: " + e.getMessage());
            }

            long rrTime = System.currentTimeMillis() - rrStart;
            if (listener != null && !Float.isNaN(pred) && pred > 0) {
                int rawValue = Math.max(8, Math.min(30, Math.round(pred)));  // 正常呼吸率范围 8-30 brpm
                int smoothedValue = smoothValue(rrHistory, rawValue);
                listener.onRrPredicted(smoothedValue);
                logDebug("RR=" + smoothedValue + " brpm (raw=" + rawValue + ", " + rrTime + "ms)");
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

    private float averagePrediction(List<Module> modules, Tensor input) {
        if (modules == null || modules.isEmpty()) return Float.NaN;
        float sum = 0f;
        int n = 0;
        for (Module m : modules) {
            try {
                IValue out = m.forward(IValue.from(input));
                Tensor t = out.toTensor();
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



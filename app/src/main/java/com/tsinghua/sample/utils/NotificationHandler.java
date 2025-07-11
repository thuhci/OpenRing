package com.tsinghua.sample.utils;

import android.util.Log;
import com.tsinghua.sample.PlotView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.*;

public class NotificationHandler {
    private static final String TAG = "NotificationHandler";

    // Real-time data display PlotViews
    private static PlotView plotViewG, plotViewI;
    private static PlotView plotViewR, plotViewX;
    private static PlotView plotViewY, plotViewZ;
    private static PlotView plotViewGyroX, plotViewGyroY, plotViewGyroZ;
    private static PlotView plotViewTemp0, plotViewTemp1, plotViewTemp2;

    // File operation callback interface
    public interface FileResponseCallback {
        void onFileListReceived(byte[] data);
        void onFileDataReceived(byte[] data);
    }

    // Time operation callback interface (includes calibration and update)
    public interface TimeSyncCallback {
        void onTimeSyncResponse(byte[] data);
        void onTimeUpdateResponse(byte[] data);
    }

    // Device command sending callback interface
    public interface DeviceCommandCallback {
        void sendCommand(byte[] commandData);
        default void onMeasurementStarted() {}
        default void onMeasurementStopped() {}
        default void onExerciseStarted(int duration, int segmentTime) {}
        default void onExerciseStopped() {}
    }

    // Exercise status callback interface
    public interface ExerciseStatusCallback {
        void onExerciseProgress(int currentSegment, int totalSegments, int progressPercent);
        void onSegmentCompleted(int segmentNumber, int totalSegments);
        void onExerciseCompleted();
    }

    private static FileResponseCallback fileResponseCallback;
    private static TimeSyncCallback timeSyncCallback;
    private static DeviceCommandCallback deviceCommandCallback;
    private static ExerciseStatusCallback exerciseStatusCallback;

    // Measurement parameter configuration class
    public static class MeasurementConfig {
        public int collectTime = 30;        // Collection time, default 30 seconds
        public int collectFreq = 25;        // Collection frequency, default 25Hz (reserved)
        public int ledGreenCurrent = 20;    // green LED current, default 20 (392uA * 20 = 7.84mA)
        public int ledIrCurrent = 20;       // IR LED current, default 20
        public int ledRedCurrent = 20;      // red LED current, default 20
        public boolean progressResponse = true;   // Progress response, default upload
        public boolean waveformResponse = true;   // Waveform response, default upload

        // Current range validation (0-50 levels)
        public void setLedCurrents(int green, int ir, int red) {
            this.ledGreenCurrent = Math.max(0, Math.min(50, green));
            this.ledIrCurrent = Math.max(0, Math.min(50, ir));
            this.ledRedCurrent = Math.max(0, Math.min(50, red));
        }

        public String getCurrentDescription() {
            return String.format("Green: %.2fmA, IR: %.2fmA, Red: %.2fmA",
                    ledGreenCurrent * 0.392, ledIrCurrent * 0.392, ledRedCurrent * 0.392);
        }
    }

    // Exercise configuration class
    public static class ExerciseConfig {
        public int totalDuration = 300;     // Total exercise duration, default 5 minutes (seconds)
        public int segmentTime = 60;        // Segment time, default 60 seconds
        public boolean autoStart = false;   // Whether to auto start
        public boolean enableRest = true;   // Whether to enable rest intervals
        public int restTime = 30;           // Rest time, default 30 seconds

        public int getTotalSegments() {
            return (totalDuration + segmentTime - 1) / segmentTime; // Round up
        }

        public String getExerciseDescription() {
            return String.format("Total: %dmin%dsec, Segment: %dsec, %d segments",
                    totalDuration / 60, totalDuration % 60, segmentTime, getTotalSegments());
        }
    }

    // Current status tracking
    private static boolean isMeasuring = false;
    private static boolean isExercising = false;
    private static boolean isMeasurementOngoing = false; // New: distinguish if measurement is in progress
    private static int currentFrameId = 1;
    private static MeasurementConfig measurementConfig = new MeasurementConfig();
    private static ExerciseConfig exerciseConfig = new ExerciseConfig();
    private static Timer exerciseTimer;
    private static Timer measurementTimer; // New: measurement timer
    private static int currentSegment = 0;

    // PlotView setting methods
    public static void setPlotViewG(PlotView chartView) { plotViewG = chartView; }
    public static void setPlotViewI(PlotView chartView) { plotViewI = chartView; }
    public static void setPlotViewR(PlotView chartView) { plotViewR = chartView; }
    public static void setPlotViewX(PlotView chartView) { plotViewX = chartView; }
    public static void setPlotViewY(PlotView chartView) { plotViewY = chartView; }
    public static void setPlotViewZ(PlotView chartView) { plotViewZ = chartView; }
    public static void setPlotViewGyroX(PlotView chartView) { plotViewGyroX = chartView; }
    public static void setPlotViewGyroY(PlotView chartView) { plotViewGyroY = chartView; }
    public static void setPlotViewGyroZ(PlotView chartView) { plotViewGyroZ = chartView; }

    // New: Temperature PlotView setting methods
    public static void setPlotViewTemp0(PlotView chartView) { plotViewTemp0 = chartView; }
    public static void setPlotViewTemp1(PlotView chartView) { plotViewTemp1 = chartView; }
    public static void setPlotViewTemp2(PlotView chartView) { plotViewTemp2 = chartView; }

    public interface LogRecorder {
        void recordLog(String message);
    }

    private static LogRecorder logRecorder;

    // Add method to set log recorder
    public static void setLogRecorder(LogRecorder recorder) {
        logRecorder = recorder;
        if (recorder != null) {
            recordLog("NotificationHandler log recorder connected to RingViewHolder");
        }
    }

    // Add internal recordLog method
    private static void recordLog(String message) {
        // Output to Android Log (maintain original functionality)
        Log.d(TAG, message);

        // If external log recorder is set, call external recordLog
        if (logRecorder != null) {
            logRecorder.recordLog("[NH] " + message);
        }
    }

    // Set callback methods
    public static void setFileResponseCallback(FileResponseCallback callback) {
        fileResponseCallback = callback;
        Log.d(TAG, "File response callback set");
    }

    public static void setTimeSyncCallback(TimeSyncCallback callback) {
        timeSyncCallback = callback;
        Log.d(TAG, "Time sync callback set");
    }

    public static void setDeviceCommandCallback(DeviceCommandCallback callback) {
        deviceCommandCallback = callback;
        Log.d(TAG, "Device command callback set");
    }

    public static void setExerciseStatusCallback(ExerciseStatusCallback callback) {
        exerciseStatusCallback = callback;
        Log.d(TAG, "Exercise status callback set");
    }

    // Get and set measurement configuration
    public static MeasurementConfig getMeasurementConfig() {
        return measurementConfig;
    }

    public static void setMeasurementConfig(MeasurementConfig config) {
        measurementConfig = config;
        Log.d(TAG, "Measurement config updated: " + config.getCurrentDescription());
    }

    // New: Simplified method to set measurement time
    public static void setMeasurementTime(int timeSeconds) {
        measurementConfig.collectTime = Math.max(1, Math.min(3600, timeSeconds));
        Log.d(TAG, "Measurement time set to: " + measurementConfig.collectTime + " seconds");
    }

    // Get and set exercise configuration
    public static ExerciseConfig getExerciseConfig() {
        return exerciseConfig;
    }

    public static void setExerciseConfig(ExerciseConfig config) {
        exerciseConfig = config;
        Log.d(TAG, "Exercise config updated: " + config.getExerciseDescription());
    }

    // New: Simplified method to set exercise parameters
    public static void setExerciseParams(int totalDurationSeconds, int segmentDurationSeconds) {
        exerciseConfig.totalDuration = Math.max(60, Math.min(86400, totalDurationSeconds));
        exerciseConfig.segmentTime = Math.max(30, Math.min(exerciseConfig.totalDuration, segmentDurationSeconds));
        Log.d(TAG, "Exercise params set: Total=" + exerciseConfig.totalDuration + "s, Segment=" + exerciseConfig.segmentTime + "s");
    }

    // Start active measurement
    public static boolean startActiveMeasurement() {
        return startActiveMeasurement(measurementConfig);
    }

    public static boolean startActiveMeasurement(MeasurementConfig config) {
        if (isMeasuring) {
            Log.w(TAG, "Measurement already in progress");
            return false;
        }

        if (deviceCommandCallback == null) {
            Log.e(TAG, "Device command callback not set");
            return false;
        }

        try {
            // Generate active measurement command
            byte[] command = buildActiveMeasurementCommand(config);
            deviceCommandCallback.sendCommand(command);

            isMeasuring = true;
            isMeasurementOngoing = true; // New: mark measurement in progress
            deviceCommandCallback.onMeasurementStarted();

            // New: Start measurement monitor timer (but don't auto stop)
            startMeasurementMonitor(config.collectTime);

            recordLog(String.format("[Start Active Measurement] %s, Duration: %d seconds",
                    config.getCurrentDescription(), config.collectTime));
            Log.i(TAG, String.format("Started active measurement: %s, Duration: %ds",
                    config.getCurrentDescription(), config.collectTime));
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start active measurement", e);
            return false;
        }
    }

    // New: Start measurement monitor timer
    private static void startMeasurementMonitor(int durationSeconds) {
        if (measurementTimer != null) {
            measurementTimer.cancel();
        }

        measurementTimer = new Timer();
        measurementTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                // Measurement time reached, but don't auto stop, just log
                recordLog(String.format("[Measurement Time Reached] Preset %d seconds completed, measurement continues", durationSeconds));
                recordLog("You can manually click 'Stop Collection' button to end measurement");
                Log.i(TAG, "Measurement duration completed, but measurement continues until manual stop");

                // Can send notification to UI here to inform user measurement time is up
                // But don't change isMeasuring status, let user manually control stop
            }
        }, durationSeconds * 1000);
    }

    // Modified: Stop measurement method, using new stop collection command
    public static boolean stopMeasurement() {
        if (!isMeasuring) {
            Log.w(TAG, "No measurement in progress");
            recordLog("[Stop Measurement Failed] No measurement currently in progress");
            return false;
        }

        if (deviceCommandCallback == null) {
            Log.e(TAG, "Device command callback not set");
            return false;
        }

        try {
            // Use new stop collection command (Cmd=0x3C, Subcmd=0x04)
            byte[] command = buildStopCollectionCommand();
            deviceCommandCallback.sendCommand(command);

            // Stop measurement monitor timer
            if (measurementTimer != null) {
                measurementTimer.cancel();
                measurementTimer = null;
            }

            isMeasuring = false;
            isMeasurementOngoing = false;
            deviceCommandCallback.onMeasurementStopped();

            recordLog("[Manual Stop Measurement] Send stop collection command");
            Log.i(TAG, "Stopped measurement manually");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to stop measurement", e);
            recordLog("[Stop Measurement Failed] " + e.getMessage());
            return false;
        }
    }

    // Start exercise
    public static boolean startExercise() {
        return startExercise(exerciseConfig);
    }

    public static boolean startExercise(ExerciseConfig config) {
        if (isExercising) {
            Log.w(TAG, "Exercise already in progress");
            return false;
        }

        if (deviceCommandCallback == null) {
            Log.e(TAG, "Device command callback not set");
            return false;
        }

        try {
            isExercising = true;
            currentSegment = 0;

            // Send exercise start command
            byte[] command = buildStartExerciseCommand(config);
            deviceCommandCallback.sendCommand(command);

            // Start exercise timer
            startExerciseTimer(config);

            deviceCommandCallback.onExerciseStarted(config.totalDuration, config.segmentTime);

            Log.i(TAG, "Started exercise: " + config.getExerciseDescription());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start exercise", e);
            isExercising = false;
            return false;
        }
    }

    // End exercise
    public static boolean stopExercise() {
        if (!isExercising) {
            Log.w(TAG, "No exercise in progress");
            return false;
        }

        try {
            // Send exercise stop command
            if (deviceCommandCallback != null) {
                byte[] command = buildStopExerciseCommand();
                deviceCommandCallback.sendCommand(command);
            }

            // Stop timer
            if (exerciseTimer != null) {
                exerciseTimer.cancel();
                exerciseTimer = null;
            }

            // Stop current measurement
            if (isMeasuring) {
                stopMeasurement();
            }

            isExercising = false;
            currentSegment = 0;

            if (deviceCommandCallback != null) {
                deviceCommandCallback.onExerciseStopped();
            }

            Log.i(TAG, "Stopped exercise");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to stop exercise", e);
            return false;
        }
    }

    // Start exercise timer
    private static void startExerciseTimer(ExerciseConfig config) {
        exerciseTimer = new Timer();
        final int totalSegments = config.getTotalSegments();

        exerciseTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                currentSegment++;

                if (currentSegment <= totalSegments) {
                    // Notify segment completed
                    if (exerciseStatusCallback != null) {
                        int progress = (currentSegment * 100) / totalSegments;
                        exerciseStatusCallback.onSegmentCompleted(currentSegment, totalSegments);
                        exerciseStatusCallback.onExerciseProgress(currentSegment, totalSegments, progress);
                    }

                    // If not the last segment, start next segment measurement
                    if (currentSegment < totalSegments) {
                        if (config.enableRest && config.restTime > 0) {
                            // Start next segment after rest interval
                            Timer restTimer = new Timer();
                            restTimer.schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    startNextSegment(config);
                                }
                            }, config.restTime * 1000);
                        } else {
                            // Start next segment directly
                            startNextSegment(config);
                        }
                    } else {
                        // Exercise completed
                        completeExercise();
                    }
                } else {
                    // Exercise completed
                    completeExercise();
                }
            }
        }, config.segmentTime * 1000, config.segmentTime * 1000);
    }

    // Start next exercise segment
    private static void startNextSegment(ExerciseConfig config) {
        if (isExercising && currentSegment < config.getTotalSegments()) {
            MeasurementConfig segmentConfig = new MeasurementConfig();
            segmentConfig.collectTime = config.segmentTime;
            segmentConfig.ledGreenCurrent = measurementConfig.ledGreenCurrent;
            segmentConfig.ledIrCurrent = measurementConfig.ledIrCurrent;
            segmentConfig.ledRedCurrent = measurementConfig.ledRedCurrent;
            segmentConfig.progressResponse = measurementConfig.progressResponse;
            segmentConfig.waveformResponse = measurementConfig.waveformResponse;

            startActiveMeasurement(segmentConfig);
            Log.d(TAG, "Started segment " + (currentSegment + 1) + "/" + config.getTotalSegments());
        }
    }

    // Complete exercise
    private static void completeExercise() {
        if (exerciseTimer != null) {
            exerciseTimer.cancel();
            exerciseTimer = null;
        }

        isExercising = false;

        if (exerciseStatusCallback != null) {
            exerciseStatusCallback.onExerciseCompleted();
        }

        if (deviceCommandCallback != null) {
            deviceCommandCallback.onExerciseStopped();
        }

        Log.i(TAG, "Exercise completed");
    }

    // Build active measurement command
    private static byte[] buildActiveMeasurementCommand(MeasurementConfig config) {
        // Command format: Frame Type(1) + Frame ID(1) + Cmd(1) + Subcmd(1) + Data(7)
        byte[] command = new byte[11];

        command[0] = 0x00;  // Frame Type
        command[1] = (byte)(currentFrameId++ & 0xFF);  // Frame ID
        command[2] = 0x3C;  // Cmd
        command[3] = 0x00;  // Subcmd

        // Data part (7 bytes)
        command[4] = (byte)(config.collectTime & 0xFF);  // Collection time
        command[5] = (byte)(config.collectFreq & 0xFF);  // Collection frequency
        command[6] = (byte)(config.ledGreenCurrent & 0xFF);  // green LED current
        command[7] = (byte)(config.ledIrCurrent & 0xFF);    // IR LED current
        command[8] = (byte)(config.ledRedCurrent & 0xFF);   // red LED current
        command[9] = (byte)(config.progressResponse ? 1 : 0);  // Progress response
        command[10] = (byte)(config.waveformResponse ? 1 : 0); // Waveform response

        Log.d(TAG, String.format("Built measurement command: Time=%ds, Green=%d, IR=%d, Red=%d",
                config.collectTime, config.ledGreenCurrent, config.ledIrCurrent, config.ledRedCurrent));

        return command;
    }

    // New: Build stop collection command (based on new command format in docs)
    private static byte[] buildStopCollectionCommand() {
        // Heart rate stop collection command format: Frame Type(1) + Frame ID(1) + Cmd(1) + Subcmd(1)
        // Request command: 00[FrameID]3C04
        byte[] command = new byte[4];

        command[0] = 0x00;  // Frame Type
        command[1] = (byte)(currentFrameId++ & 0xFF);  // Frame ID
        command[2] = 0x3C;  // Cmd (heart rate related command)
        command[3] = 0x04;  // Subcmd (stop collection)

        recordLog(String.format("Build stop collection command: %02X%02X%02X%02X",
                command[0], command[1], command[2], command[3]));
        Log.d(TAG, "Built stop collection command (0x3C04)");
        return command;
    }

    // Build stop measurement command (keep original as backup)
    private static byte[] buildStopMeasurementCommand() {
        // Command format: Frame Type(1) + Frame ID(1) + Cmd(1) + Subcmd(1)
        byte[] command = new byte[4];

        command[0] = 0x00;  // Frame Type
        command[1] = (byte)(currentFrameId++ & 0xFF);  // Frame ID
        command[2] = 0x3C;  // Cmd
        command[3] = 0x02;  // Subcmd (original stop command)

        Log.d(TAG, "Built stop measurement command (0x3C02)");
        return command;
    }

    // New: Build start exercise command
    private static byte[] buildStartExerciseCommand(ExerciseConfig config) {
        // Command format: Frame Type(1) + Frame ID(1) + Cmd(1) + Subcmd(1) + Data(10)
        byte[] command = new byte[14];

        command[0] = 0x00;  // Frame Type
        command[1] = (byte)(68);  // Frame ID
        command[2] = 0x38;  // Cmd (exercise command)
        command[3] = 0x01;  // Subcmd (start exercise)

        // Data part: sport_mode(2 bytes) + time(4 bytes) + slice_storage_time(4 bytes)
        // sport_mode (2 bytes)
        int sportMode = 1;
        command[4] = (byte)(sportMode & 0xFF);
        command[5] = (byte)((sportMode >> 8) & 0xFF);

        // time (4 bytes) - total duration, little endian
        command[6] = (byte)(config.totalDuration & 0xFF);
        command[7] = (byte)((config.totalDuration >> 8) & 0xFF);
        command[8] = (byte)((config.totalDuration >> 16) & 0xFF);
        command[9] = (byte)((config.totalDuration >> 24) & 0xFF);

        // slice_storage_time (4 bytes) - segment duration, little endian
        command[10] = (byte)(config.segmentTime & 0xFF);
        command[11] = (byte)((config.segmentTime >> 8) & 0xFF);
        command[12] = (byte)((config.segmentTime >> 16) & 0xFF);
        command[13] = (byte)((config.segmentTime >> 24) & 0xFF);

        Log.d(TAG, String.format("Built start exercise command: SportMode=%d, Total=%ds, Segment=%ds",
                sportMode, config.totalDuration, config.segmentTime));

        return command;
    }

    // New: Build stop exercise command
    private static byte[] buildStopExerciseCommand() {
        // Command format: Frame Type(1) + Frame ID(1) + Cmd(1) + Subcmd(1)
        byte[] command = new byte[4];

        command[0] = 0x00;  // Frame Type
        command[1] = (byte)(68);  // Frame ID
        command[2] = 0x38;  // Cmd (exercise command)
        command[3] = 0x03;  // Subcmd (stop exercise)

        Log.d(TAG, "Built stop exercise command");
        return command;
    }

    // Get current status
    public static boolean isMeasuring() {
        return isMeasuring;
    }

    public static boolean isExercising() {
        return isExercising;
    }

    // New: Get measurement ongoing status
    public static boolean isMeasurementOngoing() {
        return isMeasurementOngoing;
    }

    public static int getCurrentSegment() {
        return currentSegment;
    }

    public static int getTotalSegments() {
        return exerciseConfig.getTotalSegments();
    }

    /**
     * Little endian read 4-byte unsigned integer
     */
    private static long readUInt32LE(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            throw new IndexOutOfBoundsException("Not enough data to read uint32");
        }
        return ((long)(data[offset] & 0xFF)) |
                ((long)(data[offset + 1] & 0xFF) << 8) |
                ((long)(data[offset + 2] & 0xFF) << 16) |
                ((long)(data[offset + 3] & 0xFF) << 24);
    }

    /**
     * Little endian read 2-byte signed integer
     */
    private static short readInt16LE(byte[] data, int offset) {
        if (offset + 2 > data.length) {
            throw new IndexOutOfBoundsException("Not enough data to read int16");
        }
        return (short)(((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF));
    }

    /**
     * Little endian read 8-byte timestamp
     */
    private static long readUInt64LE(byte[] data, int offset) {
        if (offset + 8 > data.length) {
            throw new IndexOutOfBoundsException("Not enough data to read uint64");
        }
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long)(data[offset + i] & 0xFF)) << (i * 8);
        }
        return result;
    }

    /**
     * Main data processing entry point
     */
    public static String handleNotification(byte[] data) {
        if (data == null || data.length < 4) {
            Log.w(TAG, "Invalid data: null or length < 4");
            return "Invalid data";
        }

        int frameType = data[0] & 0xFF;
        int frameId = data[1] & 0xFF;
        int cmd = data[2] & 0xFF;
        int subcmd = data[3] & 0xFF;

        Log.d(TAG, String.format("Received data: FrameType=0x%02X, FrameID=0x%02X, Cmd=0x%02X, Subcmd=0x%02X, Length=%d",
                frameType, frameId, cmd, subcmd, data.length));

        // Time calibration handling (Cmd = 0x10)
        if (cmd == 0x10) {
            return handleTimeSyncOperations(data, frameId, subcmd);
        }
        // File operation handling (Cmd = 0x36)
        else if (cmd == 0x36) {
            return handleFileOperations(data, frameId, subcmd);
        }
        // Exercise command handling (Cmd = 0x38)
        else if (cmd == 0x38) {
            return handleExerciseOperations(data, frameId, subcmd);
        }
        // Real-time data handling (Cmd = 0x3C)
        else if (cmd == 0x3C) {
            return handleRealtimeData(data, frameId, subcmd);
        }
        // Other commands
        else {
            String result = "Unknown command: 0x" + String.format("%02X", cmd);
            Log.w(TAG, result);
            return result;
        }
    }

    /**
     * Handle exercise operation related responses (Cmd = 0x38)
     */
    private static String handleExerciseOperations(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling exercise operation: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x01: // Start exercise response
                return handleStartExerciseResponse(data, frameId);

            case 0x03: // Stop exercise response
                return handleStopExerciseResponse(data, frameId);

            default:
                String result = "Unknown exercise operation subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * Handle start exercise response
     */
    private static String handleStartExerciseResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing start exercise response");

        try {
            String result = String.format("Start Exercise Response (Frame ID: %d): Command sent successfully", frameId);
            recordLog("[Start Exercise Response] Command sent successfully");
            Log.i(TAG, result);
            return result;

        } catch (Exception e) {
            String result = "Error processing start exercise response: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * Handle stop exercise response
     */
    private static String handleStopExerciseResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing stop exercise response");

        try {
            String result = String.format("Stop Exercise Response (Frame ID: %d): Command sent successfully", frameId);
            recordLog("[Stop Exercise Response] Command sent successfully");
            Log.i(TAG, result);
            return result;

        } catch (Exception e) {
            String result = "Error processing stop exercise response: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * Handle time operation related responses (Cmd = 0x10)
     */
    private static String handleTimeSyncOperations(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling time operation: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x00: // Time update response
                return handleTimeUpdateResponse(data, frameId);

            case 0x02: // Time calibration response
                return handleTimeSyncResponse(data, frameId);

            default:
                String result = "Unknown time operation subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * Handle time update response
     */
    private static String handleTimeUpdateResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing time update response");

        try {
            // Notify callback for detailed parsing
            if (timeSyncCallback != null) {
                timeSyncCallback.onTimeUpdateResponse(data);
            } else {
                Log.w(TAG, "Time sync callback is null");
            }

            // Return simple status info
            String result = String.format("Time Update Response (Frame ID: %d): Success", frameId);
            Log.i(TAG, result);
            return result;

        } catch (Exception e) {
            String result = "Error processing time update response: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * Handle time calibration response
     */
    private static String handleTimeSyncResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing time sync response");

        try {
            // Notify callback for detailed parsing
            if (timeSyncCallback != null) {
                timeSyncCallback.onTimeSyncResponse(data);
            } else {
                Log.w(TAG, "Time sync callback is null");
            }

            // Return simple status info - using little endian read
            if (data.length >= 28) { // 4-byte frame header + 24-byte data
                long hostSentTime = readUInt64LE(data, 4);
                long ringReceivedTime = readUInt64LE(data, 12);
                long ringUploadTime = readUInt64LE(data, 20);

                String result = String.format("Time Sync Response (Frame ID: %d): Host=%d, Ring RX=%d, Ring TX=%d",
                        frameId, hostSentTime, ringReceivedTime, ringUploadTime);
                Log.i(TAG, result);
                return result;
            } else {
                String result = "Invalid time sync response length: " + data.length;
                Log.e(TAG, result);
                return result;
            }

        } catch (Exception e) {
            String result = "Error processing time sync response: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * Handle file operation related responses (Cmd = 0x36)
     */
    private static String handleFileOperations(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling file operation: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x10: // File list response
                return handleFileListResponse(data, frameId);

            case 0x11: // File data response
                return handleFileDataResponse(data, frameId);

            default:
                String result = "Unknown file operation subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * Handle file list response
     */
    private static String handleFileListResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing file list response");

        try {
            // Notify callback for detailed parsing
            if (fileResponseCallback != null) {
                fileResponseCallback.onFileListReceived(data);
            } else {
                Log.w(TAG, "File response callback is null");
            }

            // Return simple status info - using little endian read
            if (data.length >= 12) {
                long totalFiles = readUInt32LE(data, 4);
                long seqNum = readUInt32LE(data, 8);

                String result = String.format("File List Response (Frame ID: %d): Total=%d, Seq=%d",
                        frameId, totalFiles, seqNum);
                Log.i(TAG, result);
                return result;
            } else {
                String result = "Invalid file list response length: " + data.length;
                Log.e(TAG, result);
                return result;
            }

        } catch (Exception e) {
            String result = "Error processing file list: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * Handle file data response
     */
    private static String handleFileDataResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing file data response");

        try {
            // Notify callback for detailed parsing and saving
            if (fileResponseCallback != null) {
                fileResponseCallback.onFileDataReceived(data);
            } else {
                Log.w(TAG, "File response callback is null");
            }

            // Return simple status info - using little endian read
            if (data.length >= 21) {
                int fileSystemStatus = data[4] & 0xFF;
                long fileSize = readUInt32LE(data, 5);
                long totalPackets = readUInt32LE(data, 9);
                long currentPacket = readUInt32LE(data, 13);
                long currentPacketLength = readUInt32LE(data, 17);

                String result = String.format("File Data Response (Frame ID: %d): Status=%d, Size=%d, Packet=%d/%d, Length=%d",
                        frameId, fileSystemStatus, fileSize, currentPacket, totalPackets, currentPacketLength);
                Log.i(TAG, result);
                return result;
            } else {
                String result = "Invalid file data response length: " + data.length;
                Log.e(TAG, result);
                return result;
            }

        } catch (Exception e) {
            String result = "Error processing file data: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * Handle real-time data (Cmd = 0x3C)
     */
    private static String handleRealtimeData(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling realtime data: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x01: // Real-time data or time response
                if (data.length == 13) {
                    // Time response packet
                    return handleTimeResponse(data, frameId);
                } else if (data.length > 14) {
                    // Real-time waveform data packet
                    return handleRealtimeWaveformData(data, frameId);
                } else {
                    String result = "Invalid packet length for subcmd 0x01: " + data.length;
                    Log.w(TAG, result);
                    return result;
                }

            case 0x02: // Standard waveform response packet
                return handleStandardWaveformResponse(data, frameId);

            case 0x03: // Stop response (Modified: handle new stop collection response)
                return handleStopCollectionResponse(data, frameId);

            case 0x04: // New: Handle stop collection response (based on response format in docs)
                return handleStopCollectionResponse(data, frameId);

            case 0xFF: // Progress response packet
                return handleProgressResponse(data, frameId);

            default:
                String result = "Unknown realtime subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * Modified: Handle stop collection response (support new Subcmd=0x04)
     */
    private static String handleStopCollectionResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing stop collection response");

        // Update measurement status
        isMeasuring = false;
        isMeasurementOngoing = false;

        // Stop measurement monitor timer
        if (measurementTimer != null) {
            measurementTimer.cancel();
            measurementTimer = null;
        }

        if (deviceCommandCallback != null) {
            deviceCommandCallback.onMeasurementStopped();
        }

        String result = String.format("Stop Collection Response (Frame ID: %d): Measurement stopped successfully", frameId);
        recordLog("[Stop Collection Response] Measurement successfully stopped");
        Log.i(TAG, result);
        return result;
    }

    /**
     * Handle stop response (keep original method for compatibility)
     */
    private static String handleStopResponse(byte[] data, int frameId) {
        return handleStopCollectionResponse(data, frameId);
    }

    /**
     * Handle time response
     */
    private static String handleTimeResponse(byte[] data, int frameId) {
        if (data.length < 13) {
            return "Invalid time response packet length";
        }

        StringBuilder result = new StringBuilder();
        result.append("Time Response (Frame ID: ").append(frameId).append("):\n");

        long timestamp = readUInt64LE(data, 4);
        int timezone = data[12] & 0xFF;

        result.append("UNIX Timestamp: ").append(timestamp).append(" ms\n");
        result.append("Formatted Time: ").append(formatTimestamp(timestamp)).append("\n");
        result.append("Timezone: ").append(timezone);

        Log.i(TAG, "Time response processed");
        return result.toString();
    }

    /**
     * Handle real-time waveform data and update charts
     */
    private static String handleRealtimeWaveformData(byte[] data, int frameId) {
        if (data.length < 14) {
            return "Invalid realtime waveform packet length";
        }

        StringBuilder result = new StringBuilder();
        result.append("Realtime Waveform (Frame ID: ").append(frameId).append("):\n");

        // Align with Python: seq = ppg_led_data[0], data_num = ppg_led_data[1]
        int seq = data[4] & 0xFF;  // First byte after 4-byte offset
        int dataNum = data[5] & 0xFF;  // Second byte after 4-byte offset

        result.append("Sequence: ").append(seq).append(", Data Count: ").append(dataNum).append("\n");

        // Align with Python: 10-byte header + data_num * 30-byte data
        int expectedLength = 4 + 10 + dataNum * 30;  // 4-byte frame header + 10-byte data header + data
        if (data.length < expectedLength) {
            String error = "Incomplete realtime data. Expected: " + expectedLength + ", Got: " + data.length;
            Log.w(TAG, error);
            return result.append(error).toString();
        }

        // Read timestamp (align with Python: unix_ms = int.from_bytes(ppg_led_data[2:10], byteorder='little'))
        long frameTimestamp = readUInt64LE(data, 6);  // Offset 4-byte frame header + 2 bytes(seq+data_num)
        Log.e("TAG",String.valueOf(frameTimestamp));
        result.append("Frame Time: ").append(formatTimestamp(frameTimestamp)).append("\n");

        // Process real-time data points and update charts - align with Python logic
        int validPoints = 0;
        for (int i = 0; i < dataNum && i < 20; i++) { // Limit max processing count
            // Align with Python: offset = 10 + group_idx * 30
            int offset = 4 + 10 + i * 30;  // 4-byte frame header + 10-byte data header + i * 30

            if (offset + 30 <= data.length) {
                if (parseAndUpdateRealtimeDataPoint(data, offset)) {
                    validPoints++;
                }
            } else {
                Log.w(TAG, "Incomplete data point " + i);
                break;
            }
        }
        result.append("Updated ").append(validPoints).append(" data points to charts");
        Log.d(TAG, "Processed " + validPoints + " realtime data points");
        return result.toString();
    }

    /**
     * Parse single real-time data point and update charts
     */
    private static boolean parseAndUpdateRealtimeDataPoint(byte[] data, int offset) {
        try {
            // PPG data (first 12 bytes, 4 bytes each)
            long green = readUInt32LE(data, offset);
            long red = readUInt32LE(data, offset + 4);
            long ir = readUInt32LE(data, offset + 8);

            // Accelerometer data (12-17 bytes, 2 bytes signed each)
            short accX = readInt16LE(data, offset + 12);
            short accY = readInt16LE(data, offset + 14);
            short accZ = readInt16LE(data, offset + 16);

            // Gyroscope data (18-23 bytes, 2 bytes signed each)
            short gyroX = readInt16LE(data, offset + 18);
            short gyroY = readInt16LE(data, offset + 20);
            short gyroZ = readInt16LE(data, offset + 22);

            // Temperature data (24-29 bytes, 2 bytes signed each)
            short temp0 = readInt16LE(data, offset + 24);
            short temp1 = readInt16LE(data, offset + 26);
            short temp2 = readInt16LE(data, offset + 28);

            // Update real-time chart display
            updateRealtimeCharts(green, red, ir, accX, accY, accZ, gyroX, gyroY, gyroZ, temp0, temp1, temp2);

            Log.v(TAG, String.format("Realtime point: G:%d, R:%d, IR:%d, AccX:%d, AccY:%d, AccZ:%d, GyroX:%d, GyroY:%d, GyroZ:%d, T0:%d, T1:%d, T2:%d",
                    green, red, ir, accX, accY, accZ, gyroX, gyroY, gyroZ, temp0, temp1, temp2));
            recordLog(String.format("Realtime data point: Green=%d, Red=%d, IR=%d, AccX=%d, AccY=%d, AccZ=%d, GyroX=%d, GyroY=%d, GyroZ=%d, Temp0=%d, Temp1=%d, Temp2=%d",
                    green, red, ir, accX, accY, accZ, gyroX, gyroY, gyroZ, temp0, temp1, temp2));

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing realtime data point", e);
            return false;
        }
    }

    /**
     * Update real-time charts
     */
    private static void updateRealtimeCharts(long green, long red, long ir,
                                             short accX, short accY, short accZ,
                                             short gyroX, short gyroY, short gyroZ,
                                             short temp0, short temp1, short temp2) {
        try {
            // Update PPG data charts
            if (plotViewG != null) plotViewG.addValue((int)green);
            if (plotViewR != null) plotViewR.addValue((int)red);
            if (plotViewI != null) plotViewI.addValue((int)ir);

            // Update acceleration charts
            if (plotViewX != null) plotViewX.addValue(accX);
            if (plotViewY != null) plotViewY.addValue(accY);
            if (plotViewZ != null) plotViewZ.addValue(accZ);

            // New: Update gyroscope charts
            if (plotViewGyroX != null) plotViewGyroX.addValue(gyroX);
            if (plotViewGyroY != null) plotViewGyroY.addValue(gyroY);
            if (plotViewGyroZ != null) plotViewGyroZ.addValue(gyroZ);

            // New: Update temperature charts
            if (plotViewTemp0 != null) plotViewTemp0.addValue(temp0);
            if (plotViewTemp1 != null) plotViewTemp1.addValue(temp1);
            if (plotViewTemp2 != null) plotViewTemp2.addValue(temp2);

        } catch (Exception e) {
            Log.e(TAG, "Error updating realtime charts", e);
        }
    }

    /**
     * Handle standard waveform response
     */
    private static String handleStandardWaveformResponse(byte[] data, int frameId) {
        if (data.length < 6) {
            return "Invalid standard waveform response packet length";
        }

        StringBuilder result = new StringBuilder();
        result.append("Standard Waveform (Frame ID: ").append(frameId).append("):\n");

        int seq = data[4] & 0xFF;
        int dataNum = data[5] & 0xFF;

        result.append("Sequence: ").append(seq).append(", Data Count: ").append(dataNum).append("\n");

        int expectedLength = 4 + 10 + dataNum * 30;
        if (data.length < expectedLength) {
            String error = "Incomplete standard waveform. Expected: " + expectedLength + ", Got: " + data.length;
            Log.w(TAG, error);
            return result.append(error).toString();
        }

        long frameTimestamp = readUInt64LE(data, 6);
        Log.e("TAG",String.valueOf(frameTimestamp));
        result.append("Frame Time: ").append(formatTimestamp(frameTimestamp)).append("\n");

        // Process data points
        int validPoints = 0;
        for (int i = 0; i < dataNum; i++) {
            int offset = 4 + 10 + i * 30;
            if (offset + 30 <= data.length) {
                if (parseAndUpdateRealtimeDataPoint(data, offset)) {
                    validPoints++;
                }
            }
        }

        result.append("Processed ").append(validPoints).append(" standard waveform points");
        return result.toString();
    }

    /**
     * Handle progress response
     */
    private static String handleProgressResponse(byte[] data, int frameId) {
        if (data.length < 5) {
            return "Invalid progress response packet length";
        }

        int progress = data[4] & 0xFF;

        // If in exercise mode, notify exercise progress
        if (isExercising && exerciseStatusCallback != null) {
            int totalSegments = exerciseConfig.getTotalSegments();
            exerciseStatusCallback.onExerciseProgress(currentSegment, totalSegments, progress);
        }

        String result = "Progress (Frame ID: " + frameId + "): " + progress + "%";
        Log.i(TAG, result);
        return result;
    }

    /**
     * Format timestamp
     */
    private static String formatTimestamp(long timestampMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd.HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date(timestampMillis));
    }

    /**
     * Clear all chart data
     */
    public static void clearAllCharts() {
        try {
            if (plotViewG != null) plotViewG.clearPlot();
            if (plotViewI != null) plotViewI.clearPlot();
            if (plotViewR != null) plotViewR.clearPlot();
            if (plotViewX != null) plotViewX.clearPlot();
            if (plotViewY != null) plotViewY.clearPlot();
            if (plotViewZ != null) plotViewZ.clearPlot();
            Log.d(TAG, "All charts cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing charts", e);
        }
    }

    /**
     * Get whether there are valid chart connections
     */
    public static boolean hasValidCharts() {
        return plotViewG != null || plotViewI != null || plotViewR != null ||
                plotViewX != null || plotViewY != null || plotViewZ != null;
    }
}
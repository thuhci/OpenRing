package com.tsinghua.sample.utils;

import android.util.Log;
import com.tsinghua.sample.PlotView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.*;

public class NotificationHandler {
    private static final String TAG = "NotificationHandler";

    // 实时数据显示的PlotView
    private static PlotView plotViewG, plotViewI;
    private static PlotView plotViewR, plotViewX;
    private static PlotView plotViewY, plotViewZ;

    // 文件操作回调接口
    public interface FileResponseCallback {
        void onFileListReceived(byte[] data);
        void onFileDataReceived(byte[] data);
    }

    // 时间操作回调接口（包含校准和更新）
    public interface TimeSyncCallback {
        void onTimeSyncResponse(byte[] data);
        void onTimeUpdateResponse(byte[] data);
    }

    // 设备指令发送回调接口
    public interface DeviceCommandCallback {
        void sendCommand(byte[] commandData);
        default void onMeasurementStarted() {}
        default void onMeasurementStopped() {}
        default void onExerciseStarted(int duration, int segmentTime) {}
        default void onExerciseStopped() {}
    }

    // 运动状态回调接口
    public interface ExerciseStatusCallback {
        void onExerciseProgress(int currentSegment, int totalSegments, int progressPercent);
        void onSegmentCompleted(int segmentNumber, int totalSegments);
        void onExerciseCompleted();
    }

    private static FileResponseCallback fileResponseCallback;
    private static TimeSyncCallback timeSyncCallback;
    private static DeviceCommandCallback deviceCommandCallback;
    private static ExerciseStatusCallback exerciseStatusCallback;

    // 测量参数配置类
    public static class MeasurementConfig {
        public int collectTime = 30;        // 采集时间，默认30秒
        public int collectFreq = 25;        // 采集频率，默认25Hz (保留)
        public int ledGreenCurrent = 20;    // green LED电流，默认20 (392uA * 20 = 7.84mA)
        public int ledIrCurrent = 20;       // IR LED电流，默认20
        public int ledRedCurrent = 20;      // red LED电流，默认20
        public boolean progressResponse = true;   // 进度响应，默认上传
        public boolean waveformResponse = true;   // 波形响应，默认上传

        // 电流范围验证 (0-50档位)
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

    // 运动配置类
    public static class ExerciseConfig {
        public int totalDuration = 300;     // 总运动时长，默认5分钟（秒）
        public int segmentTime = 60;        // 片段时间，默认60秒
        public boolean autoStart = false;   // 是否自动开始
        public boolean enableRest = true;   // 是否启用休息间隔
        public int restTime = 30;           // 休息时间，默认30秒

        public int getTotalSegments() {
            return (totalDuration + segmentTime - 1) / segmentTime; // 向上取整
        }

        public String getExerciseDescription() {
            return String.format("总时长: %d分%d秒, 片段: %d秒, 共%d段",
                    totalDuration / 60, totalDuration % 60, segmentTime, getTotalSegments());
        }
    }

    // 当前状态跟踪
    private static boolean isMeasuring = false;
    private static boolean isExercising = false;
    private static boolean isMeasurementOngoing = false; // 新增：区分测量是否正在进行
    private static int currentFrameId = 1;
    private static MeasurementConfig measurementConfig = new MeasurementConfig();
    private static ExerciseConfig exerciseConfig = new ExerciseConfig();
    private static Timer exerciseTimer;
    private static Timer measurementTimer; // 新增：测量计时器
    private static int currentSegment = 0;

    // 设置PlotView的方法
    public static void setPlotViewG(PlotView chartView) { plotViewG = chartView; }
    public static void setPlotViewI(PlotView chartView) { plotViewI = chartView; }
    public static void setPlotViewR(PlotView chartView) { plotViewR = chartView; }
    public static void setPlotViewX(PlotView chartView) { plotViewX = chartView; }
    public static void setPlotViewY(PlotView chartView) { plotViewY = chartView; }
    public static void setPlotViewZ(PlotView chartView) { plotViewZ = chartView; }

    public interface LogRecorder {
        void recordLog(String message);
    }

    private static LogRecorder logRecorder;

    // 添加设置日志记录器的方法
    public static void setLogRecorder(LogRecorder recorder) {
        logRecorder = recorder;
        if (recorder != null) {
            recordLog("NotificationHandler日志记录器已连接到RingViewHolder");
        }
    }

    // 添加内部recordLog方法
    private static void recordLog(String message) {
        // 输出到Android Log（保持原有功能）
        Log.d(TAG, message);

        // 如果设置了外部日志记录器，调用外部recordLog
        if (logRecorder != null) {
            logRecorder.recordLog("[NH] " + message);
        }
    }

    // 设置回调方法
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

    // 获取和设置测量配置
    public static MeasurementConfig getMeasurementConfig() {
        return measurementConfig;
    }

    public static void setMeasurementConfig(MeasurementConfig config) {
        measurementConfig = config;
        Log.d(TAG, "Measurement config updated: " + config.getCurrentDescription());
    }

    // 新增：简化的设置测量时间方法
    public static void setMeasurementTime(int timeSeconds) {
        measurementConfig.collectTime = Math.max(1, Math.min(3600, timeSeconds));
        Log.d(TAG, "Measurement time set to: " + measurementConfig.collectTime + " seconds");
    }

    // 获取和设置运动配置
    public static ExerciseConfig getExerciseConfig() {
        return exerciseConfig;
    }

    public static void setExerciseConfig(ExerciseConfig config) {
        exerciseConfig = config;
        Log.d(TAG, "Exercise config updated: " + config.getExerciseDescription());
    }

    // 新增：简化的设置运动参数方法
    public static void setExerciseParams(int totalDurationSeconds, int segmentDurationSeconds) {
        exerciseConfig.totalDuration = Math.max(60, Math.min(86400, totalDurationSeconds));
        exerciseConfig.segmentTime = Math.max(30, Math.min(exerciseConfig.totalDuration, segmentDurationSeconds));
        Log.d(TAG, "Exercise params set: Total=" + exerciseConfig.totalDuration + "s, Segment=" + exerciseConfig.segmentTime + "s");
    }

    // 开始主动测量
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
            // 生成主动测量指令
            byte[] command = buildActiveMeasurementCommand(config);
            deviceCommandCallback.sendCommand(command);

            isMeasuring = true;
            isMeasurementOngoing = true; // 新增：标记测量正在进行
            deviceCommandCallback.onMeasurementStarted();

            // 新增：启动测量监控计时器（但不自动停止）
            startMeasurementMonitor(config.collectTime);

            recordLog(String.format("【开始主动测量】%s, 持续时间: %d秒",
                    config.getCurrentDescription(), config.collectTime));
            Log.i(TAG, String.format("Started active measurement: %s, Duration: %ds",
                    config.getCurrentDescription(), config.collectTime));
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start active measurement", e);
            return false;
        }
    }

    // 新增：启动测量监控计时器
    private static void startMeasurementMonitor(int durationSeconds) {
        if (measurementTimer != null) {
            measurementTimer.cancel();
        }

        measurementTimer = new Timer();
        measurementTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                // 测量时间到达，但不自动停止，只记录日志
                recordLog(String.format("【测量时间到达】预设时间 %d 秒已完成，测量仍在继续", durationSeconds));
                recordLog("可手动点击'停止采集'按钮来结束测量");
                Log.i(TAG, "Measurement duration completed, but measurement continues until manual stop");

                // 可以在这里发送通知给UI，告知用户测量时间已到
                // 但不改变isMeasuring状态，让用户手动控制停止
            }
        }, durationSeconds * 1000);
    }

    // 修改：停止测量方法，使用新的停止采集指令
    public static boolean stopMeasurement() {
        if (!isMeasuring) {
            Log.w(TAG, "No measurement in progress");
            recordLog("【停止测量失败】当前没有正在进行的测量");
            return false;
        }

        if (deviceCommandCallback == null) {
            Log.e(TAG, "Device command callback not set");
            return false;
        }

        try {
            // 使用新的停止采集指令 (Cmd=0x3C, Subcmd=0x04)
            byte[] command = buildStopCollectionCommand();
            deviceCommandCallback.sendCommand(command);

            // 停止测量监控计时器
            if (measurementTimer != null) {
                measurementTimer.cancel();
                measurementTimer = null;
            }

            isMeasuring = false;
            isMeasurementOngoing = false;
            deviceCommandCallback.onMeasurementStopped();

            recordLog("【手动停止测量】发送停止采集指令");
            Log.i(TAG, "Stopped measurement manually");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to stop measurement", e);
            recordLog("【停止测量失败】" + e.getMessage());
            return false;
        }
    }

    // 开始运动
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

            // 发送运动开始指令
            byte[] command = buildStartExerciseCommand(config);
            deviceCommandCallback.sendCommand(command);

            // 启动运动计时器
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

    // 结束运动
    public static boolean stopExercise() {
        if (!isExercising) {
            Log.w(TAG, "No exercise in progress");
            return false;
        }

        try {
            // 发送运动停止指令
            if (deviceCommandCallback != null) {
                byte[] command = buildStopExerciseCommand();
                deviceCommandCallback.sendCommand(command);
            }

            // 停止计时器
            if (exerciseTimer != null) {
                exerciseTimer.cancel();
                exerciseTimer = null;
            }

            // 停止当前测量
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

    // 启动运动计时器
    private static void startExerciseTimer(ExerciseConfig config) {
        exerciseTimer = new Timer();
        final int totalSegments = config.getTotalSegments();

        exerciseTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                currentSegment++;

                if (currentSegment <= totalSegments) {
                    // 通知片段完成
                    if (exerciseStatusCallback != null) {
                        int progress = (currentSegment * 100) / totalSegments;
                        exerciseStatusCallback.onSegmentCompleted(currentSegment, totalSegments);
                        exerciseStatusCallback.onExerciseProgress(currentSegment, totalSegments, progress);
                    }

                    // 如果不是最后一段，开始下一段测量
                    if (currentSegment < totalSegments) {
                        if (config.enableRest && config.restTime > 0) {
                            // 休息间隔后再开始下一段
                            Timer restTimer = new Timer();
                            restTimer.schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    startNextSegment(config);
                                }
                            }, config.restTime * 1000);
                        } else {
                            // 直接开始下一段
                            startNextSegment(config);
                        }
                    } else {
                        // 运动完成
                        completeExercise();
                    }
                } else {
                    // 运动完成
                    completeExercise();
                }
            }
        }, config.segmentTime * 1000, config.segmentTime * 1000);
    }

    // 开始下一段运动
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

    // 完成运动
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

    // 构建主动测量指令
    private static byte[] buildActiveMeasurementCommand(MeasurementConfig config) {
        // 指令格式: Frame Type(1) + Frame ID(1) + Cmd(1) + Subcmd(1) + Data(7)
        byte[] command = new byte[11];

        command[0] = 0x00;  // Frame Type
        command[1] = (byte)(currentFrameId++ & 0xFF);  // Frame ID
        command[2] = 0x3C;  // Cmd
        command[3] = 0x00;  // Subcmd

        // Data部分 (7字节)
        command[4] = (byte)(config.collectTime & 0xFF);  // 采集时间
        command[5] = (byte)(config.collectFreq & 0xFF);  // 采集频率
        command[6] = (byte)(config.ledGreenCurrent & 0xFF);  // green LED电流
        command[7] = (byte)(config.ledIrCurrent & 0xFF);    // IR LED电流
        command[8] = (byte)(config.ledRedCurrent & 0xFF);   // red LED电流
        command[9] = (byte)(config.progressResponse ? 1 : 0);  // 进度响应
        command[10] = (byte)(config.waveformResponse ? 1 : 0); // 波形响应

        Log.d(TAG, String.format("Built measurement command: Time=%ds, Green=%d, IR=%d, Red=%d",
                config.collectTime, config.ledGreenCurrent, config.ledIrCurrent, config.ledRedCurrent));

        return command;
    }

    // 新增：构建停止采集指令 (基于文档中的新指令格式)
    private static byte[] buildStopCollectionCommand() {
        // 心率停止采集指令格式: Frame Type(1) + Frame ID(1) + Cmd(1) + Subcmd(1)
        // 请求指令：00[FrameID]3C04
        byte[] command = new byte[4];

        command[0] = 0x00;  // Frame Type
        command[1] = (byte)(currentFrameId++ & 0xFF);  // Frame ID
        command[2] = 0x3C;  // Cmd (心率相关指令)
        command[3] = 0x04;  // Subcmd (停止采集)

        recordLog(String.format("构建停止采集指令: %02X%02X%02X%02X",
                command[0], command[1], command[2], command[3]));
        Log.d(TAG, "Built stop collection command (0x3C04)");
        return command;
    }

    // 构建停止测量指令（保留原有的，作为备用）
    private static byte[] buildStopMeasurementCommand() {
        // 指令格式: Frame Type(1) + Frame ID(1) + Cmd(1) + Subcmd(1)
        byte[] command = new byte[4];

        command[0] = 0x00;  // Frame Type
        command[1] = (byte)(currentFrameId++ & 0xFF);  // Frame ID
        command[2] = 0x3C;  // Cmd
        command[3] = 0x02;  // Subcmd (原有的停止指令)

        Log.d(TAG, "Built stop measurement command (0x3C02)");
        return command;
    }

    // 新增：构建开始运动指令
    private static byte[] buildStartExerciseCommand(ExerciseConfig config) {
        // 指令格式: Frame Type(1) + Frame ID(1) + Cmd(1) + Subcmd(1) + Data(10)
        byte[] command = new byte[14];

        command[0] = 0x00;  // Frame Type
        command[1] = (byte)(68);  // Frame ID
        command[2] = 0x38;  // Cmd (运动指令)
        command[3] = 0x01;  // Subcmd (开始运动)

        // Data部分: sport_mode(2字节) + time(4字节) + slice_storage_time(4字节)
        // sport_mode (2字节)
        int sportMode = 1;
        command[4] = (byte)(sportMode & 0xFF);
        command[5] = (byte)((sportMode >> 8) & 0xFF);

        // time (4字节) - 总时长，小端序
        command[6] = (byte)(config.totalDuration & 0xFF);
        command[7] = (byte)((config.totalDuration >> 8) & 0xFF);
        command[8] = (byte)((config.totalDuration >> 16) & 0xFF);
        command[9] = (byte)((config.totalDuration >> 24) & 0xFF);

        // slice_storage_time (4字节) - 片段时长，小端序
        command[10] = (byte)(config.segmentTime & 0xFF);
        command[11] = (byte)((config.segmentTime >> 8) & 0xFF);
        command[12] = (byte)((config.segmentTime >> 16) & 0xFF);
        command[13] = (byte)((config.segmentTime >> 24) & 0xFF);

        Log.d(TAG, String.format("Built start exercise command: SportMode=%d, Total=%ds, Segment=%ds",
                sportMode, config.totalDuration, config.segmentTime));

        return command;
    }

    // 新增：构建停止运动指令
    private static byte[] buildStopExerciseCommand() {
        // 指令格式: Frame Type(1) + Frame ID(1) + Cmd(1) + Subcmd(1)
        byte[] command = new byte[4];

        command[0] = 0x00;  // Frame Type
        command[1] = (byte)(68);  // Frame ID
        command[2] = 0x38;  // Cmd (运动指令)
        command[3] = 0x03;  // Subcmd (停止运动)

        Log.d(TAG, "Built stop exercise command");
        return command;
    }

    // 获取当前状态
    public static boolean isMeasuring() {
        return isMeasuring;
    }

    public static boolean isExercising() {
        return isExercising;
    }

    // 新增：获取测量是否正在进行的状态
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
     * 小端序读取4字节无符号整数
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
     * 小端序读取2字节有符号整数
     */
    private static short readInt16LE(byte[] data, int offset) {
        if (offset + 2 > data.length) {
            throw new IndexOutOfBoundsException("Not enough data to read int16");
        }
        return (short)(((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF));
    }

    /**
     * 小端序读取8字节时间戳
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
     * 主要数据处理入口
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

        // 时间校准处理 (Cmd = 0x10)
        if (cmd == 0x10) {
            return handleTimeSyncOperations(data, frameId, subcmd);
        }
        // 文件操作处理 (Cmd = 0x36)
        else if (cmd == 0x36) {
            return handleFileOperations(data, frameId, subcmd);
        }
        // 运动指令处理 (Cmd = 0x38)
        else if (cmd == 0x38) {
            return handleExerciseOperations(data, frameId, subcmd);
        }
        // 实时数据处理 (Cmd = 0x3C)
        else if (cmd == 0x3C) {
            return handleRealtimeData(data, frameId, subcmd);
        }
        // 其他命令
        else {
            String result = "Unknown command: 0x" + String.format("%02X", cmd);
            Log.w(TAG, result);
            return result;
        }
    }

    /**
     * 处理运动操作相关的响应 (Cmd = 0x38)
     */
    private static String handleExerciseOperations(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling exercise operation: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x01: // 开始运动响应
                return handleStartExerciseResponse(data, frameId);

            case 0x03: // 停止运动响应
                return handleStopExerciseResponse(data, frameId);

            default:
                String result = "Unknown exercise operation subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * 处理开始运动响应
     */
    private static String handleStartExerciseResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing start exercise response");

        try {
            String result = String.format("Start Exercise Response (Frame ID: %d): Command sent successfully", frameId);
            recordLog("【开始运动响应】指令发送成功");
            Log.i(TAG, result);
            return result;

        } catch (Exception e) {
            String result = "Error processing start exercise response: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * 处理停止运动响应
     */
    private static String handleStopExerciseResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing stop exercise response");

        try {
            String result = String.format("Stop Exercise Response (Frame ID: %d): Command sent successfully", frameId);
            recordLog("【停止运动响应】指令发送成功");
            Log.i(TAG, result);
            return result;

        } catch (Exception e) {
            String result = "Error processing stop exercise response: " + e.getMessage();
            Log.e(TAG, result, e);
            return result;
        }
    }

    /**
     * 处理时间操作相关的响应 (Cmd = 0x10)
     */
    private static String handleTimeSyncOperations(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling time operation: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x00: // 时间更新响应
                return handleTimeUpdateResponse(data, frameId);

            case 0x02: // 时间校准响应
                return handleTimeSyncResponse(data, frameId);

            default:
                String result = "Unknown time operation subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * 处理时间更新响应
     */
    private static String handleTimeUpdateResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing time update response");

        try {
            // 通知回调处理详细解析
            if (timeSyncCallback != null) {
                timeSyncCallback.onTimeUpdateResponse(data);
            } else {
                Log.w(TAG, "Time sync callback is null");
            }

            // 返回简单的状态信息
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
     * 处理时间校准响应
     */
    private static String handleTimeSyncResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing time sync response");

        try {
            // 通知回调处理详细解析
            if (timeSyncCallback != null) {
                timeSyncCallback.onTimeSyncResponse(data);
            } else {
                Log.w(TAG, "Time sync callback is null");
            }

            // 返回简单的状态信息 - 使用小端序读取
            if (data.length >= 28) { // 4字节帧头 + 24字节数据
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
     * 处理文件操作相关的响应 (Cmd = 0x36)
     */
    private static String handleFileOperations(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling file operation: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x10: // 文件列表响应
                return handleFileListResponse(data, frameId);

            case 0x11: // 文件数据响应
                return handleFileDataResponse(data, frameId);

            default:
                String result = "Unknown file operation subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * 处理文件列表响应
     */
    private static String handleFileListResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing file list response");

        try {
            // 通知回调处理详细解析
            if (fileResponseCallback != null) {
                fileResponseCallback.onFileListReceived(data);
            } else {
                Log.w(TAG, "File response callback is null");
            }

            // 返回简单的状态信息 - 使用小端序读取
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
     * 处理文件数据响应
     */
    private static String handleFileDataResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing file data response");

        try {
            // 通知回调处理详细解析和保存
            if (fileResponseCallback != null) {
                fileResponseCallback.onFileDataReceived(data);
            } else {
                Log.w(TAG, "File response callback is null");
            }

            // 返回简单的状态信息 - 使用小端序读取
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
     * 处理实时数据 (Cmd = 0x3C)
     */
    private static String handleRealtimeData(byte[] data, int frameId, int subcmd) {
        Log.d(TAG, String.format("Handling realtime data: Subcmd=0x%02X", subcmd));

        switch (subcmd) {
            case 0x01: // 实时数据或时间响应
                if (data.length == 13) {
                    // 时间响应包
                    return handleTimeResponse(data, frameId);
                } else if (data.length > 14) {
                    // 实时波形数据包
                    return handleRealtimeWaveformData(data, frameId);
                } else {
                    String result = "Invalid packet length for subcmd 0x01: " + data.length;
                    Log.w(TAG, result);
                    return result;
                }

            case 0x02: // 标准波形响应包
                return handleStandardWaveformResponse(data, frameId);

            case 0x03: // 停止响应 (修改：处理新的停止采集响应)
                return handleStopCollectionResponse(data, frameId);

            case 0x04: // 新增：处理停止采集响应 (基于文档中的响应格式)
                return handleStopCollectionResponse(data, frameId);

            case 0xFF: // 进度响应包
                return handleProgressResponse(data, frameId);

            default:
                String result = "Unknown realtime subcmd: 0x" + String.format("%02X", subcmd);
                Log.w(TAG, result);
                return result;
        }
    }

    /**
     * 修改：处理停止采集响应 (支持新的Subcmd=0x04)
     */
    private static String handleStopCollectionResponse(byte[] data, int frameId) {
        Log.d(TAG, "Processing stop collection response");

        // 更新测量状态
        isMeasuring = false;
        isMeasurementOngoing = false;

        // 停止测量监控计时器
        if (measurementTimer != null) {
            measurementTimer.cancel();
            measurementTimer = null;
        }

        if (deviceCommandCallback != null) {
            deviceCommandCallback.onMeasurementStopped();
        }

        String result = String.format("Stop Collection Response (Frame ID: %d): Measurement stopped successfully", frameId);
        recordLog("【停止采集响应】测量已成功停止");
        Log.i(TAG, result);
        return result;
    }

    /**
     * 处理停止响应 (保留原有方法作为兼容)
     */
    private static String handleStopResponse(byte[] data, int frameId) {
        return handleStopCollectionResponse(data, frameId);
    }

    /**
     * 处理时间响应
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
     * 处理实时波形数据并更新图表
     */
    private static String handleRealtimeWaveformData(byte[] data, int frameId) {
        if (data.length < 14) {
            return "Invalid realtime waveform packet length";
        }

        StringBuilder result = new StringBuilder();
        result.append("Realtime Waveform (Frame ID: ").append(frameId).append("):\n");

        // 对齐Python: seq = ppg_led_data[0], data_num = ppg_led_data[1]
        int seq = data[4] & 0xFF;  // 偏移4字节后的第一个字节
        int dataNum = data[5] & 0xFF;  // 偏移4字节后的第二个字节

        result.append("Sequence: ").append(seq).append(", Data Count: ").append(dataNum).append("\n");

        // 对齐Python: 10字节头部 + data_num * 30字节数据
        int expectedLength = 4 + 10 + dataNum * 30;  // 4字节帧头 + 10字节数据头 + 数据
        if (data.length < expectedLength) {
            String error = "Incomplete realtime data. Expected: " + expectedLength + ", Got: " + data.length;
            Log.w(TAG, error);
            return result.append(error).toString();
        }

        // 读取时间戳 (对齐Python: unix_ms = int.from_bytes(ppg_led_data[2:10], byteorder='little'))
        long frameTimestamp = readUInt64LE(data, 6);  // 偏移4字节帧头 + 2字节(seq+data_num)
        Log.e("TAG",String.valueOf(frameTimestamp));
        result.append("Frame Time: ").append(formatTimestamp(frameTimestamp)).append("\n");

        // 处理实时数据点并更新图表 - 对齐Python逻辑
        int validPoints = 0;
        for (int i = 0; i < dataNum && i < 20; i++) { // 限制最大处理数量
            // 对齐Python: offset = 10 + group_idx * 30
            int offset = 4 + 10 + i * 30;  // 4字节帧头 + 10字节数据头 + i * 30

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
     * 解析单个实时数据点并更新图表
     */
    private static boolean parseAndUpdateRealtimeDataPoint(byte[] data, int offset) {
        try {
            // PPG数据 (前12字节，每个4字节)
            long green = readUInt32LE(data, offset);
            long red = readUInt32LE(data, offset + 4);
            long ir = readUInt32LE(data, offset + 8);

            // 加速度计数据 (12-17字节，每个2字节有符号)
            short accX = readInt16LE(data, offset + 12);
            short accY = readInt16LE(data, offset + 14);
            short accZ = readInt16LE(data, offset + 16);

            // 陀螺仪数据 (18-23字节，每个2字节有符号)
            short gyroX = readInt16LE(data, offset + 18);
            short gyroY = readInt16LE(data, offset + 20);
            short gyroZ = readInt16LE(data, offset + 22);

            // 温度数据 (24-29字节，每个2字节有符号)
            short temp0 = readInt16LE(data, offset + 24);
            short temp1 = readInt16LE(data, offset + 26);
            short temp2 = readInt16LE(data, offset + 28);

            // 更新实时图表显示
            updateRealtimeCharts(green, red, ir, accX, accY, accZ);

            Log.v(TAG, String.format("Realtime point: G:%d, R:%d, IR:%d, AccX:%d, AccY:%d, AccZ:%d, GyroX:%d, GyroY:%d, GyroZ:%d, T0:%d, T1:%d, T2:%d",
                    green, red, ir, accX, accY, accZ, gyroX, gyroY, gyroZ, temp0, temp1, temp2));
            recordLog(String.format("实时数据点: Green=%d, Red=%d, IR=%d, AccX=%d, AccY=%d, AccZ=%d, GyroX=%d, GyroY=%d, GyroZ=%d, Temp0=%d, Temp1=%d, Temp2=%d",
                    green, red, ir, accX, accY, accZ, gyroX, gyroY, gyroZ, temp0, temp1, temp2));

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing realtime data point", e);
            return false;
        }
    }

    /**
     * 更新实时图表
     */
    private static void updateRealtimeCharts(long green, long red, long ir, short accX, short accY, short accZ) {
        try {
            // 更新PPG数据图表
            if (plotViewG != null) plotViewG.addValue((int)green);
            if (plotViewR != null) plotViewR.addValue((int)red);
            if (plotViewI != null) plotViewI.addValue((int)ir);

            // 更新加速度图表
            if (plotViewX != null) plotViewX.addValue(accX);
            if (plotViewY != null) plotViewY.addValue(accY);
            if (plotViewZ != null) plotViewZ.addValue(accZ);

        } catch (Exception e) {
            Log.e(TAG, "Error updating realtime charts", e);
        }
    }

    /**
     * 处理标准波形响应
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

        // 处理数据点
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
     * 处理进度响应
     */
    private static String handleProgressResponse(byte[] data, int frameId) {
        if (data.length < 5) {
            return "Invalid progress response packet length";
        }

        int progress = data[4] & 0xFF;

        // 如果是运动模式，通知运动进度
        if (isExercising && exerciseStatusCallback != null) {
            int totalSegments = exerciseConfig.getTotalSegments();
            exerciseStatusCallback.onExerciseProgress(currentSegment, totalSegments, progress);
        }

        String result = "Progress (Frame ID: " + frameId + "): " + progress + "%";
        Log.i(TAG, result);
        return result;
    }

    /**
     * 格式化时间戳
     */
    private static String formatTimestamp(long timestampMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd.HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date(timestampMillis));
    }

    /**
     * 清空所有图表数据
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
     * 获取当前是否有有效的图表连接
     */
    public static boolean hasValidCharts() {
        return plotViewG != null || plotViewI != null || plotViewR != null ||
                plotViewX != null || plotViewY != null || plotViewZ != null;
    }
}
package com.tsinghua.sample.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.lm.sdk.LmAPI;
import com.lm.sdk.inter.IResponseListener;
import com.lm.sdk.inter.ICustomizeCmdListener;
import com.lm.sdk.mode.SystemControlBean;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.sample.R;
import com.tsinghua.sample.PlotView;
import com.tsinghua.sample.utils.NotificationHandler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements IResponseListener {

    // UI组件
    private TextView statusText;
    private TextView ringIdText;
    private TextView macAddressText;
    private TextView statusIndicator;
    private TextView batteryText;
    private ImageView connectionIcon;
    private Button scanButton;
    private Button connectButton;
    private Button updateTimeButton;
    private Button calibrateButton;

    private Button getFileListButton;
    private Button downloadSelectedButton;
    private EditText totalDurationInput;
    private EditText segmentDurationInput;
    private TextView fileListStatus;
    private LinearLayout fileListContainer;
    private BottomNavigationView bottomNavigation;

    // 状态变量
    private boolean isConnected = false;
    private boolean isRecording = false;
    private boolean isOfflineRecording = false;
    private int connectionStatus = 0; // 蓝牙连接状态
    private int currentTab = 0; // 0: Dashboard, 1: Online, 2: Offline, 3: Logs
    private Handler mainHandler;
    private Random random = new Random();

    // 设备信息
    private String deviceName = "";
    private String macAddress = "";
    private String version = "";
    private int batteryLevel = 0;

    // 时间同步相关
    private boolean isTimeUpdating = false;
    private boolean isTimeSyncing = false;
    private int timeUpdateFrameId = 0;
    private int timeSyncFrameId = 0;
    private long timeSyncRequestTime = 0;

    // 在线测量相关
    private EditText measurementTimeInput;
    private Button startMeasurementButton;
    private Button stopMeasurementButton;
    private TextView measurementStatusText;
    private PlotView plotViewG, plotViewI, plotViewR;
    private PlotView plotViewX, plotViewY, plotViewZ;
    private PlotView plotViewGyroX, plotViewGyroY, plotViewGyroZ;
    private PlotView plotViewTemp0, plotViewTemp1, plotViewTemp2;
    private boolean isMeasuring = false;

    // 文件操作相关
    private List<FileInfo> fileList = new ArrayList<>();
    private List<FileInfo> selectedFiles = new ArrayList<>();
    private boolean isDownloadingFiles = false;
    private int currentDownloadIndex = 0;

    // 运动控制相关
    private EditText exerciseTotalDurationInput;
    private EditText exerciseSegmentDurationInput;
    private Button startExerciseButton;
    private Button stopExerciseButton;
    private TextView exerciseStatusText;
    private boolean isExercising = false;

    // 日志记录相关
    private Button startLogRecordingButton;
    private Button stopLogRecordingButton;
    private TextView logStatusText;
    private TextView logDisplayText;
    private BufferedWriter logWriter;
    private boolean isLogRecording = false;

    // 测量相关
    private Timer measurementTimer;
    private int measurementElapsed = 0;
    private int totalMeasurementTime = 0;

    // 文件信息类
    public static class FileInfo {
        public String fileName;
        public int fileSize;
        public int fileType;
        public String userId;
        public String timestamp;
        public boolean isSelected = false;

        public FileInfo(String fileName, int fileSize) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            parseFileName();
        }

        private void parseFileName() {
            String[] parts = fileName.replace(".bin", "").split("_");
            if (parts.length >= 3) {
                this.userId = parts[0];
                this.timestamp = convertUTCToChinaTime(parts[1]+parts[2]+parts[3]);
                this.fileType = Integer.parseInt(parts[parts.length-1]);
            }
        }

        public String getFileTypeDescription() {
            switch (fileType) {
                case 1: return "三轴数据";
                case 2: return "六轴数据";
                case 3: return "PPG数据红外+红色+三轴(spo2)";
                case 4: return "PPG数据绿色";
                case 5: return "PPG数据红外";
                case 6: return "温度数据红外";
                case 7: return "红外+红色+绿色+温度+三轴";
                case 8: return "PPG数据绿色+三轴(hr)";
                default: return "未知类型";
            }
        }

        public String getFormattedSize() {
            if (fileSize < 1024) {
                return fileSize + " B";
            } else if (fileSize < 1024 * 1024) {
                return String.format("%.1f KB", fileSize / 1024.0);
            } else {
                return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
            }
        }

        private static String convertUTCToChinaTime(String utcTimeStr) {
            try {
                if (utcTimeStr == null || utcTimeStr.length() < 15) {
                    return utcTimeStr;
                }

                String dateStr = utcTimeStr.substring(0, 8);
                String timeStr = utcTimeStr.substring(9);
                String year = dateStr.substring(0, 4);
                String month = dateStr.substring(4, 6);
                String day = dateStr.substring(6, 8);
                String fullUtcTimeStr = String.format("%s-%s-%s %s", year, month, day, timeStr);

                SimpleDateFormat utcFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date utcDate = utcFormat.parse(fullUtcTimeStr);

                SimpleDateFormat chinaFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                chinaFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
                return chinaFormat.format(utcDate);

            } catch (Exception e) {
                return utcTimeStr;
            }
        }
    }

    // 自定义指令监听器
    private ICustomizeCmdListener customizeCmdListener = new ICustomizeCmdListener() {
        @Override
        public void cmdData(String responseData) {
            byte[] responseBytes = hexStringToByteArray(responseData);
            recordLog("收到自定义指令响应: " + responseData);
            handleCustomizeResponse(responseBytes);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());

        // 初始化SDK
        LmAPI.init(getApplication());
        LmAPI.setDebug(true);
        LmAPI.addWLSCmdListener(this, this);

        initializeViews();
        setupBottomNavigation();
        setupClickListeners();
        setupNotificationHandler();
        loadDeviceInfo();

        // 显示Dashboard页面
        showDashboard();
    }

    private void initializeViews() {
        // 状态显示
        statusText = findViewById(R.id.statusText);
        ringIdText = findViewById(R.id.ringIdText);
        macAddressText = findViewById(R.id.macAddressText);
        statusIndicator = findViewById(R.id.statusIndicator);
        batteryText = findViewById(R.id.batteryText);
        connectionIcon = findViewById(R.id.connectionIcon);

        // 按钮
        scanButton = findViewById(R.id.scanButton);
        connectButton = findViewById(R.id.connectButton);
        updateTimeButton = findViewById(R.id.updateTimeButton);
        calibrateButton = findViewById(R.id.calibrateButton);
        getFileListButton = findViewById(R.id.getFileListButton);
        downloadSelectedButton = findViewById(R.id.downloadSelectedButton);

        // 输入框
        // 文件列表
        fileListStatus = findViewById(R.id.fileListStatus);
        fileListContainer = findViewById(R.id.fileListContainer);

        // 在线测量相关
        measurementTimeInput = findViewById(R.id.measurementTimeInput);
        startMeasurementButton = findViewById(R.id.startMeasurementButton);
        stopMeasurementButton = findViewById(R.id.stopMeasurementButton);
        measurementStatusText = findViewById(R.id.measurementStatusText);

        // 运动控制相关
        exerciseTotalDurationInput = findViewById(R.id.exerciseTotalDurationInput);
        exerciseSegmentDurationInput = findViewById(R.id.exerciseSegmentDurationInput);
        startExerciseButton = findViewById(R.id.startExerciseButton);
        stopExerciseButton = findViewById(R.id.stopExerciseButton);
        exerciseStatusText = findViewById(R.id.exerciseStatusText);

        // 日志记录相关
        startLogRecordingButton = findViewById(R.id.startLogRecordingButton);
        stopLogRecordingButton = findViewById(R.id.stopLogRecordingButton);
        logStatusText = findViewById(R.id.logStatusText);
        logDisplayText = findViewById(R.id.logDisplayText);

        // 初始化PlotView
        plotViewG = findViewById(R.id.plotViewG);
        plotViewI = findViewById(R.id.plotViewI);
        plotViewR = findViewById(R.id.plotViewR);
        plotViewX = findViewById(R.id.plotViewX);
        plotViewY = findViewById(R.id.plotViewY);
        plotViewZ = findViewById(R.id.plotViewZ);
        plotViewGyroX = findViewById(R.id.plotViewGyroX);
        plotViewGyroY = findViewById(R.id.plotViewGyroY);
        plotViewGyroZ = findViewById(R.id.plotViewGyroZ);
        plotViewTemp0 = findViewById(R.id.plotViewTemp0);
        plotViewTemp1 = findViewById(R.id.plotViewTemp1);
        plotViewTemp2 = findViewById(R.id.plotViewTemp2);

        // 设置PlotView颜色
        setupPlotViewColors();

        // 底部导航
        bottomNavigation = findViewById(R.id.bottomNavigation);

        // 设置默认值
        setDefaultValues();

        // 初始化状态
        updateConnectionStatus(false);
        updateMeasurementUI(false);
        updateExerciseUI(false);
        updateLogRecordingUI(false);
    }

    private void setupPlotViewColors() {
        if (plotViewG != null) plotViewG.setPlotColor(Color.parseColor("#4CAF50"));
        if (plotViewI != null) plotViewI.setPlotColor(Color.parseColor("#FF9800"));
        if (plotViewR != null) plotViewR.setPlotColor(Color.parseColor("#F44336"));
        if (plotViewX != null) plotViewX.setPlotColor(Color.parseColor("#2196F3"));
        if (plotViewY != null) plotViewY.setPlotColor(Color.parseColor("#9C27B0"));
        if (plotViewZ != null) plotViewZ.setPlotColor(Color.parseColor("#00BCD4"));
        if (plotViewGyroX != null) plotViewGyroX.setPlotColor(Color.parseColor("#FF6B6B"));
        if (plotViewGyroY != null) plotViewGyroY.setPlotColor(Color.parseColor("#4ECDC4"));
        if (plotViewGyroZ != null) plotViewGyroZ.setPlotColor(Color.parseColor("#45B7D1"));
        if (plotViewTemp0 != null) plotViewTemp0.setPlotColor(Color.parseColor("#FFA726"));
        if (plotViewTemp1 != null) plotViewTemp1.setPlotColor(Color.parseColor("#FF7043"));
        if (plotViewTemp2 != null) plotViewTemp2.setPlotColor(Color.parseColor("#FF5722"));
    }

    private void setDefaultValues() {
        if (totalDurationInput != null) totalDurationInput.setText("120");
        if (segmentDurationInput != null) segmentDurationInput.setText("60");
        if (fileListStatus != null) fileListStatus.setText("0 files, 0 selected");
        if (measurementTimeInput != null) measurementTimeInput.setText("30");
        if (exerciseTotalDurationInput != null) exerciseTotalDurationInput.setText("300");
        if (exerciseSegmentDurationInput != null) exerciseSegmentDurationInput.setText("60");
        if (logStatusText != null) logStatusText.setText("状态: 就绪");
        if (logDisplayText != null) logDisplayText.setText("日志将显示在这里...");
    }

    private void setupNotificationHandler() {
        // 连接NotificationHandler的PlotView
        NotificationHandler.setPlotViewG(plotViewG);
        NotificationHandler.setPlotViewI(plotViewI);
        NotificationHandler.setPlotViewR(plotViewR);
        NotificationHandler.setPlotViewX(plotViewX);
        NotificationHandler.setPlotViewY(plotViewY);
        NotificationHandler.setPlotViewZ(plotViewZ);
        NotificationHandler.setPlotViewGyroX(plotViewGyroX);
        NotificationHandler.setPlotViewGyroY(plotViewGyroY);
        NotificationHandler.setPlotViewGyroZ(plotViewGyroZ);
        NotificationHandler.setPlotViewTemp0(plotViewTemp0);
        NotificationHandler.setPlotViewTemp1(plotViewTemp1);
        NotificationHandler.setPlotViewTemp2(plotViewTemp2);

        // 设置设备指令回调
        NotificationHandler.setDeviceCommandCallback(new NotificationHandler.DeviceCommandCallback() {
            @Override
            public void sendCommand(byte[] commandData) {
                LmAPI.CUSTOMIZE_CMD(commandData, customizeCmdListener);
                recordLog("发送设备指令: " + bytesToHexString(commandData));
            }

            @Override
            public void onMeasurementStarted() {
                recordLog("【测量开始】");
                mainHandler.post(() -> {
                    updateMeasurementUI(true);
                    updateMeasurementStatus("测量中...");
                });
            }

            @Override
            public void onMeasurementStopped() {
                recordLog("【测量停止】");
                mainHandler.post(() -> {
                    updateMeasurementUI(false);
                    updateMeasurementStatus("已停止");
                });
            }

            @Override
            public void onExerciseStarted(int duration, int segmentTime) {
                recordLog(String.format("【运动开始】总时长: %d秒, 片段: %d秒", duration, segmentTime));
                mainHandler.post(() -> {
                    updateExerciseUI(true);
                    updateExerciseStatus("运动中...");
                });
            }

            @Override
            public void onExerciseStopped() {
                recordLog("【运动停止】");
                mainHandler.post(() -> {
                    updateExerciseUI(false);
                    updateExerciseStatus("已停止");
                });
            }
        });

        // 设置文件操作回调
        NotificationHandler.setFileResponseCallback(new NotificationHandler.FileResponseCallback() {
            @Override
            public void onFileListReceived(byte[] data) {
                handleFileListResponse(data);
            }

            @Override
            public void onFileDataReceived(byte[] data) {
                handleFileDataResponse(data);
            }
        });

        // 设置时间同步回调
        NotificationHandler.setTimeSyncCallback(new NotificationHandler.TimeSyncCallback() {
            @Override
            public void onTimeSyncResponse(byte[] data) {
                handleTimeSyncResponse(data);
            }

            @Override
            public void onTimeUpdateResponse(byte[] data) {
                handleTimeUpdateResponse(data);
            }
        });

        // 设置日志记录器
        NotificationHandler.setLogRecorder(new NotificationHandler.LogRecorder() {
            @Override
            public void recordLog(String message) {
                MainActivity.this.recordLog(message);
            }
        });
    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_dashboard) {
                showDashboard();
                currentTab = 0;
                return true;
            } else if (itemId == R.id.nav_online) {
                showOnlineVisualize();
                currentTab = 1;
                return true;
            } else if (itemId == R.id.nav_offline) {
                showOfflineDownload();
                currentTab = 2;
                return true;
            } else if (itemId == R.id.nav_logs) {
                showLogs();
                currentTab = 3;
                return true;
            }
            return false;
        });
    }

    private void setupClickListeners() {
        scanButton.setOnClickListener(v -> scanForDevices());
        connectButton.setOnClickListener(v -> connectToDevice());
        updateTimeButton.setOnClickListener(v -> updateDeviceTime());
        calibrateButton.setOnClickListener(v -> calibrateTime());
        getFileListButton.setOnClickListener(v -> getFileList());
        downloadSelectedButton.setOnClickListener(v -> downloadSelectedFiles());

        // 在线测量按钮
        if (startMeasurementButton != null) {
            startMeasurementButton.setOnClickListener(v -> startOnlineMeasurement());
        }
        if (stopMeasurementButton != null) {
            stopMeasurementButton.setOnClickListener(v -> stopOnlineMeasurement());
        }

        // 运动控制按钮
        if (startExerciseButton != null) {
            startExerciseButton.setOnClickListener(v -> startExercise());
        }
        if (stopExerciseButton != null) {
            stopExerciseButton.setOnClickListener(v -> stopExercise());
        }

        // 日志记录按钮
        if (startLogRecordingButton != null) {
            startLogRecordingButton.setOnClickListener(v -> startLogRecording());
        }
        if (stopLogRecordingButton != null) {
            stopLogRecordingButton.setOnClickListener(v -> stopLogRecording());
        }
    }

    // ==================== 页面切换 ====================

    private void showDashboard() {
        hideAllViews();
        findViewById(R.id.dashboardLayout).setVisibility(View.VISIBLE);
    }

    private void showOnlineVisualize() {
        hideAllViews();
        findViewById(R.id.onlineLayout).setVisibility(View.VISIBLE);
    }

    private void showOfflineDownload() {
        hideAllViews();
        findViewById(R.id.offlineLayout).setVisibility(View.VISIBLE);
        setupFileList();
    }

    private void showLogs() {
        hideAllViews();
        findViewById(R.id.logsLayout).setVisibility(View.VISIBLE);
    }

    private void hideAllViews() {
        findViewById(R.id.dashboardLayout).setVisibility(View.GONE);
        findViewById(R.id.onlineLayout).setVisibility(View.GONE);
        findViewById(R.id.offlineLayout).setVisibility(View.GONE);
        findViewById(R.id.logsLayout).setVisibility(View.GONE);
    }

    // ==================== 文件操作功能 ====================

    private void getFileList() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        recordLog("【请求文件列表】使用自定义指令");
        try {
            String hexCommand = String.format("00%02X3610", generateRandomFrameId());
            byte[] data = hexStringToByteArray(hexCommand);
            recordLog("发送文件列表命令: " + hexCommand);
            LmAPI.CUSTOMIZE_CMD(data, customizeCmdListener);

            fileList.clear();
            selectedFiles.clear();
            updateFileListUI();

            mainHandler.post(() -> {
                getFileListButton.setText("获取中...");
                getFileListButton.setEnabled(false);
            });

        } catch (Exception e) {
            recordLog("发送文件列表请求失败: " + e.getMessage());
            Toast.makeText(this, "获取文件列表失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleFileListResponse(byte[] data) {
        try {
            if (data == null || data.length < 12) {
                recordLog("文件列表响应数据长度不足");
                return;
            }

            int totalFiles = readUInt32LE(data, 4);
            int seqNum = readUInt32LE(data, 8);
            int fileSize = readUInt32LE(data, 12);

            recordLog(String.format("文件列表信息 - 总数: %d, 序号: %d, 大小: %d", totalFiles, seqNum, fileSize));

            if (totalFiles > 0 && data.length > 16) {
                // 解析文件名
                byte[] fileNameBytes = new byte[data.length - 16];
                System.arraycopy(data, 16, fileNameBytes, 0, fileNameBytes.length);

                String fileName = new String(fileNameBytes, "UTF-8").trim();
                fileName = fileName.replace("\0", "");

                if (!fileName.isEmpty()) {
                    FileInfo fileInfo = new FileInfo(fileName, fileSize);
                    fileList.add(fileInfo);
                    recordLog("添加文件: " + fileName + " (" + fileInfo.getFormattedSize() + ")");
                }
            }

            mainHandler.post(() -> {
                setupFileList();
                getFileListButton.setText("获取文件列表");
                getFileListButton.setEnabled(true);
                if (fileList.size() > 0) {
                    Toast.makeText(this, "获取到 " + fileList.size() + " 个文件", Toast.LENGTH_SHORT).show();
                }
            });

        } catch (Exception e) {
            recordLog("解析文件列表失败: " + e.getMessage());
            mainHandler.post(() -> {
                getFileListButton.setText("获取文件列表");
                getFileListButton.setEnabled(true);
            });
        }
    }

    private void handleFileDataResponse(byte[] data) {
        recordLog("收到文件数据响应，长度: " + data.length);
        // 这里可以处理文件数据的保存逻辑
    }

    private void downloadSelectedFiles() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "请先选择要下载的文件", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        isDownloadingFiles = true;
        currentDownloadIndex = 0;
        recordLog(String.format("【开始批量下载】选中文件数: %d", selectedFiles.size()));

        downloadNextSelectedFile();
    }

    private void downloadNextSelectedFile() {
        if (currentDownloadIndex >= selectedFiles.size()) {
            isDownloadingFiles = false;
            mainHandler.post(() -> {
                downloadSelectedButton.setText("下载选中 (" + selectedFiles.size() + ")");
                downloadSelectedButton.setEnabled(true);
                Toast.makeText(this, "所有文件下载完成", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        FileInfo fileInfo = selectedFiles.get(currentDownloadIndex);
        recordLog(String.format("下载进度 %d/%d: %s", currentDownloadIndex + 1, selectedFiles.size(), fileInfo.fileName));

        try {
            byte[] fileNameBytes = fileInfo.fileName.getBytes("UTF-8");
            String hexCommand = String.format("00%02X3611", generateRandomFrameId());
            StringBuilder sb = new StringBuilder(hexCommand);
            for (byte b : fileNameBytes) {
                sb.append(String.format("%02X", b & 0xFF));
            }

            byte[] commandData = hexStringToByteArray(sb.toString());
            LmAPI.CUSTOMIZE_CMD(commandData, customizeCmdListener);

            currentDownloadIndex++;
            mainHandler.postDelayed(this::downloadNextSelectedFile, 2000);

        } catch (Exception e) {
            recordLog("下载文件失败: " + e.getMessage());
            currentDownloadIndex++;
            mainHandler.postDelayed(this::downloadNextSelectedFile, 1000);
        }
    }

    private void setupFileList() {
        fileListContainer.removeAllViews();

        for (FileInfo fileInfo : fileList) {
            addFileItem(fileInfo);
        }

        updateFileListUI();
    }

    private void addFileItem(FileInfo fileInfo) {
        LinearLayout fileItem = new LinearLayout(this);
        fileItem.setOrientation(LinearLayout.HORIZONTAL);
        fileItem.setPadding(16, 12, 16, 12);
        fileItem.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        CheckBox checkBox = new CheckBox(this);
        checkBox.setChecked(fileInfo.isSelected);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            fileInfo.isSelected = isChecked;
            updateSelectedFiles();
        });

        LinearLayout fileInfoLayout = new LinearLayout(this);
        fileInfoLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        layoutParams.setMargins(24, 0, 0, 0);
        fileInfoLayout.setLayoutParams(layoutParams);

        TextView fileName = new TextView(this);
        fileName.setText(fileInfo.fileName);
        fileName.setTextSize(12);
        fileName.setTextColor(Color.BLACK);

        TextView fileDetails = new TextView(this);
        fileDetails.setText(fileInfo.getFileTypeDescription() + " | " + fileInfo.getFormattedSize() + " | " + fileInfo.timestamp);
        fileDetails.setTextSize(10);
        fileDetails.setTextColor(Color.GRAY);

        fileInfoLayout.addView(fileName);
        fileInfoLayout.addView(fileDetails);

        fileItem.addView(checkBox);
        fileItem.addView(fileInfoLayout);

        fileItem.setOnClickListener(v -> {
            checkBox.setChecked(!checkBox.isChecked());
        });

        fileListContainer.addView(fileItem);
    }

    private void updateSelectedFiles() {
        selectedFiles.clear();
        for (FileInfo file : fileList) {
            if (file.isSelected) {
                selectedFiles.add(file);
            }
        }
        updateFileListUI();
    }

    private void updateFileListUI() {
        mainHandler.post(() -> {
            fileListStatus.setText(String.format("共%d个文件，已选%d个", fileList.size(), selectedFiles.size()));
            downloadSelectedButton.setText("下载选中 (" + selectedFiles.size() + ")");
            downloadSelectedButton.setEnabled(selectedFiles.size() > 0 && !isDownloadingFiles);
        });
    }

    // ==================== 运动控制功能 ====================

    private void startExercise() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isExercising) {
            Toast.makeText(this, "运动正在进行中", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String totalDurationStr = exerciseTotalDurationInput.getText().toString().trim();
            String segmentDurationStr = exerciseSegmentDurationInput.getText().toString().trim();

            if (totalDurationStr.isEmpty() || segmentDurationStr.isEmpty()) {
                Toast.makeText(this, "请输入运动时长和片段时长", Toast.LENGTH_SHORT).show();
                return;
            }

            int totalDuration = Integer.parseInt(totalDurationStr);
            int segmentDuration = Integer.parseInt(segmentDurationStr);

            if (totalDuration < 60 || totalDuration > 86400) {
                Toast.makeText(this, "运动总时长应在60-86400秒之间", Toast.LENGTH_SHORT).show();
                return;
            }

            if (segmentDuration < 30 || segmentDuration > totalDuration) {
                Toast.makeText(this, "片段时长应在30秒到总时长之间", Toast.LENGTH_SHORT).show();
                return;
            }

            NotificationHandler.setExerciseParams(totalDuration, segmentDuration);
            boolean success = NotificationHandler.startExercise();

            if (success) {
                isExercising = true;
                recordLog(String.format("【开始运动】总时长: %d秒, 片段: %d秒", totalDuration, segmentDuration));
                updateExerciseUI(true);
                updateExerciseStatus(String.format("运动中 - 总时长: %d分钟, 片段: %d分钟", totalDuration/60, segmentDuration/60));
                Toast.makeText(this, "运动开始", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "开始运动失败", Toast.LENGTH_SHORT).show();
            }

        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            recordLog("开始运动失败: " + e.getMessage());
            Toast.makeText(this, "开始运动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopExercise() {
        if (!isExercising) {
            Toast.makeText(this, "当前没有正在进行的运动", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            boolean success = NotificationHandler.stopExercise();
            if (success) {
                isExercising = false;
                recordLog("【结束运动】用户手动停止");
                updateExerciseUI(false);
                updateExerciseStatus("已停止");
                Toast.makeText(this, "运动已停止", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "停止运动失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            recordLog("停止运动失败: " + e.getMessage());
            Toast.makeText(this, "停止运动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateExerciseUI(boolean exercising) {
        if (startExerciseButton != null) {
            startExerciseButton.setEnabled(!exercising);
            startExerciseButton.setText(exercising ? "运动中..." : "开始运动");
        }

        if (stopExerciseButton != null) {
            stopExerciseButton.setEnabled(exercising);
            stopExerciseButton.setBackgroundColor(exercising ? Color.parseColor("#F44336") : Color.GRAY);
        }
    }

    private void updateExerciseStatus(String status) {
        if (exerciseStatusText != null) {
            exerciseStatusText.setText("运动状态: " + status);
        }
    }

    // ==================== 日志记录功能 ====================

    private void startLogRecording() {
        if (isLogRecording) {
            Toast.makeText(this, "日志记录正在进行中", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            createLogFile();
            isLogRecording = true;

            recordLog("=".repeat(50));
            recordLog("【开始日志记录会话】时间: " + getCurrentTimestamp());
            recordLog("设备: " + deviceName);
            recordLog("MAC: " + macAddress);
            recordLog("=".repeat(50));

            updateLogRecordingUI(true);
            updateLogStatus("录制中...");
            Toast.makeText(this, "日志记录开始", Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            recordLog("创建日志文件失败: " + e.getMessage());
            Toast.makeText(this, "创建日志文件失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopLogRecording() {
        if (!isLogRecording) {
            Toast.makeText(this, "当前没有正在进行的日志记录", Toast.LENGTH_SHORT).show();
            return;
        }

        recordLog("=".repeat(50));
        recordLog("【结束日志记录会话】时间: " + getCurrentTimestamp());
        recordLog("=".repeat(50));

        isLogRecording = false;
        updateLogRecordingUI(false);
        updateLogStatus("就绪");

        try {
            if (logWriter != null) {
                logWriter.close();
                logWriter = null;
            }
        } catch (IOException e) {
            recordLog("关闭日志文件失败: " + e.getMessage());
        }

        Toast.makeText(this, "日志记录已停止", Toast.LENGTH_SHORT).show();
    }

    private void createLogFile() throws IOException {

        String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                + "/Sample/" +  "/RingLog/";
        File directory = new File(directoryPath);

        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new IOException("创建目录失败: " + directoryPath);
            }
        }

        String fileName = "MainSession_" + System.currentTimeMillis() + ".txt";
        File logFile = new File(directory, fileName);
        logWriter = new BufferedWriter(new FileWriter(logFile, true));

        recordLog("日志文件创建: " + logFile.getAbsolutePath());
    }

    private void updateLogRecordingUI(boolean recording) {
        if (startLogRecordingButton != null) {
            startLogRecordingButton.setEnabled(!recording);
            startLogRecordingButton.setText(recording ? "录制中..." : "开始记录");
        }

        if (stopLogRecordingButton != null) {
            stopLogRecordingButton.setEnabled(recording);
            stopLogRecordingButton.setBackgroundColor(recording ? Color.parseColor("#F44336") : Color.GRAY);
        }
    }

    private void updateLogStatus(String status) {
        if (logStatusText != null) {
            logStatusText.setText("状态: " + status);
        }
    }

    // ==================== 在线测量功能 ====================

    private void startOnlineMeasurement() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        String timeStr = measurementTimeInput.getText().toString().trim();
        if (timeStr.isEmpty()) {
            Toast.makeText(this, "请输入测量时间", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int measurementTime = Integer.parseInt(timeStr);
            if (measurementTime < 1 || measurementTime > 3600) {
                Toast.makeText(this, "测量时间应在1-3600秒之间", Toast.LENGTH_SHORT).show();
                return;
            }

            isMeasuring = true;
            clearAllPlots();

            NotificationHandler.setMeasurementTime(measurementTime);
            updateMeasurementUI(true);
            updateMeasurementStatus("测量中... (0/" + measurementTime + "s)");

            recordLog("【开始在线测量】时间: " + measurementTime + "秒");

            startMeasurementTimer(measurementTime);

            if (NotificationHandler.startActiveMeasurement()) {
                Toast.makeText(this, "在线测量开始", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "启动测量失败", Toast.LENGTH_SHORT).show();
                stopOnlineMeasurement();
            }

        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效的测量时间", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopOnlineMeasurement() {
        if (isMeasuring) {
            isMeasuring = false;

            if (measurementTimer != null) {
                measurementTimer.cancel();
                measurementTimer = null;
            }

            updateMeasurementUI(false);
            updateMeasurementStatus("测量已停止");

            recordLog("【停止在线测量】");
            NotificationHandler.stopMeasurement();

            Toast.makeText(this, "在线测量已停止", Toast.LENGTH_SHORT).show();
        }
    }

    private void startMeasurementTimer(int totalTime) {
        totalMeasurementTime = totalTime;
        measurementElapsed = 0;

        measurementTimer = new Timer();
        measurementTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                measurementElapsed++;

                mainHandler.post(() -> {
                    updateMeasurementStatus("测量中... (" + measurementElapsed + "/" + totalMeasurementTime + "s)");
                });

                if (measurementElapsed >= totalMeasurementTime) {
                    mainHandler.post(() -> {
                        updateMeasurementStatus("测量完成");
                        Toast.makeText(MainActivity.this, "测量完成", Toast.LENGTH_SHORT).show();
                    });
                    this.cancel();
                }
            }
        }, 1000, 1000);
    }

    private void updateMeasurementUI(boolean measuring) {
        if (startMeasurementButton != null) {
            startMeasurementButton.setEnabled(!measuring);
            startMeasurementButton.setText(measuring ? "测量中..." : "开始测量");
        }

        if (stopMeasurementButton != null) {
            stopMeasurementButton.setEnabled(measuring);
            stopMeasurementButton.setBackgroundColor(measuring ? Color.parseColor("#F44336") : Color.GRAY);
        }
    }

    private void updateMeasurementStatus(String status) {
        if (measurementStatusText != null) {
            measurementStatusText.setText(status);
        }
    }



    // ==================== 基础功能 ====================

    private void loadDeviceInfo() {
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        macAddress = prefs.getString("mac_address", "");
        deviceName = prefs.getString("device_name", "");

        if (!macAddress.isEmpty()) {
            macAddressText.setText("MAC: " + macAddress + " | Version: " + version);
        } else {
            macAddressText.setText("MAC: Not Selected | Version: --");
        }
    }

    private void scanForDevices() {
        Intent intent = new Intent(this, RingSettingsActivity.class);
        intent.putExtra("deviceName", "指环设备");
        startActivityForResult(intent, 100);
    }

    private void connectToDevice() {
        if (isConnected && connectionStatus == 7) {
            recordLog("用户点击断开连接");
            updateConnectionStatus(false);
            connectionStatus = 0;
            Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        String savedMacAddress = prefs.getString("mac_address", "");

        if (savedMacAddress.isEmpty()) {
            Toast.makeText(this, "请先扫描并选择设备", Toast.LENGTH_SHORT).show();
            return;
        }

        recordLog("开始连接设备: " + savedMacAddress);
        connectButton.setText("连接中...");
        connectButton.setBackgroundColor(Color.parseColor("#FF9800"));

        try {
            android.bluetooth.BluetoothAdapter bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            android.bluetooth.BluetoothDevice device = bluetoothAdapter.getRemoteDevice(savedMacAddress);
            if (device != null) {
                BLEUtils.connectLockByBLE(this, device);
                macAddress = savedMacAddress;
            } else {
                Toast.makeText(this, "无效的MAC地址", Toast.LENGTH_SHORT).show();
                connectButton.setText("连接");
                connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
            }
        } catch (Exception e) {
            recordLog("连接失败: " + e.getMessage());
            Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            connectButton.setText("连接");
            connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
        }
    }

    private void updateConnectionStatus(boolean connected) {
        isConnected = connected;
        if (connected && connectionStatus == 7) {
            statusText.setText("已连接");
            statusText.setTextColor(Color.WHITE);
            statusIndicator.setText("✓ " + deviceName);
            statusIndicator.setTextColor(Color.parseColor("#4CAF50"));
            connectButton.setText("已连接");
            connectButton.setBackgroundColor(Color.parseColor("#4CAF50"));
            ringIdText.setText("Ring ID: " + deviceName);
            macAddressText.setText("MAC: " + macAddress + " | Version: " + version);
            batteryText.setText(batteryLevel + "%");
        } else {
            statusText.setText("未连接");
            statusText.setTextColor(Color.parseColor("#FF5722"));
            statusIndicator.setText("✗ 未连接");
            statusIndicator.setTextColor(Color.parseColor("#FF5722"));
            connectButton.setText("连接");
            connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
            ringIdText.setText("Ring ID: --");
            batteryText.setText("--");
        }
    }

    private void clearAllPlots() {
        if (plotViewG != null) plotViewG.clearPlot();
        if (plotViewI != null) plotViewI.clearPlot();
        if (plotViewR != null) plotViewR.clearPlot();
        if (plotViewX != null) plotViewX.clearPlot();
        if (plotViewY != null) plotViewY.clearPlot();
        if (plotViewZ != null) plotViewZ.clearPlot();
        if (plotViewGyroX != null) plotViewGyroX.clearPlot();
        if (plotViewGyroY != null) plotViewGyroY.clearPlot();
        if (plotViewGyroZ != null) plotViewGyroZ.clearPlot();
        if (plotViewTemp0 != null) plotViewTemp0.clearPlot();
        if (plotViewTemp1 != null) plotViewTemp1.clearPlot();
        if (plotViewTemp2 != null) plotViewTemp2.clearPlot();
    }

    // ==================== 时间同步功能 ====================

    private void updateDeviceTime() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isTimeUpdating) {
            Toast.makeText(this, "时间更新正在进行中，请等待", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            isTimeUpdating = true;
            timeUpdateFrameId = generateRandomFrameId();

            recordLog("【开始更新戒指时间】使用自定义指令");

            long currentTime = System.currentTimeMillis();
            TimeZone timeZone = TimeZone.getDefault();
            int timezoneOffset = timeZone.getRawOffset() / (1000 * 60 * 60);

            StringBuilder hexCommand = new StringBuilder();
            hexCommand.append(String.format("00%02X1000", timeUpdateFrameId));

            for (int i = 0; i < 8; i++) {
                hexCommand.append(String.format("%02X", (currentTime >> (i * 8)) & 0xFF));
            }

            int timezoneValue = timezoneOffset;
            if (timezoneValue < 0) {
                timezoneValue = 256 + timezoneValue;
            }
            hexCommand.append(String.format("%02X", timezoneValue & 0xFF));

            byte[] data = hexStringToByteArray(hexCommand.toString());
            recordLog("发送时间更新命令: " + hexCommand.toString());

            updateTimeButton.setText("更新中...");
            updateTimeButton.setBackgroundColor(Color.parseColor("#FF9800"));

            LmAPI.CUSTOMIZE_CMD(data, customizeCmdListener);

        } catch (Exception e) {
            recordLog("发送时间更新命令失败: " + e.getMessage());
            updateTimeButton.setText("更新时间");
            updateTimeButton.setBackgroundColor(Color.parseColor("#2196F3"));
            isTimeUpdating = false;
        }
    }

    private void calibrateTime() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isTimeSyncing) {
            Toast.makeText(this, "时间校准正在进行中，请等待", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            isTimeSyncing = true;
            timeSyncRequestTime = System.currentTimeMillis();
            timeSyncFrameId = generateRandomFrameId();

            recordLog("【开始时间校准同步】使用自定义指令");

            StringBuilder hexCommand = new StringBuilder();
            hexCommand.append(String.format("00%02X1002", timeSyncFrameId));

            long timestamp = timeSyncRequestTime;
            for (int i = 0; i < 8; i++) {
                hexCommand.append(String.format("%02X", (timestamp >> (i * 8)) & 0xFF));
            }

            byte[] data = hexStringToByteArray(hexCommand.toString());
            recordLog("发送时间校准命令: " + hexCommand.toString());

            calibrateButton.setText("校准中...");
            calibrateButton.setBackgroundColor(Color.parseColor("#FF9800"));
            timeSyncRequestTime = timeSyncRequestTime / 1000;

            LmAPI.CUSTOMIZE_CMD(data, customizeCmdListener);

        } catch (Exception e) {
            recordLog("发送时间校准命令失败: " + e.getMessage());
            calibrateButton.setText("校准");
            calibrateButton.setBackgroundColor(Color.parseColor("#4CAF50"));
            isTimeSyncing = false;
        }
    }

    // ==================== 自定义指令响应处理 ====================

    private void handleCustomizeResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("自定义指令响应数据长度不足");
                return;
            }

            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            recordLog(String.format("响应解析: FrameType=0x%02X, FrameID=0x%02X, Cmd=0x%02X, Subcmd=0x%02X",
                    frameType, frameId, cmd, subcmd));

            if (frameType == 0x00) {
                if (cmd == 0x10) {
                    if (subcmd == 0x00) {
                        recordLog("识别为时间更新响应");
                        handleTimeUpdateResponse(data);
                    } else if (subcmd == 0x02) {
                        recordLog("识别为时间校准响应");
                        handleTimeSyncResponse(data);
                    }
                } else if (cmd == 0x36) {
                    if (subcmd == 0x10) {
                        recordLog("识别为文件列表响应");
                        handleFileListResponse(data);
                    } else if (subcmd == 0x11) {
                        recordLog("识别为文件数据响应");
                        handleFileDataResponse(data);
                    }
                }
            }
        } catch (Exception e) {
            recordLog("处理自定义指令响应失败: " + e.getMessage());
        }
    }

    private void handleTimeUpdateResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("时间更新响应数据长度不足");
                return;
            }

            int frameId = data[1] & 0xFF;
            if (frameId != timeUpdateFrameId) {
                recordLog("时间更新响应Frame ID不匹配");
                return;
            }

            recordLog("【时间更新完成】戒指时间已成功更新");

            mainHandler.post(() -> {
                updateTimeButton.setText("更新时间");
                updateTimeButton.setBackgroundColor(Color.parseColor("#2196F3"));
                Toast.makeText(this, "戒指时间更新成功", Toast.LENGTH_SHORT).show();
            });

        } catch (Exception e) {
            recordLog("解析时间更新响应失败: " + e.getMessage());
            mainHandler.post(() -> {
                updateTimeButton.setText("更新时间");
                updateTimeButton.setBackgroundColor(Color.parseColor("#2196F3"));
            });
        } finally {
            isTimeUpdating = false;
        }
    }

    private void handleTimeSyncResponse(byte[] data) {
        try {
            long currentTime = System.currentTimeMillis() / 1000;

            if (data == null || data.length < 28) {
                recordLog("时间校准响应数据长度不足");
                return;
            }

            int frameId = data[1] & 0xFF;
            if (frameId != timeSyncFrameId) {
                recordLog("时间校准响应Frame ID不匹配");
                return;
            }

            int offset = 4;
            long hostSentTime = readUInt64LE(data, offset) / 1000;
            offset += 8;
            long ringReceivedTime = readUInt64LE(data, offset) / 1000;
            offset += 8;
            long ringUploadTime = readUInt64LE(data, offset) / 1000;

            long roundTripTime = currentTime - timeSyncRequestTime;
            long timeDifference = ringReceivedTime - hostSentTime;

            recordLog("【时间校准结果】");
            recordLog(String.format("往返延迟: %d s", roundTripTime));
            recordLog(String.format("时间差: %d s", timeDifference));

            mainHandler.post(() -> {
                calibrateButton.setText("校准");
                calibrateButton.setBackgroundColor(Color.parseColor("#4CAF50"));
                Toast.makeText(this,
                        String.format("时间校准完成\n时间差: %d s\n延迟: %d s", timeDifference, roundTripTime),
                        Toast.LENGTH_LONG).show();
            });

        } catch (Exception e) {
            recordLog("解析时间校准响应失败: " + e.getMessage());
            mainHandler.post(() -> {
                calibrateButton.setText("校准");
                calibrateButton.setBackgroundColor(Color.parseColor("#4CAF50"));
            });
        } finally {
            isTimeSyncing = false;
        }
    }

    // ==================== 日志记录功能 ====================

    private void recordLog(String message) {
        String timestamp = getCurrentTimestamp();
        String fullLogMessage = "[" + timestamp + "] " + message;

        // 显示到UI
        mainHandler.post(() -> {
            if (logDisplayText != null) {
                    logDisplayText.setText(message);

            }
        });

        // 写入文件（仅在录制状态下）
        if (isLogRecording && logWriter != null) {
            try {
                logWriter.write(fullLogMessage + "\n");
                logWriter.flush();
            } catch (IOException e) {
                android.util.Log.e("MainActivity", "写入日志失败: " + e.getMessage());
            }
        }

        android.util.Log.d("MainActivity", fullLogMessage);
    }

    // ==================== 工具方法 ====================

    public static byte[] hexStringToByteArray(String hexString) {
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i + 1), 16));
        }
        return data;
    }

    private String bytesToHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date());
    }

    private int generateRandomFrameId() {
        return random.nextInt(256);
    }

    private int readUInt32LE(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            throw new IndexOutOfBoundsException("数据不足以读取4字节整型");
        }
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }

    private long readUInt64LE(byte[] data, int offset) {
        if (offset + 8 > data.length) {
            throw new IndexOutOfBoundsException("数据不足以读取8字节时间戳");
        }
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long)(data[offset + i] & 0xFF)) << (i * 8);
        }
        return result;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK) {
            // 从RingSettingsActivity返回，重新加载设备信息
            loadDeviceInfo();
        }
    }

    // ==================== IResponseListener 接口实现 ====================

    @Override
    public void lmBleConnecting(int i) {
        recordLog("蓝牙连接中，状态码：" + i);
        connectionStatus = i;
        mainHandler.post(() -> {
            connectButton.setText("连接中...");
            connectButton.setBackgroundColor(Color.parseColor("#FF9800"));
        });
    }

    @Override
    public void lmBleConnectionSucceeded(int i) {
        recordLog("蓝牙连接成功，状态码：" + i);
        connectionStatus = i;

        if (i == 7) {
            BLEUtils.setGetToken(true);
            recordLog("连接成功，状态码为7");

            // 延迟获取设备信息
            mainHandler.postDelayed(() -> {
                LmAPI.GET_BATTERY((byte) 0x00);
            }, 1000);

            mainHandler.postDelayed(() -> {
                LmAPI.GET_VERSION((byte) 0x00);
            }, 1500);

            // 更新连接状态
            mainHandler.post(() -> {
                isConnected = true;
                updateConnectionStatus(true);
                Toast.makeText(this, "连接成功", Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override
    public void lmBleConnectionFailed(int i) {
        recordLog("蓝牙连接失败，状态码：" + i);
        connectionStatus = i;
        mainHandler.post(() -> {
            connectButton.setText("连接");
            connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
            Toast.makeText(this, "连接失败", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void VERSION(byte b, String s) {
        recordLog("获取版本信息: " + s);
        version = s;
        mainHandler.post(() -> {
            macAddressText.setText("MAC: " + macAddress + " | Version: " + version);
        });
    }

    @Override
    public void syncTime(byte b, byte[] bytes) {
        recordLog("时间同步完成");
    }

    @Override
    public void stepCount(byte[] bytes) {
        // 步数计数
    }

    @Override
    public void clearStepCount(byte b) {
        // 清除步数
    }

    @Override
    public void battery(byte b, byte b1) {
        if (b == 0) {
            batteryLevel = b1 & 0xFF;
            recordLog("电池电量为: " + batteryLevel);
            mainHandler.post(() -> {
                batteryText.setText(batteryLevel + "%");
            });
        }
    }

    @Override
    public void battery_push(byte b, byte datum) {
        // 电池推送
    }

    @Override
    public void timeOut() {
        recordLog("连接超时");
    }

    @Override
    public void saveData(String s) {
        // 这里可以处理实时数据
        String msg = NotificationHandler.handleNotification(hexStringToByteArray(s));
        recordLog("接收数据: " + msg);

        // 如果连接成功但设备名称为空，尝试获取设备名称
        if (connectionStatus == 7 && deviceName.isEmpty()) {
            // 从SharedPreferences获取设备名称或使用默认名称
            SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
            deviceName = prefs.getString("device_name", "Ring Device");
            if (deviceName.isEmpty()) {
                deviceName = "Ring Device";
            }
            mainHandler.post(() -> {
                updateConnectionStatus(true);
            });
        }
    }

    @Override
    public void reset(byte[] bytes) {
        // 重置
    }

    @Override
    public void setCollection(byte b) {
        // 设置采集
    }

    @Override
    public void getCollection(byte[] bytes) {
        // 获取采集
    }

    @Override
    public void getSerialNum(byte[] bytes) {
        // 获取序列号
    }

    @Override
    public void setSerialNum(byte b) {
        // 设置序列号
    }

    @Override
    public void cleanHistory(byte b) {
        // 清除历史
    }

    @Override
    public void setBlueToolName(byte b) {
        // 设置蓝牙工具名称
    }

    @Override
    public void readBlueToolName(byte b, String s) {
        // 读取蓝牙工具名称
        if (deviceName.isEmpty()) {
            deviceName = s;
            mainHandler.post(() -> {
                updateConnectionStatus(true);
            });
        }
    }

    @Override
    public void stopRealTimeBP(byte b) {
        // 停止实时血压
    }

    @Override
    public void BPwaveformData(byte b, byte b1, String s) {
        // 血压波形数据
    }

    @Override
    public void onSport(int i, byte[] bytes) {
        // 运动数据
    }

    @Override
    public void breathLight(byte b) {
        // 呼吸灯
    }

    @Override
    public void SET_HID(byte b) {
        // 设置HID
    }

    @Override
    public void GET_HID(byte b, byte b1, byte b2) {
        // 获取HID
    }

    @Override
    public void GET_HID_CODE(byte[] bytes) {
        // 获取HID代码
    }

    @Override
    public void GET_CONTROL_AUDIO_ADPCM(byte b) {
        // 获取控制音频ADPCM
    }

    @Override
    public void SET_AUDIO_ADPCM_AUDIO(byte b) {
        // 设置音频ADPCM
    }

    @Override
    public void TOUCH_AUDIO_FINISH_XUN_FEI() {
        // 触摸音频完成讯飞
    }

    @Override
    public void setAudio(short i, int i1, byte[] bytes) {
        // 设置音频
    }

    @Override
    public void stopHeart(byte b) {
        // 停止心率
    }

    @Override
    public void stopQ2(byte b) {
        // 停止Q2
    }

    @Override
    public void GET_ECG(byte[] bytes) {
        // 获取ECG
    }

    @Override
    public void SystemControl(SystemControlBean systemControlBean) {
        // 系统控制
    }

    @Override
    public void setUserInfo(byte result) {
        // 设置用户信息
    }

    @Override
    public void getUserInfo(int sex, int height, int weight, int age) {
        // 获取用户信息
    }

    @Override
    public void CONTROL_AUDIO(byte[] bytes) {
        // 控制音频
    }

    @Override
    public void motionCalibration(byte b) {
        // 运动校准
    }

    @Override
    public void stopBloodPressure(byte b) {
        // 停止血压
    }

    @Override
    public void appBind(SystemControlBean systemControlBean) {
        // 应用绑定
    }

    @Override
    public void appConnect(SystemControlBean systemControlBean) {
        // 应用连接
    }

    @Override
    public void appRefresh(SystemControlBean systemControlBean) {
        // 应用刷新
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 清理资源
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        if (measurementTimer != null) {
            measurementTimer.cancel();
            measurementTimer = null;
        }

        // 关闭日志文件
        if (logWriter != null) {
            try {
                logWriter.close();
                logWriter = null;
            } catch (IOException e) {
                android.util.Log.e("MainActivity", "关闭日志文件失败: " + e.getMessage());
            }
        }

        recordLog("MainActivity销毁，资源已清理");
    }
}
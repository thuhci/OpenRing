package com.tsinghua.sample.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

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

import java.util.Random;
import java.util.TimeZone;

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
    private Button startRecordingButton;
    private Button resetButton;
    private Button startOfflineButton;
    private Button getFileListButton;
    private Button downloadSelectedButton;
    private EditText totalDurationInput;
    private EditText segmentDurationInput;
    private TextView fileListStatus;
    private LinearLayout fileListContainer;
    private BottomNavigationView bottomNavigation;

    // PPG数据显示
    private TextView ppgGreenText;
    private TextView ppgIrText;
    private TextView ppgRedText;
    private TextView xAxisText;
    private TextView yAxisText;
    private TextView zAxisText;

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

    // 模拟数据
    private int ppgGreen = 1024;
    private int ppgIr = 856;
    private int ppgRed = 732;
    private float xAxis = 0.12f;
    private float yAxis = -0.05f;
    private float zAxis = 9.81f;

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

        startOfflineButton = findViewById(R.id.startOfflineButton);
        getFileListButton = findViewById(R.id.getFileListButton);
        downloadSelectedButton = findViewById(R.id.downloadSelectedButton);

        // 输入框
        totalDurationInput = findViewById(R.id.totalDurationInput);
        segmentDurationInput = findViewById(R.id.segmentDurationInput);

        // 文件列表
        fileListStatus = findViewById(R.id.fileListStatus);
        fileListContainer = findViewById(R.id.fileListContainer);

        // 在线测量相关
        measurementTimeInput = findViewById(R.id.measurementTimeInput);
        startMeasurementButton = findViewById(R.id.startMeasurementButton);
        stopMeasurementButton = findViewById(R.id.stopMeasurementButton);
        measurementStatusText = findViewById(R.id.measurementStatusText);

        // 初始化PlotView
        plotViewG = findViewById(R.id.plotViewG);
        plotViewI = findViewById(R.id.plotViewI);
        plotViewR = findViewById(R.id.plotViewR);
        plotViewX = findViewById(R.id.plotViewX);
        plotViewY = findViewById(R.id.plotViewY);
        plotViewZ = findViewById(R.id.plotViewZ);

        // 初始化陀螺仪PlotView
        plotViewGyroX = findViewById(R.id.plotViewGyroX);
        plotViewGyroY = findViewById(R.id.plotViewGyroY);
        plotViewGyroZ = findViewById(R.id.plotViewGyroZ);

        // 初始化温度PlotView
        plotViewTemp0 = findViewById(R.id.plotViewTemp0);
        plotViewTemp1 = findViewById(R.id.plotViewTemp1);
        plotViewTemp2 = findViewById(R.id.plotViewTemp2);

        // 设置PlotView颜色
        if (plotViewG != null) plotViewG.setPlotColor(Color.parseColor("#4CAF50"));
        if (plotViewI != null) plotViewI.setPlotColor(Color.parseColor("#FF9800"));
        if (plotViewR != null) plotViewR.setPlotColor(Color.parseColor("#F44336"));
        if (plotViewX != null) plotViewX.setPlotColor(Color.parseColor("#2196F3"));
        if (plotViewY != null) plotViewY.setPlotColor(Color.parseColor("#9C27B0"));
        if (plotViewZ != null) plotViewZ.setPlotColor(Color.parseColor("#00BCD4"));

        // 设置陀螺仪PlotView颜色
        if (plotViewGyroX != null) plotViewGyroX.setPlotColor(Color.parseColor("#FF6B6B"));
        if (plotViewGyroY != null) plotViewGyroY.setPlotColor(Color.parseColor("#4ECDC4"));
        if (plotViewGyroZ != null) plotViewGyroZ.setPlotColor(Color.parseColor("#45B7D1"));

        // 设置温度PlotView颜色
        if (plotViewTemp0 != null) plotViewTemp0.setPlotColor(Color.parseColor("#FFA726"));
        if (plotViewTemp1 != null) plotViewTemp1.setPlotColor(Color.parseColor("#FF7043"));
        if (plotViewTemp2 != null) plotViewTemp2.setPlotColor(Color.parseColor("#FF5722"));

        // 连接NotificationHandler的PlotView
        NotificationHandler.setPlotViewG(plotViewG);
        NotificationHandler.setPlotViewI(plotViewI);
        NotificationHandler.setPlotViewR(plotViewR);
        NotificationHandler.setPlotViewX(plotViewX);
        NotificationHandler.setPlotViewY(plotViewY);
        NotificationHandler.setPlotViewZ(plotViewZ);

        // 连接NotificationHandler的陀螺仪PlotView
        NotificationHandler.setPlotViewGyroX(plotViewGyroX);
        NotificationHandler.setPlotViewGyroY(plotViewGyroY);
        NotificationHandler.setPlotViewGyroZ(plotViewGyroZ);

        // 连接NotificationHandler的温度PlotView
        NotificationHandler.setPlotViewTemp0(plotViewTemp0);
        NotificationHandler.setPlotViewTemp1(plotViewTemp1);
        NotificationHandler.setPlotViewTemp2(plotViewTemp2);

        // 设置设备指令回调
        NotificationHandler.setDeviceCommandCallback(new NotificationHandler.DeviceCommandCallback() {
            @Override
            public void sendCommand(byte[] commandData) {
                // 发送自定义指令
                LmAPI.CUSTOMIZE_CMD(commandData, customizeCmdListener);
                recordLog("发送设备指令: " + bytesToHexString(commandData));
            }

            @Override
            public void onMeasurementStarted() {
                recordLog("【测量开始】");
                // 可以在这里更新UI状态
            }

            @Override
            public void onMeasurementStopped() {
                recordLog("【测量停止】");
                // 可以在这里更新UI状态
            }

            @Override
            public void onExerciseStarted(int duration, int segmentTime) {
                recordLog(String.format("【运动开始】总时长: %d秒, 片段: %d秒", duration, segmentTime));
            }

            @Override
            public void onExerciseStopped() {
                recordLog("【运动停止】");
            }
        });

        // 底部导航
        bottomNavigation = findViewById(R.id.bottomNavigation);

        // 设置默认值
        totalDurationInput.setText("120");
        segmentDurationInput.setText("60");
        fileListStatus.setText("4 files, 1 selected");
        if (measurementTimeInput != null) {
            measurementTimeInput.setText("30");
        }

        // 初始化状态
        updateConnectionStatus(false);
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
        startOfflineButton.setOnClickListener(v -> startOfflineRecording());
        getFileListButton.setOnClickListener(v -> getFileList());
        downloadSelectedButton.setOnClickListener(v -> downloadSelectedFiles());

        // 在线测量按钮
        if (startMeasurementButton != null) {
            startMeasurementButton.setOnClickListener(v -> startOnlineMeasurement());
        }
        if (stopMeasurementButton != null) {
            stopMeasurementButton.setOnClickListener(v -> stopOnlineMeasurement());
        }
    }

    private void loadDeviceInfo() {
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        macAddress = prefs.getString("mac_address", "");

        if (!macAddress.isEmpty()) {
            macAddressText.setText("MAC: " + macAddress + " | Version: " + version);
        } else {
            macAddressText.setText("MAC: Not Selected | Version: --");
        }
    }

    private void updateConnectionStatus(boolean connected) {
        isConnected = connected;
        if (connected && connectionStatus == 7) {
            statusText.setText("Connected");
            statusText.setTextColor(Color.WHITE);
            statusIndicator.setText("✓ " + deviceName);
            statusIndicator.setTextColor(Color.parseColor("#4CAF50"));
            connectButton.setText("Connected");
            connectButton.setBackgroundColor(Color.parseColor("#4CAF50"));
            ringIdText.setText("Ring ID: " + deviceName);
            macAddressText.setText("MAC: " + macAddress + " | Version: " + version);
            batteryText.setText(batteryLevel + "%");
        } else {
            statusText.setText("Disconnected");
            statusText.setTextColor(Color.parseColor("#FF5722"));
            statusIndicator.setText("✗ Disconnected");
            statusIndicator.setTextColor(Color.parseColor("#FF5722"));
            connectButton.setText("Connect");
            connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
            ringIdText.setText("Ring ID: --");
            batteryText.setText("--");
        }
    }

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
        // 可以在这里添加日志显示逻辑
    }

    private void hideAllViews() {
        findViewById(R.id.dashboardLayout).setVisibility(View.GONE);
        findViewById(R.id.onlineLayout).setVisibility(View.GONE);
        findViewById(R.id.offlineLayout).setVisibility(View.GONE);
        findViewById(R.id.logsLayout).setVisibility(View.GONE);
    }


    private void setupFileList() {
        fileListContainer.removeAllViews();

        // 模拟文件列表
        String[] fileNames = {
                "010203040506_2025_06_23:13:31:31-...",
                "010203040506_2025_06_23:13:32:00-...",
                "010203040506_2025_06_23:14:06:47-..."
        };

        String[] fileSizes = {"190.4 KB", "192.1 KB", "1.8 MB"};
        String[] fileTimes = {"2025-06-23 21:31:31", "2025-06-23 21:32:00", "2025-06-23 22:06:47"};
        boolean[] selected = {false, true, false};

        for (int i = 0; i < fileNames.length; i++) {
            addFileItem(fileNames[i], fileSizes[i], fileTimes[i], selected[i]);
        }
    }

    private void addFileItem(String fileName, String fileSize, String time, boolean isSelected) {
        LinearLayout fileItem = new LinearLayout(this);
        fileItem.setOrientation(LinearLayout.HORIZONTAL);
        fileItem.setPadding(16, 12, 16, 12);

        // 创建选择框
        androidx.appcompat.widget.AppCompatCheckBox checkBox = new androidx.appcompat.widget.AppCompatCheckBox(this);
        checkBox.setChecked(isSelected);

        // 创建文件信息布局
        LinearLayout fileInfo = new LinearLayout(this);
        fileInfo.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams fileInfoParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        fileInfoParams.setMargins(24, 0, 0, 0);
        fileInfo.setLayoutParams(fileInfoParams);

        // 文件名
        TextView fileNameView = new TextView(this);
        fileNameView.setText(fileName);
        fileNameView.setTextSize(12);
        fileNameView.setTextColor(Color.BLACK);

        // 文件详情
        TextView fileDetails = new TextView(this);
        fileDetails.setText("IR+Red+Green+Temp+3-Axis | " + fileSize + " | " + time);
        fileDetails.setTextSize(10);
        fileDetails.setTextColor(Color.GRAY);

        fileInfo.addView(fileNameView);
        fileInfo.addView(fileDetails);

        fileItem.addView(checkBox);
        fileItem.addView(fileInfo);

        // 设置点击事件
        fileItem.setOnClickListener(v -> {
            checkBox.setChecked(!checkBox.isChecked());
            updateDownloadButton();
        });

        fileListContainer.addView(fileItem);
    }

    private void updateDownloadButton() {
        // 这里可以计算选中的文件数量并更新按钮文本
        downloadSelectedButton.setText("Download Selected (1)");
    }

    private void clearAllPlots() {
        // PPG图表
        if (plotViewG != null) plotViewG.clearPlot();
        if (plotViewI != null) plotViewI.clearPlot();
        if (plotViewR != null) plotViewR.clearPlot();

        // 加速度计图表
        if (plotViewX != null) plotViewX.clearPlot();
        if (plotViewY != null) plotViewY.clearPlot();
        if (plotViewZ != null) plotViewZ.clearPlot();

        // 陀螺仪图表
        if (plotViewGyroX != null) plotViewGyroX.clearPlot();
        if (plotViewGyroY != null) plotViewGyroY.clearPlot();
        if (plotViewGyroZ != null) plotViewGyroZ.clearPlot();

        // 温度图表
        if (plotViewTemp0 != null) plotViewTemp0.clearPlot();
        if (plotViewTemp1 != null) plotViewTemp1.clearPlot();
        if (plotViewTemp2 != null) plotViewTemp2.clearPlot();
    }

    // 在线测量功能
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

            // 开始测量
            isMeasuring = true;
            clearAllPlots();

            // 设置测量时间到NotificationHandler
            NotificationHandler.setMeasurementTime(measurementTime);

            // 更新UI状态
            updateMeasurementUI(true);
            updateMeasurementStatus("测量中... (0/" + measurementTime + "s)");

            recordLog("【开始在线测量】时间: " + measurementTime + "秒");

            // 启动测量计时器
            startMeasurementTimer(measurementTime);

            // 这里可以发送开始测量的指令到设备
            // 使用NotificationHandler或直接发送自定义指令
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

            // 停止测量计时器
            if (measurementTimer != null) {
                measurementTimer.cancel();
                measurementTimer = null;
            }

            // 更新UI状态
            updateMeasurementUI(false);
            updateMeasurementStatus("测量已停止");

            recordLog("【停止在线测量】");

            // 发送停止测量指令到设备
            NotificationHandler.stopMeasurement();

            Toast.makeText(this, "在线测量已停止", Toast.LENGTH_SHORT).show();
        }
    }

    private java.util.Timer measurementTimer;
    private int measurementElapsed = 0;
    private int totalMeasurementTime = 0;

    private void startMeasurementTimer(int totalTime) {
        totalMeasurementTime = totalTime;
        measurementElapsed = 0;

        measurementTimer = new java.util.Timer();
        measurementTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                measurementElapsed++;

                mainHandler.post(() -> {
                    updateMeasurementStatus("测量中... (" + measurementElapsed + "/" + totalMeasurementTime + "s)");
                });

                if (measurementElapsed >= totalMeasurementTime) {
                    // 测量时间到，自动停止
                    mainHandler.post(() -> {
                        stopOnlineMeasurement();
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
            stopMeasurementButton.setBackgroundColor(measuring ?
                    Color.parseColor("#F44336") : Color.GRAY);
        }
    }

    private void updateMeasurementStatus(String status) {
        if (measurementStatusText != null) {
            measurementStatusText.setText(status);
        }
    }

    // 按钮点击事件处理
    private void scanForDevices() {
        // 启动RingSettingsActivity
        Intent intent = new Intent(this, RingSettingsActivity.class);
        intent.putExtra("deviceName", "指环设备");
        startActivityForResult(intent, 100);
    }

    private void connectToDevice() {
        if (isConnected && connectionStatus == 7) {
            // 如果已连接，执行断开操作
            recordLog("用户点击断开连接");
            // 这里可以添加断开连接的逻辑
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
        connectButton.setText("Connecting...");
        connectButton.setBackgroundColor(Color.parseColor("#FF9800"));

        // 开始蓝牙连接
        try {
            android.bluetooth.BluetoothAdapter bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            android.bluetooth.BluetoothDevice device = bluetoothAdapter.getRemoteDevice(savedMacAddress);
            if (device != null) {
                BLEUtils.connectLockByBLE(this, device);
                macAddress = savedMacAddress;
            } else {
                Toast.makeText(this, "无效的MAC地址", Toast.LENGTH_SHORT).show();
                connectButton.setText("Connect");
                connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
            }
        } catch (Exception e) {
            recordLog("连接失败: " + e.getMessage());
            Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            connectButton.setText("Connect");
            connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
        }
    }

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

            recordLog("主机当前时间: " + currentTime + " ms");
            recordLog("当前时区偏移: UTC" + (timezoneOffset >= 0 ? "+" : "") + timezoneOffset);

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

            updateTimeButton.setText("Updating...");
            updateTimeButton.setBackgroundColor(Color.parseColor("#FF9800"));

            LmAPI.CUSTOMIZE_CMD(data, customizeCmdListener);

        } catch (Exception e) {
            recordLog("发送时间更新命令失败: " + e.getMessage());
            e.printStackTrace();

            updateTimeButton.setText("Update Time");
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
            recordLog("主机发送时间: " + timeSyncRequestTime + " ms");

            StringBuilder hexCommand = new StringBuilder();
            hexCommand.append(String.format("00%02X1002", timeSyncFrameId));

            long timestamp = timeSyncRequestTime;
            for (int i = 0; i < 8; i++) {
                hexCommand.append(String.format("%02X", (timestamp >> (i * 8)) & 0xFF));
            }

            byte[] data = hexStringToByteArray(hexCommand.toString());
            recordLog("发送时间校准命令: " + hexCommand.toString());

            calibrateButton.setText("Calibrating...");
            calibrateButton.setBackgroundColor(Color.parseColor("#FF9800"));
            timeSyncRequestTime = timeSyncRequestTime / 1000;

            LmAPI.CUSTOMIZE_CMD(data, customizeCmdListener);

        } catch (Exception e) {
            recordLog("发送时间校准命令失败: " + e.getMessage());
            e.printStackTrace();

            calibrateButton.setText("Calibrate");
            calibrateButton.setBackgroundColor(Color.parseColor("#4CAF50"));
            isTimeSyncing = false;
        }
    }

    private void toggleRecording() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        isRecording = !isRecording;
        if (isRecording) {
            startRecordingButton.setText("Stop");
            startRecordingButton.setBackgroundColor(Color.parseColor("#FF5722"));
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        } else {
            startRecordingButton.setText("Start");
            startRecordingButton.setBackgroundColor(Color.parseColor("#4CAF50"));
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetData() {
        clearAllPlots();

        Toast.makeText(this, "Data reset", Toast.LENGTH_SHORT).show();
    }

    private void startOfflineRecording() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        String totalDuration = totalDurationInput.getText().toString();
        String segmentDuration = segmentDurationInput.getText().toString();

        if (totalDuration.isEmpty() || segmentDuration.isEmpty()) {
            Toast.makeText(this, "请输入持续时间值", Toast.LENGTH_SHORT).show();
            return;
        }

        isOfflineRecording = true;
        startOfflineButton.setText("Recording...");
        startOfflineButton.setBackgroundColor(Color.parseColor("#FF5722"));

        Toast.makeText(this, "离线录制开始", Toast.LENGTH_SHORT).show();

        // 模拟录制完成
        mainHandler.postDelayed(() -> {
            isOfflineRecording = false;
            startOfflineButton.setText("Start Offline Recording");
            startOfflineButton.setBackgroundColor(Color.parseColor("#2196F3"));
            Toast.makeText(this, "离线录制完成", Toast.LENGTH_SHORT).show();
        }, 5000);
    }

    private void getFileList() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "获取文件列表中...", Toast.LENGTH_SHORT).show();
        getFileListButton.setText("Getting...");

        mainHandler.postDelayed(() -> {
            getFileListButton.setText("Get File List");
            setupFileList();
            Toast.makeText(this, "文件列表已更新", Toast.LENGTH_SHORT).show();
        }, 2000);
    }

    private void downloadSelectedFiles() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "下载选中文件中...", Toast.LENGTH_SHORT).show();
        downloadSelectedButton.setText("Downloading...");
        downloadSelectedButton.setBackgroundColor(Color.parseColor("#FF9800"));

        mainHandler.postDelayed(() -> {
            downloadSelectedButton.setText("Download Selected (1)");
            downloadSelectedButton.setBackgroundColor(Color.parseColor("#FF5722"));
            Toast.makeText(this, "文件下载成功", Toast.LENGTH_SHORT).show();
        }, 3000);
    }

    // 自定义指令响应处理
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
                }
            }
        } catch (Exception e) {
            recordLog("处理自定义指令响应失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleTimeUpdateResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("时间更新响应数据长度不足");
                return;
            }

            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            if (frameType != 0x00 || cmd != 0x10 || subcmd != 0x00) {
                recordLog("时间更新响应格式错误");
                return;
            }

            if (frameId != timeUpdateFrameId) {
                recordLog("时间更新响应Frame ID不匹配");
                return;
            }

            recordLog("【时间更新完成】戒指时间已成功更新");

            mainHandler.post(() -> {
                updateTimeButton.setText("Update Time");
                updateTimeButton.setBackgroundColor(Color.parseColor("#2196F3"));
                Toast.makeText(this, "戒指时间更新成功", Toast.LENGTH_SHORT).show();
            });

        } catch (Exception e) {
            recordLog("解析时间更新响应失败: " + e.getMessage());
            e.printStackTrace();

            mainHandler.post(() -> {
                updateTimeButton.setText("Update Time");
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

            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            if (frameType != 0x00 || cmd != 0x10 || subcmd != 0x02) {
                recordLog("时间校准响应格式错误");
                return;
            }

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
            long oneWayDelay = roundTripTime / 2;
            long timeDifference = ringReceivedTime - hostSentTime;

            recordLog("【时间校准结果】");
            recordLog(String.format("主机发送时间: %d ", hostSentTime));
            recordLog(String.format("戒指接收时间: %d ", ringReceivedTime));
            recordLog(String.format("戒指上传时间: %d ", ringUploadTime));
            recordLog(String.format("往返延迟: %d s", roundTripTime));
            recordLog(String.format("单程延迟估计: %d s", oneWayDelay));
            recordLog(String.format("时间差: %d s", timeDifference));

            mainHandler.post(() -> {
                calibrateButton.setText("Calibrate");
                calibrateButton.setBackgroundColor(Color.parseColor("#4CAF50"));
                Toast.makeText(this,
                        String.format("时间校准完成\n时间差: %d s\n延迟: %d s", timeDifference, roundTripTime),
                        Toast.LENGTH_LONG).show();
            });

        } catch (Exception e) {
            recordLog("解析时间校准响应失败: " + e.getMessage());
            e.printStackTrace();

            mainHandler.post(() -> {
                calibrateButton.setText("Calibrate");
                calibrateButton.setBackgroundColor(Color.parseColor("#4CAF50"));
            });
        } finally {
            isTimeSyncing = false;
        }
    }

    // 工具方法
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

    private int generateRandomFrameId() {
        Random random = new Random();
        return random.nextInt(256);
    }

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

    private void recordLog(String message) {
        android.util.Log.d("MainActivity", message);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK) {
            // 从RingSettingsActivity返回，重新加载设备信息
            loadDeviceInfo();
        }
    }

    // IResponseListener 接口实现
    @Override
    public void lmBleConnecting(int i) {
        recordLog("蓝牙连接中，状态码：" + i);
        connectionStatus = i;
        mainHandler.post(() -> {
            connectButton.setText("Connecting...");
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
            connectButton.setText("Connect");
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
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (measurementTimer != null) {
            measurementTimer.cancel();
            measurementTimer = null;
        }
    }
}
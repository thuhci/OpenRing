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
import com.tsinghua.sample.R;

import java.util.Random;

public class MainActivity extends AppCompatActivity {

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
    private int currentTab = 0; // 0: Dashboard, 1: Online, 2: Offline, 3: Logs
    private Handler mainHandler;
    private Random random = new Random();

    // 模拟数据
    private int ppgGreen = 1024;
    private int ppgIr = 856;
    private int ppgRed = 732;
    private float xAxis = 0.12f;
    private float yAxis = -0.05f;
    private float zAxis = 9.81f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());

        initializeViews();
        setupBottomNavigation();
        setupClickListeners();
        loadDeviceInfo();

        // 显示Dashboard页面
        showDashboard();

        // 启动数据模拟
        startDataSimulation();
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
        startRecordingButton = findViewById(R.id.startRecordingButton);
        resetButton = findViewById(R.id.resetButton);
        startOfflineButton = findViewById(R.id.startOfflineButton);
        getFileListButton = findViewById(R.id.getFileListButton);
        downloadSelectedButton = findViewById(R.id.downloadSelectedButton);

        // 输入框
        totalDurationInput = findViewById(R.id.totalDurationInput);
        segmentDurationInput = findViewById(R.id.segmentDurationInput);

        // 文件列表
        fileListStatus = findViewById(R.id.fileListStatus);
        fileListContainer = findViewById(R.id.fileListContainer);

        // PPG数据
        ppgGreenText = findViewById(R.id.ppgGreenText);
        ppgIrText = findViewById(R.id.ppgIrText);
        ppgRedText = findViewById(R.id.ppgRedText);
        xAxisText = findViewById(R.id.xAxisText);
        yAxisText = findViewById(R.id.yAxisText);
        zAxisText = findViewById(R.id.zAxisText);

        // 底部导航
        bottomNavigation = findViewById(R.id.bottomNavigation);

        // 设置默认值
        totalDurationInput.setText("120");
        segmentDurationInput.setText("60");
        fileListStatus.setText("4 files, 1 selected");
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
        connectButton.setOnClickListener(v -> toggleConnection());
        updateTimeButton.setOnClickListener(v -> updateDeviceTime());
        calibrateButton.setOnClickListener(v -> calibrateTime());
        startRecordingButton.setOnClickListener(v -> toggleRecording());
        resetButton.setOnClickListener(v -> resetData());
        startOfflineButton.setOnClickListener(v -> startOfflineRecording());
        getFileListButton.setOnClickListener(v -> getFileList());
        downloadSelectedButton.setOnClickListener(v -> downloadSelectedFiles());
    }

    private void loadDeviceInfo() {
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        String ringId = prefs.getString("ring_id", "BCL603DDCF");
        String macAddress = prefs.getString("mac_address", "DE:12:89:5B:DD:CF");

        ringIdText.setText("Ring ID: " + ringId);
        macAddressText.setText("MAC: " + macAddress + " | Version: v2.1.3");

        // 模拟连接状态
        updateConnectionStatus(true);
    }

    private void updateConnectionStatus(boolean connected) {
        isConnected = connected;
        if (connected) {
            statusText.setText("Connected");
            statusText.setTextColor(Color.WHITE);
            statusIndicator.setText("✓ Connected");
            statusIndicator.setTextColor(Color.parseColor("#4CAF50"));
            connectButton.setText("Connected");
            connectButton.setBackgroundColor(Color.parseColor("#4CAF50"));
            batteryText.setText("85%");
        } else {
            statusText.setText("Disconnected");
            statusText.setTextColor(Color.parseColor("#FF5722"));
            statusIndicator.setText("✗ Disconnected");
            statusIndicator.setTextColor(Color.parseColor("#FF5722"));
            connectButton.setText("Connect");
            connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
            batteryText.setText("--");
        }
    }

    private void showDashboard() {
        hideAllViews();
        findViewById(R.id.dashboardLayout).setVisibility(View.VISIBLE);
        updatePPGData();
    }

    private void showOnlineVisualize() {
        hideAllViews();
        findViewById(R.id.onlineLayout).setVisibility(View.VISIBLE);
        updatePPGData();
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

    private void updatePPGData() {
        if (ppgGreenText != null) {
            ppgGreenText.setText(String.valueOf(ppgGreen));
            ppgIrText.setText(String.valueOf(ppgIr));
            ppgRedText.setText(String.valueOf(ppgRed));
            xAxisText.setText(String.format("%.2f", xAxis));
            yAxisText.setText(String.format("%.2f", yAxis));
            zAxisText.setText(String.format("%.2f", zAxis));
        }
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

    private void startDataSimulation() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnected) {
                    // 模拟PPG数据变化
                    ppgGreen = 1024 + random.nextInt(200) - 100;
                    ppgIr = 856 + random.nextInt(200) - 100;
                    ppgRed = 732 + random.nextInt(200) - 100;

                    // 模拟加速度计数据
                    xAxis = 0.12f + (random.nextFloat() - 0.5f) * 0.2f;
                    yAxis = -0.05f + (random.nextFloat() - 0.5f) * 0.2f;
                    zAxis = 9.81f + (random.nextFloat() - 0.5f) * 0.5f;

                    updatePPGData();
                }

                // 继续模拟
                mainHandler.postDelayed(this, 100);
            }
        }, 100);
    }

    // 按钮点击事件处理
    private void scanForDevices() {
        Toast.makeText(this, "Scanning for devices...", Toast.LENGTH_SHORT).show();
        scanButton.setText("Scanning...");

        // 模拟扫描
        mainHandler.postDelayed(() -> {
            scanButton.setText("Scan Ring");
            Toast.makeText(this, "Scan completed", Toast.LENGTH_SHORT).show();
        }, 2000);
    }

    private void toggleConnection() {
        if (isConnected) {
            updateConnectionStatus(false);
            Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
        } else {
            updateConnectionStatus(true);
            Toast.makeText(this, "Connected to BCL603DDCF", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateDeviceTime() {
        if (!isConnected) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Updating device time...", Toast.LENGTH_SHORT).show();
        updateTimeButton.setText("Updating...");

        mainHandler.postDelayed(() -> {
            updateTimeButton.setText("Update Time");
            Toast.makeText(this, "Time updated successfully", Toast.LENGTH_SHORT).show();
        }, 2000);
    }

    private void calibrateTime() {
        if (!isConnected) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Calibrating time...", Toast.LENGTH_SHORT).show();
        calibrateButton.setText("Calibrating...");

        mainHandler.postDelayed(() -> {
            calibrateButton.setText("Calibrate");
            Toast.makeText(this, "Time calibrated successfully", Toast.LENGTH_SHORT).show();
        }, 2000);
    }

    private void toggleRecording() {
        if (!isConnected) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
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
        ppgGreen = 1024;
        ppgIr = 856;
        ppgRed = 732;
        xAxis = 0.12f;
        yAxis = -0.05f;
        zAxis = 9.81f;
        updatePPGData();
        Toast.makeText(this, "Data reset", Toast.LENGTH_SHORT).show();
    }

    private void startOfflineRecording() {
        if (!isConnected) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        String totalDuration = totalDurationInput.getText().toString();
        String segmentDuration = segmentDurationInput.getText().toString();

        if (totalDuration.isEmpty() || segmentDuration.isEmpty()) {
            Toast.makeText(this, "Please enter duration values", Toast.LENGTH_SHORT).show();
            return;
        }

        isOfflineRecording = true;
        startOfflineButton.setText("Recording...");
        startOfflineButton.setBackgroundColor(Color.parseColor("#FF5722"));

        Toast.makeText(this, "Offline recording started", Toast.LENGTH_SHORT).show();

        // 模拟录制完成
        mainHandler.postDelayed(() -> {
            isOfflineRecording = false;
            startOfflineButton.setText("Start Offline Recording");
            startOfflineButton.setBackgroundColor(Color.parseColor("#2196F3"));
            Toast.makeText(this, "Offline recording completed", Toast.LENGTH_SHORT).show();
        }, 5000);
    }

    private void getFileList() {
        if (!isConnected) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Getting file list...", Toast.LENGTH_SHORT).show();
        getFileListButton.setText("Getting...");

        mainHandler.postDelayed(() -> {
            getFileListButton.setText("Get File List");
            setupFileList();
            Toast.makeText(this, "File list updated", Toast.LENGTH_SHORT).show();
        }, 2000);
    }

    private void downloadSelectedFiles() {
        if (!isConnected) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Downloading selected files...", Toast.LENGTH_SHORT).show();
        downloadSelectedButton.setText("Downloading...");
        downloadSelectedButton.setBackgroundColor(Color.parseColor("#FF9800"));

        mainHandler.postDelayed(() -> {
            downloadSelectedButton.setText("Download Selected (1)");
            downloadSelectedButton.setBackgroundColor(Color.parseColor("#FF5722"));
            Toast.makeText(this, "Files downloaded successfully", Toast.LENGTH_SHORT).show();
        }, 3000);
    }
}
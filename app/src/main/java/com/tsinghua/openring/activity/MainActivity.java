package com.tsinghua.openring.activity;

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
import com.lm.sdk.LmAPI;
import com.lm.sdk.inter.IResponseListener;
import com.lm.sdk.inter.ICustomizeCmdListener;
import com.lm.sdk.mode.SystemControlBean;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.openring.R;
import com.tsinghua.openring.PlotView;
import com.tsinghua.openring.utils.NotificationHandler;

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

    // UI Components
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
    private Button formatFileSystemBtn;

    private EditText totalDurationInput;
    private EditText segmentDurationInput;
    private TextView fileListStatus;
    private LinearLayout fileListContainer;
    private BottomNavigationView bottomNavigation;

    // Status Variables
    private boolean isConnected = false;
    private boolean isRecording = false;
    private boolean isOfflineRecording = false;
    private int connectionStatus = 0; // Bluetooth connection status
    private int currentTab = 0; // 0: Dashboard, 1: Online, 2: Offline, 3: Logs
    private Handler mainHandler;
    private Random random = new Random();

    // Device Information
    private String deviceName = "";
    private String macAddress = "";
    private String version = "";
    private int batteryLevel = 0;
    private int currentFilePackets = 0;  // 当前文件总包数
    private int receivedPackets = 0;     // 已接收包数
    // Time Sync Related
    private boolean isTimeUpdating = false;
    private boolean isTimeSyncing = false;
    private int timeUpdateFrameId = 0;
    private int timeSyncFrameId = 0;
    private long timeSyncRequestTime = 0;

    // Online Measurement Related
    private EditText measurementTimeInput;
    private Button startMeasurementButton;
    private Button stopMeasurementButton;
    private TextView measurementStatusText;
    private PlotView plotViewG, plotViewI, plotViewR;
    private PlotView plotViewX, plotViewY, plotViewZ;
    private PlotView plotViewGyroX, plotViewGyroY, plotViewGyroZ;
    private PlotView plotViewTemp0, plotViewTemp1, plotViewTemp2;
    private boolean isMeasuring = false;

    // File Operation Related
    private List<FileInfo> fileList = new ArrayList<>();
    private List<FileInfo> selectedFiles = new ArrayList<>();
    private boolean isDownloadingFiles = false;
    private int currentDownloadIndex = 0;

    // Exercise Control Related
    private EditText exerciseTotalDurationInput;
    private EditText exerciseSegmentDurationInput;
    private Button startExerciseButton;
    private Button stopExerciseButton;
    private TextView exerciseStatusText;
    private boolean isExercising = false;
    private ICustomizeCmdListener fileTransferCmdListener = new ICustomizeCmdListener() {
        @Override
        public void cmdData(String responseData) {
            byte[] responseBytes = hexStringToByteArray(responseData);
            handleCustomizeResponse(responseBytes);
        }
    };

    // Log Recording Related
    private Button startLogRecordingButton;
    private Button stopLogRecordingButton;
    private TextView logStatusText;
    private TextView logDisplayText;
    private BufferedWriter logWriter;
    private boolean isLogRecording = false;

    // Measurement Related
    private Timer measurementTimer;
    private int measurementElapsed = 0;
    private int totalMeasurementTime = 0;

    // File Information Class
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
                case 1: return "3-Axis Data";
                case 2: return "6-Axis Data";
                case 3: return "PPG IR+Red+3-Axis (SpO2)";
                case 4: return "PPG Green";
                case 5: return "PPG IR";
                case 6: return "Temperature IR";
                case 7: return "IR+Red+Green+Temp+3-Axis";
                case 8: return "PPG Green+3-Axis (HR)";
                default: return "Unknown Type";
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

    // Custom Command Listener
    private ICustomizeCmdListener customizeCmdListener = new ICustomizeCmdListener() {
        @Override
        public void cmdData(String responseData) {
            byte[] responseBytes = hexStringToByteArray(responseData);
            recordLog("Received custom command response: " + responseData);
            handleCustomizeResponse(responseBytes);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize SDK
        LmAPI.init(getApplication());
        LmAPI.setDebug(true);
        LmAPI.addWLSCmdListener(this, this);

        initializeViews();
        setupBottomNavigation();
        setupClickListeners();
        setupNotificationHandler();
        loadDeviceInfo();

        // Show Dashboard page
        showDashboard();
    }

    private void initializeViews() {
        // Status display
        statusText = findViewById(R.id.statusText);
        ringIdText = findViewById(R.id.ringIdText);
        macAddressText = findViewById(R.id.macAddressText);
        statusIndicator = findViewById(R.id.statusIndicator);
        batteryText = findViewById(R.id.batteryText);
        connectionIcon = findViewById(R.id.connectionIcon);

        // Buttons
        scanButton = findViewById(R.id.scanButton);
        connectButton = findViewById(R.id.connectButton);
        updateTimeButton = findViewById(R.id.updateTimeButton);
        calibrateButton = findViewById(R.id.calibrateButton);
        getFileListButton = findViewById(R.id.getFileListButton);
        downloadSelectedButton = findViewById(R.id.downloadSelectedButton);
        formatFileSystemBtn = findViewById(R.id.formatFileSystemButton);

        // Input fields
        // File list
        fileListStatus = findViewById(R.id.fileListStatus);
        fileListContainer = findViewById(R.id.fileListContainer);

        // Online measurement related
        measurementTimeInput = findViewById(R.id.measurementTimeInput);
        startMeasurementButton = findViewById(R.id.startMeasurementButton);
        stopMeasurementButton = findViewById(R.id.stopMeasurementButton);
        measurementStatusText = findViewById(R.id.measurementStatusText);

        // Exercise control related
        exerciseTotalDurationInput = findViewById(R.id.exerciseTotalDurationInput);
        exerciseSegmentDurationInput = findViewById(R.id.exerciseSegmentDurationInput);
        startExerciseButton = findViewById(R.id.startExerciseButton);
        stopExerciseButton = findViewById(R.id.stopExerciseButton);
        exerciseStatusText = findViewById(R.id.exerciseStatusText);

        // Log recording related
        startLogRecordingButton = findViewById(R.id.startLogRecordingButton);
        stopLogRecordingButton = findViewById(R.id.stopLogRecordingButton);
        logStatusText = findViewById(R.id.logStatusText);
        logDisplayText = findViewById(R.id.logDisplayText);

        // Initialize PlotView
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

        // Set PlotView colors
        setupPlotViewColors();

        // Bottom navigation
        bottomNavigation = findViewById(R.id.bottomNavigation);

        // Set default values
        setDefaultValues();

        // Initialize status
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
        if (logStatusText != null) logStatusText.setText("Status: Ready");
        if (logDisplayText != null) logDisplayText.setText("Logs will be displayed here...");
    }

    private void setupNotificationHandler() {
        // Connect NotificationHandler's PlotView
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

        // Set device command callback
        NotificationHandler.setDeviceCommandCallback(new NotificationHandler.DeviceCommandCallback() {
            @Override
            public void sendCommand(byte[] commandData) {
                LmAPI.CUSTOMIZE_CMD(commandData, customizeCmdListener);
                recordLog("Send device command: " + bytesToHexString(commandData));
            }

            @Override
            public void onMeasurementStarted() {
                recordLog("[Measurement Started]");
                mainHandler.post(() -> {
                    updateMeasurementUI(true);
                    updateMeasurementStatus("Measuring...");
                });
            }

            @Override
            public void onMeasurementStopped() {
                recordLog("[Measurement Stopped]");
                mainHandler.post(() -> {
                    updateMeasurementUI(false);
                    updateMeasurementStatus("Stopped");
                });
            }

            @Override
            public void onExerciseStarted(int duration, int segmentTime) {
                recordLog(String.format("[Exercise Started] Total: %d sec, Segment: %d sec", duration, segmentTime));
                mainHandler.post(() -> {
                    updateExerciseUI(true);
                    updateExerciseStatus("Exercising...");
                });
            }

            @Override
            public void onExerciseStopped() {
                recordLog("[Exercise Stopped]");
                mainHandler.post(() -> {
                    updateExerciseUI(false);
                    updateExerciseStatus("Stopped");
                });
            }
        });

        // Set file operation callback
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

        // Set time sync callback
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

        // Set log recorder
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
        formatFileSystemBtn.setOnClickListener(v-> formatFileSystem());
        // Online measurement buttons
        if (startMeasurementButton != null) {
            startMeasurementButton.setOnClickListener(v -> startOnlineMeasurement());
        }
        if (stopMeasurementButton != null) {
            stopMeasurementButton.setOnClickListener(v -> stopOnlineMeasurement());
        }

        // Exercise control buttons
        if (startExerciseButton != null) {
            startExerciseButton.setOnClickListener(v -> startExercise());
        }
        if (stopExerciseButton != null) {
            stopExerciseButton.setOnClickListener(v -> stopExercise());
        }

        // Log recording buttons
        if (startLogRecordingButton != null) {
            startLogRecordingButton.setOnClickListener(v -> startLogRecording());
        }
        if (stopLogRecordingButton != null) {
            stopLogRecordingButton.setOnClickListener(v -> stopLogRecording());
        }
    }

    // ==================== Page Switching ====================

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

    // ==================== File Operations ====================

    private void getFileList() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        recordLog("[Request File List] Using custom command");
        try {
            String hexCommand = String.format("00%02X3610", generateRandomFrameId());
            byte[] data = hexStringToByteArray(hexCommand);
            recordLog("Send file list command: " + hexCommand);
            LmAPI.CUSTOMIZE_CMD(data, customizeCmdListener);

            fileList.clear();
            selectedFiles.clear();
            updateFileListUI();

            mainHandler.post(() -> {
                getFileListButton.setText("Getting...");
                getFileListButton.setEnabled(false);
            });

        } catch (Exception e) {
            recordLog("Failed to send file list request: " + e.getMessage());
            Toast.makeText(this, "Failed to get file list", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleFileListResponse(byte[] data) {
        try {
            if (data == null || data.length < 12) {
                recordLog("File list response data length insufficient");
                return;
            }

            int totalFiles = readUInt32LE(data, 4);
            int seqNum = readUInt32LE(data, 8);
            int fileSize = readUInt32LE(data, 12);

            recordLog(String.format("File list info - Total: %d, Seq: %d, Size: %d", totalFiles, seqNum, fileSize));

            if (totalFiles > 0 && data.length > 16) {
                // Parse filename
                byte[] fileNameBytes = new byte[data.length - 16];
                System.arraycopy(data, 16, fileNameBytes, 0, fileNameBytes.length);

                String fileName = new String(fileNameBytes, "UTF-8").trim();
                fileName = fileName.replace("\0", "");

                if (!fileName.isEmpty()) {
                    FileInfo fileInfo = new FileInfo(fileName, fileSize);
                    fileList.add(fileInfo);
                    recordLog("Add file: " + fileName + " (" + fileInfo.getFormattedSize() + ")");
                }
            }

            mainHandler.post(() -> {
                setupFileList();
                getFileListButton.setText("Get File List");
                getFileListButton.setEnabled(true);

            });

        } catch (Exception e) {
            recordLog("Failed to parse file list: " + e.getMessage());
            mainHandler.post(() -> {
                getFileListButton.setText("Get File List");
                getFileListButton.setEnabled(true);
            });
        }
    }

    private void handleFileDataResponse(byte[] data) {
        try {
            if (data.length < 4) {
                recordLog("File data response length insufficient");
                return;
            }

            if (data[2] == 0x36 && data[3] == 0x11) {
                int offset = 4;

                if (data.length < offset + 25) {
                    recordLog("File data structure incomplete, requires at least 25 bytes header");
                    recordLog("Actual length: " + (data.length - offset) + " bytes");
                    return;
                }

                int fileStatus = data[offset] & 0xFF;
                offset += 1;
                int fileSize = readUInt32LE(data, offset);
                offset += 4;
                int totalPackets = readUInt32LE(data, offset);
                offset += 4;
                int currentPacket = readUInt32LE(data, offset);
                offset += 4;
                int currentPacketLength = readUInt32LE(data, offset);
                offset += 4;
                long timestamp = readUInt64LE(data, offset);
                offset += 8;

                // 更新包计数器
                currentFilePackets = totalPackets;
                receivedPackets = currentPacket;

                recordLog(String.format("File packet %d/%d received, size: %d bytes",
                        currentPacket, totalPackets, currentPacketLength));

                // 实时更新下载进度到按钮
                if (isDownloadingFiles && currentDownloadIndex < selectedFiles.size()) {
                    FileInfo currentFile = selectedFiles.get(currentDownloadIndex);
                    updateDownloadButtonProgress(currentDownloadIndex + 1, selectedFiles.size(),
                            String.format("%s (%d/%d)", currentFile.fileName, currentPacket, totalPackets));
                }

                // 处理数据内容（保持原有的数据解析逻辑）
                int requiredLength = 25 + 5 * 30;
                int availableLength = data.length - 4;

                if (availableLength >= requiredLength) {
                    int dataNum = 5;
                    for (int groupIdx = 0; groupIdx < dataNum; groupIdx++) {
                        int dataOffset = (4 + 25) + groupIdx * 30;

                        if (dataOffset + 30 > data.length) {
                            recordLog("Group " + (groupIdx + 1) + " data incomplete");
                            break;
                        }

                        long green = readUInt32LE(data, dataOffset);
                        long red = readUInt32LE(data, dataOffset + 4);
                        long ir = readUInt32LE(data, dataOffset + 8);
                        short accX = readInt16LE(data, dataOffset + 12);
                        short accY = readInt16LE(data, dataOffset + 14);
                        short accZ = readInt16LE(data, dataOffset + 16);
                        short gyroX = readInt16LE(data, dataOffset + 18);
                        short gyroY = readInt16LE(data, dataOffset + 20);
                        short gyroZ = readInt16LE(data, dataOffset + 22);
                        short temper0 = readInt16LE(data, dataOffset + 24);
                        short temper1 = readInt16LE(data, dataOffset + 26);
                        short temper2 = readInt16LE(data, dataOffset + 28);

                        String logMsg = String.format("green:%d red:%d ir:%d " +
                                        "acc_x:%d acc_y:%d acc_z:%d " +
                                        "gyro_x:%d gyro_y:%d gyro_z:%d " +
                                        "temper0:%d temper1:%d temper2:%d",
                                green, red, ir, accX, accY, accZ, gyroX, gyroY, gyroZ, temper0, temper1, temper2);
                        recordLog(logMsg);
                    }
                }

                // 保存文件数据
                if (isDownloadingFiles && currentDownloadIndex < selectedFiles.size()) {
                    FileInfo currentFile = selectedFiles.get(currentDownloadIndex);
                    saveFileDataToFixedLocation(currentFile, data, currentPacket, totalPackets);

                    // 检查是否是最后一个包
                    if (currentPacket >= totalPackets) {
                        recordLog("File download completed: " + currentFile.fileName);

                        // 显示文件完成状态
                        updateDownloadButtonProgress(currentDownloadIndex + 1, selectedFiles.size(),
                                currentFile.fileName + " ✓ Complete");

                        // 文件下载完成，处理下一个文件
                        currentDownloadIndex++;

                        // 短暂显示完成状态后开始下一个文件
                        mainHandler.postDelayed(() -> downloadNextSelectedFile(), 800);
                    }
                }
            }
        } catch (Exception e) {
            recordLog("Failed to handle file data: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void updateDownloadButtonProgress(int currentFileIndex, int totalFiles, String statusText) {
        mainHandler.post(() -> {
            if (downloadSelectedButton != null) {
                String buttonText = String.format("Downloading %d/%d\n%s",
                        currentFileIndex, totalFiles, statusText);
                downloadSelectedButton.setText(buttonText);
                downloadSelectedButton.setEnabled(false);
            }
        });
    }


    private void downloadSelectedFiles() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "Please select files to download first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        isDownloadingFiles = true;
        currentDownloadIndex = 0;
        currentFilePackets = 0;
        receivedPackets = 0;

        recordLog(String.format("[Start Batch Download] Selected files: %d", selectedFiles.size()));

        // 更新按钮显示初始状态
        updateDownloadButtonProgress(0, 0, "Initializing...");

        downloadNextSelectedFile();
    }
    private void saveFileDataToFixedLocation(FileInfo fileInfo, byte[] data, int currentPacket, int totalPackets) {
        try {
            String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    + "/Sample/" + "/RingLog/Downloads/";
            File directory = new File(directoryPath);
            if (!directory.exists()) {
                directory.mkdirs();
            }
            String safeFileName = fileInfo.fileName.replace(":", "_");

            File file = new File(directory, safeFileName);
            boolean append = currentPacket > 1;

            try (FileWriter fileWriter = new FileWriter(file, append);
                 BufferedWriter writer = new BufferedWriter(fileWriter)) {

                if (currentPacket == 1) {
                    writer.write("# ========== File Information ==========\n");
                    writer.write("# File name: " + fileInfo.fileName + "\n");
                    writer.write("# File type: " + fileInfo.getFileTypeDescription() + "\n");
                    writer.write("# User ID: " + fileInfo.userId + "\n");
                    writer.write("# Time: " + fileInfo.timestamp + "\n");
                    writer.write("# Download time: " + getCurrentTimestamp() + "\n");
                    writer.write("# Total packets: " + totalPackets + "\n");
                    writer.write("# =======================================\n\n");
                }
                writer.write("# Packet " + currentPacket + "/" + totalPackets + ":\n");
                writer.write("# Raw data: " + bytesToHexString(data) + "\n");
                writer.write("\n");
                writer.flush();
            }

            recordLog(String.format("File data saved: %s (Packet %d/%d) -> %s",
                    fileInfo.fileName, currentPacket, totalPackets, file.getAbsolutePath()));

        } catch (IOException e) {
            recordLog("Failed to save file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void downloadNextSelectedFile() {
        if (currentDownloadIndex >= selectedFiles.size()) {
            // 所有文件下载完成
            isDownloadingFiles = false;
            mainHandler.post(() -> {
                downloadSelectedButton.setText("Download Selected (" + selectedFiles.size() + ")");
                downloadSelectedButton.setEnabled(true);
                Toast.makeText(this, "All files downloaded", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        FileInfo fileInfo = selectedFiles.get(currentDownloadIndex);
        recordLog(String.format("Downloading file %d/%d: %s",
                currentDownloadIndex + 1, selectedFiles.size(), fileInfo.fileName));

        // 重置当前文件的包计数器
        currentFilePackets = 0;
        receivedPackets = 0;

        // 更新按钮显示
        updateDownloadButtonProgress(currentDownloadIndex + 1, selectedFiles.size(),
                "Starting " + fileInfo.fileName + "...");

        try {
            byte[] fileNameBytes = fileInfo.fileName.getBytes("UTF-8");
            String hexCommand = String.format("00%02X3611", generateRandomFrameId());
            StringBuilder sb = new StringBuilder(hexCommand);
            for (byte b : fileNameBytes) {
                sb.append(String.format("%02X", b & 0xFF));
            }

            byte[] commandData = hexStringToByteArray(sb.toString());
            LmAPI.CUSTOMIZE_CMD(commandData, customizeCmdListener);

            recordLog("Sent download command for: " + fileInfo.fileName);

        } catch (Exception e) {
            recordLog("Download file failed: " + e.getMessage());
            // 如果发送命令失败，跳过这个文件继续下一个
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
            fileListStatus.setText(String.format("Total %d files, %d selected", fileList.size(), selectedFiles.size()));

            // 只有在不下载时才更新按钮文本
            if (!isDownloadingFiles) {
                downloadSelectedButton.setText("Download Selected (" + selectedFiles.size() + ")");
                downloadSelectedButton.setEnabled(selectedFiles.size() > 0);
            }
        });
    }

    // ==================== Exercise Control ====================

    private void startExercise() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isExercising) {
            Toast.makeText(this, "Exercise is already in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String totalDurationStr = exerciseTotalDurationInput.getText().toString().trim();
            String segmentDurationStr = exerciseSegmentDurationInput.getText().toString().trim();

            if (totalDurationStr.isEmpty() || segmentDurationStr.isEmpty()) {
                Toast.makeText(this, "Please enter exercise duration and segment duration", Toast.LENGTH_SHORT).show();
                return;
            }

            int totalDuration = Integer.parseInt(totalDurationStr);
            int segmentDuration = Integer.parseInt(segmentDurationStr);

            if (totalDuration < 60 || totalDuration > 86400) {
                Toast.makeText(this, "Total exercise duration should be between 60-86400 seconds", Toast.LENGTH_SHORT).show();
                return;
            }

            if (segmentDuration < 30 || segmentDuration > totalDuration) {
                Toast.makeText(this, "Segment duration should be between 30 seconds and total duration", Toast.LENGTH_SHORT).show();
                return;
            }

            NotificationHandler.setExerciseParams(totalDuration, segmentDuration);
            boolean success = NotificationHandler.startExercise();

            if (success) {
                isExercising = true;
                recordLog(String.format("[Start Exercise] Total: %d sec, Segment: %d sec", totalDuration, segmentDuration));
                updateExerciseUI(true);
                updateExerciseStatus(String.format("Exercising - Total: %d min, Segment: %d min", totalDuration/60, segmentDuration/60));
                Toast.makeText(this, "Exercise started", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Failed to start exercise", Toast.LENGTH_SHORT).show();
            }

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            recordLog("Failed to start exercise: " + e.getMessage());
            Toast.makeText(this, "Failed to start exercise: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private short readInt16LE(byte[] data, int offset) {
        if (offset + 2 > data.length) {
            throw new IndexOutOfBoundsException("数据不足以读取2字节短整型");
        }
        return (short)((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
    }
    private void stopExercise() {
        if (!isExercising) {
            Toast.makeText(this, "No exercise currently in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            boolean success = NotificationHandler.stopExercise();
            if (success) {
                isExercising = false;
                recordLog("[End Exercise] User manually stopped");
                updateExerciseUI(false);
                updateExerciseStatus("Stopped");
                Toast.makeText(this, "Exercise stopped", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Failed to stop exercise", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            recordLog("Failed to stop exercise: " + e.getMessage());
            Toast.makeText(this, "Failed to stop exercise: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateExerciseUI(boolean exercising) {
        if (startExerciseButton != null) {
            startExerciseButton.setEnabled(!exercising);
            startExerciseButton.setText(exercising ? "Exercising..." : "Start Exercise");
        }

        if (stopExerciseButton != null) {
            stopExerciseButton.setEnabled(exercising);
            stopExerciseButton.setBackgroundColor(exercising ? Color.parseColor("#F44336") : Color.GRAY);
        }
    }

    private void updateExerciseStatus(String status) {
        if (exerciseStatusText != null) {
            exerciseStatusText.setText("Exercise Status: " + status);
        }
    }

    // ==================== Log Recording ====================

    private void startLogRecording() {
        if (isLogRecording) {
            Toast.makeText(this, "Log recording is already in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            createLogFile();
            isLogRecording = true;

            recordLog("=".repeat(50));
            recordLog("[Start Log Recording Session] Time: " + getCurrentTimestamp());
            recordLog("Device: " + deviceName);
            recordLog("MAC: " + macAddress);
            recordLog("=".repeat(50));

            updateLogRecordingUI(true);
            updateLogStatus("Recording...");
            Toast.makeText(this, "Log recording started", Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            recordLog("Failed to create log file: " + e.getMessage());
            Toast.makeText(this, "Failed to create log file", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopLogRecording() {
        if (!isLogRecording) {
            Toast.makeText(this, "No log recording currently in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        recordLog("=".repeat(50));
        recordLog("[End Log Recording Session] Time: " + getCurrentTimestamp());
        recordLog("=".repeat(50));

        isLogRecording = false;
        updateLogRecordingUI(false);
        updateLogStatus("Ready");

        try {
            if (logWriter != null) {
                logWriter.close();
                logWriter = null;
            }
        } catch (IOException e) {
            recordLog("Failed to close log file: " + e.getMessage());
        }

        Toast.makeText(this, "Log recording stopped", Toast.LENGTH_SHORT).show();
    }

    private void createLogFile() throws IOException {

        String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                + "/Sample/" +  "/RingLog/";
        File directory = new File(directoryPath);

        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new IOException("Failed to create directory: " + directoryPath);
            }
        }

        String fileName = "MainSession_" + System.currentTimeMillis() + ".txt";
        File logFile = new File(directory, fileName);
        logWriter = new BufferedWriter(new FileWriter(logFile, true));

        recordLog("Log file created: " + logFile.getAbsolutePath());
    }

    private void updateLogRecordingUI(boolean recording) {
        if (startLogRecordingButton != null) {
            startLogRecordingButton.setEnabled(!recording);
            startLogRecordingButton.setText(recording ? "Recording..." : "Start Recording");
        }

        if (stopLogRecordingButton != null) {
            stopLogRecordingButton.setEnabled(recording);
            stopLogRecordingButton.setBackgroundColor(recording ? Color.parseColor("#F44336") : Color.GRAY);
        }
    }

    private void updateLogStatus(String status) {
        if (logStatusText != null) {
            logStatusText.setText("Status: " + status);
        }
    }

    // ==================== Online Measurement ====================

    private void startOnlineMeasurement() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        String timeStr = measurementTimeInput.getText().toString().trim();
        if (timeStr.isEmpty()) {
            Toast.makeText(this, "Please enter measurement time", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int measurementTime = Integer.parseInt(timeStr);
            if (measurementTime < 1 || measurementTime > 3600) {
                Toast.makeText(this, "Measurement time should be between 1-3600 seconds", Toast.LENGTH_SHORT).show();
                return;
            }

            isMeasuring = true;
            clearAllPlots();

            NotificationHandler.setMeasurementTime(measurementTime);
            updateMeasurementUI(true);
            updateMeasurementStatus("Measuring... (0/" + measurementTime + "s)");

            recordLog("[Start Online Measurement] Time: " + measurementTime + " seconds");

            startMeasurementTimer(measurementTime);

            if (NotificationHandler.startActiveMeasurement()) {
                Toast.makeText(this, "Online measurement started", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Failed to start measurement", Toast.LENGTH_SHORT).show();
                stopOnlineMeasurement();
            }

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid measurement time", Toast.LENGTH_SHORT).show();
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
            updateMeasurementStatus("Measurement stopped");

            recordLog("[Stop Online Measurement]");
            NotificationHandler.stopMeasurement();

            Toast.makeText(this, "Online measurement stopped", Toast.LENGTH_SHORT).show();
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
                    updateMeasurementStatus("Measuring... (" + measurementElapsed + "/" + totalMeasurementTime + "s)");
                });

                if (measurementElapsed >= totalMeasurementTime) {
                    mainHandler.post(() -> {
                        updateMeasurementStatus("Measurement completed");
                        Toast.makeText(MainActivity.this, "Measurement completed", Toast.LENGTH_SHORT).show();
                    });
                    this.cancel();
                }
            }
        }, 1000, 1000);
    }

    private void updateMeasurementUI(boolean measuring) {
        if (startMeasurementButton != null) {
            startMeasurementButton.setEnabled(!measuring);
            startMeasurementButton.setText(measuring ? "Measuring..." : "Start Measurement");
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

    // ==================== Basic Functions ====================

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
        intent.putExtra("deviceName", "Ring Device");
        startActivityForResult(intent, 100);
    }

    private void connectToDevice() {
        if (isConnected && connectionStatus == 7) {
            recordLog("User clicked disconnect");
            updateConnectionStatus(false);
            connectionStatus = 0;
            Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        String savedMacAddress = prefs.getString("mac_address", "");

        if (savedMacAddress.isEmpty()) {
            Toast.makeText(this, "Please scan and select device first", Toast.LENGTH_SHORT).show();
            return;
        }

        recordLog("Start connecting device: " + savedMacAddress);
        connectButton.setText("Connecting...");
        connectButton.setBackgroundColor(Color.parseColor("#FF9800"));

        try {
            android.bluetooth.BluetoothAdapter bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            android.bluetooth.BluetoothDevice device = bluetoothAdapter.getRemoteDevice(savedMacAddress);
            if (device != null) {
                BLEUtils.connectLockByBLE(this, device);
                macAddress = savedMacAddress;
            } else {
                Toast.makeText(this, "Invalid MAC address", Toast.LENGTH_SHORT).show();
                connectButton.setText("Connect");
                connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
            }
        } catch (Exception e) {
            recordLog("Connection failed: " + e.getMessage());
            Toast.makeText(this, "Connection failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            connectButton.setText("Connect");
            connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
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

    // ==================== Time Sync Functions ====================

    private void updateDeviceTime() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isTimeUpdating) {
            Toast.makeText(this, "Time update in progress, please wait", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            isTimeUpdating = true;
            timeUpdateFrameId = generateRandomFrameId();

            recordLog("[Start Ring Time Update] Using custom command");

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
            recordLog("Send time update command: " + hexCommand.toString());

            updateTimeButton.setText("Updating...");
            updateTimeButton.setBackgroundColor(Color.parseColor("#FF9800"));

            LmAPI.CUSTOMIZE_CMD(data, customizeCmdListener);

        } catch (Exception e) {
            recordLog("Failed to send time update command: " + e.getMessage());
            updateTimeButton.setText("Update Time");
            updateTimeButton.setBackgroundColor(Color.parseColor("#2196F3"));
            isTimeUpdating = false;
        }
    }

    private void calibrateTime() {
        if (!isConnected || connectionStatus != 7) {
            Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isTimeSyncing) {
            Toast.makeText(this, "Time calibration in progress, please wait", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            isTimeSyncing = true;
            timeSyncRequestTime = System.currentTimeMillis();
            timeSyncFrameId = generateRandomFrameId();

            recordLog("[Start Time Calibration Sync] Using custom command");

            StringBuilder hexCommand = new StringBuilder();
            hexCommand.append(String.format("00%02X1002", timeSyncFrameId));

            long timestamp = timeSyncRequestTime;
            for (int i = 0; i < 8; i++) {
                hexCommand.append(String.format("%02X", (timestamp >> (i * 8)) & 0xFF));
            }

            byte[] data = hexStringToByteArray(hexCommand.toString());
            recordLog("Send time calibration command: " + hexCommand.toString());

            calibrateButton.setText("Calibrating...");
            calibrateButton.setBackgroundColor(Color.parseColor("#FF9800"));
            timeSyncRequestTime = timeSyncRequestTime / 1000;

            LmAPI.CUSTOMIZE_CMD(data, customizeCmdListener);

        } catch (Exception e) {
            recordLog("Failed to send time calibration command: " + e.getMessage());
            calibrateButton.setText("Calibrate");
            calibrateButton.setBackgroundColor(Color.parseColor("#4CAF50"));
            isTimeSyncing = false;
        }
    }

    // ==================== Custom Command Response Handling ====================

    private void handleCustomizeResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("Custom command response data length insufficient");
                return;
            }

            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            recordLog(String.format("Response parsing: FrameType=0x%02X, FrameID=0x%02X, Cmd=0x%02X, Subcmd=0x%02X",
                    frameType, frameId, cmd, subcmd));

            if (frameType == 0x00) {
                if (cmd == 0x10) {
                    if (subcmd == 0x00) {
                        recordLog("Identified as time update response");
                        handleTimeUpdateResponse(data);
                    } else if (subcmd == 0x02) {
                        recordLog("Identified as time calibration response");
                        handleTimeSyncResponse(data);
                    }
                } else if (cmd == 0x36) {
                    if (subcmd == 0x10) {
                        recordLog("Identified as file list response");
                        handleFileListResponse(data);
                    } else if (subcmd == 0x11) {
                        recordLog("Identified as file data response");
                        handleFileDataResponse(data);
                    } else if (subcmd == 0x13){
                        recordLog("Identified as format file system response");
                    }
                }
            }
        } catch (Exception e) {
            recordLog("Failed to handle custom command response: " + e.getMessage());
        }
    }

    private void handleTimeUpdateResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("Time update response data length insufficient");
                return;
            }

            int frameId = data[1] & 0xFF;
            if (frameId != timeUpdateFrameId) {
                recordLog("Time update response Frame ID does not match");
                return;
            }

            recordLog("[Time Update Complete] Ring time has been successfully updated");

            mainHandler.post(() -> {
                updateTimeButton.setText("Update Time");
                updateTimeButton.setBackgroundColor(Color.parseColor("#2196F3"));
                Toast.makeText(this, "Ring time updated successfully", Toast.LENGTH_SHORT).show();
            });

        } catch (Exception e) {
            recordLog("Failed to parse time update response: " + e.getMessage());
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
                recordLog("Time calibration response data length insufficient");
                return;
            }

            int frameId = data[1] & 0xFF;
            if (frameId != timeSyncFrameId) {
                recordLog("Time calibration response Frame ID does not match");
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

            recordLog("[Time Calibration Result]");
            recordLog(String.format("Round trip delay: %d s", roundTripTime));
            recordLog(String.format("Time difference: %d s", timeDifference));

            mainHandler.post(() -> {
                calibrateButton.setText("Calibrate");
                calibrateButton.setBackgroundColor(Color.parseColor("#4CAF50"));
                Toast.makeText(this,
                        String.format("Time calibration complete\nTime diff: %d s\nDelay: %d s", timeDifference, roundTripTime),
                        Toast.LENGTH_LONG).show();
            });

        } catch (Exception e) {
            recordLog("Failed to parse time calibration response: " + e.getMessage());
            mainHandler.post(() -> {
                calibrateButton.setText("Calibrate");
                calibrateButton.setBackgroundColor(Color.parseColor("#4CAF50"));
            });
        } finally {
            isTimeSyncing = false;
        }
    }

    // ==================== Log Recording ====================

    private void recordLog(String message) {
        String timestamp = getCurrentTimestamp();
        String fullLogMessage = "[" + timestamp + "] " + message;

        // Display to UI
        mainHandler.post(() -> {
            if (logDisplayText != null) {
                logDisplayText.setText(message);

            }
        });

        // Write to file (only when recording)
        if (isLogRecording && logWriter != null) {
            try {
                logWriter.write(fullLogMessage + "\n");
                logWriter.flush();
            } catch (IOException e) {
                android.util.Log.e("MainActivity", "Failed to write log: " + e.getMessage());
            }
        }

        android.util.Log.d("MainActivity", fullLogMessage);
    }

    // ==================== Utility Methods ====================

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
            throw new IndexOutOfBoundsException("Insufficient data to read 4-byte integer");
        }
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }
    public void formatFileSystem() {
        // Show confirmation dialog
        new android.app.AlertDialog.Builder(this)
                .setTitle("⚠️ Format File System")
                .setMessage("Warning: This operation will permanently delete all file data in the device!\n\nAre you sure you want to continue with formatting?")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Confirm Format", (dialog, which) -> {
                    performFormatFileSystem();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    private void performFormatFileSystem() {
        try {

            int frameId = generateRandomFrameId();

            String hexCommand = String.format("00%02X3613", frameId);
            byte[] commandData = hexStringToByteArray(hexCommand);

            LmAPI.CUSTOMIZE_CMD(commandData, fileTransferCmdListener);


        } catch (Exception e) {
            e.printStackTrace();


        }
    }
    private long readUInt64LE(byte[] data, int offset) {
        if (offset + 8 > data.length) {
            throw new IndexOutOfBoundsException("Insufficient data to read 8-byte timestamp");
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
            // Returned from RingSettingsActivity, reload device info
            loadDeviceInfo();
        }
    }

    // ==================== IResponseListener Interface Implementation ====================

    @Override
    public void lmBleConnecting(int i) {
        recordLog("Bluetooth connecting, status code: " + i);
        connectionStatus = i;
        mainHandler.post(() -> {
            connectButton.setText("Connecting...");
            connectButton.setBackgroundColor(Color.parseColor("#FF9800"));
        });
    }

    @Override
    public void lmBleConnectionSucceeded(int i) {
        recordLog("Bluetooth connection successful, status code: " + i);
        connectionStatus = i;

        if (i == 7) {
            BLEUtils.setGetToken(true);
            recordLog("Connection successful, status code is 7");

            // Delayed device info retrieval
            mainHandler.postDelayed(() -> {
                LmAPI.GET_BATTERY((byte) 0x00);
            }, 1000);

            mainHandler.postDelayed(() -> {
                LmAPI.GET_VERSION((byte) 0x00);
            }, 1500);

            // Update connection status
            mainHandler.post(() -> {
                isConnected = true;
                updateConnectionStatus(true);
                Toast.makeText(this, "Connection successful", Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override
    public void lmBleConnectionFailed(int i) {
        recordLog("Bluetooth connection failed, status code: " + i);
        connectionStatus = i;
        mainHandler.post(() -> {
            connectButton.setText("Connect");
            connectButton.setBackgroundColor(Color.parseColor("#2196F3"));
            Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void VERSION(byte b, String s) {
        recordLog("Get version info: " + s);
        version = s;
        mainHandler.post(() -> {
            macAddressText.setText("MAC: " + macAddress + " | Version: " + version);
        });
    }

    @Override
    public void syncTime(byte b, byte[] bytes) {
        recordLog("Time sync completed");
    }

    @Override
    public void stepCount(byte[] bytes) {
        // Step count
    }

    @Override
    public void clearStepCount(byte b) {
        // Clear step count
    }

    @Override
    public void battery(byte b, byte b1) {
        if (b == 0) {
            batteryLevel = b1 & 0xFF;
            recordLog("Battery level: " + batteryLevel);
            mainHandler.post(() -> {
                batteryText.setText(batteryLevel + "%");
            });
        }
    }

    @Override
    public void battery_push(byte b, byte datum) {
        // Battery push
    }

    @Override
    public void timeOut() {
        recordLog("Connection timeout");
    }

    @Override
    public void saveData(String s) {
        // Handle real-time data here
        String msg = NotificationHandler.handleNotification(hexStringToByteArray(s));
        recordLog("Received data: " + msg);

        // If connection successful but device name is empty, try to get device name
        if (connectionStatus == 7 && deviceName.isEmpty()) {
            // Get device name from SharedPreferences or use default name
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
        // Reset
    }

    @Override
    public void setCollection(byte b) {
        // Set collection
    }

    @Override
    public void getCollection(byte[] bytes) {
        // Get collection
    }

    @Override
    public void getSerialNum(byte[] bytes) {
        // Get serial number
    }

    @Override
    public void setSerialNum(byte b) {
        // Set serial number
    }

    @Override
    public void cleanHistory(byte b) {
        // Clean history
    }

    @Override
    public void setBlueToolName(byte b) {
        // Set Bluetooth tool name
    }

    @Override
    public void readBlueToolName(byte b, String s) {
        // Read Bluetooth tool name
        if (deviceName.isEmpty()) {
            deviceName = s;
            mainHandler.post(() -> {
                updateConnectionStatus(true);
            });
        }
    }

    @Override
    public void stopRealTimeBP(byte b) {
        // Stop real-time blood pressure
    }

    @Override
    public void BPwaveformData(byte b, byte b1, String s) {
        // Blood pressure waveform data
    }

    @Override
    public void onSport(int i, byte[] bytes) {
        // Sport data
    }

    @Override
    public void breathLight(byte b) {
        // Breathing light
    }

    @Override
    public void SET_HID(byte b) {
        // Set HID
    }

    @Override
    public void GET_HID(byte b, byte b1, byte b2) {
        // Get HID
    }

    @Override
    public void GET_HID_CODE(byte[] bytes) {
        // Get HID code
    }

    @Override
    public void GET_CONTROL_AUDIO_ADPCM(byte b) {
        // Get control audio ADPCM
    }

    @Override
    public void SET_AUDIO_ADPCM_AUDIO(byte b) {
        // Set audio ADPCM
    }

    @Override
    public void TOUCH_AUDIO_FINISH_XUN_FEI() {
        // Touch audio finish XunFei
    }

    @Override
    public void setAudio(short i, int i1, byte[] bytes) {
        // Set audio
    }

    @Override
    public void stopHeart(byte b) {
        // Stop heart rate
    }

    @Override
    public void stopQ2(byte b) {
        // Stop Q2
    }

    @Override
    public void GET_ECG(byte[] bytes) {
        // Get ECG
    }

    @Override
    public void SystemControl(SystemControlBean systemControlBean) {
        // System control
    }

    @Override
    public void setUserInfo(byte result) {
        // Set user info
    }

    @Override
    public void getUserInfo(int sex, int height, int weight, int age) {
        // Get user info
    }

    @Override
    public void CONTROL_AUDIO(byte[] bytes) {
        // Control audio
    }

    @Override
    public void motionCalibration(byte b) {
        // Motion calibration
    }

    @Override
    public void stopBloodPressure(byte b) {
        // Stop blood pressure
    }

    @Override
    public void appBind(SystemControlBean systemControlBean) {
        // App bind
    }

    @Override
    public void appConnect(SystemControlBean systemControlBean) {
        // App connect
    }

    @Override
    public void appRefresh(SystemControlBean systemControlBean) {
        // App refresh
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Clean up resources
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        if (measurementTimer != null) {
            measurementTimer.cancel();
            measurementTimer = null;
        }

        // Close log file
        if (logWriter != null) {
            try {
                logWriter.close();
                logWriter = null;
            } catch (IOException e) {
                android.util.Log.e("MainActivity", "Failed to close log file: " + e.getMessage());
            }
        }

        recordLog("MainActivity destroyed, resources cleaned up");
    }
}
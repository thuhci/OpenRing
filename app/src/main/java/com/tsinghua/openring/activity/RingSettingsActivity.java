package com.tsinghua.openring.activity;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lm.sdk.LogicalApi;
import com.lm.sdk.mode.BleDeviceInfo;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.openring.R;
import com.tsinghua.openring.RingAdapter;
import com.tsinghua.openring.utils.CloudConfig;
import com.tsinghua.openring.utils.CloudSyncService;

import java.util.ArrayList;

public class RingSettingsActivity extends AppCompatActivity {
    private BluetoothAdapter bluetoothAdapter;
    private boolean isScanning = false;
    private RecyclerView recyclerView;
    private Button scanButton;
    private TextView selectedDeviceInfo;
    private TextView deviceCountText;
    private LinearLayout emptyStateLayout;
    private RingAdapter deviceAdapter;
    private ArrayList<String> deviceInfoList;
    private ArrayList<BluetoothDevice> scannedDevices;

    // 云端同步相关组件
    private EditText serverUrlInput;
    private EditText apiKeyInput;
    private Switch cloudSyncSwitch;
    private Button testConnectionButton;
    private Button syncOfflineButton;
    private Button syncOnlineButton;
    private Button syncAllButton;
    private Button logoutButton;
    private TextView connectionStatus;
    private CloudConfig cloudConfig;
    private CloudSyncService cloudSyncService;

    private BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] bytes) {
            if (device == null || TextUtils.isEmpty(device.getName())) {
                return;
            }

            String deviceInfo = device.getName() + " - MAC: " + device.getAddress();
            if(device.getName().contains("BCL")) {
                // Prevent duplicate device info
                if (!deviceInfoList.contains(deviceInfo)) {
                    deviceInfoList.add(deviceInfo);
                    scannedDevices.add(device);

                    // Ensure UI updates on main thread
                    runOnUiThread(() -> {
                        if (deviceAdapter != null) {
                            deviceAdapter.notifyDataSetChanged();
                        }
                        updateDeviceCount();
                        updateEmptyState();
                    });
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ring_settings);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Device does not support Bluetooth", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initializeViews();
        setupClickListeners();
        loadSelectedDevice();
        loadCloudSettings();
        updateLogoutButtonVisibility();
        updateDeviceCount();
        updateEmptyState();

        String name = getIntent().getStringExtra("deviceName");
        if (name != null) {
            setTitle(name + " Settings");
        }
    }

    private void initializeViews() {
        recyclerView = findViewById(R.id.deviceListView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        scanButton = findViewById(R.id.scanButton);
        selectedDeviceInfo = findViewById(R.id.selectedDeviceInfo);
        deviceCountText = findViewById(R.id.deviceCountText);
        emptyStateLayout = findViewById(R.id.emptyStateLayout);

        // Back button - using Button instead of ImageView
        Button backBtn = findViewById(R.id.backButton);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> finish());
        }

        TextView info = findViewById(R.id.settingsInfo);
        if (info != null) {
            info.setText("Ring Device Settings");
        }

        // 初始化云端同步相关组件
        serverUrlInput = findViewById(R.id.serverUrlInput);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        cloudSyncSwitch = findViewById(R.id.cloudSyncSwitch);
        testConnectionButton = findViewById(R.id.testConnectionButton);
        syncOfflineButton = findViewById(R.id.syncOfflineButton);
        syncOnlineButton = findViewById(R.id.syncOnlineButton);
        syncAllButton = findViewById(R.id.syncAllButton);
        logoutButton = findViewById(R.id.logoutButton);
        connectionStatus = findViewById(R.id.connectionStatus);

        // 初始化CloudConfig和CloudSyncService
        cloudConfig = new CloudConfig(this);
        cloudSyncService = new CloudSyncService(this);

        deviceInfoList = new ArrayList<>();
        scannedDevices = new ArrayList<>();
        deviceAdapter = new RingAdapter(this, scannedDevices, deviceInfoList);
        recyclerView.setAdapter(deviceAdapter);
    }

    private void setupClickListeners() {
        scanButton.setOnClickListener(v -> {
            if (isScanning) {
                stopBluetoothScan();
            } else {
                startBluetoothScan();
            }
        });

        // 云端同步相关点击事件
        testConnectionButton.setOnClickListener(v -> testCloudConnection());

        // 分离的同步按钮事件
        syncOfflineButton.setOnClickListener(v -> syncOfflineFiles());
        syncOnlineButton.setOnClickListener(v -> syncOnlineFiles());
        syncAllButton.setOnClickListener(v -> syncAllFiles());
        logoutButton.setOnClickListener(v -> logout());

        cloudSyncSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            cloudConfig.setCloudSyncEnabled(isChecked);
            updateConnectionStatus("Cloud sync " + (isChecked ? "enabled" : "disabled"));
        });

        // 配置变更时自动保存
        serverUrlInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveCloudSettings();
            }
        });

        apiKeyInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveCloudSettings();
            }
        });
    }

    private void loadSelectedDevice() {
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        String savedMac = prefs.getString("mac_address", "");
        String savedName = prefs.getString("device_name", "");

        if (!TextUtils.isEmpty(savedMac)) {
            String displayText = !TextUtils.isEmpty(savedName) ?
                    "Selected Device: " + savedName + " (" + savedMac + ")" :
                    "Selected Device: " + savedMac;
            selectedDeviceInfo.setText(displayText);
        } else {
            selectedDeviceInfo.setText("Selected Device: None");
        }
    }

    private void updateDeviceCount() {
        int count = scannedDevices.size();
        if (deviceCountText != null) {
            deviceCountText.setText(count + " devices");
        }
    }

    private void updateEmptyState() {
        if (emptyStateLayout != null) {
            if (scannedDevices.isEmpty()) {
                emptyStateLayout.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            } else {
                emptyStateLayout.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void startBluetoothScan() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth adapter not available", Toast.LENGTH_SHORT).show();
            Log.e("RingSettings", "Bluetooth adapter is null");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth in system settings first", Toast.LENGTH_LONG).show();

            // 尝试提示用户启用蓝牙
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            try {
                startActivity(enableBtIntent);
            } catch (Exception ignored) {
            }
            return;
        }

        // Clear previous scan results
        deviceInfoList.clear();
        scannedDevices.clear();
        if (deviceAdapter != null) {
            deviceAdapter.notifyDataSetChanged();
        }
        updateDeviceCount();
        updateEmptyState();

        // Start scan
        isScanning = true;
        scanButton.setText("Stop Scan");
        scanButton.setBackgroundColor(getResources().getColor(android.R.color.holo_red_light));

        BLEUtils.startLeScan(this, leScanCallback);
        Toast.makeText(this, "Starting device scan...", Toast.LENGTH_SHORT).show();
    }

    private void stopBluetoothScan() {
        if (isScanning) {
            BLEUtils.stopLeScan(this, leScanCallback);
            isScanning = false;
            scanButton.setText("Start Scan");
            scanButton.setBackgroundColor(getResources().getColor(android.R.color.holo_blue_light));
            Log.d("RingLog", "Stopping Bluetooth scan");

            Toast.makeText(this, "Scan stopped", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isScanning) {
            stopBluetoothScan();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isScanning) {
            stopBluetoothScan();
        }
    }

    public void updateSelectedDeviceInfo(String macAddress) {
        // Update display of selected device info
        String deviceName = "";

        // Find device name from scan results
        for (int i = 0; i < scannedDevices.size(); i++) {
            if (scannedDevices.get(i).getAddress().equals(macAddress)) {
                deviceName = scannedDevices.get(i).getName();
                break;
            }
        }

        // Save device name to SharedPreferences
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("device_name", deviceName);
        editor.apply();

        // Update display
        String displayText = !TextUtils.isEmpty(deviceName) ?
                "Selected Device: " + deviceName + " (" + macAddress + ")" :
                "Selected Device: " + macAddress;
        selectedDeviceInfo.setText(displayText);

        // Set result to notify MainActivity
        setResult(RESULT_OK);

        Toast.makeText(this, "Device selection saved", Toast.LENGTH_SHORT).show();
    }

    /**
     * 加载云端设置到UI
     */
    private void loadCloudSettings() {
        serverUrlInput.setText(cloudConfig.getServerUrl());
        apiKeyInput.setText(cloudConfig.getApiKey());
        cloudSyncSwitch.setChecked(cloudConfig.isCloudSyncEnabled());

        // 更新连接状态显示
        if (cloudConfig.isConfigValid()) {
            updateConnectionStatus("Config saved, click test connection to verify");
        } else {
            updateConnectionStatus("Connection status: Not configured");
        }

        Log.d("RingSettings", "Cloud config loaded: " + cloudConfig.getConfigSummary());
    }

    /**
     * 保存云端设置
     */
    private void saveCloudSettings() {
        String serverUrl = serverUrlInput.getText().toString().trim();
        String apiKey = apiKeyInput.getText().toString().trim();

        // 基本验证
        if (!serverUrl.isEmpty()) {
            if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
                serverUrl = "https://" + serverUrl;
                serverUrlInput.setText(serverUrl);
            }
        }

        cloudConfig.setServerUrl(serverUrl);
        cloudConfig.setApiKey(apiKey);

        // 更新设备ID（从当前选中的设备获取）
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        String macAddress = prefs.getString("mac_address", "");
        if (!macAddress.isEmpty()) {
            cloudConfig.setDeviceId(macAddress.replace(":", "").toLowerCase());
        }

        Log.d("RingSettings", "Cloud settings saved: " + cloudConfig.getConfigSummary());
        updateConnectionStatus("Config saved");
    }

    /**
     * 测试云端连接
     */
    private void testCloudConnection() {
        saveCloudSettings(); // 先保存当前设置

        if (!cloudConfig.isConfigValid()) {
            updateConnectionStatus("Error: Please enter valid server address");
            Toast.makeText(this, "Please enter valid server address", Toast.LENGTH_SHORT).show();
            return;
        }

        if (cloudSyncService == null) {
            updateConnectionStatus("Error: Cloud sync service not initialized");
            Toast.makeText(this, "Service initialization failed", Toast.LENGTH_SHORT).show();
            return;
        }

        updateConnectionStatus("Testing connection...");
        testConnectionButton.setEnabled(false);

        // 使用真实的CloudSyncService测试连接
        cloudSyncService.testConnection(new CloudSyncService.TestConnectionCallback() {
            @Override
            public void onResult(boolean success, String message) {
                runOnUiThread(() -> {
                    if (success) {
                        updateConnectionStatus("Connection successful!");
                        Toast.makeText(RingSettingsActivity.this, "Cloud connection test successful", Toast.LENGTH_SHORT).show();
                        Log.d("RingSettings", "Cloud connection test successful");
                    } else {
                        updateConnectionStatus("Connection failed: " + message);
                        Toast.makeText(RingSettingsActivity.this, "Connection test failed: " + message, Toast.LENGTH_SHORT).show();
                        Log.e("RingSettings", "Cloud connection test failed: " + message);
                    }
                    testConnectionButton.setEnabled(true);
                });
            }
        });
    }

    /**
     * 更新连接状态显示
     */
    private void updateConnectionStatus(String status) {
        if (connectionStatus != null) {
            connectionStatus.setText("Connection status: " + status);

            // 根据状态设置不同颜色
            if (status.contains("successful")) {
                connectionStatus.setBackgroundColor(getResources().getColor(android.R.color.holo_green_light));
            } else if (status.contains("failed") || status.contains("Error")) {
                connectionStatus.setBackgroundColor(getResources().getColor(android.R.color.holo_red_light));
            } else {
                connectionStatus.setBackgroundColor(getResources().getColor(android.R.color.background_light));
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面失去焦点时保存设置
        saveCloudSettings();
    }

    /**
     * 同步离线测量文件
     */
    private void syncOfflineFiles() {
        if (!cloudConfig.isCloudSyncEnabled() || !cloudConfig.isConfigValid()) {
            Toast.makeText(this, "Please configure and enable cloud sync first", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查登录状态，如果未登录则跳转到登录界面
        if (!cloudConfig.isLoggedIn()) {
            Toast.makeText(this, "Please login first to sync data to cloud", Toast.LENGTH_SHORT).show();
            Intent loginIntent = new Intent(this, LoginActivity.class);
            startActivity(loginIntent);
            return;
        }

        saveCloudSettings(); // 确保设置已保存
        updateConnectionStatus("Syncing offline measurement data...");
        syncOfflineButton.setEnabled(false);

        // 获取用户信息
        String userName = getUserName();
        String userDescription = getUserDescription();

        if (userName.isEmpty()) {
            updateConnectionStatus("Error: Please set user info in main page");
            syncOfflineButton.setEnabled(true);
            return;
        }

        cloudSyncService.uploadOfflineFiles(userName, userDescription, new CloudSyncService.UploadProgressCallback() {
            @Override
            public void onProgress(int current, int total, String fileName) {
                runOnUiThread(() -> updateConnectionStatus(
                    String.format("Syncing offline data: %d/%d - %s", current, total, fileName)));
            }

            @Override
            public void onFileCompleted(String fileName, boolean success, String message) {
                // 单个文件完成时不需要特殊处理
            }

            @Override
            public void onAllCompleted(int uploaded, int failed) {
                runOnUiThread(() -> {
                    String resultMsg = String.format("Offline data sync completed: %d succeeded, %d failed", uploaded, failed);
                    updateConnectionStatus(resultMsg);
                    Toast.makeText(RingSettingsActivity.this, resultMsg, Toast.LENGTH_LONG).show();
                    syncOfflineButton.setEnabled(true);
                });
            }
        });
    }

    /**
     * 同步在线测量文件
     */
    private void syncOnlineFiles() {
        if (!cloudConfig.isCloudSyncEnabled() || !cloudConfig.isConfigValid()) {
            Toast.makeText(this, "Please configure and enable cloud sync first", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查登录状态，如果未登录则跳转到登录界面
        if (!cloudConfig.isLoggedIn()) {
            Toast.makeText(this, "Please login first to sync data to cloud", Toast.LENGTH_SHORT).show();
            Intent loginIntent = new Intent(this, LoginActivity.class);
            startActivity(loginIntent);
            return;
        }

        saveCloudSettings(); // 确保设置已保存
        updateConnectionStatus("Syncing online measurement data...");
        syncOnlineButton.setEnabled(false);

        // 获取用户信息
        String userName = getUserName();
        String userDescription = getUserDescription();

        if (userName.isEmpty()) {
            updateConnectionStatus("Error: Please set user info in main page");
            syncOnlineButton.setEnabled(true);
            return;
        }

        cloudSyncService.uploadOnlineFiles(userName, userDescription, new CloudSyncService.UploadProgressCallback() {
            @Override
            public void onProgress(int current, int total, String fileName) {
                runOnUiThread(() -> updateConnectionStatus(
                    String.format("Syncing online data: %d/%d - %s", current, total, fileName)));
            }

            @Override
            public void onFileCompleted(String fileName, boolean success, String message) {
                // 单个文件完成时不需要特殊处理
            }

            @Override
            public void onAllCompleted(int uploaded, int failed) {
                runOnUiThread(() -> {
                    String resultMsg = String.format("Online data sync completed: %d succeeded, %d failed", uploaded, failed);
                    updateConnectionStatus(resultMsg);
                    Toast.makeText(RingSettingsActivity.this, resultMsg, Toast.LENGTH_LONG).show();
                    syncOnlineButton.setEnabled(true);
                });
            }
        });
    }

    /**
     * 同步所有文件
     */
    private void syncAllFiles() {
        if (!cloudConfig.isCloudSyncEnabled() || !cloudConfig.isConfigValid()) {
            Toast.makeText(this, "Please configure and enable cloud sync first", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查登录状态，如果未登录则跳转到登录界面
        if (!cloudConfig.isLoggedIn()) {
            Toast.makeText(this, "Please login first to sync data to cloud", Toast.LENGTH_SHORT).show();
            Intent loginIntent = new Intent(this, LoginActivity.class);
            startActivity(loginIntent);
            return;
        }

        saveCloudSettings(); // 确保设置已保存
        updateConnectionStatus("Syncing all data...");
        syncAllButton.setEnabled(false);

        // 获取用户信息
        String userName = getUserName();
        String userDescription = getUserDescription();

        if (userName.isEmpty()) {
            updateConnectionStatus("Error: Please set user info in main page");
            syncAllButton.setEnabled(true);
            return;
        }

        cloudSyncService.uploadAllFiles(userName, userDescription, new CloudSyncService.UploadProgressCallback() {
            @Override
            public void onProgress(int current, int total, String fileName) {
                runOnUiThread(() -> updateConnectionStatus(
                    String.format("Syncing all data: %d/%d - %s", current, total, fileName)));
            }

            @Override
            public void onFileCompleted(String fileName, boolean success, String message) {
                // 单个文件完成时不需要特殊处理
            }

            @Override
            public void onAllCompleted(int uploaded, int failed) {
                runOnUiThread(() -> {
                    String resultMsg = String.format("All data sync completed: %d succeeded, %d failed", uploaded, failed);
                    updateConnectionStatus(resultMsg);
                    Toast.makeText(RingSettingsActivity.this, resultMsg, Toast.LENGTH_LONG).show();
                    syncAllButton.setEnabled(true);
                });
            }
        });
    }

    /**
     * 获取用户名
     */
    private String getUserName() {
        SharedPreferences userInfoPrefs = getSharedPreferences("UserInfo", MODE_PRIVATE);
        return userInfoPrefs.getString("user_name", "");
    }

    /**
     * 获取用户描述
     */
    private String getUserDescription() {
        SharedPreferences userInfoPrefs = getSharedPreferences("UserInfo", MODE_PRIVATE);
        return userInfoPrefs.getString("user_description", "");
    }

    /**
     * 登出功能
     */
    private void logout() {
        cloudConfig.clearLoginInfo();
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();
        updateLogoutButtonVisibility();
        updateConnectionStatus("Logged out successfully");
        Log.d("RingSettings", "User logged out");
    }

    /**
     * 更新登出按钮的可见性
     */
    private void updateLogoutButtonVisibility() {
        if (logoutButton != null) {
            if (cloudConfig.isLoggedIn()) {
                logoutButton.setVisibility(View.VISIBLE);
            } else {
                logoutButton.setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 页面恢复时更新登出按钮状态，以防用户在其他页面登录/登出
        updateLogoutButtonVisibility();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        // 返回时保存设置并停止扫描
        saveCloudSettings();
        if (isScanning) {
            stopBluetoothScan();
        }
    }
}
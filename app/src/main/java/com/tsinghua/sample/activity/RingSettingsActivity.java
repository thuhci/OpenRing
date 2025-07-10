package com.tsinghua.sample.activity;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lm.sdk.LogicalApi;
import com.lm.sdk.mode.BleDeviceInfo;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.sample.R;
import com.tsinghua.sample.RingAdapter;

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

    private BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] bytes) {
            if (device == null || TextUtils.isEmpty(device.getName())) {
                return;
            }

            // 解析扫描到的设备数据
            BleDeviceInfo bleDeviceInfo = LogicalApi.getBleDeviceInfoWhenBleScan(device, rssi, bytes, true);
            if (bleDeviceInfo != null) {
                Log.e("RingLog", bleDeviceInfo.getDevice().getName() + " - " + bleDeviceInfo.getDevice().getAddress());

                String deviceInfo = device.getName() + " - MAC: " + device.getAddress();

                // 防止重复添加设备信息
                if (!deviceInfoList.contains(deviceInfo)) {
                    deviceInfoList.add(deviceInfo);
                    scannedDevices.add(device);

                    // 确保 UI 更新在主线程
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
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initializeViews();
        setupClickListeners();
        loadSelectedDevice();
        updateDeviceCount();
        updateEmptyState();

        String name = getIntent().getStringExtra("deviceName");
        if (name != null) {
            setTitle(name + " 设置");
        }
    }

    private void initializeViews() {
        recyclerView = findViewById(R.id.deviceListView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        scanButton = findViewById(R.id.scanButton);
        selectedDeviceInfo = findViewById(R.id.selectedDeviceInfo);
        deviceCountText = findViewById(R.id.deviceCountText);
        emptyStateLayout = findViewById(R.id.emptyStateLayout);

        // 返回按钮 - 使用Button而不是ImageView
        Button backBtn = findViewById(R.id.backButton);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> finish());
        }

        TextView info = findViewById(R.id.settingsInfo);
        if (info != null) {
            info.setText("指环设备设置");
        }

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
    }

    private void loadSelectedDevice() {
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        String savedMac = prefs.getString("mac_address", "");
        String savedName = prefs.getString("device_name", "");

        if (!TextUtils.isEmpty(savedMac)) {
            String displayText = !TextUtils.isEmpty(savedName) ?
                    "选中设备: " + savedName + " (" + savedMac + ")" :
                    "选中设备: " + savedMac;
            selectedDeviceInfo.setText(displayText);
        } else {
            selectedDeviceInfo.setText("选中设备: 无");
        }
    }

    private void updateDeviceCount() {
        int count = scannedDevices.size();
        if (deviceCountText != null) {
            deviceCountText.setText(count + "个设备");
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
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "请先启用蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }

        // 清空之前的扫描结果
        deviceInfoList.clear();
        scannedDevices.clear();
        if (deviceAdapter != null) {
            deviceAdapter.notifyDataSetChanged();
        }
        updateDeviceCount();
        updateEmptyState();

        // 开始扫描
        isScanning = true;
        scanButton.setText("停止扫描");
        scanButton.setBackgroundColor(getResources().getColor(android.R.color.holo_red_light));

        BLEUtils.startLeScan(this, leScanCallback);
        Log.d("RingLog", "开始蓝牙扫描...");

        Toast.makeText(this, "开始扫描设备...", Toast.LENGTH_SHORT).show();
    }

    private void stopBluetoothScan() {
        if (isScanning) {
            BLEUtils.stopLeScan(this, leScanCallback);
            isScanning = false;
            scanButton.setText("开始扫描");
            scanButton.setBackgroundColor(getResources().getColor(android.R.color.holo_blue_light));
            Log.d("RingLog", "停止蓝牙扫描");

            Toast.makeText(this, "扫描已停止", Toast.LENGTH_SHORT).show();
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
        // 更新显示选中设备的信息
        String deviceName = "";

        // 从扫描结果中查找设备名称
        for (int i = 0; i < scannedDevices.size(); i++) {
            if (scannedDevices.get(i).getAddress().equals(macAddress)) {
                deviceName = scannedDevices.get(i).getName();
                break;
            }
        }

        // 保存设备名称到SharedPreferences
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("device_name", deviceName);
        editor.apply();

        // 更新显示
        String displayText = !TextUtils.isEmpty(deviceName) ?
                "选中设备: " + deviceName + " (" + macAddress + ")" :
                "选中设备: " + macAddress;
        selectedDeviceInfo.setText(displayText);

        // 设置结果，通知MainActivity
        setResult(RESULT_OK);

        Toast.makeText(this, "设备选择已保存", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        // 确保返回时停止扫描
        if (isScanning) {
            stopBluetoothScan();
        }
    }
}
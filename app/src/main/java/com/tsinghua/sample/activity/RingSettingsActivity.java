package com.tsinghua.sample.activity;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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
            BleDeviceInfo bleDeviceInfo = LogicalApi.getBleDeviceInfoWhenBleScan(device, rssi, bytes,true);
            if (bleDeviceInfo != null) {
                Log.e("RingLog", bleDeviceInfo.getDevice().getName() + " - " + bleDeviceInfo.getDevice().getAddress());

                String deviceInfo = device.getName() + " - MAC: " + device.getAddress();

                // 防止重复添加设备信息
                if (!deviceInfoList.contains(deviceInfo)) {
                    deviceInfoList.add(deviceInfo);
                    scannedDevices.add(device);

                    // 确保 UI 更新在主线程
                    runOnUiThread(() -> deviceAdapter.notifyDataSetChanged());
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
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 初始化 UI 元素
        recyclerView = findViewById(R.id.deviceListView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this)); // 添加 LayoutManager
        scanButton = findViewById(R.id.scanButton);
        selectedDeviceInfo = findViewById(R.id.selectedDeviceInfo);
        TextView info = findViewById(R.id.settingsInfo);
        info.setText("指环设备设置");

        deviceInfoList = new ArrayList<>();
        scannedDevices = new ArrayList<>();
        deviceAdapter = new RingAdapter(this, scannedDevices, deviceInfoList);
        recyclerView.setAdapter(deviceAdapter);

        // 读取 SharedPreferences 中保存的设备 MAC 地址
        SharedPreferences prefs = getSharedPreferences("AppSettings", MODE_PRIVATE);
        String savedMac = prefs.getString("mac_address", "");

        // 如果有已选中的设备 MAC 地址，显示该设备信息
        if (!TextUtils.isEmpty(savedMac)) {
            selectedDeviceInfo.setText("选中设备: " + savedMac);
        } else {
            selectedDeviceInfo.setText("选中设备: 无");
        }

        // 启动蓝牙扫描
        scanButton.setOnClickListener(v -> {
            if (isScanning) {
                stopBluetoothScan();
            } else {
                startBluetoothScan();
            }
        });

        String name = getIntent().getStringExtra("deviceName");
        setTitle(name + " 设置");
    }

    private void startBluetoothScan() {
        if (!bluetoothAdapter.isEnabled()) {
            bluetoothAdapter.enable();  // 启用蓝牙
        }

        // 执行蓝牙扫描
        isScanning = true;
        scanButton.setText("停止扫描");
        scanButton.setBackgroundResource(R.drawable.button_stop_scan);  // 修改为停止扫描按钮的样式
        BLEUtils.startLeScan(this, leScanCallback);  // 启动设备扫描
        Log.d("RingLog", "Bluetooth scanning started...");
    }

    private void stopBluetoothScan() {
        if (isScanning) {
            BLEUtils.stopLeScan(this, leScanCallback);  // 停止扫描
            isScanning = false;
            scanButton.setText("开始扫描");
            scanButton.setBackgroundResource(R.drawable.button_start_scan);  // 修改为开始扫描按钮的样式
            Log.d("RingLog", "Bluetooth scanning stopped.");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isScanning) {
            stopBluetoothScan();  // 停止扫描
        }
    }
    public void updateSelectedDeviceInfo(String macAddress) {
        // 更新显示选中设备的信息
        selectedDeviceInfo.setText("选中设备: " + macAddress);
    }

}

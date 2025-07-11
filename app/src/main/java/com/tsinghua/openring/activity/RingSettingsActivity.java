package com.tsinghua.openring.activity;

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

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lm.sdk.LogicalApi;
import com.lm.sdk.mode.BleDeviceInfo;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.openring.R;
import com.tsinghua.openring.RingAdapter;

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

            // Parse scanned device data
            BleDeviceInfo bleDeviceInfo = LogicalApi.getBleDeviceInfoWhenBleScan(device, rssi, bytes, true);
            if (bleDeviceInfo != null) {
                Log.e("RingLog", bleDeviceInfo.getDevice().getName() + " - " + bleDeviceInfo.getDevice().getAddress());

                String deviceInfo = device.getName() + " - MAC: " + device.getAddress();

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
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show();
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
        Log.d("RingLog", "Starting Bluetooth scan...");

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

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        // Ensure scan stops when going back
        if (isScanning) {
            stopBluetoothScan();
        }
    }
}
package com.tsinghua.sample.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lm.sdk.LmAPI;
import com.lm.sdk.inter.IResponseListener;
import com.lm.sdk.mode.SystemControlBean;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.sample.DeviceAdapter;
import com.tsinghua.sample.R;
import com.tsinghua.sample.RingViewHolder;
import com.tsinghua.sample.SettingsActivity;
import com.tsinghua.sample.model.Device;
import com.tsinghua.sample.utils.NotificationHandler;
import com.vivalnk.sdk.BuildConfig;
import com.vivalnk.sdk.VitalClient;
import com.vivalnk.sdk.exception.VitalCode;
import com.vivalnk.sdk.utils.ProcessUtils;

import org.opencv.android.OpenCVLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ListActivity extends AppCompatActivity implements IResponseListener {

    private static final String ACTION_USB_PERMISSION = "com.tsinghua.sample.USB_PERMISSION";
    private UsbManager usbManager;
    private static final int PERMISSION_REQUEST_CODE = 100;  // Permission request code
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    public RingViewHolder ringViewHolder;

    private RecyclerView recyclerView;
    private DeviceAdapter adapter;
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF) {
                    // Bluetooth is off
                    Toast.makeText(context, "Bluetooth is OFF", Toast.LENGTH_SHORT).show();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    // Bluetooth is on
                    Toast.makeText(context, "Bluetooth is ON", Toast.LENGTH_SHORT).show();
                }
            }


        }
};


    @SuppressLint("NewApi")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        OpenCVLoader.initLocal();

        LmAPI.init(getApplication());
        LmAPI.setDebug(true);
        LmAPI.addWLSCmdListener(this, this);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        registerReceiver(broadcastReceiver,intentFilter);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!checkPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            })) {
                showPermissionDialog();
                return;
            }
        } else {
            if (!checkPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            })) {
                showPermissionDialog();
                return;
            }
        }
        checkPermissions();

        EdgeToEdge.enable(this);
        Button btnSettings = findViewById(R.id.btn_settings);

        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(ListActivity.this, SettingsActivity.class));
        });
        Button btnTimeStamp = findViewById(R.id.btn_Timestamp);



        recyclerView = findViewById(R.id.deviceRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        if (ProcessUtils.isMainProcess(this)) {
            VitalClient.getInstance().init(this);
            if (BuildConfig.DEBUG) {
                VitalClient.getInstance().openLog();
                VitalClient.getInstance().allowWriteToFile(true);
            }
        }
        int resultCode = VitalClient.getInstance().checkBle();
        Log.e("VivaLink",String.valueOf(resultCode));
        if (resultCode != VitalCode.RESULT_OK) {
            Toast.makeText(ListActivity.this, "Vital Client runtime check failed",
                    Toast.LENGTH_LONG).show();
        }
        List<Device> devices = new ArrayList<>();

        devices.add(new Device(Device.TYPE_RING, "指环"));
        adapter = new DeviceAdapter(this, devices);
        recyclerView.setAdapter(adapter);
        recyclerView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                // 在布局完成后获取 ViewHolder
                ringViewHolder = (RingViewHolder) recyclerView.findViewHolderForAdapterPosition(4);
                if (ringViewHolder != null) {
                    // 进行操作，例如设置数据或改变视图
                    Log.d("RingViewHolder", "RingViewHolder at position 4 is ready.");
                } else {
                    Log.d("RingViewHolder", "RingViewHolder at position 4 is null.");
                }
            }
        });

    }

    private void checkOximeterUsbPermission() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            Log.d("USB", "Detected device: VID=" + device.getVendorId() + " PID=" + device.getProductId());
            if (!usbManager.hasPermission(device)) {
                PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0,
                        new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                usbManager.requestPermission(device, permissionIntent);
            } else {
                Log.d("USB", "已有权限，无需请求");
            }
        }
    }


    private void checkPermissions(){

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        }

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("权限请求")
                .setMessage("该应用需要访问位置信息和蓝牙权限，请授予权限以继续使用蓝牙功能。")
                .setPositiveButton("确认", (dialog, which) -> {
                    requestPermission(new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE
                    }, PERMISSION_REQUEST_CODE);
                })
                .setNegativeButton("取消", null)
                .show();

    }
    private void requestPermission(String[] permissions, int requestCode) {
        ActivityCompat.requestPermissions(this, permissions, requestCode);
    }
    private boolean checkPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void lmBleConnecting(int i) {
        Log.e("ConnectDevice"," 蓝牙连接中");
        String msg = "蓝牙连接中，状态码：" + i;
        ringViewHolder.recordLog(msg);

    }

    @Override
    public void lmBleConnectionSucceeded(int i) {
        String msg = "蓝牙连接成功，状态码：" + i;
        ringViewHolder.recordLog(msg);
        if(i==7){
            BLEUtils.setGetToken(true);
            Log.e("TAG","\n连接成功");
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                }
            }, 500); // 延迟 2 秒（2000 毫秒）
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {

                    LmAPI.GET_BATTERY((byte) 0x00);
                }
            }, 1000); // 延迟 2 秒（2000 毫秒）
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {

                    LmAPI.GET_VERSION((byte) 0x00);
                }
            }, 1500); // 延迟 2 秒（2000 毫秒）

        }
    }

    @Override
    public void lmBleConnectionFailed(int i) {
        String msg = "蓝牙连接失败，状态码：" + i;
        Log.e("RingLog", msg);
        ringViewHolder.recordLog(msg);
    }

    @Override
    public void VERSION(byte b, String s) {
        ringViewHolder.recordLog(s);
    }

    @Override
    public void syncTime(byte b, byte[] bytes) {
        ringViewHolder.recordLog("时间同步完成");
    }

    @Override
    public void stepCount(byte[] bytes) {

    }

    @Override
    public void clearStepCount(byte b) {

    }

    @Override
    public void battery(byte b, byte b1) {
        if (b == 0) {
            ringViewHolder.recordLog("电池电量为" + b1);
        }
    }

    @Override
    public void battery_push(byte b, byte datum) {

    }

    @Override
    public void timeOut() {

    }

    @Override
    public void saveData(String s) {
        String msg = NotificationHandler.handleNotification(hexStringToByteArray(s));
        ringViewHolder.recordLog(msg);
    }

    @Override
    public void reset(byte[] bytes) {

    }

    @Override
    public void setCollection(byte b) {

    }

    @Override
    public void getCollection(byte[] bytes) {

    }

    @Override
    public void getSerialNum(byte[] bytes) {

    }

    @Override
    public void setSerialNum(byte b) {

    }

    @Override
    public void cleanHistory(byte b) {

    }

    @Override
    public void setBlueToolName(byte b) {

    }

    @Override
    public void readBlueToolName(byte b, String s) {

    }

    @Override
    public void stopRealTimeBP(byte b) {

    }

    @Override
    public void BPwaveformData(byte b, byte b1, String s) {

    }

    @Override
    public void onSport(int i, byte[] bytes) {

    }

    @Override
    public void breathLight(byte b) {

    }

    @Override
    public void SET_HID(byte b) {

    }

    @Override
    public void GET_HID(byte b, byte b1, byte b2) {

    }

    @Override
    public void GET_HID_CODE(byte[] bytes) {

    }

    @Override
    public void GET_CONTROL_AUDIO_ADPCM(byte b) {

    }

    @Override
    public void SET_AUDIO_ADPCM_AUDIO(byte b) {

    }

    @Override
    public void TOUCH_AUDIO_FINISH_XUN_FEI() {

    }

    @Override
    public void setAudio(short i, int i1, byte[] bytes) {

    }

    @Override
    public void stopHeart(byte b) {

    }

    @Override
    public void stopQ2(byte b) {

    }

    @Override
    public void GET_ECG(byte[] bytes) {

    }

    @Override
    public void SystemControl(SystemControlBean systemControlBean) {

    }

    @Override
    public void setUserInfo(byte result) {

    }

    @Override
    public void getUserInfo(int sex, int height, int weight, int age) {

    }

    @Override
    public void CONTROL_AUDIO(byte[] bytes) {

    }

    @Override
    public void motionCalibration(byte b) {

    }

    @Override
    public void stopBloodPressure(byte b) {

    }

    @Override
    public void appBind(SystemControlBean systemControlBean) {

    }

    @Override
    public void appConnect(SystemControlBean systemControlBean) {

    }

    @Override
    public void appRefresh(SystemControlBean systemControlBean) {

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
}

//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.tsinghua.openring.utils;

import static com.tsinghua.openring.utils.NotificationHandler.recordLog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Build.VERSION;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.lm.sdk.LmAPI;
import com.lm.sdk.LmAPILite;
import com.lm.sdk.R.drawable;
import com.lm.sdk.inter.BluetoothConnectCallback;
import com.lm.sdk.utils.BLEUtils;
import com.lm.sdk.utils.CMDUtils;
import com.lm.sdk.utils.ImageSaverUtil;
import com.lm.sdk.utils.Logger;
import com.lm.sdk.utils.StringUtils;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;
import java.util.UUID;

public class BLEService extends Service {
    public static final String TAG = "BLEService";
    UUID RBL_SERVICE = UUID.fromString("BAE80001-4F05-4503-8E65-3AF1F7329D1F");
    UUID RBL_DEVICE_RX_UUID = UUID.fromString("BAE80011-4F05-4503-8E65-3AF1F7329D1F");
    UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    UUID RBL_DEVICE_TX_UUID = UUID.fromString("BAE80010-4F05-4503-8E65-3AF1F7329D1F");
    UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    UUID RBL_SERVICE2 = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb");
    UUID RBL_DFU = UUID.fromString("8ec90003-f315-4F60-9FB8-838830daea50");
    private static UUID RBL_SERVICE_ECG = UUID.fromString("974cbe40-3e83-465e-acde-6f92fe712134");
    private static UUID RBL_DEVICE_RX_UUID_ECG = UUID.fromString("974cbe43-3e83-465e-acde-6f92fe712134");
    private static UUID RBL_DEVICE_TX_UUID_ECG = UUID.fromString("974cbe41-3e83-465e-acde-6f92fe712134");
    private static final String BROADCAST_BASE = "com.nokelock.y";
    public static final String BROADCAST_CONNECT_STATE_CHANGE = "com.nokelock.y.ble_connect_state_change";
    public static final String BROADCAST_CONNECT_STATE_VALUE = "com.nokelock.y.ble_connect_state";
    public static final String BROADCAST_CMD = "com.nokelock.y.ble_cmd";
    public static final String BROADCAST_CMD_TYPE = "com.nokelock.y.ble_cmd_type";
    public static final String BROADCAST_CMD_VALUE = "com.nokelock.y.ble_cmd_value";
    public static final String BROADCAST_DATA = "com.nokelock.y.ble_data";
    public static final String BROADCAST_DATA_BYTE = "com.nokelock.y.ble_data_byte";
    public static final String BROADCAST_DATA_TIME_OUT = "com.nokelock.y.ble_data_time_out";
    public static final String BROADCAST_DATA_TO_DECRYPT = "com.nokelock.y.ble_data_to_decrypt";
    public static final int CMD_TYPE_SEND_CMD = 0;
    private static int CONNECT_STATE = -1;
    public static final int CONNECT_STATE_GATT_CONNECTING = 1;
    public static final int CONNECT_STATE_GATT_CONNECTED = 2;
    public static final int CONNECT_STATE_SERVICE_CONNECTING = 3;
    public static final int CONNECT_STATE_SERVICE_CONNECTED = 4;
    public static final int CONNECT_STATE_WRITE_CONNECTING = 5;
    public static final int CONNECT_STATE_RESPOND_CONNECTING = 6;
    public static final int CONNECT_STATE_SUCCESS = 7;
    public static final int CONNECT_STATE_SERVICE_DISCONNECTED = 8;
    public static final int CONNECT_STATE_WRITE_DISCONNECTED = 9;
    public static final int CONNECT_STATE_RESPOND_DISCONNECTED = 10;
    public static final int CONNECT_STATE_DISCONNECTED = 11;
    public static final String BLUETOOTH_CONNECT_DEVICE = "CONNECT_DEVICE";
    public static final String BLUETOOTH_HID_MODE = "BLUETOOTH_HID_MODE";
    private static boolean ecgCmd = false;
    static BluetoothDevice mBluetoothDevice;
    public static final String CMD_ERROR = "CMD_ERROR";
    private static BluetoothGatt mBluetoothGatt;
    BluetoothGattService mDeviceService;
    BluetoothGattCharacteristic cmdRespondCharacter;
    BluetoothGattCharacteristic cmdWriteCharacter;
    private static BluetoothGattService mDeviceServiceECG;
    private static BluetoothGattCharacteristic cmdRespondCharacterECG;
    private static BluetoothGattCharacteristic cmdWriteCharacterECG;
    private static boolean alreadyWriteECG = false;
    BroadcastReceiver broadcast_cmd;
    private BluetoothAdapter mBluetoothAdapter;
    public static int RSSI = 0;
    private boolean isReceiverRegistered = false;
    private Handler handler = new Handler(Looper.getMainLooper()) {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 153:
                    byte[] obj = (byte[])msg.obj;
                    Logger.show("BLEService", "===执行指令超时===");
                    BLEService.sendData("执行指令超时", obj);
                    BLEService.alreadyWriteECG = false;
                    Intent intent = new Intent("com.nokelock.y.ble_data_time_out");
                    intent.putExtra("CMD_ERROR", CMDUtils.toHexString(obj));
                    LmAPI.sendBroadcast(intent);
                    LmAPILite.sendBroadcast(intent);
                default:
            }
        }
    };
    private BroadcastReceiver searchDevices = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BLEUtils.setConnecting(false);
            if ("android.bluetooth.device.action.BOND_STATE_CHANGED".equals(action)) {
                switch (BLEService.mBluetoothDevice.getBondState()) {
                    case 10:
                        Log.d("BLEService", "取消配对    =======");
                        Logger.show("BLEService", "取消配对");
                        BLEService.sendData("取消配对", (byte[])null);
                    default:
                        Log.d("BLEService", "default    =======");
                        break;
                    case 11:
                        Log.d("BLEService", "正在配对......===============");
                        Logger.show("BLEService", "正在配对");
                        BLEService.sendData("正在配对", (byte[])null);
                        break;
                    case 12:
                        if (BLEService.this.initializeBluetooth()) {
                            Log.d("BLEService", "连接成功    =======");
                            Logger.show("BLEService", "连接成功");
                            BLEService.sendData("连接成功", (byte[])null);
                            BLEUtils.setMac(BLEService.mBluetoothDevice.getAddress());
                        } else {
                            Log.d("BLEService", "连接失败    =======");
                            Logger.show("BLEService", "连接失败");
                            BLEService.sendData("连接失败", (byte[])null);
                        }
                }
            }

        }
    };
    private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == 133) {
                Logger.show("BLEService", "onConnectionStateChange status" + status + "，重连");
                gatt.disconnect();
                gatt.close();
                BLEUtils.setConnecting(false);
                BLEService.this.connect();
            }

            if (newState == 2) {
                Logger.show("BLEService", "GATT通信链接成功");
                ImageSaverUtil.saveImageToInternalStorage(BLEService.this, "GATT通信链接成功", "LM", "BLEService.txt", true);

                BLEService.sendData("连接成功", (byte[])null);
                BLEService.sendStatusChange(2);
                BLEService.this.handler.postDelayed(new Runnable() {
                    public void run() {
                        if (BLEService.mBluetoothGatt != null) {
                            BLEService.mBluetoothGatt.discoverServices();
                        }

                        BLEService.sendStatusChange(3);
                    }
                }, 2000L);
            } else if (newState == 0) {
                BLEService.sendData("连接失败", (byte[])null);
                Logger.show("BLEService", "连接失败或者连接断开");
                ImageSaverUtil.saveImageToInternalStorage(BLEService.this, "连接失败或者连接断开", "LM", "BLEService.txt", true);
                BLEService.sendStatusChange(11);
                Logger.show("TAG", "setGetToken   连接失败或者连接断开");
                gatt.close();
                if (BLEService.mBluetoothGatt != null) {
                    Logger.show("BLEService", "BluetoothGatt.close()");
                    BLEService.mBluetoothGatt.disconnect();
                    BLEService.mBluetoothGatt.close();
                    BLEService.mBluetoothGatt = null;
                }

                BLEService.this.refreshDeviceCache();
            }

        }

        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BLEService.alreadyWriteECG = false;
            Logger.show("BLEService", "===搜到设备服务===");
            if (status == 0) {

                BLEService.this.mDeviceService = BLEService.mBluetoothGatt.getService(BLEService.this.RBL_SERVICE);
                BLEService.mDeviceServiceECG = BLEService.mBluetoothGatt.getService(BLEService.RBL_SERVICE_ECG);
                if (BLEService.this.mDeviceService == null) {
                    BLEService.sendData("连接失败", (byte[])null);
                    Logger.show("BLEService", "===设备链接失败，结束操作===");
                    ImageSaverUtil.saveImageToInternalStorage(BLEService.this, "设备链接失败", "LM", "BLEService.txt", true);
                    BLEService.sendStatusChange(8);
                    BLEUtils.setConnecting(false);
                    return;
                }

                Logger.show("BLEService", "===设备链接成功===");
                ImageSaverUtil.saveImageToInternalStorage(BLEService.this, "设备链接成功", "LM", "BLEService.txt", true);
                BLEService.sendData("连接成功", (byte[])null);
                BLEUtils.setConnecting(false);
                BLEService.sendStatusChange(4);
                Logger.show("BLEService", "===获取命令响应和写入Character===");
                ImageSaverUtil.saveImageToInternalStorage(BLEService.this, "获取命令响应和写入Character", "LM", "BLEService.txt", true);
                BLEService.sendStatusChange(5);
                BLEService.this.cmdWriteCharacter = BLEService.this.mDeviceService.getCharacteristic(BLEService.this.RBL_DEVICE_TX_UUID);
                if (BLEService.mDeviceServiceECG != null) {
                    BLEService.cmdWriteCharacterECG = BLEService.mDeviceServiceECG.getCharacteristic(BLEService.RBL_DEVICE_TX_UUID_ECG);
                }

                if (BLEService.this.cmdWriteCharacter == null) {
                    Logger.show("BLEService", "===获取写入Character失败，程序结束===");
                    ImageSaverUtil.saveImageToInternalStorage(BLEService.this, "获取写入Character失败，程序结束", "LM", "BLEService.txt", true);
                    BLEService.sendStatusChange(9);
                    return;
                }

                Logger.show("BLEService", "===获取写入Character成功===");
                ImageSaverUtil.saveImageToInternalStorage(BLEService.this, "获取写入Character成功", "LM", "BLEService.txt", true);
                BLEService.sendStatusChange(6);
                BLEService.this.cmdRespondCharacter = BLEService.this.mDeviceService.getCharacteristic(BLEService.this.RBL_DEVICE_RX_UUID);
                if (BLEService.mDeviceServiceECG != null) {
                    BLEService.cmdRespondCharacterECG = BLEService.mDeviceServiceECG.getCharacteristic(BLEService.RBL_DEVICE_RX_UUID_ECG);
                }

                if (BLEService.this.cmdRespondCharacter == null) {
                    BLEService.sendStatusChange(10);
                    Logger.show("BLEService", "===获取响应Character失败，程序结束===");
                    ImageSaverUtil.saveImageToInternalStorage(BLEService.this, "获取响应Character失败，程序结束", "LM", "BLEService.txt", true);
                    return;
                }

                Logger.show("BLEService", "===获取响应Character成功===");
                ImageSaverUtil.saveImageToInternalStorage(BLEService.this, "获取响应Character成功", "LM", "BLEService.txt", true);
                Iterator var3 = gatt.getServices().iterator();

                while(var3.hasNext()) {
                    BluetoothGattService service = (BluetoothGattService)var3.next();
                    if ("974cbe40-3e83-465e-acde-6f92fe712134".equals(service.getUuid().toString())) {
                        BLEUtils.setSupportElectrocardiogram(true);
                        break;
                    }
                }

                BLEService.this.enableNotification(true, BLEService.mBluetoothGatt, BLEService.this.cmdRespondCharacter, false);
            } else {
                BLEService.sendData("未发现设备", (byte[])null);
                Logger.show("BLEService", "===未发现设备服务===");
                ImageSaverUtil.saveImageToInternalStorage(BLEService.this, "未发现设备服务", "LM", "BLEService.txt", true);
            }

            if (gatt != null) {
                super.onServicesDiscovered(gatt, status);
            }

        }

        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            if (BLEUtils.isSupportElectrocardiogram() && !BLEService.alreadyWriteECG) {
                BLEService.this.enableNotification(true, BLEService.mBluetoothGatt, BLEService.cmdRespondCharacterECG, true);
            }
            gatt.requestMtu(512);

        }

        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
        }

        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            BLEService.this.handler.removeMessages(153);
            byte[] data = characteristic.getValue();
            BLEService.this.sendResultData(data);
        }

        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            super.onReadRemoteRssi(gatt, rssi, status);
            BLEService.RSSI = rssi;
            BLEService.sendRssiData(rssi);
        }
    };
    private static boolean getToken = false;
    private static BluetoothConnectCallback callback;

    public BLEService() {
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onCreate() {
        super.onCreate();
        this.broadcast_cmd = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (intent != null) {
                    int type = intent.getIntExtra("com.nokelock.y.ble_cmd_type", 0);
                    switch (type) {
                        case 0:
                    }

                    byte[] cmd = intent.getByteArrayExtra("com.nokelock.y.ble_cmd_value");
                    if (cmd == null) {
                        Logger.show("BLEService", "发送指令不成功断开，cmd为null");
                    }

                    if (BLEService.mBluetoothGatt == null) {
                        Logger.show("BLEService", "发送指令不成功断开，mBluetoothGatt为null");
                    }

                    if (cmd != null && BLEService.mBluetoothGatt != null) {
                        BLEService.this.doWrite(cmd);
                    } else {
                        Logger.show("BLEService", "发送指令不成功断开");
                        BLEService.sendStatusChange(11);
                    }
                }

            }
        };
        LocalBroadcastManager.getInstance(this.getApplicationContext()).registerReceiver(this.broadcast_cmd, new IntentFilter("com.nokelock.y.ble_cmd"));
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return 1;
        } else {
            if (!StringUtils.isEmpty(BLEUtils.contentTitle) && VERSION.SDK_INT >= 26) {
                this.startForeground(1, this.createNotification(BLEUtils.contentTitle, BLEUtils.pendingIntent));
            }

            Logger.show("BLEService", "onStartCommand");
            mBluetoothDevice = (BluetoothDevice)intent.getParcelableExtra("CONNECT_DEVICE");
            boolean hidMode = intent.getBooleanExtra("BLUETOOTH_HID_MODE", false);
            boolean isBonded = intent.getBooleanExtra("isBonded", false);
            if (!hidMode) {
                Log.d("Jason", "isBonded:" + isBonded);
                this.connect();
            } else {
                boolean isbonded = mBluetoothDevice.createBond();
                if (!isbonded) {
                    Logger.show("BLEService", "HID绑定不匹配，直接连接");
                    this.connect();
                } else if (this.isHuaWei()) {
                    this.connect();
                } else {
                    Logger.show("BLEService", "HID绑定操作");
                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction("android.bluetooth.device.action.FOUND");
                    intentFilter.addAction("android.bluetooth.device.action.BOND_STATE_CHANGED");
                    intentFilter.addAction("android.bluetooth.adapter.action.SCAN_MODE_CHANGED");
                    intentFilter.addAction("android.bluetooth.adapter.action.STATE_CHANGED");
                    intentFilter.addAction("android.bluetooth.adapter.action.REQUEST_ENABLE");
                    if (VERSION.SDK_INT >= 31) {
                        this.getApplicationContext().registerReceiver(this.searchDevices, intentFilter, 2);
                    } else {
                        this.getApplicationContext().registerReceiver(this.searchDevices, intentFilter);
                    }

                    this.isReceiverRegistered = true;
                }
            }

            return 1;
        }
    }

    private Notification createNotification(String contentTitle, PendingIntent pendingIntent) {
        NotificationChannel channel = null;
        channel = new NotificationChannel("my_channel", "My Service", 3);
        NotificationManager notificationManager = (NotificationManager)this.getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
        NotificationCompat.Builder myChannel = new NotificationCompat.Builder(this, "my_channel");
        myChannel.setContentTitle(contentTitle).setSmallIcon(drawable.footer_error);
        if (pendingIntent != null) {
            myChannel.setContentIntent(pendingIntent);
        }

        return myChannel.build();
    }

    public String getPhoneBrand() {
        String manufacturer = Build.MANUFACTURER;
        return manufacturer != null && manufacturer.length() > 0 ? manufacturer.toLowerCase() : "unknown";
    }

    public boolean isHuaWei() {
        String phoneBrand = this.getPhoneBrand();
        return phoneBrand.contains("HUAWEI") || phoneBrand.contains("OCE") || phoneBrand.contains("huawei") || phoneBrand.contains("honor");
    }

    void connect() {
        if (!BLEUtils.isConnecting()) {
            BLEUtils.setConnecting(true);
            Logger.show("BLEService", "connect");
            if (mBluetoothGatt != null) {
                mBluetoothGatt.close();
                mBluetoothGatt.disconnect();
                mBluetoothGatt = null;
            }

            this.refreshDeviceCache();
            if (VERSION.SDK_INT >= 23) {
                mBluetoothGatt = mBluetoothDevice.connectGatt(this, false, this.mGattCallback, 2);
            } else {
                mBluetoothGatt = mBluetoothDevice.connectGatt(this, false, this.mGattCallback);
            }
            // 重试逻辑 + 异常捕获

            sendStatusChange(1);
        }

    }

    public boolean initializeBluetooth() {
        this.mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (this.mBluetoothAdapter == null) {
            return false;
        } else {
            this.handler.postDelayed(new Runnable() {
                public void run() {
                    BLEService.this.connect();
                }
            }, 2000L);
            return true;
        }
    }

    public static void readRomoteRssi() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.readRemoteRssi();
        }

    }

    private void enableNotification(boolean enable, BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, boolean isEcg) {
        if (gatt != null && characteristic != null) {
            gatt.setCharacteristicNotification(characteristic, enable);
            BluetoothGattDescriptor clientConfig = characteristic.getDescriptor(this.CCC);



            if (clientConfig != null) {
                if (enable) {
                    clientConfig.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    clientConfig.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                }

                boolean descriptor = mBluetoothGatt.writeDescriptor(clientConfig);
                if (isEcg && descriptor) {
                    alreadyWriteECG = true;
                }

                Logger.show("BLEService", "=== enableNotification isWriting = true ===" + descriptor);
            }

            Logger.show("BLEService", "=== enableNotification isWriting = true ===");
            ImageSaverUtil.saveImageToInternalStorage(this, "=== enableNotification isWriting = true ===", "LM", "BLEService.txt", true);
            if (BLEUtils.isSupportElectrocardiogram()) {
                if (isEcg) {
                    sendStatusChange(7);
                }
            } else {
                sendStatusChange(7);
            }

            BLEUtils.setMac(gatt.getDevice().getAddress());
        }
    }

    private synchronized void doWrite(byte[] data) {

        Message message;
        if (this.cmdWriteCharacter != null && !ecgCmd) {
            this.cmdWriteCharacter.setWriteType(1);
            this.cmdWriteCharacter.setValue(data);
            if (mBluetoothGatt != null) {
                mBluetoothGatt.writeCharacteristic(this.cmdWriteCharacter);
                Logger.show("BLEService", "===执行指令===");
                message = this.handler.obtainMessage();
                message.what = 153;
                message.obj = data;
                this.handler.sendMessageDelayed(message, 5000L);
            }
        }

        if (cmdWriteCharacterECG != null && ecgCmd) {
            cmdWriteCharacterECG.setWriteType(1);
            cmdWriteCharacterECG.setValue(data);
            if (mBluetoothGatt != null) {
                mBluetoothGatt.writeCharacteristic(cmdWriteCharacterECG);
                message = this.handler.obtainMessage();
                message.what = 153;
                message.obj = data;
                Logger.show("BLEService", "===执行指令===");
                this.handler.sendMessageDelayed(message, 5000L);
            }
        }

    }

    public void onDestroy() {
        Logger.show("BLEService", "BluetoothService onDestroy");
        Log.e("BLEService", "BLEService 销毁对象：" + this.getApplicationContext());
        ImageSaverUtil.saveImageToInternalStorage(this, "BLEService 销毁对象", "LM", "BLEService.txt", true);
        super.onDestroy();
        sendStatusChange(11);
        Logger.show("TAG", "setGetToken   onDestroy=");

        Exception e;
        try {
            if (mBluetoothGatt != null) {
                mBluetoothGatt.disconnect();
                mBluetoothGatt.close();
                mBluetoothGatt = null;
            }
        } catch (Exception var3) {
            e = var3;
            e.printStackTrace();
        }

        try {
            if (this.broadcast_cmd != null) {
                LocalBroadcastManager.getInstance(this.getApplicationContext()).unregisterReceiver(this.broadcast_cmd);
            }

            if (this.isReceiverRegistered) {
                this.getApplicationContext().unregisterReceiver(this.searchDevices);
                this.isReceiverRegistered = false;
            }
        } catch (Exception var2) {
            e = var2;
            e.printStackTrace();
            Log.e("BLEService", "BLEService Exception：" + this.getApplicationContext());
        }

    }

    private static void sendStatusChange(int state) {
        Logger.show("--------------------", "当前状态" + state);
        Intent intent = new Intent("com.nokelock.y.ble_connect_state_change");
        intent.putExtra("com.nokelock.y.ble_connect_state", state);
        LmAPI.sendBroadcast(intent);
        LmAPILite.sendBroadcast(intent);
    }

    public static void sendCmd(byte[] cmd) {
        ecgCmd = cmd[0] == -62 || cmd[0] == -61 || cmd[0] == -60;
        Intent intent = new Intent("com.nokelock.y.ble_cmd");
        intent.putExtra("com.nokelock.y.ble_cmd_type", 0);
        intent.putExtra("com.nokelock.y.ble_cmd_value", cmd);
        LmAPI.sendBroadcast(intent);
        LmAPILite.sendBroadcast(intent);
    }

    public boolean refreshDeviceCache() {
        if (mBluetoothGatt != null) {
            Log.e("BLEService", "清除缓存 refreshDeviceCache");

            try {
                BluetoothGatt localBluetoothGatt = mBluetoothGatt;
                Method localMethod = localBluetoothGatt.getClass().getMethod("refresh");
                if (localMethod != null) {
                    boolean bool = (Boolean)localMethod.invoke(localBluetoothGatt);
                    return bool;
                }
            } catch (Exception var4) {
                Log.i("BLEService", "An exception occured while refreshing device");
            }
        }

        return false;
    }

    private void sendResultData(final byte[] data) {
        Intent intent = new Intent("com.nokelock.y.ble_data");
        intent.putExtra("com.nokelock.y.ble_data_byte", data);
        LmAPI.sendBroadcast(intent);
        LmAPILite.sendBroadcast(intent);
    }

    public static int getConnectState() {
        return CONNECT_STATE;
    }

    public static boolean isGetToken() {
        return getToken;
    }

    public static void setGetToken(boolean getToken) {
        BLEService.getToken = getToken;
    }

    public static BluetoothDevice getmBluetoothDevice() {
        return mBluetoothDevice;
    }

    public static void setCallback(BluetoothConnectCallback callResultBack) {
        callback = callResultBack;
    }

    public static void sendData(String data, byte[] cmdData) {
        if (callback != null) {
            callback.onConnectReceived(data, cmdData);
        }

    }

    public static void sendRssiData(int rssi) {
        if (callback != null) {
            callback.onGetRssi(rssi);
        }

    }
}

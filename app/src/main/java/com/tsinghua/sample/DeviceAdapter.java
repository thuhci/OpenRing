package com.tsinghua.sample;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.sample.activity.RingSettingsActivity;

import com.tsinghua.sample.model.Device;


import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private Context context;
    private List<Device> devices;
    private boolean serviceBound = false;


    public DeviceAdapter(Context context, List<Device> devices) {
        this.context = context;
        this.devices = devices;
    }

    @Override
    public int getItemViewType(int position) {
        return devices.get(position).getType();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        switch (viewType) {

            case Device.TYPE_RING:
                return new RingViewHolder(inflater.inflate(R.layout.item_ring, parent, false));

            default:
                throw new IllegalArgumentException("Invalid device type");
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, @SuppressLint("RecyclerView") int position) {
        Device device = devices.get(position);


         if (holder instanceof RingViewHolder) {
            RingViewHolder h = (RingViewHolder) holder;

            h.deviceName.setText(device.getName());

            // 点击展开或收起设备详细信息面板
            h.itemView.setOnClickListener(v -> {
                h.toggleInfo();  // 点击展开或收起 IMU 数据的预览
            });

            // 蓝牙连接按钮
            h.connectBtn.setOnClickListener(v -> h.connectToDevice(context));

            // ============ 修改后的录制控制逻辑 ============
            // startBtn现在只控制录制状态，不进行实际的测量操作
            h.startBtn.setOnClickListener(v -> {
                if (h.isRecording()) {
                    // 当前在录制中，点击停止录制
                    h.stopRingRecording();
                    device.setRunning(false);
                    h.startBtn.setText("开始录制");

                    // 记录停止录制的操作
                    h.recordLog("=== 用户停止录制会话 ===");

                } else {
                    // 当前没有录制，点击开始录制
                    h.startRingRecording(context);
                    device.setRunning(true);
                    h.startBtn.setText("停止录制");

                    // 记录开始录制的操作
                    h.recordLog("=== 用户开始录制会话 ===");
                    h.recordLog("设备: " + device.getName());
                    h.recordLog("录制会话已启动，所有后续操作将被记录到日志文件");
                }
            });

            // 设置按钮 - 进入设置页面
            h.settingsBtn.setOnClickListener(v -> {
                // 记录进入设置的操作
                h.recordLog("【进入设备设置】设备: " + device.getName());

                Intent intent = new Intent(context, RingSettingsActivity.class);
                intent.putExtra("deviceName", device.getName());
                context.startActivity(intent);
            });

            // ============ 新增：在所有操作按钮中添加录制日志 ============

            // 覆盖连接按钮的点击事件，添加日志记录
            h.connectBtn.setOnClickListener(v -> {
                h.recordLog("【尝试连接蓝牙】设备: " + device.getName());
                h.connectToDevice(context);
            });

            // 文件操作按钮日志记录
            h.requestFileListBtn.setOnClickListener(v -> {
                h.recordLog("【用户操作】请求获取文件列表");
                h.requestFileList(context);
            });

            h.downloadSelectedBtn.setOnClickListener(v -> {
                h.recordLog("【用户操作】开始下载选中文件");
                h.startDownloadSelectedFiles(context);
            });

            // 时间操作按钮日志记录
            h.timeUpdateBtn.setOnClickListener(v -> {
                h.recordLog("【用户操作】开始更新戒指时间");
                h.updateRingTime(context);
            });

            h.timeSyncBtn.setOnClickListener(v -> {
                h.recordLog("【用户操作】开始时间校准");
                h.performTimeSync(context);
            });

            // 测量和运动控制按钮日志记录
            h.startMeasurementBtn.setOnClickListener(v -> {
                String timeStr = h.measurementTimeInput.getText().toString();
                h.recordLog("【用户操作】开始主动测量，时长: " + timeStr + "秒");
                h.startActiveMeasurement(context);
            });

            h.startExerciseBtn.setOnClickListener(v -> {
                String duration = h.exerciseDurationInput.getText().toString();
                String segment = h.segmentDurationInput.getText().toString();
                h.recordLog("【用户操作】开始运动控制，总时长: " + duration + "秒，片段: " + segment + "秒");
                h.startExercise(context);
            });

            h.stopExerciseBtn.setOnClickListener(v -> {
                h.recordLog("【用户操作】手动停止运动");
                h.stopExercise(context);
            });

            // ============ 设置初始状态 ============

            // 根据设备当前状态设置按钮文本
            if (device.isRunning() || h.isRecording()) {
                h.startBtn.setText("停止录制");
            } else {
                h.startBtn.setText("开始录制");
            }

            // 初始化设备状态显示
            h.recordLog("设备初始化完成: " + device.getName());

        }

    }


    @Override
    public int getItemCount() {
        return devices.size();
    }
}

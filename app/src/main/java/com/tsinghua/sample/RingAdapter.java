package com.tsinghua.sample;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.sample.activity.RingSettingsActivity;

import java.util.ArrayList;

public class RingAdapter extends RecyclerView.Adapter<RingAdapter.DeviceViewHolder> {
    private Context context;
    private ArrayList<BluetoothDevice> devices;
    private ArrayList<String> deviceInfoList;
    private SharedPreferences prefs;

    public RingAdapter(Context context, ArrayList<BluetoothDevice> devices, ArrayList<String> deviceInfoList) {
        this.context = context;
        this.devices = devices;
        this.deviceInfoList = deviceInfoList;
        this.prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 创建设备项布局
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(16, 16, 16, 16);
        layout.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT));

        // 设备信息容器
        LinearLayout deviceInfoLayout = new LinearLayout(context);
        deviceInfoLayout.setOrientation(LinearLayout.VERTICAL);
        deviceInfoLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 设备名称
        TextView deviceName = new TextView(context);
        deviceName.setId(android.R.id.text1);
        deviceName.setTextSize(16);
        deviceName.setTextColor(Color.BLACK);
        // 设备MAC地址
        TextView deviceMAC = new TextView(context);
        deviceMAC.setId(android.R.id.text2);
        deviceMAC.setTextSize(14);
        deviceMAC.setTextColor(Color.GRAY);
        deviceMAC.setPadding(0, 4, 0, 0);

        deviceInfoLayout.addView(deviceName);
        deviceInfoLayout.addView(deviceMAC);
        layout.addView(deviceInfoLayout);

        // 设置点击效果
        layout.setBackgroundResource(android.R.drawable.list_selector_background);
        layout.setClickable(true);
        layout.setFocusable(true);

        return new DeviceViewHolder(layout);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        if (position >= devices.size()) return;

        BluetoothDevice device = devices.get(position);
        String macAddress = device.getAddress();
        String deviceName = device.getName();

        if (TextUtils.isEmpty(deviceName)) {
            deviceName = "Unknown Device";
        }

        holder.deviceName.setText(deviceName);
        holder.deviceMAC.setText("MAC: " + macAddress);

        // 读取保存的设备MAC地址
        String savedMac = prefs.getString("mac_address", "");

        // 设置选中状态的显示
        if (!TextUtils.isEmpty(savedMac) && savedMac.equals(macAddress)) {
            // 选中状态
            holder.itemView.setBackgroundColor(Color.parseColor("#E3F2FD"));
            holder.deviceName.setTextColor(Color.parseColor("#1976D2"));
            holder.deviceMAC.setTextColor(Color.parseColor("#1976D2"));
        } else {
            // 默认状态
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
            holder.deviceName.setTextColor(Color.BLACK);
            holder.deviceMAC.setTextColor(Color.GRAY);
        }

        // 设置点击事件
        String finalDeviceName = deviceName;
        holder.itemView.setOnClickListener(v -> {
            // 保存选中的设备MAC地址到SharedPreferences
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("mac_address", macAddress);
            editor.putString("device_name", finalDeviceName);
            editor.apply();

            // 更新选中的设备信息显示
            if (context instanceof RingSettingsActivity) {
                ((RingSettingsActivity) context).updateSelectedDeviceInfo(macAddress);
            }

            Toast.makeText(context, "已选择设备: " + finalDeviceName, Toast.LENGTH_SHORT).show();

            // 刷新UI，更新设备选择状态
            notifyDataSetChanged();
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    public static class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView deviceName;
        TextView deviceMAC;

        public DeviceViewHolder(View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(android.R.id.text1);
            deviceMAC = itemView.findViewById(android.R.id.text2);
        }
    }
}
package com.tsinghua.sample;

import static android.content.Context.MODE_PRIVATE;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.sample.R;
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
        this.prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_ring_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        String deviceInfo = deviceInfoList.get(position);
        BluetoothDevice device = devices.get(position);

        String macAddress = device.getAddress();
        holder.deviceName.setText(device.getName());
        holder.deviceMAC.setText(macAddress);

        // 读取 SharedPreferences 中保存的设备 MAC 地址
        String savedMac = context.getSharedPreferences("AppSettings", MODE_PRIVATE).getString("mac_address", "");

        if (!TextUtils.isEmpty(savedMac) && savedMac.equals(macAddress)) {
            // 如果设备是选中的设备，标记为选中状态
            holder.itemView.setBackgroundColor(context.getResources().getColor(R.color.selectedItemBackground)); // 使用选中的背景色
            holder.deviceName.setTextColor(context.getResources().getColor(R.color.selectedItemTextColor)); // 使用选中的文本颜色
        } else {
            holder.itemView.setBackgroundColor(context.getResources().getColor(android.R.color.white)); // 默认背景色
            holder.deviceName.setTextColor(context.getResources().getColor(R.color.defaultItemTextColor)); // 默认文本颜色
        }

        holder.itemView.setOnClickListener(v -> {
            // 保存选中的设备 MAC 地址到 SharedPreferences
            SharedPreferences.Editor editor = context.getSharedPreferences("AppSettings", MODE_PRIVATE).edit();
            editor.putString("mac_address", macAddress); // 保存设备 MAC 地址
            editor.apply();

            // 更新选中的设备信息显示
            ((RingSettingsActivity) context).updateSelectedDeviceInfo(macAddress);

            Toast.makeText(context, "已选择设备: " + device.getName(), Toast.LENGTH_SHORT).show();

            // 刷新 UI，更新设备选择状态
            notifyDataSetChanged();
        });
    }



    @Override
    public int getItemCount() {
        return deviceInfoList.size();
    }

    public static class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView deviceName,deviceMAC;

        public DeviceViewHolder(View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.deviceName);
            deviceMAC = itemView.findViewById(R.id.deviceMAC);
        }
    }
}

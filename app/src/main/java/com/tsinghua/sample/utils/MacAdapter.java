package com.tsinghua.sample.utils;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.sample.R;

import java.util.List;

public class MacAdapter extends RecyclerView.Adapter<MacAdapter.MacViewHolder> {
    private List<MacItem> macList;
    private Context context;
    private OnMacDeleteListener deleteListener;
    private String selectedMacAddress;
    private SharedPreferences prefs;


    public MacAdapter(Context context, List<MacItem> macList, OnMacDeleteListener deleteListener) {
        this.context = context;
        this.macList = macList;
        this.deleteListener = deleteListener;
        this.selectedMacAddress = context.getSharedPreferences("AppSettings", MODE_PRIVATE)
                .getString("mac_address", "");  // 加载选中的 MAC 地址
        this.prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);

    }

    @NonNull
    @Override
    public MacViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_mac, parent, false);
        return new MacViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MacViewHolder holder, int position) {
        MacItem macItem = macList.get(position);
        holder.tvMac.setText(macItem.getMacAddress());
        holder.tvDescription.setText(macItem.getDescription());

        // 设置选中项的背景
        if (macItem.getMacAddress().equals(selectedMacAddress)) {
            holder.itemView.setBackgroundColor(context.getResources().getColor(R.color.selected_background));
        } else {
            holder.itemView.setBackgroundColor(context.getResources().getColor(R.color.default_background));
        }

        holder.itemView.setOnClickListener(v -> {
            // 更新选中的 MAC 地址
            selectedMacAddress = macItem.getMacAddress();
            saveSelectedMacAddress(selectedMacAddress);

            // 通知适配器更新 UI
            notifyDataSetChanged();
        });

        holder.btnDelete.setOnClickListener(v -> {
            new AlertDialog.Builder(context)
                    .setTitle("删除确认")
                    .setMessage("确定要删除该 MAC 地址吗？")
                    .setPositiveButton("删除", (dialog, which) -> {
                        deleteListener.onMacDeleted(macItem);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    @Override
    public int getItemCount() {
        return macList.size();
    }
    private void saveSelectedMacAddress(String macAddress) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("mac_address", macAddress);
        editor.apply();
    }
    public static class MacViewHolder extends RecyclerView.ViewHolder {
        TextView tvMac, tvDescription;
        ImageButton btnDelete;

        public MacViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMac = itemView.findViewById(R.id.tv_mac);
            tvDescription = itemView.findViewById(R.id.tv_description);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }

    public interface OnMacDeleteListener {
        void onMacDeleted(MacItem macItem);
    }

}

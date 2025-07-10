package com.tsinghua.sample;

import static android.content.Context.MODE_PRIVATE;

import static com.tsinghua.sample.activity.ListActivity.hexStringToByteArray;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lm.sdk.LmAPI;
import com.lm.sdk.LogicalApi;
import com.lm.sdk.inter.ICustomizeCmdListener;
import com.lm.sdk.utils.BLEUtils;
import com.tsinghua.sample.activity.ListActivity;
import com.tsinghua.sample.utils.NotificationHandler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

public class RingViewHolder extends RecyclerView.ViewHolder {
    TextView deviceName;
    Button startBtn;
    ImageButton settingsBtn;
    private NestedScrollView infoLayout;
    private boolean infoVisible = false;
    private TextView tvLog;
    Button connectBtn;

    // 文件操作按钮
    Button requestFileListBtn;
    Button downloadSelectedBtn;  // 改名为下载选中文件

    // 文件列表相关UI
    RecyclerView fileListRecyclerView;
    FileListAdapter fileListAdapter;
    TextView fileListStatusText;

    // 时间相关按钮
    Button timeSyncBtn;
    Button timeUpdateBtn;

    // 单个文件下载相关UI (保留作为备用)
    EditText fileNameInput;
    Button downloadSingleBtn;

    // 主动测量和运动控制UI
    EditText measurementTimeInput;
    Button startMeasurementBtn;
    EditText exerciseDurationInput;
    EditText segmentDurationInput;
    Button startExerciseBtn;
    Button stopExerciseBtn;
    TextView exerciseStatusText;
    Button formatFileSystemBtn;  // 格式化文件系统按钮
    Button stopCollectionBtn;
    TextView measurementStatusText;
    private BufferedWriter logWriter;
    private boolean isRecordingRing = false;  // 控制是否记录日志
    private PlotView plotViewG, plotViewI;
    private PlotView plotViewR, plotViewX;
    private PlotView plotViewY, plotViewZ;

    // 文件操作相关
    private List<FileInfo> fileList = new ArrayList<>();
    private List<FileInfo> selectedFiles = new ArrayList<>();  // 选中的文件列表
    private boolean isDownloadingFiles = false;
    private int currentDownloadIndex = 0;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // 时间相关操作状态
    private boolean isTimeSyncing = false;
    private boolean isTimeUpdating = false;
    private long timeSyncRequestTime = 0;
    private int timeSyncFrameId = 0;
    private int timeUpdateFrameId = 0;

    // 文件信息类
    public static class FileInfo {
        public String fileName;
        public int fileSize;
        public int fileType;
        public String userId;
        public String timestamp;
        public boolean isSelected = false;  // 新增：是否被选中

        public FileInfo(String fileName, int fileSize) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            parseFileName();
        }

        private void parseFileName() {
            String[] parts = fileName.replace(".bin", "").split("_");
            if (parts.length >= 3) {
                this.userId = parts[0];
                this.timestamp = convertUTCToChinaTime(parts[1]+parts[2]+parts[3]);
                this.fileType = Integer.parseInt(parts[parts.length-1]);
            }
        }

        public String getFileTypeDescription() {
            switch (fileType) {
                case 1: return "三轴数据";
                case 2: return "六轴数据";
                case 3: return "PPG数据红外+红色+三轴(spo2)";
                case 4: return "PPG数据绿色";
                case 5: return "PPG数据红外";
                case 6: return "温度数据红外";
                case 7: return "红外+红色+绿色+温度+三轴";
                case 8: return "PPG数据绿色+三轴(hr)";
                default: return "未知类型";
            }
        }

        public String getFormattedSize() {
            if (fileSize < 1024) {
                return fileSize + " B";
            } else if (fileSize < 1024 * 1024) {
                return String.format("%.1f KB", fileSize / 1024.0);
            } else {
                return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
            }
        }


    }
    private static String convertUTCToChinaTime(String utcTimeStr) {
        try {
            if (utcTimeStr == null || utcTimeStr.length() < 15) {
                return utcTimeStr; // 格式不正确，返回原始字符串
            }

            // 解析UTC时间字符串: 20250623:13:31:31
            String dateStr = utcTimeStr.substring(0, 8);        // 20250623
            String timeStr = utcTimeStr.substring(9);           // 13:31:31

            String year = dateStr.substring(0, 4);              // 2025
            String month = dateStr.substring(4, 6);             // 06
            String day = dateStr.substring(6, 8);               // 23

            // 构建完整的UTC时间字符串
            String fullUtcTimeStr = String.format("%s-%s-%s %s", year, month, day, timeStr);

            // 解析UTC时间
            SimpleDateFormat utcFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date utcDate = utcFormat.parse(fullUtcTimeStr);

            // 转换为中国时间 (UTC+8)
            SimpleDateFormat chinaFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            chinaFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            String chinaTimeStr = chinaFormat.format(utcDate);

            return chinaTimeStr;

        } catch (Exception e) {
            return utcTimeStr; // 转换失败，返回原始字符串
        }
    }
    // 文件列表适配器
    public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.FileViewHolder> {
        private List<FileInfo> files;

        public FileListAdapter(List<FileInfo> files) {
            this.files = files;
        }

        @Override
        public FileViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            // 创建文件列表项布局
            LinearLayout layout = new LinearLayout(parent.getContext());
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            layoutParams.setMargins(0, 4, 0, 4);
            layout.setLayoutParams(layoutParams);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setPadding(8, 8, 8, 8);
            layout.setMinimumHeight(60); // 增加最小高度，更容易点击

            // 设置背景和点击效果
            layout.setBackgroundResource(android.R.drawable.list_selector_background);
            layout.setClickable(true);
            layout.setFocusable(true);

            // 关键修复：设置触摸事件处理
            layout.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    // 确保item可以接收触摸事件
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        v.getParent().getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return false;
                }
            });

            // 选择框
            android.widget.CheckBox checkBox = new android.widget.CheckBox(parent.getContext());
            checkBox.setId(android.R.id.checkbox);
            LinearLayout.LayoutParams checkBoxParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            checkBoxParams.gravity = android.view.Gravity.CENTER_VERTICAL;
            checkBox.setLayoutParams(checkBoxParams);

            // 文件信息容器
            LinearLayout infoLayout = new LinearLayout(parent.getContext());
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            infoParams.setMargins(12, 0, 0, 0);
            infoLayout.setLayoutParams(infoParams);
            infoLayout.setOrientation(LinearLayout.VERTICAL);

            // 文件名
            TextView fileName = new TextView(parent.getContext());
            fileName.setId(android.R.id.text1);
            fileName.setTextSize(12);
            fileName.setTextColor(Color.BLACK);
            fileName.setMaxLines(1);
            fileName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams fileNameParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            fileName.setLayoutParams(fileNameParams);

            // 文件详情
            TextView fileDetails = new TextView(parent.getContext());
            fileDetails.setId(android.R.id.text2);
            fileDetails.setTextSize(10);
            fileDetails.setTextColor(Color.GRAY);
            fileDetails.setMaxLines(2); // 允许两行显示更多信息
            fileDetails.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            detailsParams.setMargins(0, 2, 0, 0);
            fileDetails.setLayoutParams(detailsParams);

            infoLayout.addView(fileName);
            infoLayout.addView(fileDetails);

            layout.addView(checkBox);
            layout.addView(infoLayout);

            return new FileViewHolder(layout);
        }

        @Override
        public void onBindViewHolder(FileViewHolder holder, int position) {
            FileInfo file = files.get(position);

            holder.checkBox.setChecked(file.isSelected);
            holder.fileName.setText(file.fileName);
            holder.fileDetails.setText(String.format("%s | %s | %s",
                    file.getFileTypeDescription(),
                    file.getFormattedSize(),
                    file.timestamp));

            // 设置整个item的点击事件 - 优化版
            holder.itemView.setOnClickListener(v -> {
                file.isSelected = !file.isSelected;
                holder.checkBox.setChecked(file.isSelected);
                updateSelectedFiles();

                // 提供触觉反馈
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);

                recordLog(String.format("文件选择状态变更: %s -> %s",
                        file.fileName, file.isSelected ? "选中" : "未选中"));
            });

            // 优化checkbox的事件处理
            holder.checkBox.setOnCheckedChangeListener(null); // 先清除监听器
            holder.checkBox.setChecked(file.isSelected);
            holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (file.isSelected != isChecked) {
                    file.isSelected = isChecked;
                    updateSelectedFiles();

                    recordLog(String.format("复选框状态变更: %s -> %s",
                            file.fileName, isChecked ? "选中" : "未选中"));
                }
            });
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        public class FileViewHolder extends RecyclerView.ViewHolder {
            android.widget.CheckBox checkBox;
            TextView fileName;
            TextView fileDetails;

            public FileViewHolder(View itemView) {
                super(itemView);
                checkBox = itemView.findViewById(android.R.id.checkbox);
                fileName = itemView.findViewById(android.R.id.text1);
                fileDetails = itemView.findViewById(android.R.id.text2);
            }
        }
    }


    // 更新选中文件列表
    private void updateSelectedFiles() {
        selectedFiles.clear();
        for (FileInfo file : fileList) {
            if (file.isSelected) {
                selectedFiles.add(file);
            }
        }

        // 更新下载按钮状态
        mainHandler.post(() -> {
            downloadSelectedBtn.setText(String.format("下载选中 (%d)", selectedFiles.size()));

            fileListStatusText.setText(String.format("文件列表 (共%d个，已选%d个)",
                    fileList.size(), selectedFiles.size()));
        });
    }

    private ICustomizeCmdListener fileTransferCmdListener = new ICustomizeCmdListener() {
        @Override
        public void cmdData(String responseData) {
            byte[] responseBytes = hexStringToByteArray(responseData);
            recordLog("收到自定义指令响应: " + responseData);
            handleCustomizeResponse(responseBytes);
        }
    };

    private void handleCustomizeResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("自定义指令响应数据长度不足");
                return;
            }

            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            recordLog(String.format("响应解析: FrameType=0x%02X, FrameID=0x%02X, Cmd=0x%02X, Subcmd=0x%02X",
                    frameType, frameId, cmd, subcmd));

            if (frameType == 0x00) {
                if (cmd == 0x3C) {
                    // 新增：处理停止采集响应
                    if (subcmd == 0x04) {
                        recordLog("识别为停止采集响应");
                        handleStopCollectionResponse(data);
                    }
                }
                else if (cmd == 0x36) {
                    if (subcmd == 0x10) {
                        recordLog("识别为文件列表响应");
                        handleFileListResponse(data);
                    } else if (subcmd == 0x11) {
                        recordLog("识别为文件数据响应");
                        handleFileDataResponse(data);
                    }
                } else if (cmd == 0x10) {
                    if (subcmd == 0x00) {
                        recordLog("识别为时间更新响应");
                        handleTimeUpdateResponse(data);
                    } else if (subcmd == 0x02) {
                        recordLog("识别为时间校准响应");
                        handleTimeSyncResponse(data);
                    }
                } else if (cmd == 0x38) {
                    if (subcmd == 0x01) {
                        recordLog("识别为开始运动响应");
                        handleStartExerciseResponse(data);
                    } else if (subcmd == 0x03) {
                        recordLog("识别为结束运动响应");
                        handleStopExerciseResponse(data);
                    }
                }
                else if (cmd == 0x36 && subcmd == 0x13) {
                    recordLog("识别为格式化文件系统响应");
                    handleFormatFileSystemResponse(data);
                }
            }
        } catch (Exception e) {
            recordLog("处理自定义指令响应失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void handleFormatFileSystemResponse(byte[] data) {
        try {
            if (data == null || data.length < 5) {
                recordLog("格式化响应数据长度不足");
                return;
            }

            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;
            int result = data[4] & 0xFF;

            recordLog("格式化文件系统响应解析:");
            recordLog("  - Frame Type: 0x" + String.format("%02X", frameType));
            recordLog("  - Frame ID: 0x" + String.format("%02X", frameId));
            recordLog("  - Cmd: 0x" + String.format("%02X", cmd));
            recordLog("  - Subcmd: 0x" + String.format("%02X", subcmd));
            recordLog("  - Result: " + result + " (" + (result == 1 ? "成功" : "失败") + ")");

            if (frameType != 0x00 || cmd != 0x36 || subcmd != 0x13) {
                recordLog("格式化响应格式错误");
                return;
            }

            boolean success = (result == 1);

            recordLog("【格式化文件系统结果】: " + (success ? "✅ 成功" : "❌ 失败"));

            mainHandler.post(() -> {
                // 恢复按钮状态
                formatFileSystemBtn.setText("格式化文件系统");
                formatFileSystemBtn.setBackgroundColor(Color.parseColor("#FF5722"));

                if (success) {
                    // 格式化成功，清空文件列表
                    fileList.clear();
                    selectedFiles.clear();
                    if (fileListAdapter != null) {
                        fileListAdapter.notifyDataSetChanged();
                    }
                    updateSelectedFiles();

                    Toast.makeText(itemView.getContext(),
                            "✅ 文件系统格式化成功！\n所有文件已被清除",
                            Toast.LENGTH_LONG).show();

                    fileListStatusText.setText("文件列表 (已格式化，无文件)");

                } else {
                    Toast.makeText(itemView.getContext(),
                            "❌ 文件系统格式化失败！\n请检查设备状态",
                            Toast.LENGTH_LONG).show();
                }
            });

        } catch (Exception e) {
            recordLog("解析格式化响应失败: " + e.getMessage());
            e.printStackTrace();

            mainHandler.post(() -> {
                formatFileSystemBtn.setText("格式化文件系统");
                formatFileSystemBtn.setBackgroundColor(Color.parseColor("#FF5722"));

                Toast.makeText(itemView.getContext(),
                        "解析格式化响应失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            });
        }
    }
    private void handleStopCollectionResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("停止采集响应数据长度不足");
                return;
            }

            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            recordLog("停止采集响应解析:");
            recordLog("  - Frame Type: 0x" + String.format("%02X", frameType));
            recordLog("  - Frame ID: 0x" + String.format("%02X", frameId));
            recordLog("  - Cmd: 0x" + String.format("%02X", cmd));
            recordLog("  - Subcmd: 0x" + String.format("%02X", subcmd));

            if (frameType != 0x00 || cmd != 0x3C || subcmd != 0x04) {
                recordLog("停止采集响应格式错误");
                return;
            }

            recordLog("【停止采集响应】: ✅ 设备确认采集已停止");

            mainHandler.post(() -> {
                // 更新UI状态
                updateMeasurementUIState(false);
                updateMeasurementStatus("已停止");

                Toast.makeText(itemView.getContext(),
                        "✅ 停止采集成功！\n设备已确认停止数据采集",
                        Toast.LENGTH_LONG).show();
            });

        } catch (Exception e) {
            recordLog("解析停止采集响应失败: " + e.getMessage());
            e.printStackTrace();

            mainHandler.post(() -> {
                Toast.makeText(itemView.getContext(),
                        "解析停止采集响应失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            });
        }
    }

    public RingViewHolder(View itemView) {
        super(itemView);

        deviceName = itemView.findViewById(R.id.deviceName);
        startBtn = itemView.findViewById(R.id.startBtn);
        settingsBtn = itemView.findViewById(R.id.settingsBtn);
        infoLayout = itemView.findViewById(R.id.infoLayout);
        tvLog = itemView.findViewById(R.id.tvLog);
        connectBtn = itemView.findViewById(R.id.connectBtn);
        requestFileListBtn = itemView.findViewById(R.id.requestFileListBtn);
        downloadSelectedBtn = itemView.findViewById(R.id.downloadSelectedBtn);
        stopCollectionBtn = itemView.findViewById(R.id.btn_stop_collection);
        measurementStatusText = itemView.findViewById(R.id.text_measurement_status);
        // 文件列表UI初始化
        fileListRecyclerView = itemView.findViewById(R.id.fileListRecyclerView);
        fileListStatusText = itemView.findViewById(R.id.fileListStatusText);

        // 时间操作按钮初始化
        timeSyncBtn = itemView.findViewById(R.id.timeSyncBtn);
        timeUpdateBtn = itemView.findViewById(R.id.timeUpdateBtn);

        // 单个文件下载UI初始化
        fileNameInput = itemView.findViewById(R.id.editText_file_name);
        downloadSingleBtn = itemView.findViewById(R.id.btn_download_single_file);

        // 主动测量和运动控制UI初始化
        measurementTimeInput = itemView.findViewById(R.id.editText_measurement_time);
        startMeasurementBtn = itemView.findViewById(R.id.btn_start_measurement);
        exerciseDurationInput = itemView.findViewById(R.id.editText_exercise_duration);
        segmentDurationInput = itemView.findViewById(R.id.editText_segment_duration);
        startExerciseBtn = itemView.findViewById(R.id.btn_start_exercise);
        stopExerciseBtn = itemView.findViewById(R.id.btn_stop_exercise);
        exerciseStatusText = itemView.findViewById(R.id.text_exercise_status);
        setupNotificationHandlerLogging();
        formatFileSystemBtn = itemView.findViewById(R.id.formatFileSystemBtn);

        // 设置按钮事件
        connectBtn.setOnClickListener(v -> connectToDevice(itemView.getContext()));
        requestFileListBtn.setOnClickListener(v -> requestFileList(itemView.getContext()));
        downloadSelectedBtn.setOnClickListener(v -> startDownloadSelectedFiles(itemView.getContext()));
        timeUpdateBtn.setOnClickListener(v -> updateRingTime(itemView.getContext()));
        timeSyncBtn.setOnClickListener(v -> performTimeSync(itemView.getContext()));

        if (downloadSingleBtn != null) {
            downloadSingleBtn.setOnClickListener(v -> {
                if (fileNameInput != null) {
                    String fileName = fileNameInput.getText().toString();
                    downloadFileByName(itemView.getContext(), fileName);
                }
            });
        }

        if (startMeasurementBtn != null) {
            startMeasurementBtn.setOnClickListener(v -> startActiveMeasurement(itemView.getContext()));
        }

        if (startExerciseBtn != null) {
            startExerciseBtn.setOnClickListener(v -> startExercise(itemView.getContext()));
        }

        if (stopExerciseBtn != null) {
            stopExerciseBtn.setOnClickListener(v -> stopExercise(itemView.getContext()));
        }
        if (formatFileSystemBtn != null) {
            formatFileSystemBtn.setOnClickListener(v -> formatFileSystem(itemView.getContext()));
        }
        if (startMeasurementBtn != null) {
            startMeasurementBtn.setOnClickListener(v -> startActiveMeasurement(itemView.getContext()));
        }

        // 新增：停止采集按钮事件
        if (stopCollectionBtn != null) {
            stopCollectionBtn.setOnClickListener(v -> stopActiveMeasurement(itemView.getContext()));
        }

        // 初始化文件列表
        initializeFileList();
        initializePlotViews(itemView);
        setupNotificationCallback();
        setupDeviceCommandCallback();
        updateMeasurementUIState(false);

        initializeUI();
    }
    public void formatFileSystem(Context context) {
        // 显示确认对话框
        new android.app.AlertDialog.Builder(context)
                .setTitle("⚠️ 格式化文件系统")
                .setMessage("警告：此操作将永久删除戒指中的所有文件数据！\n\n确定要继续格式化吗？")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("确认格式化", (dialog, which) -> {
                    performFormatFileSystem(context);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 执行格式化文件系统操作
     */
    private void performFormatFileSystem(Context context) {
        try {
            recordLog("【开始格式化文件系统】使用自定义指令");

            // 生成随机Frame ID
            int frameId = generateRandomFrameId();

            // 构建格式化指令: 00[FrameID]3613
            String hexCommand = String.format("00%02X3613", frameId);
            byte[] commandData = hexStringToByteArray(hexCommand);

            recordLog("发送格式化指令: " + hexCommand);
            recordLog("指令结构:");
            recordLog("  - Frame Type: 0x00");
            recordLog("  - Frame ID: 0x" + String.format("%02X", frameId));
            recordLog("  - Cmd: 0x36 (文件操作)");
            recordLog("  - Subcmd: 0x13 (格式化文件系统)");
            recordLog("  - Data: 无");

            // 更新按钮状态
            formatFileSystemBtn.setText("格式化中...");
            formatFileSystemBtn.setBackgroundColor(Color.GRAY);

            // 发送指令
            LmAPI.CUSTOMIZE_CMD(commandData, fileTransferCmdListener);

            Toast.makeText(context, "格式化指令已发送，请等待响应", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            recordLog("发送格式化指令失败: " + e.getMessage());
            e.printStackTrace();

            // 恢复按钮状态
            mainHandler.post(() -> {
                formatFileSystemBtn.setText("格式化文件系统");
                formatFileSystemBtn.setBackgroundColor(Color.parseColor("#FF5722"));
            });

            Toast.makeText(context, "发送格式化指令失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    // 初始化文件列表
    private void initializeFileList() {
        if (fileListRecyclerView != null) {
            fileListAdapter = new FileListAdapter(fileList);

            // 设置布局管理器
            LinearLayoutManager layoutManager = new LinearLayoutManager(itemView.getContext()) {
                @Override
                public boolean canScrollVertically() {
                    // 重写此方法，确保内部RecyclerView可以垂直滚动
                    return true;
                }

                @Override
                public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
                    try {
                        super.onLayoutChildren(recycler, state);
                    } catch (IndexOutOfBoundsException e) {
                        // 防止布局异常
                        recordLog("RecyclerView布局异常，已处理: " + e.getMessage());
                    }
                }
            };
            fileListRecyclerView.setLayoutManager(layoutManager);

            // 设置适配器
            fileListRecyclerView.setAdapter(fileListAdapter);

            // 关键修复：完全禁用嵌套滚动，让RecyclerView自己处理滚动
            fileListRecyclerView.setNestedScrollingEnabled(false);

            // 设置固定高度，避免高度计算问题
            fileListRecyclerView.setHasFixedSize(true);

            // 设置触摸拦截，确保RecyclerView可以接收到触摸事件
            fileListRecyclerView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    // 请求父容器不要拦截触摸事件
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return false;
                }
            });

            // 添加滚动监听器来处理触摸事件拦截
            fileListRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);

                    // 在滚动时请求父容器不要拦截触摸事件
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        recyclerView.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
            });

            // 添加分割线
            try {
                androidx.recyclerview.widget.DividerItemDecoration dividerDecoration =
                        new androidx.recyclerview.widget.DividerItemDecoration(itemView.getContext(), LinearLayoutManager.VERTICAL);
                fileListRecyclerView.addItemDecoration(dividerDecoration);
            } catch (Exception e) {
                recordLog("添加分割线失败: " + e.getMessage());
            }

            recordLog("文件列表RecyclerView初始化完成，已修复滚动冲突");
        } else {
            recordLog("警告：fileListRecyclerView为null");
        }
        updateSelectedFiles();
    }


    private void setupDeviceCommandCallback() {
        NotificationHandler.setDeviceCommandCallback(new NotificationHandler.DeviceCommandCallback() {
            @Override
            public void sendCommand(byte[] commandData) {
                try {
                    recordLog("发送设备指令: " + bytesToHexString(commandData));
                    LmAPI.CUSTOMIZE_CMD(commandData, fileTransferCmdListener);
                } catch (Exception e) {
                    recordLog("发送设备指令失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }


            @Override
            public void onMeasurementStarted() {
                recordLog("【测量开始回调】");
                updateMeasurementUIState(true);
                updateMeasurementStatus("数据采集中");
            }

            @Override
            public void onMeasurementStopped() {
                recordLog("【测量停止回调】");
                updateMeasurementUIState(false);
                updateMeasurementStatus("就绪");
            }
            @Override
            public void onExerciseStarted(int duration, int segmentTime) {
                recordLog(String.format("【运动开始】总时长: %d秒, 片段: %d秒", duration, segmentTime));
            }

            @Override
            public void onExerciseStopped() {
                recordLog("【运动停止】");
            }
        });
    }

    private void initializeUI() {
        if (measurementTimeInput != null) {
            measurementTimeInput.setText("30");
        }

        if (exerciseDurationInput != null) {
            exerciseDurationInput.setText("14400");
        }

        if (segmentDurationInput != null) {
            segmentDurationInput.setText("600");
        }

        // 新增：初始化测量状态
        updateMeasurementUIState(false);
        updateMeasurementStatus("就绪");
        updateExerciseStatus("就绪");
    }


    // ==================== 修改后的录制控制逻辑 ====================

    /**
     * 开始录制日志 - 只控制是否记录日志，不进行实际测量
     */
    public void startRingRecording(Context context) {
        if (!isRecordingRing) {
            isRecordingRing = true;
            startBtn.setText("停止录制");

            // 清空图表
            clearAllCharts();

            // 创建日志文件
            try {
                createLogFile(context);
                recordLog("=".repeat(50));
                recordLog("【开始录制会话】时间: " + getCurrentTimestamp());
                recordLog("设备: " + deviceName.getText());
                recordLog("=".repeat(50));

                // 重置测量状态
                updateMeasurementStatus("录制中 - 就绪");

                Toast.makeText(context, "开始录制日志", Toast.LENGTH_SHORT).show();

            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(context, "创建日志文件失败", Toast.LENGTH_SHORT).show();
                isRecordingRing = false;
                startBtn.setText("开始录制");
            }
        }
    }

    /**
     * 停止录制日志
     */
    public void stopRingRecording() {
        if (isRecordingRing) {
            recordLog("=".repeat(50));
            recordLog("【结束录制会话】时间: " + getCurrentTimestamp());
            recordLog("=".repeat(50));

            isRecordingRing = false;
            startBtn.setText("开始录制");

            // 如果正在测量，提醒用户
            if (NotificationHandler.isMeasuring()) {
                recordLog("注意：录制会话已结束，但测量仍在继续");
                updateMeasurementStatus("测量中 - 录制已停止");

                mainHandler.post(() -> {
                    Toast.makeText(itemView.getContext(),
                            "录制已停止\n注意：测量仍在继续，请手动停止采集",
                            Toast.LENGTH_LONG).show();
                });
            } else {
                updateMeasurementStatus("就绪");
            }

            try {
                if (logWriter != null) {
                    logWriter.close();
                    logWriter = null;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    public boolean isCurrentlyMeasuring() {
        return NotificationHandler.isMeasuring();
    }
    public void refreshMeasurementUIState() {
        boolean isMeasuring = NotificationHandler.isMeasuring();
        updateMeasurementUIState(isMeasuring);

        if (isMeasuring) {
            updateMeasurementStatus("测量中");
        } else {
            updateMeasurementStatus("就绪");
        }
    }

    /**
     * 创建日志文件
     */
    private void createLogFile(Context context) throws IOException {
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String experimentId = prefs.getString("experiment_id", "default");

        String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                + "/Sample/" + experimentId + "/RingLog/";
        File directory = new File(directoryPath);

        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new IOException("创建目录失败: " + directoryPath);
            }
        }

        String fileName = "RingSession_" + System.currentTimeMillis() + ".txt";
        File logFile = new File(directory, fileName);
        logWriter = new BufferedWriter(new FileWriter(logFile, true));

        Log.d("RingLog", "日志文件创建: " + logFile.getAbsolutePath());
    }

    /**
     * 获取当前时间戳字符串
     */
    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date());
    }

    /**
     * 清空所有图表
     */
    private void clearAllCharts() {
        if (plotViewG != null) plotViewG.clearPlot();
        if (plotViewI != null) plotViewI.clearPlot();
        if (plotViewR != null) plotViewR.clearPlot();
        if (plotViewX != null) plotViewX.clearPlot();
        if (plotViewY != null) plotViewY.clearPlot();
        if (plotViewZ != null) plotViewZ.clearPlot();
    }

    // ==================== 增强的文件下载功能 ====================

    /**
     * 开始下载选中的文件
     */
    public void startDownloadSelectedFiles(Context context) {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(context, "请先选择要下载的文件", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isDownloadingFiles) {
            Toast.makeText(context, "正在下载中，请等待", Toast.LENGTH_SHORT).show();
            return;
        }

        isDownloadingFiles = true;
        currentDownloadIndex = 0;
        downloadSelectedBtn.setText("下载中...");

        recordLog(String.format("【开始批量下载】选中文件数: %d", selectedFiles.size()));

        // 显示下载列表
        for (int i = 0; i < selectedFiles.size(); i++) {
            FileInfo file = selectedFiles.get(i);
            recordLog(String.format("  %d. %s (%s)", i + 1, file.fileName, file.getFormattedSize()));
        }

        downloadNextSelectedFile(context);
    }

    /**
     * 下载下一个选中的文件
     */
    private void downloadNextSelectedFile(Context context) {
        if (currentDownloadIndex >= selectedFiles.size()) {
            // 所有文件下载完成
            isDownloadingFiles = false;
            mainHandler.post(() -> {
                downloadSelectedBtn.setText(String.format("下载选中 (%d)", selectedFiles.size()));

                recordLog("【批量下载完成】所有选中文件已下载");
                Toast.makeText(context, "所有选中文件下载完成", Toast.LENGTH_LONG).show();
            });
            return;
        }

        FileInfo fileInfo = selectedFiles.get(currentDownloadIndex);
        recordLog(String.format("下载进度 %d/%d: %s",
                currentDownloadIndex + 1, selectedFiles.size(), fileInfo.fileName));

        requestFileData(context, fileInfo);
    }

    /**
     * 请求文件列表 - 增强版
     */
    public void requestFileList(Context context) {
        recordLog("【请求文件列表】使用自定义指令");

        try {
            String hexCommand = String.format("00%02X3610", generateRandomFrameId());
            byte[] data = hexStringToByteArray(hexCommand);

            recordLog("发送文件列表命令: " + hexCommand);
            LmAPI.CUSTOMIZE_CMD(data, fileTransferCmdListener);

            // 清空之前的文件列表和选择
            fileList.clear();
            selectedFiles.clear();

            mainHandler.post(() -> {
                if (fileListAdapter != null) {
                    fileListAdapter.notifyDataSetChanged();
                }
                updateSelectedFiles();
                requestFileListBtn.setText("获取中...");
            });

        } catch (Exception e) {
            recordLog("发送文件列表请求失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 处理文件列表响应 - 修正版，一次性解析所有文件
     */
    private void handleFileListResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("文件列表响应数据长度不足: " + (data != null ? data.length : "null"));
                return;
            }

            // 验证响应格式
            if (data[0] != 0x00 || data[2] != 0x36 || data[3] != 0x10) {
                recordLog("文件列表响应格式错误");
                recordLog(String.format("期望: FrameType=0x00, Cmd=0x36, Subcmd=0x10"));
                recordLog(String.format("实际: FrameType=0x%02X, Cmd=0x%02X, Subcmd=0x%02X",
                        data[0] & 0xFF, data[2] & 0xFF, data[3] & 0xFF));
                return;
            }

            recordLog("收到文件列表响应: " + bytesToHexString(data));
            recordLog("Response Frame ID: 0x" + String.format("%02X", data[1] & 0xFF));

            int offset = 4; // 跳过帧头

            // 检查数据长度是否足够读取基本信息 (总数4 + 序号4 + 大小4 = 12字节)
            if (data.length < offset + 12) {
                recordLog("数据长度不足，无法读取文件基本信息");
                recordLog("需要至少12字节，实际剩余: " + (data.length - offset));
                return;
            }

            // 读取文件基本信息 (对齐Python逻辑)
            int totalFiles = readUInt32LE(data, offset);    // 文件总数
            offset += 4;
            int seqNum = readUInt32LE(data, offset);        // 当前序号
            offset += 4;
            int fileSize = readUInt32LE(data, offset);      // 当前文件大小
            offset += 4;

            recordLog(String.format("文件列表信息 - 总数: %d, 当前序号: %d, 文件大小: %d",
                    totalFiles, seqNum, fileSize));

            if (totalFiles == 0) {
                recordLog("设备中没有文件");
                mainHandler.post(() -> {
                    requestFileListBtn.setText("获取文件列表");
                    fileListStatusText.setText("文件列表 (设备中无文件)");
                });
                return;
            }

            // 检查剩余数据是否包含文件名
            int remainingBytes = data.length - offset;
            if (remainingBytes <= 0) {
                recordLog("没有文件名数据");
                return;
            }

            recordLog("文件名数据长度: " + remainingBytes + " 字节");

            // 读取文件名数据
            byte[] fileNameBytes = new byte[remainingBytes];
            System.arraycopy(data, offset, fileNameBytes, 0, remainingBytes);

            // 解析文件名 (对齐Python的字符串处理)
            String fileName = "";
            try {
                // 查找第一个null终止符
                int nameLength = 0;
                for (int i = 0; i < fileNameBytes.length; i++) {
                    if (fileNameBytes[i] == 0) {
                        nameLength = i;
                        break;
                    }
                }

                // 如果没有找到null终止符，使用全部字节
                if (nameLength == 0) {
                    nameLength = fileNameBytes.length;
                }

                // 使用UTF-8解码文件名
                fileName = new String(fileNameBytes, 0, nameLength, StandardCharsets.UTF_8).trim();

                // 如果解析结果为空，尝试其他编码
                if (fileName.isEmpty()) {
                    fileName = new String(fileNameBytes, StandardCharsets.UTF_8).trim();
                }

            } catch (Exception e) {
                recordLog("文件名解析失败: " + e.getMessage());
                // 作为备份，显示十六进制
                fileName = "HEX_" + bytesToHexString(fileNameBytes);
            }

            recordLog(String.format("解析文件信息:"));
            recordLog(String.format("  - 文件名: '%s'", fileName));
            recordLog(String.format("  - 文件大小: %d bytes", fileSize));
            recordLog(String.format("  - 序号: %d/%d", seqNum, totalFiles));
            recordLog(String.format("  - 文件名字节: %s", bytesToHexString(fileNameBytes)));

            // 验证文件名有效性
            if (!fileName.isEmpty() && !fileName.startsWith("HEX_")) {

                // 检查是否已经存在相同文件 (避免重复添加)
                boolean exists = false;
                for (FileInfo existingFile : fileList) {
                    if (existingFile.fileName.equals(fileName)) {
                        exists = true;
                        break;
                    }
                }

                if (!exists) {
                    FileInfo fileInfo = new FileInfo(fileName, fileSize);
                    fileList.add(fileInfo);
                    recordLog(String.format("✓ 成功添加文件: %s (%s)", fileName, fileInfo.getFormattedSize()));

                    // 解析文件名详细信息 (对齐Python的解析逻辑)
                    parseFileNameDetails(fileName);

                } else {
                    recordLog("文件已存在，跳过: " + fileName);
                }
            } else {
                recordLog("文件名无效，跳过添加: " + fileName);
            }

            // 更新UI
            mainHandler.post(() -> {
                if (fileListAdapter != null) {
                    fileListAdapter.notifyDataSetChanged();
                }
                updateSelectedFiles();
                requestFileListBtn.setText("获取文件列表");
            });

            // 检查是否需要继续获取更多文件 (对齐Python的分页逻辑)
            if (seqNum < totalFiles) {
                recordLog(String.format("当前是第 %d/%d 个文件，可能需要继续获取后续文件", seqNum, totalFiles));

                // 自动请求下一个文件 (可选，根据协议需要)
                // mainHandler.postDelayed(() -> requestFileList(itemView.getContext()), 500);
            } else {
                recordLog(String.format("文件列表获取完成，共 %d 个文件", fileList.size()));

                // 最终更新UI状态
                mainHandler.post(() -> {
                    fileListStatusText.setText(String.format("文件列表 (共%d个文件)", fileList.size()));

                    if (fileList.size() > 0) {
                        Toast.makeText(itemView.getContext(),
                                String.format("获取到 %d 个文件", fileList.size()),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }

        } catch (Exception e) {
            recordLog("解析文件列表失败: " + e.getMessage());
            e.printStackTrace();

            // 恢复UI状态
            mainHandler.post(() -> {
                requestFileListBtn.setText("获取文件列表");
                fileListStatusText.setText("文件列表解析失败");
            });
        }
    }

    /**
     * 解析文件名详细信息 (对齐Python逻辑)
     */
    private void parseFileNameDetails(String fileName) {
        try {
            recordLog("解析文件名详情: " + fileName);

            // 解析文件名格式：用户id_年_月_日时分秒_文件类型.扩展名
            // 例如：010203040506_2025_06_17:02:06:26_7.bin

            if (fileName.contains("_")) {
                String[] parts = fileName.split("_");
                if (parts.length >= 2) {
                    String userId = parts[0];
                    recordLog("  - 用户ID: " + userId);

                    if (parts.length >= 3) {
                        String year = parts[1];
                        String monthDay = parts[2];
                        recordLog("  - 年份: " + year);
                        recordLog("  - 月日: " + monthDay);
                    }

                    if (parts.length >= 4) {
                        String timeAndType = parts[3];
                        recordLog("  - 时间和类型: " + timeAndType);

                        // 进一步解析时间部分
                        if (timeAndType.contains(":")) {
                            String[] timeParts = timeAndType.split(":");
                            if (timeParts.length >= 3) {
                                recordLog("  - 时: " + timeParts[0]);
                                recordLog("  - 分: " + timeParts[1]);
                                if (timeParts[2].contains("_")) {
                                    String[] secType = timeParts[2].split("_");
                                    recordLog("  - 秒: " + secType[0]);
                                    if (secType.length > 1) {
                                        String typeAndExt = secType[1];
                                        if (typeAndExt.contains(".")) {
                                            String[] typeExt = typeAndExt.split("\\.");
                                            recordLog("  - 类型: " + typeExt[0]);
                                            recordLog("  - 扩展名: " + typeExt[1]);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 根据扩展名判断文件格式
            if (fileName.endsWith(".bin")) {
                recordLog("  - 文件格式: 二进制文件");
            } else if (fileName.endsWith(".txt")) {
                recordLog("  - 文件格式: 文本文件");
            }

        } catch (Exception e) {
            recordLog("解析文件名详情失败: " + e.getMessage());
        }
    }
    // ==================== 主动测量功能 ====================

    void startActiveMeasurement(Context context) {
        try {
            String timeStr = measurementTimeInput.getText().toString().trim();
            if (timeStr.isEmpty()) {
                Toast.makeText(context, "请输入测量时间", Toast.LENGTH_SHORT).show();
                return;
            }

            int measurementTime = Integer.parseInt(timeStr);
            if (measurementTime < 1 || measurementTime > 3600) {
                Toast.makeText(context, "测量时间应在1-3600秒之间", Toast.LENGTH_SHORT).show();
                return;
            }

            NotificationHandler.setMeasurementTime(measurementTime);
            boolean success = NotificationHandler.startActiveMeasurement();
            if (success) {
                recordLog("【开始主动测量】时间: " + measurementTime + "秒");

                // 更新UI状态：测量开始
                updateMeasurementUIState(true);
                updateMeasurementStatus("测量中...");

                Toast.makeText(context, "测量开始，可随时点击'停止采集'结束", Toast.LENGTH_SHORT).show();

                // 注意：不再设置自动停止的延时任务，让用户手动控制

            } else {
                Toast.makeText(context, "开始测量失败", Toast.LENGTH_SHORT).show();
            }

        } catch (NumberFormatException e) {
            Toast.makeText(context, "请输入有效的测量时间", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            recordLog("开始主动测量失败: " + e.getMessage());
            Toast.makeText(context, "开始测量失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }


    // ==================== 运动控制功能 ====================
    void stopActiveMeasurement(Context context) {
        try {
            if (!NotificationHandler.isMeasuring()) {
                Toast.makeText(context, "当前没有正在进行的测量", Toast.LENGTH_SHORT).show();
                return;
            }

            boolean success = NotificationHandler.stopMeasurement();
            if (success) {
                recordLog("【手动停止测量】用户点击停止采集按钮");

                // 更新UI状态：测量停止
                updateMeasurementUIState(false);
                updateMeasurementStatus("已停止");

                Toast.makeText(context, "测量已停止", Toast.LENGTH_SHORT).show();

            } else {
                Toast.makeText(context, "停止测量失败", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            recordLog("停止测量失败: " + e.getMessage());
            Toast.makeText(context, "停止测量失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 新增：更新测量UI状态
    private void updateMeasurementUIState(boolean isMeasuring) {
        mainHandler.post(() -> {
            if (startMeasurementBtn != null) {
                startMeasurementBtn.setEnabled(!isMeasuring);
                startMeasurementBtn.setText(isMeasuring ? "测量中..." : "开始测量");
            }

            if (stopCollectionBtn != null) {
                stopCollectionBtn.setEnabled(isMeasuring);
                stopCollectionBtn.setBackgroundColor(isMeasuring ?
                        Color.parseColor("#F44336") : Color.GRAY);
            }
        });
    }

    // 新增：更新测量状态文本
    private void updateMeasurementStatus(String status) {
        if (measurementStatusText != null) {
            mainHandler.post(() -> measurementStatusText.setText("测量状态: " + status));
        }
    }
    void startExercise(Context context) {
        try {
            String durationStr = exerciseDurationInput.getText().toString().trim();
            String segmentStr = segmentDurationInput.getText().toString().trim();

            if (durationStr.isEmpty() || segmentStr.isEmpty()) {
                Toast.makeText(context, "请输入运动时长和片段时长", Toast.LENGTH_SHORT).show();
                return;
            }

            int totalDuration = Integer.parseInt(durationStr);
            int segmentDuration = Integer.parseInt(segmentStr);

            if (totalDuration < 60 || totalDuration > 86400) {
                Toast.makeText(context, "运动总时长应在60-86400秒之间", Toast.LENGTH_SHORT).show();
                return;
            }

            if (segmentDuration < 30 || segmentDuration > totalDuration) {
                Toast.makeText(context, "片段时长应在30秒到总时长之间", Toast.LENGTH_SHORT).show();
                return;
            }

            NotificationHandler.setExerciseParams(totalDuration, segmentDuration);
            boolean success = NotificationHandler.startExercise();
            if (success) {
                recordLog(String.format("【开始运动】总时长: %d秒, 片段: %d秒", totalDuration, segmentDuration));


                updateExerciseStatus(String.format("运动中 - 总时长: %d分钟, 片段: %d分钟",
                        totalDuration/60, segmentDuration/60));

                Toast.makeText(context, "运动开始", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "开始运动失败", Toast.LENGTH_SHORT).show();
            }

        } catch (NumberFormatException e) {
            Toast.makeText(context, "请输入有效的数字", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            recordLog("开始运动失败: " + e.getMessage());
            Toast.makeText(context, "开始运动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    void stopExercise(Context context) {
        try {
            boolean success = NotificationHandler.stopExercise();
            if (success) {
                recordLog("【结束运动】用户手动停止");


                updateExerciseStatus("已停止");

                Toast.makeText(context, "运动已停止", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "停止运动失败", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            recordLog("停止运动失败: " + e.getMessage());
            Toast.makeText(context, "停止运动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateExerciseStatus(String status) {
        if (exerciseStatusText != null) {
            mainHandler.post(() -> exerciseStatusText.setText("运动状态: " + status));
        }
    }

    // ==================== 时间同步功能 ====================
    private void setupNotificationHandlerLogging() {
        // 将当前RingViewHolder的recordLog方法连接到NotificationHandler
        NotificationHandler.setLogRecorder(new NotificationHandler.LogRecorder() {
            @Override
            public void recordLog(String message) {
                // 调用RingViewHolder的recordLog方法
                RingViewHolder.this.recordLog(message);
            }
        });

        recordLog("✅ NotificationHandler日志记录已连接到RingViewHolder");
        recordLog("现在NotificationHandler的所有操作都会记录到当前会话日志中");
    }
    public void updateRingTime(Context context) {
        if (isTimeUpdating) {
            Toast.makeText(context, "时间更新正在进行中，请等待", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            isTimeUpdating = true;
            timeUpdateFrameId = generateRandomFrameId();

            recordLog("【开始更新戒指时间】使用自定义指令");

            long currentTime = System.currentTimeMillis();
            TimeZone timeZone = TimeZone.getDefault();
            int timezoneOffset = timeZone.getRawOffset() / (1000 * 60 * 60);

            recordLog("主机当前时间: " + currentTime + " ms");
            recordLog("当前时区偏移: UTC" + (timezoneOffset >= 0 ? "+" : "") + timezoneOffset);

            StringBuilder hexCommand = new StringBuilder();
            hexCommand.append(String.format("00%02X1000", timeUpdateFrameId));

            for (int i = 0; i < 8; i++) {
                hexCommand.append(String.format("%02X", (currentTime >> (i * 8)) & 0xFF));
            }

            int timezoneValue = timezoneOffset;
            if (timezoneValue < 0) {
                timezoneValue = 256 + timezoneValue;
            }
            hexCommand.append(String.format("%02X", timezoneValue & 0xFF));

            byte[] data = hexStringToByteArray(hexCommand.toString());
            recordLog("发送时间更新命令: " + hexCommand.toString());

            timeUpdateBtn.setText("更新中...");

            LmAPI.CUSTOMIZE_CMD(data, fileTransferCmdListener);

        } catch (Exception e) {
            recordLog("发送时间更新命令失败: " + e.getMessage());
            e.printStackTrace();

            mainHandler.post(() -> {
                timeUpdateBtn.setText("更新时间");
            });
            isTimeUpdating = false;
        }
    }

    public void performTimeSync(Context context) {
        if (isTimeSyncing) {
            Toast.makeText(context, "时间校准正在进行中，请等待", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            isTimeSyncing = true;
            timeSyncRequestTime = System.currentTimeMillis();
            timeSyncFrameId = generateRandomFrameId();

            recordLog("【开始时间校准同步】使用自定义指令");
            recordLog("主机发送时间: " + timeSyncRequestTime + " ms");
            StringBuilder hexCommand = new StringBuilder();
            hexCommand.append(String.format("00%02X1002", timeSyncFrameId));

            long timestamp = timeSyncRequestTime;
            for (int i = 0; i < 8; i++) {
                hexCommand.append(String.format("%02X", (timestamp >> (i * 8)) & 0xFF));
            }

            byte[] data = hexStringToByteArray(hexCommand.toString());
            recordLog("发送时间校准命令: " + hexCommand.toString());

            timeSyncBtn.setText("校准中...");
            timeSyncRequestTime = timeSyncRequestTime/1000;

            LmAPI.CUSTOMIZE_CMD(data, fileTransferCmdListener);

        } catch (Exception e) {
            recordLog("发送时间校准命令失败: " + e.getMessage());
            e.printStackTrace();

            mainHandler.post(() -> {
                timeSyncBtn.setText("时间校准");
            });
            isTimeSyncing = false;
        }
    }

    // ==================== 文件下载相关方法 ====================

    private void requestFileData(Context context, FileInfo fileInfo) {
        recordLog("请求文件数据: " + fileInfo.fileName);

        try {
            byte[] fileNameBytes = fileInfo.fileName.getBytes(StandardCharsets.UTF_8);
            int length = fileNameBytes.length;

            recordLog("文件名UTF-8编码长度: " + length + " 字节");
            recordLog("文件名字节数据: " + bytesToHexString(fileNameBytes));

            sendFileGetCommand(fileNameBytes, length);

        } catch (Exception e) {
            recordLog("请求文件数据失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendFileGetCommand(byte[] fileNameBytes, int length) {
        try {
            int frameId = generateRandomFrameId();
            StringBuilder hexCommand = new StringBuilder();
            hexCommand.append(String.format("00%02X3611", frameId));

            for (byte b : fileNameBytes) {
                hexCommand.append(String.format("%02X", b & 0xFF));
            }

            byte[] commandData = hexStringToByteArray(hexCommand.toString());

            recordLog("发送文件获取命令: " + hexCommand.toString());
            recordLog("命令结构:");
            recordLog("  - Frame ID: 0x" + String.format("%02X", frameId));
            recordLog("  - 文件名: " + new String(fileNameBytes, StandardCharsets.UTF_8));
            recordLog("  - 文件名字节数: " + length);

            LmAPI.CUSTOMIZE_CMD(commandData, fileTransferCmdListener);

        } catch (Exception e) {
            recordLog("发送文件获取命令失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void downloadFileByName(Context context, String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            Toast.makeText(context, "请输入文件名", Toast.LENGTH_SHORT).show();
            return;
        }

        recordLog("【手动下载文件】: " + fileName.trim());
        requestSpecificFile(context, fileName.trim());
    }

    public void requestSpecificFile(Context context, String fileName) {
        recordLog("【请求特定文件】: " + fileName);

        try {
            byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
            int length = fileNameBytes.length;

            recordLog("文件名: " + fileName);
            recordLog("UTF-8编码长度: " + length + " 字节");

            sendFileGetCommand(fileNameBytes, length);

        } catch (Exception e) {
            recordLog("请求文件失败: " + e.getMessage());
            e.printStackTrace();

            Toast.makeText(context, "请求文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 响应处理方法 ====================

    private void handleFileDataResponse(byte[] data) {
        try {
            if (data.length < 4) {
                recordLog("文件数据响应长度不足");
                return;
            }

            if (data[2] == 0x36 && data[3] == 0x11) {
                int offset = 4;

                if (data.length < offset + 25) {
                    recordLog("文件数据结构不完整，需要至少25字节头部信息");
                    recordLog("实际长度: " + (data.length - offset) + "字节");
                    return;
                }

                int fileStatus = data[offset] & 0xFF;
                offset += 1;
                int fileSize = readUInt32LE(data, offset);
                offset += 4;
                int totalPackets = readUInt32LE(data, offset);
                offset += 4;
                int currentPacket = readUInt32LE(data, offset);
                offset += 4;
                int currentPacketLength = readUInt32LE(data, offset);
                offset += 4;
                long timestamp = readUInt64LE(data, offset);
                offset += 8;

                recordLog("文件数据包解析结果:");
                recordLog("  文件状态: " + fileStatus);
                recordLog("  文件大小: " + fileSize + " bytes");
                recordLog("  总包数: " + totalPackets);
                recordLog("  当前包号: " + currentPacket);
                recordLog("  当前包长度: " + currentPacketLength);
                recordLog("  时间戳: " + timestamp);

                int requiredLength = 25 + 5 * 30;
                int availableLength = data.length - 4;

                if (availableLength < requiredLength) {
                    recordLog("数据长度不足: " + availableLength + "，需要至少" + requiredLength + "字节");
                    return;
                }

                int dataNum = 5;
                for (int groupIdx = 0; groupIdx < dataNum; groupIdx++) {
                    int dataOffset = (4 + 25) + groupIdx * 30;

                    if (dataOffset + 30 > data.length) {
                        recordLog("第" + (groupIdx + 1) + "组数据不完整");
                        break;
                    }

                    long green = readUInt32LE(data, dataOffset);
                    long red = readUInt32LE(data, dataOffset + 4);
                    long ir = readUInt32LE(data, dataOffset + 8);
                    short accX = readInt16LE(data, dataOffset + 12);
                    short accY = readInt16LE(data, dataOffset + 14);
                    short accZ = readInt16LE(data, dataOffset + 16);
                    short gyroX = readInt16LE(data, dataOffset + 18);
                    short gyroY = readInt16LE(data, dataOffset + 20);
                    short gyroZ = readInt16LE(data, dataOffset + 22);
                    short temper0 = readInt16LE(data, dataOffset + 24);
                    short temper1 = readInt16LE(data, dataOffset + 26);
                    short temper2 = readInt16LE(data, dataOffset + 28);

                    updatePlotViews(green, red, ir, accX, accY, accZ);

                    String logMsg = String.format("green:%d red:%d ir:%d " +
                                    "acc_x:%d acc_y:%d acc_z:%d " +
                                    "gyro_x:%d gyro_y:%d gyro_z:%d " +
                                    "temper0:%d temper1:%d temper2:%d",
                            green, red, ir, accX, accY, accZ, gyroX, gyroY, gyroZ, temper0, temper1, temper2);

                    recordLog(logMsg);
                }

                // 保存文件数据到固定位置
                if (isDownloadingFiles && currentDownloadIndex < selectedFiles.size()) {
                    FileInfo fileInfo = selectedFiles.get(currentDownloadIndex);
                    saveFileDataToFixedLocation(fileInfo, data, currentPacket, totalPackets);

                    if (currentPacket >= totalPackets) {
                        recordLog(String.format("文件下载完成: %s", fileInfo.fileName));
                        currentDownloadIndex++;
                        mainHandler.postDelayed(() -> downloadNextSelectedFile(itemView.getContext()), 500);
                    }
                }
            }
        } catch (Exception e) {
            recordLog("解析文件数据失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 保存文件数据到固定位置
     */
    private void saveFileDataToFixedLocation(FileInfo fileInfo, byte[] data, int currentPacket, int totalPackets) {
        try {
            Context context = itemView.getContext();
            SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
            String experimentId = prefs.getString("experiment_id", "default");

            // 固定下载目录：/Sample/[实验ID]/RingLog/Downloads/
            String directoryPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    + "/Sample/" + experimentId + "/RingLog/Downloads/";
            File directory = new File(directoryPath);
            if (!directory.exists()) {
                directory.mkdirs();
            }
            String safeFileName = fileInfo.fileName.replace(":", "_");

            File file = new File(directory, safeFileName);
            boolean append = currentPacket > 1;

            try (FileWriter fileWriter = new FileWriter(file, append);
                 BufferedWriter writer = new BufferedWriter(fileWriter)) {

                if (currentPacket == 1) {
                    writer.write("# ========== 文件信息 ==========\n");
                    writer.write("# 文件名: " + fileInfo.fileName + "\n");
                    writer.write("# 文件类型: " + fileInfo.getFileTypeDescription() + "\n");
                    writer.write("# 用户ID: " + fileInfo.userId + "\n");
                    writer.write("# 时间: " + fileInfo.timestamp + "\n");
                    writer.write("# 下载时间: " + getCurrentTimestamp() + "\n");
                    writer.write("# 总包数: " + totalPackets + "\n");
                    writer.write("# ================================\n\n");
                }
                writer.write("# 数据包 " + currentPacket + "/" + totalPackets + ":\n");
                writer.write("# 原始数据: " + bytesToHexString(data) + "\n");
                writer.write("\n");
                writer.flush();
            }

            recordLog(String.format("文件数据已保存: %s (包 %d/%d) -> %s",
                    fileInfo.fileName, currentPacket, totalPackets, file.getAbsolutePath()));

        } catch (IOException e) {
            recordLog("保存文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void parseType7DataForFile(byte[] data, BufferedWriter writer) throws IOException {
        try {
            int offset = 25;
            int pointIndex = 0;

            writer.write("# 解析的传感器数据:\n");

            while (offset + 30 <= data.length && pointIndex < 5) {
                writer.write("# 数据点 " + (pointIndex + 1) + ":\n");

                long green = readUInt32LE(data, offset);
                writer.write("#   Green PPG: " + green + "\n");
                offset += 4;

                long red = readUInt32LE(data, offset);
                writer.write("#   Red PPG: " + red + "\n");
                offset += 4;

                long ir = readUInt32LE(data, offset);
                writer.write("#   IR PPG: " + ir + "\n");
                offset += 4;

                short accX = readInt16LE(data, offset);
                short accY = readInt16LE(data, offset + 2);
                short accZ = readInt16LE(data, offset + 4);
                writer.write(String.format("#   加速度: X=%d, Y=%d, Z=%d\n", accX, accY, accZ));
                offset += 6;

                short gyroX = readInt16LE(data, offset);
                short gyroY = readInt16LE(data, offset + 2);
                short gyroZ = readInt16LE(data, offset + 4);
                writer.write(String.format("#   陀螺仪: X=%d, Y=%d, Z=%d\n", gyroX, gyroY, gyroZ));
                offset += 6;

                short temp0 = readInt16LE(data, offset);
                short temp1 = readInt16LE(data, offset + 2);
                short temp2 = readInt16LE(data, offset + 4);
                writer.write(String.format("#   温度: T0=%d, T1=%d, T2=%d\n", temp0, temp1, temp2));
                offset += 6;

                pointIndex++;
            }
        } catch (Exception e) {
            writer.write("# 数据解析错误: " + e.getMessage() + "\n");
        }
    }

    // ==================== 时间响应处理 ====================

    private void handleTimeUpdateResponse(byte[] data) {
        try {
            if (data == null || data.length < 4) {
                recordLog("时间更新响应数据长度不足");
                return;
            }

            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            if (frameType != 0x00 || cmd != 0x10 || subcmd != 0x00) {
                recordLog("时间更新响应格式错误");
                return;
            }

            if (frameId != timeUpdateFrameId) {
                recordLog("时间更新响应Frame ID不匹配");
                return;
            }

            recordLog("【时间更新完成】戒指时间已成功更新");

            mainHandler.post(() -> {
                timeUpdateBtn.setText("更新时间");
                Toast.makeText(itemView.getContext(), "戒指时间更新成功", Toast.LENGTH_SHORT).show();
            });

        } catch (Exception e) {
            recordLog("解析时间更新响应失败: " + e.getMessage());
            e.printStackTrace();

            mainHandler.post(() -> {
                timeUpdateBtn.setText("更新时间");
            });
        } finally {
            isTimeUpdating = false;
        }
    }

    private void handleTimeSyncResponse(byte[] data) {
        try {
            long currentTime = System.currentTimeMillis()/1000;

            if (data == null || data.length < 28) {
                recordLog("时间校准响应数据长度不足");
                return;
            }

            int frameType = data[0] & 0xFF;
            int frameId = data[1] & 0xFF;
            int cmd = data[2] & 0xFF;
            int subcmd = data[3] & 0xFF;

            if (frameType != 0x00 || cmd != 0x10 || subcmd != 0x02) {
                recordLog("时间校准响应格式错误");
                return;
            }

            if (frameId != timeSyncFrameId) {
                recordLog("时间校准响应Frame ID不匹配");
                return;
            }

            int offset = 4;
            long hostSentTime = readUInt64LE(data, offset)/1000;
            offset += 8;
            long ringReceivedTime = readUInt64LE(data, offset)/1000;
            offset += 8;
            long ringUploadTime = readUInt64LE(data, offset)/1000;

            long roundTripTime = currentTime - timeSyncRequestTime;
            long oneWayDelay = roundTripTime / 2;
            long timeDifference = ringReceivedTime - hostSentTime;

            recordLog("【时间校准结果】");
            recordLog(String.format("主机发送时间: %d ", hostSentTime));
            recordLog(String.format("戒指接收时间: %d ", ringReceivedTime));
            recordLog(String.format("戒指上传时间: %d ", ringUploadTime));
            recordLog(String.format("往返延迟: %d s", roundTripTime));
            recordLog(String.format("单程延迟估计: %d s", oneWayDelay));
            recordLog(String.format("时间差: %d s", timeDifference));

//            String quality;
//            if (Math.abs(timeDifference) < 50) {
//                quality = "✓ 时间同步良好 (差值 < 50ms)";
//            } else if (Math.abs(timeDifference) < 200) {
//                quality = "⚠ 时间同步一般 (差值 < 200ms)";
//            } else {
//                quality = "✗ 时间同步较差 (差值 >= 200ms)";
//            }
//            recordLog(quality);

            mainHandler.post(() -> {
                timeSyncBtn.setText("时间校准");
                Toast.makeText(itemView.getContext(),
                        String.format("时间校准完成\n时间差: %d s\n延迟: %d s", timeDifference, roundTripTime),
                        Toast.LENGTH_LONG).show();
            });

        } catch (Exception e) {
            recordLog("解析时间校准响应失败: " + e.getMessage());
            e.printStackTrace();

            mainHandler.post(() -> {
                timeSyncBtn.setText("时间校准");
            });
        } finally {
            isTimeSyncing = false;
        }
    }

    private void handleStartExerciseResponse(byte[] data) {
        try {
            recordLog("收到开始运动响应");

            if (data.length >= 4) {
                int frameId = data[1] & 0xFF;
                recordLog("开始运动响应 Frame ID: 0x" + String.format("%02X", frameId));

                mainHandler.post(() -> {
                    updateExerciseStatus("运动指令已发送");
                    Toast.makeText(itemView.getContext(), "运动指令发送成功", Toast.LENGTH_SHORT).show();
                });
            }
        } catch (Exception e) {
            recordLog("处理开始运动响应失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleStopExerciseResponse(byte[] data) {
        try {
            recordLog("收到结束运动响应");

            if (data.length >= 4) {
                int frameId = data[1] & 0xFF;
                recordLog("结束运动响应 Frame ID: 0x" + String.format("%02X", frameId));

                mainHandler.post(() -> {
                    updateExerciseStatus("运动已结束");
                    Toast.makeText(itemView.getContext(), "运动结束指令发送成功", Toast.LENGTH_SHORT).show();
                });
            }
        } catch (Exception e) {
            recordLog("处理结束运动响应失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== 图表和回调初始化 ====================

    private void initializePlotViews(View itemView) {
        plotViewG = itemView.findViewById(R.id.plotViewG);
        plotViewI = itemView.findViewById(R.id.plotViewI);
        plotViewR = itemView.findViewById(R.id.plotViewR);
        plotViewX = itemView.findViewById(R.id.plotViewX);
        plotViewY = itemView.findViewById(R.id.plotViewY);
        plotViewZ = itemView.findViewById(R.id.plotViewZ);

        if (plotViewG != null) plotViewG.setPlotColor(Color.parseColor("#00FF00"));
        if (plotViewI != null) plotViewI.setPlotColor(Color.parseColor("#0000FF"));
        if (plotViewR != null) plotViewR.setPlotColor(Color.parseColor("#FF0000"));
        if (plotViewX != null) plotViewX.setPlotColor(Color.parseColor("#FFFF00"));
        if (plotViewY != null) plotViewY.setPlotColor(Color.parseColor("#FF00FF"));
        if (plotViewZ != null) plotViewZ.setPlotColor(Color.parseColor("#00FFFF"));

        NotificationHandler.setPlotViewG(plotViewG);
        NotificationHandler.setPlotViewI(plotViewI);
        NotificationHandler.setPlotViewR(plotViewR);
        NotificationHandler.setPlotViewX(plotViewX);
        NotificationHandler.setPlotViewY(plotViewY);
        NotificationHandler.setPlotViewZ(plotViewZ);
    }

    private void setupNotificationCallback() {
        NotificationHandler.setFileResponseCallback(new NotificationHandler.FileResponseCallback() {
            @Override
            public void onFileListReceived(byte[] data) {
                handleFileListResponse(data);
            }

            @Override
            public void onFileDataReceived(byte[] data) {
                handleFileDataResponse(data);
            }
        });

        NotificationHandler.setTimeSyncCallback(new NotificationHandler.TimeSyncCallback() {
            @Override
            public void onTimeSyncResponse(byte[] data) {
                handleTimeSyncResponse(data);
            }

            @Override
            public void onTimeUpdateResponse(byte[] data) {
                handleTimeUpdateResponse(data);
            }
        });
    }

    // ==================== 工具方法 ====================

    private int readUInt32LE(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            throw new IndexOutOfBoundsException("数据不足以读取4字节整型");
        }
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }

    private long readUInt64LE(byte[] data, int offset) {
        if (offset + 8 > data.length) {
            throw new IndexOutOfBoundsException("数据不足以读取8字节时间戳");
        }
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long)(data[offset + i] & 0xFF)) << (i * 8);
        }
        return result;
    }

    private short readInt16LE(byte[] data, int offset) {
        if (offset + 2 > data.length) {
            throw new IndexOutOfBoundsException("数据不足以读取2字节短整型");
        }
        return (short)((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
    }

    private String formatTimestamp(long timestampMillis) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(timestampMillis));
    }

    private void updatePlotViews(long green, long red, long ir, short accX, short accY, short accZ) {
        if (plotViewG != null) plotViewG.addValue((int)green);
        if (plotViewR != null) plotViewR.addValue((int)red);
        if (plotViewI != null) plotViewI.addValue((int)ir);
        if (plotViewX != null) plotViewX.addValue(accX);
        if (plotViewY != null) plotViewY.addValue(accY);
        if (plotViewZ != null) plotViewZ.addValue(accZ);
    }

    private String bytesToHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private int generateRandomFrameId() {
        Random random = new Random();
        return random.nextInt(256);
    }

    // ==================== 基础功能保持不变 ====================

    public void toggleInfo() {
        if (infoVisible) {
            infoLayout.animate()
                    .translationY(100)
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> infoLayout.setVisibility(View.GONE))
                    .start();
        } else {
            infoLayout.setAlpha(0f);
            infoLayout.setTranslationY(100);
            infoLayout.setVisibility(View.VISIBLE);
            infoLayout.animate()
                    .translationY(0)
                    .alpha(1f)
                    .setDuration(200)
                    .start();
        }
        infoVisible = !infoVisible;
    }

    public void connectToDevice(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("AppSettings", MODE_PRIVATE);
        String macAddress = prefs.getString("mac_address", "");

        if (macAddress.isEmpty()) {
            Toast.makeText(context, "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(macAddress);
        if (device != null) {
            BLEUtils.connectLockByBLE(context, device);
            recordLog("【连接蓝牙设备】MAC: " + macAddress);
        } else {
            Toast.makeText(context, "Invalid MAC address", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 增强的日志记录功能 - 记录所有重要操作
     */
    public void recordLog(String logMessage) {
        String timestamp = getCurrentTimestamp();
        String fullLogMessage = "[" + timestamp + "] " + logMessage;

        // 显示到UI
        mainHandler.post(() -> tvLog.setText(logMessage));

        // 写入文件（仅在录制状态下）
        if (isRecordingRing && logWriter != null) {
            try {
                logWriter.write(fullLogMessage + "\n");
                logWriter.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Log.d("RingViewHolder", fullLogMessage);
    }

    /**
     * 获取录制状态
     */
    public boolean isRecording() {
        return isRecordingRing;
    }

    /**
     * 设置录制状态（供外部调用）
     */
    public void setRecording(boolean recording) {
        if (recording && !isRecordingRing) {
            startRingRecording(itemView.getContext());
        } else if (!recording && isRecordingRing) {
            stopRingRecording();
        }
    }
}
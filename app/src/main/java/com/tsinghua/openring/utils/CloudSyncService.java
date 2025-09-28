package com.tsinghua.openring.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 云端同步服务类
 * 按照技术设计文档实现真实的HTTP上传功能
 */
public class CloudSyncService {
    private static final String TAG = "CloudSyncService";

    // 按照实际文件结构定义的常量
    private static final String ONLINE_DIR = "/Sample/RingLog/";  // 在线数据：MainSession_*.txt
    private static final String OFFLINE_DIR = "/Sample/RingLog/BatchDownloads/";  // 离线数据：*.bin
    private static final int HTTP_TIMEOUT = 30000; // 30秒
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY = 5000; // 5秒

    // 上传状态存储
    private static final String UPLOAD_STATUS_PREF = "UploadStatus";
    private static final String STATUS_UPLOADED = "uploaded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_PENDING = "pending";

    private Context context;
    private CloudConfig cloudConfig;
    private OkHttpClient httpClient;
    private SharedPreferences uploadStatusPrefs;

    // 上传进度回调接口
    public interface UploadProgressCallback {
        void onProgress(int current, int total, String fileName);
        void onFileCompleted(String fileName, boolean success, String message);
        void onAllCompleted(int uploaded, int failed);
    }

    // 文件上传信息
    public static class FileUploadInfo {
        public String filePath;
        public String fileName;
        public long fileSize;
        public String status;
        public String errorMessage;
        public long uploadTime;
        public int retryCount;

        public FileUploadInfo(String filePath) {
            this.filePath = filePath;
            this.fileName = new File(filePath).getName();
            this.fileSize = new File(filePath).length();
            this.status = STATUS_PENDING;
            this.retryCount = 0;
        }
    }

    public CloudSyncService(Context context) {
        this.context = context;
        this.cloudConfig = new CloudConfig(context);
        this.uploadStatusPrefs = context.getSharedPreferences(UPLOAD_STATUS_PREF, Context.MODE_PRIVATE);

        // 初始化HTTP客户端
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(HTTP_TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(HTTP_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(HTTP_TIMEOUT, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 扫描本地文件（包含所有类型）
     */
    public List<File> scanLocalFiles() {
        List<File> files = new ArrayList<>();
        files.addAll(scanOfflineFiles());
        files.addAll(scanOnlineFiles());
        Log.d(TAG, "Scanned " + files.size() + " total local files");
        return files;
    }

    /**
     * 扫描离线测量文件 (BatchDownloads目录下的.bin文件)
     */
    public List<File> scanOfflineFiles() {
        List<File> files = new ArrayList<>();
        File offlineDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                + OFFLINE_DIR);
        if (offlineDir.exists()) {
            File[] dataFiles = offlineDir.listFiles((dir, name) ->
                !name.startsWith(".") && name.endsWith(".bin"));
            if (dataFiles != null) {
                for (File file : dataFiles) {
                    files.add(file);
                }
            }
        }
        Log.d(TAG, "Scanned " + files.size() + " offline measurement files (.bin)");
        return files;
    }

    /**
     * 扫描在线测量文件 (RingLog目录下的MainSession_*.txt文件)
     */
    public List<File> scanOnlineFiles() {
        List<File> files = new ArrayList<>();
        File onlineDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                + ONLINE_DIR);
        if (onlineDir.exists()) {
            File[] dataFiles = onlineDir.listFiles((dir, name) ->
                !name.startsWith(".") && name.startsWith("MainSession_") && name.endsWith(".txt"));
            if (dataFiles != null) {
                for (File file : dataFiles) {
                    files.add(file);
                }
            }
        }
        Log.d(TAG, "Scanned " + files.size() + " online measurement files (MainSession_*.txt)");
        return files;
    }

    /**
     * 检查文件是否已上传
     */
    public boolean isFileUploaded(File file) {
        String status = uploadStatusPrefs.getString(file.getAbsolutePath(), STATUS_PENDING);
        return STATUS_UPLOADED.equals(status);
    }

    /**
     * 获取文件上传状态
     */
    public String getFileUploadStatus(File file) {
        return uploadStatusPrefs.getString(file.getAbsolutePath(), STATUS_PENDING);
    }

    /**
     * 设置文件上传状态
     */
    private void setFileUploadStatus(String filePath, String status) {
        uploadStatusPrefs.edit().putString(filePath, status).apply();
    }

    /**
     * 获取文件统计信息
     */
    public FileStatistics getFileStatistics() {
        List<File> allFiles = scanLocalFiles();
        int uploaded = 0;
        int pending = 0;
        int failed = 0;

        for (File file : allFiles) {
            String status = getFileUploadStatus(file);
            switch (status) {
                case STATUS_UPLOADED:
                    uploaded++;
                    break;
                case STATUS_FAILED:
                    failed++;
                    break;
                default:
                    pending++;
                    break;
            }
        }

        return new FileStatistics(allFiles.size(), uploaded, pending, failed);
    }

    /**
     * 文件统计信息类
     */
    public static class FileStatistics {
        public int total;
        public int uploaded;
        public int pending;
        public int failed;

        public FileStatistics(int total, int uploaded, int pending, int failed) {
            this.total = total;
            this.uploaded = uploaded;
            this.pending = pending;
            this.failed = failed;
        }
    }

    /**
     * 上传单个文件
     */
    public void uploadFile(File file, String deviceId, String userName, String userDescription, UploadProgressCallback callback) {
        if (!cloudConfig.isConfigValid()) {
            if (callback != null) {
                callback.onFileCompleted(file.getName(), false, "Invalid cloud configuration");
            }
            return;
        }

        try {
            // 按照技术设计文档构建上传请求
            String apiUrl = cloudConfig.getApiBaseUrl() + "files/upload";

            // 构建multipart请求体
            MultipartBody.Builder builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("deviceId", deviceId)
                    .addFormDataPart("userName", userName != null ? userName : "")
                    .addFormDataPart("userDescription", userDescription != null ? userDescription : "");

            // 添加文件
            RequestBody fileBody = RequestBody.create(MediaType.parse("application/octet-stream"), file);
            builder.addFormDataPart("file", file.getName(), fileBody);

            // 添加元数据
            JSONObject metadata = new JSONObject();
            metadata.put("originalPath", file.getAbsolutePath());
            metadata.put("uploadTime", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date()));
            builder.addFormDataPart("metadata", metadata.toString());

            RequestBody requestBody = builder.build();

            // 构建请求
            Request.Builder requestBuilder = new Request.Builder()
                    .url(apiUrl)
                    .post(requestBody);

            // 添加认证头（优先使用JWT Token，向下兼容API Key）
            addAuthHeaders(requestBuilder);

            Request request = requestBuilder.build();

            Log.d(TAG, "Uploading file: " + file.getName() + " to " + apiUrl);

            // 发送请求
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Upload failed for " + file.getName() + ": " + e.getMessage());
                    setFileUploadStatus(file.getAbsolutePath(), STATUS_FAILED);
                    if (callback != null) {
                        callback.onFileCompleted(file.getName(), false, "网络错误: " + e.getMessage());
                    }
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (response.isSuccessful()) {
                            String responseBody = response.body().string();
                            Log.d(TAG, "Upload successful for " + file.getName() + ": " + responseBody);
                            setFileUploadStatus(file.getAbsolutePath(), STATUS_UPLOADED);
                            if (callback != null) {
                                callback.onFileCompleted(file.getName(), true, "Upload successful");
                            }
                        } else {
                            String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                            Log.e(TAG, "Upload failed for " + file.getName() + " - HTTP " + response.code() + ": " + errorBody);
                            setFileUploadStatus(file.getAbsolutePath(), STATUS_FAILED);
                            if (callback != null) {
                                callback.onFileCompleted(file.getName(), false, "服务器错误: HTTP " + response.code());
                            }
                        }
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (JSONException e) {
            Log.e(TAG, "Failed to create metadata for " + file.getName(), e);
            setFileUploadStatus(file.getAbsolutePath(), STATUS_FAILED);
            if (callback != null) {
                callback.onFileCompleted(file.getName(), false, "Failed to create metadata");
            }
        }
    }

    /**
     * 上传离线测量文件
     */
    public void uploadOfflineFiles(String userName, String userDescription, UploadProgressCallback callback) {
        uploadFilesFromList(scanOfflineFiles(), "offline", userName, userDescription, callback);
    }

    /**
     * 上传在线测量文件
     */
    public void uploadOnlineFiles(String userName, String userDescription, UploadProgressCallback callback) {
        uploadFilesFromList(scanOnlineFiles(), "online", userName, userDescription, callback);
    }

    /**
     * 通用的文件批量上传方法
     */
    private void uploadFilesFromList(List<File> allFiles, String category, String userName, String userDescription, UploadProgressCallback callback) {
        if (!cloudConfig.isCloudSyncEnabled() || !cloudConfig.isConfigValid()) {
            Log.w(TAG, "Cloud sync is disabled or config is invalid");
            if (callback != null) {
                callback.onAllCompleted(0, 0);
            }
            return;
        }

        List<File> filesToUpload = new ArrayList<>();

        // 筛选出待上传的文件
        for (File file : allFiles) {
            if (!isFileUploaded(file)) {
                filesToUpload.add(file);
            }
        }

        if (filesToUpload.isEmpty()) {
            Log.d(TAG, "No " + category + " files to upload");
            if (callback != null) {
                callback.onAllCompleted(0, 0);
            }
            return;
        }

        String deviceId = cloudConfig.getDeviceId();
        Log.d(TAG, "Starting " + category + " upload of " + filesToUpload.size() + " files with deviceId: " + deviceId);

        // 统计变量
        final int[] completed = {0};
        final int[] succeeded = {0};
        final int[] failed = {0};
        final int totalFiles = filesToUpload.size();

        // 逐个上传文件
        for (int i = 0; i < filesToUpload.size(); i++) {
            final File file = filesToUpload.get(i);
            final int currentIndex = i;

            uploadFile(file, deviceId, userName, userDescription, new UploadProgressCallback() {
                @Override
                public void onProgress(int current, int total, String fileName) {
                    if (callback != null) {
                        callback.onProgress(currentIndex + 1, totalFiles, fileName);
                    }
                }

                @Override
                public void onFileCompleted(String fileName, boolean success, String message) {
                    completed[0]++;
                    if (success) {
                        succeeded[0]++;
                    } else {
                        failed[0]++;
                    }

                    Log.d(TAG, String.format("%s file %d/%d completed: %s - %s",
                            category, completed[0], totalFiles, fileName, success ? "SUCCESS" : "FAILED"));

                    if (callback != null) {
                        callback.onFileCompleted(fileName, success, message);
                    }

                    // 检查是否全部完成
                    if (completed[0] >= totalFiles) {
                        Log.d(TAG, String.format("%s upload completed: %d succeeded, %d failed",
                                category, succeeded[0], failed[0]));
                        if (callback != null) {
                            callback.onAllCompleted(succeeded[0], failed[0]);
                        }
                    }
                }

                @Override
                public void onAllCompleted(int uploaded, int failed) {
                    // 这个方法在单个文件上传时不会被调用
                    // 它由上面的检查逻辑处理
                }
            });
        }
    }

    /**
     * 批量上传所有待上传的文件
     */
    public void uploadAllFiles(String userName, String userDescription, UploadProgressCallback callback) {
        uploadFilesFromList(scanLocalFiles(), "all", userName, userDescription, callback);
    }

    /**
     * 获取离线文件统计信息
     */
    public FileStatistics getOfflineFileStatistics() {
        return getFileStatisticsForList(scanOfflineFiles());
    }

    /**
     * 获取在线文件统计信息
     */
    public FileStatistics getOnlineFileStatistics() {
        return getFileStatisticsForList(scanOnlineFiles());
    }

    /**
     * 通用文件统计方法
     */
    private FileStatistics getFileStatisticsForList(List<File> files) {
        int uploaded = 0;
        int pending = 0;
        int failed = 0;

        for (File file : files) {
            String status = getFileUploadStatus(file);
            switch (status) {
                case STATUS_UPLOADED:
                    uploaded++;
                    break;
                case STATUS_FAILED:
                    failed++;
                    break;
                default:
                    pending++;
                    break;
            }
        }

        return new FileStatistics(files.size(), uploaded, pending, failed);
    }

    /**
     * 测试云端连接
     */
    public void testConnection(TestConnectionCallback callback) {
        if (!cloudConfig.isConfigValid()) {
            if (callback != null) {
                callback.onResult(false, "配置无效");
            }
            return;
        }

        // Health endpoint is at server root, not under /api
        String baseServerUrl = cloudConfig.getApiBaseUrl().replace("/api", "");
        String healthUrl = baseServerUrl + "/health";
        Request.Builder requestBuilder = new Request.Builder().url(healthUrl);

        // 添加认证头（优先使用JWT Token，向下兼容API Key）
        addAuthHeaders(requestBuilder);

        Request request = requestBuilder.build();

        Log.d(TAG, "Testing connection to: " + healthUrl);

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Connection test failed: " + e.getMessage());
                if (callback != null) {
                    callback.onResult(false, "Connection failed: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "Connection test successful");
                        if (callback != null) {
                            callback.onResult(true, "Connection successful");
                        }
                    } else {
                        Log.e(TAG, "Connection test failed - HTTP " + response.code());
                        if (callback != null) {
                            callback.onResult(false, "服务器错误: HTTP " + response.code());
                        }
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * 连接测试回调接口
     */
    public interface TestConnectionCallback {
        void onResult(boolean success, String message);
    }

    /**
     * 清除所有上传状态记录
     */
    public void clearAllUploadStatus() {
        uploadStatusPrefs.edit().clear().apply();
        Log.d(TAG, "All upload status cleared");
    }

    /**
     * 添加认证头到HTTP请求（新增，支持JWT和API Key）
     */
    private void addAuthHeaders(Request.Builder requestBuilder) {
        // 优先使用JWT Token认证
        String authToken = cloudConfig.getAuthToken();
        if (!authToken.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + authToken);
            Log.d(TAG, "Added JWT Authorization header");
            return;
        }

        // 向下兼容：如果没有JWT Token，使用API Key
        String apiKey = cloudConfig.getApiKey();
        if (!apiKey.isEmpty()) {
            requestBuilder.addHeader("X-API-Key", apiKey);
            Log.d(TAG, "Added X-API-Key header (fallback)");
        }
        // 如果都没有配置，将以匿名方式访问（如果服务器支持的话）
    }
}
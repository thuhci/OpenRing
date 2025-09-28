package com.tsinghua.openring.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 云端同步配置管理类
 * 按照技术设计文档规范统一管理云服务器配置参数
 */
public class CloudConfig {
    private static final String TAG = "CloudConfig";

    // SharedPreferences配置
    public static final String PREF_NAME = "CloudSettings";

    // 配置键名统一定义
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_ENABLED = "cloud_sync_enabled";
    public static final String KEY_LAST_SYNC = "last_sync_time";
    public static final String KEY_DEVICE_ID = "device_id";

    // 用户认证相关配置（新增）
    public static final String KEY_AUTH_TOKEN = "auth_token";
    public static final String KEY_USER_ID = "user_id";
    public static final String KEY_USER_EMAIL = "user_email";
    public static final String KEY_USER_NAME = "user_name";
    public static final String KEY_USER_ROLE = "user_role";

    // 默认配置
    public static final String DEFAULT_SERVER_URL = "https://your-domain.com/api";
    public static final boolean DEFAULT_ENABLED = false;

    private Context context;
    private SharedPreferences prefs;

    public CloudConfig(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 获取服务器地址
     */
    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    /**
     * 设置服务器地址
     */
    public void setServerUrl(String serverUrl) {
        prefs.edit().putString(KEY_SERVER_URL, serverUrl).apply();
    }

    /**
     * 获取API密钥
     */
    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    /**
     * 设置API密钥
     */
    public void setApiKey(String apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply();
    }

    /**
     * 获取云端同步是否启用
     */
    public boolean isCloudSyncEnabled() {
        return prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    /**
     * 设置云端同步启用状态
     */
    public void setCloudSyncEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * 获取最后同步时间
     */
    public long getLastSyncTime() {
        return prefs.getLong(KEY_LAST_SYNC, 0);
    }

    /**
     * 设置最后同步时间
     */
    public void setLastSyncTime(long timestamp) {
        prefs.edit().putLong(KEY_LAST_SYNC, timestamp).apply();
    }

    /**
     * 获取设备ID（用作上传时的设备标识）
     */
    public String getDeviceId() {
        String deviceId = prefs.getString(KEY_DEVICE_ID, "");
        if (deviceId.isEmpty()) {
            // 如果没有设备ID，尝试从主设置中获取MAC地址
            SharedPreferences mainPrefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE);
            String macAddress = mainPrefs.getString("mac_address", "");
            if (!macAddress.isEmpty()) {
                deviceId = macAddress.replace(":", "").toLowerCase();
                setDeviceId(deviceId);
            }
        }
        return deviceId;
    }

    /**
     * 设置设备ID
     */
    public void setDeviceId(String deviceId) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
    }

    /**
     * 验证配置是否有效
     */
    public boolean isConfigValid() {
        String serverUrl = getServerUrl();
        return serverUrl != null && !serverUrl.trim().isEmpty() &&
               (serverUrl.startsWith("http://") || serverUrl.startsWith("https://"));
    }

    /**
     * 获取完整的API基础URL
     */
    public String getApiBaseUrl() {
        String serverUrl = getServerUrl();
        if (serverUrl.endsWith("/")) {
            return serverUrl;
        }
        return serverUrl + "/";
    }

    /**
     * 清除所有云端配置
     */
    public void clearAllSettings() {
        prefs.edit().clear().apply();
    }

    /**
     * 获取配置摘要信息（用于调试）
     */
    public String getConfigSummary() {
        return String.format("Server: %s, Enabled: %s, HasApiKey: %s, DeviceId: %s, LoggedIn: %s",
                getServerUrl(),
                isCloudSyncEnabled(),
                !getApiKey().isEmpty(),
                getDeviceId(),
                isLoggedIn());
    }

    // ========== 用户认证相关方法（新增，不影响现有功能）==========

    /**
     * 检查用户是否已登录
     */
    public boolean isLoggedIn() {
        return !getAuthToken().isEmpty();
    }

    /**
     * 获取认证Token
     */
    public String getAuthToken() {
        return prefs.getString(KEY_AUTH_TOKEN, "");
    }

    /**
     * 获取用户ID
     */
    public String getUserId() {
        return prefs.getString(KEY_USER_ID, "");
    }

    /**
     * 获取用户邮箱
     */
    public String getUserEmail() {
        return prefs.getString(KEY_USER_EMAIL, "");
    }

    /**
     * 获取用户名
     */
    public String getUserName() {
        return prefs.getString(KEY_USER_NAME, "");
    }

    /**
     * 获取用户角色
     */
    public String getUserRole() {
        return prefs.getString(KEY_USER_ROLE, "user");
    }

    /**
     * 保存登录信息
     */
    public void saveLoginInfo(String token, String userId, String email, String userName, String role) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_AUTH_TOKEN, token);
        editor.putString(KEY_USER_ID, userId);
        editor.putString(KEY_USER_EMAIL, email);
        editor.putString(KEY_USER_NAME, userName);
        editor.putString(KEY_USER_ROLE, role);
        editor.apply();
    }

    /**
     * 清除登录信息
     */
    public void clearLoginInfo() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(KEY_AUTH_TOKEN);
        editor.remove(KEY_USER_ID);
        editor.remove(KEY_USER_EMAIL);
        editor.remove(KEY_USER_NAME);
        editor.remove(KEY_USER_ROLE);
        editor.apply();
    }

    /**
     * 获取用户显示名称
     */
    public String getUserDisplayName() {
        String userName = getUserName();
        if (!userName.isEmpty()) {
            return userName;
        }
        String email = getUserEmail();
        if (!email.isEmpty()) {
            return email.split("@")[0]; // 使用邮箱@前的部分作为显示名
        }
        return "用户";
    }
}
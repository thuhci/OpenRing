package com.tsinghua.openring.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.tsinghua.openring.R;
import com.tsinghua.openring.utils.CloudConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 用户登录/注册界面
 * 安全地添加认证功能，不影响现有的云端同步逻辑
 */
public class LoginActivity extends AppCompatActivity {
    private static final String TAG = "LoginActivity";

    // UI组件
    private EditText etEmail, etPassword, etUsername, etPasswordConfirm;
    private Button btnLogin;
    private TextView btnToggleMode;
    private TextView tvTitle, tvToggleText;
    private ProgressBar progressBar;
    private View usernameLayout, confirmPasswordLayout;

    // 状态管理
    private boolean isRegisterMode = false;
    private CloudConfig cloudConfig;
    private OkHttpClient httpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        cloudConfig = new CloudConfig(this);
        initHttpClient();
        initViews();
        setupEventListeners();

        // 检查是否已经登录
        if (cloudConfig.isLoggedIn()) {
            // 已登录，直接进入主界面
            startMainActivity();
            return;
        }
    }

    private void initHttpClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tv_title);
        tvToggleText = findViewById(R.id.tv_toggle_text);
        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        etUsername = findViewById(R.id.et_username);
        etPasswordConfirm = findViewById(R.id.et_password_confirm);
        btnLogin = findViewById(R.id.btn_login);
        btnToggleMode = findViewById(R.id.btn_toggle_mode);
        progressBar = findViewById(R.id.progress_bar);

        // 获取TextInputLayout父容器
        usernameLayout = findViewById(R.id.layout_username);
        confirmPasswordLayout = findViewById(R.id.layout_password_confirm);

        // 初始状态为登录模式
        updateUI();
    }

    private void setupEventListeners() {
        btnLogin.setOnClickListener(v -> {
            if (isRegisterMode) {
                handleRegister();
            } else {
                handleLogin();
            }
        });

        btnToggleMode.setOnClickListener(v -> toggleMode());
    }

    private void toggleMode() {
        isRegisterMode = !isRegisterMode;
        updateUI();
        clearInputs();
    }

    private void updateUI() {
        if (isRegisterMode) {
            // 注册模式
            tvTitle.setText("注册账户");
            btnLogin.setText("注册");
            if (usernameLayout != null) usernameLayout.setVisibility(View.VISIBLE);
            if (confirmPasswordLayout != null) confirmPasswordLayout.setVisibility(View.VISIBLE);
            tvToggleText.setText("已有账户？");
            btnToggleMode.setText("立即登录");
        } else {
            // 登录模式
            tvTitle.setText("登录账户");
            btnLogin.setText("登录");
            if (usernameLayout != null) usernameLayout.setVisibility(View.GONE);
            if (confirmPasswordLayout != null) confirmPasswordLayout.setVisibility(View.GONE);
            tvToggleText.setText("还没有账户？");
            btnToggleMode.setText("立即注册");
        }
    }

    private void clearInputs() {
        etEmail.setText("");
        etPassword.setText("");
        etUsername.setText("");
        etPasswordConfirm.setText("");
    }

    private void handleLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        // 输入验证
        if (TextUtils.isEmpty(email)) {
            etEmail.setError("请输入邮箱地址");
            etEmail.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(password)) {
            etPassword.setError("请输入密码");
            etPassword.requestFocus();
            return;
        }

        if (!isValidEmail(email)) {
            etEmail.setError("请输入有效的邮箱地址");
            etEmail.requestFocus();
            return;
        }

        performLogin(email, password);
    }

    private void handleRegister() {
        String email = etEmail.getText().toString().trim();
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String passwordConfirm = etPasswordConfirm.getText().toString().trim();

        // 输入验证
        if (TextUtils.isEmpty(email)) {
            etEmail.setError("请输入邮箱地址");
            etEmail.requestFocus();
            return;
        }

        if (!isValidEmail(email)) {
            etEmail.setError("请输入有效的邮箱地址");
            etEmail.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(password)) {
            etPassword.setError("请输入密码");
            etPassword.requestFocus();
            return;
        }

        if (password.length() < 6) {
            etPassword.setError("密码至少需要6位字符");
            etPassword.requestFocus();
            return;
        }

        if (!password.equals(passwordConfirm)) {
            etPasswordConfirm.setError("两次输入的密码不一致");
            etPasswordConfirm.requestFocus();
            return;
        }

        performRegister(email, username, password);
    }

    private void performLogin(String email, String password) {
        setLoading(true);

        try {
            JSONObject json = new JSONObject();
            json.put("email", email);
            json.put("password", password);

            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json"),
                    json.toString()
            );

            String serverUrl = cloudConfig.getServerUrl();
            if (TextUtils.isEmpty(serverUrl)) {
                showError("请先在设置中配置服务器地址");
                setLoading(false);
                return;
            }

            Request request = new Request.Builder()
                    .url(serverUrl + "/auth/login")
                    .post(body)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        setLoading(false);
                        showError("网络错误: " + e.getMessage());
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    runOnUiThread(() -> {
                        setLoading(false);
                        try {
                            String responseBody = response.body().string();
                            Log.e("TAG",responseBody);
                            JSONObject jsonResponse = new JSONObject(responseBody);

                            if (response.isSuccessful()) {
                                // 登录成功，保存用户信息
                                JSONObject data = jsonResponse.getJSONObject("data");
                                JSONObject user = data.getJSONObject("user");
                                String token = data.getString("token");

                                cloudConfig.saveLoginInfo(
                                        token,
                                        user.getString("userId"),
                                        user.getString("email"),
                                        user.optString("username", ""),
                                        user.getString("role")
                                );

                                Toast.makeText(LoginActivity.this, "登录成功", Toast.LENGTH_SHORT).show();
                                startMainActivity();
                            } else {
                                String message = jsonResponse.optString("message", "登录失败");
                                showError(message);
                            }
                        } catch (JSONException e) {
                            showError("响应解析错误"+e.toString());
                        } catch (IOException e) {
                            showError("读取响应失败");
                        }
                    });
                }
            });

        } catch (JSONException e) {
            setLoading(false);
            showError("请求构建失败");
        }
    }

    private void performRegister(String email, String username, String password) {
        setLoading(true);

        try {
            JSONObject json = new JSONObject();
            json.put("email", email);
            json.put("password", password);
            if (!TextUtils.isEmpty(username)) {
                json.put("username", username);
            }

            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json"),
                    json.toString()
            );

            String serverUrl = cloudConfig.getServerUrl();
            if (TextUtils.isEmpty(serverUrl)) {
                showError("请先在设置中配置服务器地址");
                setLoading(false);
                return;
            }

            Request request = new Request.Builder()
                    .url(serverUrl + "/auth/register")
                    .post(body)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        setLoading(false);
                        showError("网络错误: " + e.getMessage());
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    runOnUiThread(() -> {
                        setLoading(false);
                        try {
                            String responseBody = response.body().string();
                            JSONObject jsonResponse = new JSONObject(responseBody);

                            if (response.isSuccessful()) {
                                // 注册成功，保存用户信息
                                JSONObject data = jsonResponse.getJSONObject("data");
                                JSONObject user = data.getJSONObject("user");
                                String token = data.getString("token");

                                cloudConfig.saveLoginInfo(
                                        token,
                                        user.getString("userId"),
                                        user.getString("email"),
                                        user.optString("username", ""),
                                        user.getString("role")
                                );

                                Toast.makeText(LoginActivity.this, "注册成功", Toast.LENGTH_SHORT).show();
                                startMainActivity();
                            } else {
                                String message = jsonResponse.optString("message", "注册失败");
                                showError(message);
                            }
                        } catch (JSONException e) {
                            showError("响应解析错误"+e);
                        } catch (IOException e) {
                            showError("读取响应失败");
                        }
                    });
                }
            });

        } catch (JSONException e) {
            setLoading(false);
            showError("请求构建失败");
        }
    }

    private boolean isValidEmail(String email) {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches();
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
        btnToggleMode.setEnabled(!loading);
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void startMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
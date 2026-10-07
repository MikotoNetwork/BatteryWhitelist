package com.batterywhitelist;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 自动更新检查器 (基于原生 / Support 库，无 AndroidX 依赖)
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private final Context context;
    private final String versionJsonUrl;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public UpdateChecker(Context context, String versionJsonUrl) {
        this.context = context;
        this.versionJsonUrl = versionJsonUrl;
    }

    public void check(UpdateCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(versionJsonUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "BatteryWhitelist-UpdateChecker");

                // ============ 修复点 1：提取 responseCode 到 final 变量 ============
                final int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    int remoteVersionCode = json.optInt("versionCode", 0);
                    String remoteVersionName = json.optString("versionName", "未知版本");
                    String updateLog = json.optString("updateLog", "无更新日志");
                    String downloadUrl = json.optString("downloadUrl", "");
                    boolean forceUpdate = json.optBoolean("forceUpdate", false);

                    PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                    int localVersionCode = pInfo.versionCode;

                    if (remoteVersionCode > localVersionCode) {
                        final VersionInfo info = new VersionInfo(remoteVersionName, updateLog, downloadUrl, forceUpdate);
                        uiHandler.post(() -> {
                            if (callback != null) callback.onUpdateAvailable(info);
                            showUpdateDialog(info);
                        });
                    } else {
                        uiHandler.post(() -> {
                            if (callback != null) callback.onLatest();
                        });
                    }
                } else {
                    uiHandler.post(() -> {
                        if (callback != null) callback.onError("服务器响应错误: " + responseCode);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "检查更新失败", e);
                uiHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }, "UpdateChecker-Thread").start();
    }

    private void showUpdateDialog(VersionInfo info) {
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("发现新版本 " + info.versionName)
                .setMessage(info.updateLog)
                .setPositiveButton("立即更新", (dialog, which) -> {
                    if (info.downloadUrl != null && !info.downloadUrl.isEmpty()) {
                        downloadAndInstall(info.downloadUrl);
                    } else {
                        Toast.makeText(context, "未配置下载链接", Toast.LENGTH_SHORT).show();
                    }
                });

        if (!info.forceUpdate) {
            builder.setNegativeButton("暂不更新", (dialog, which) -> dialog.dismiss());
            builder.setCancelable(true);
        } else {
            builder.setCancelable(false);
        }
        builder.show();
    }

    private void downloadAndInstall(String downloadUrl) {
        Toast.makeText(context, "开始下载更新包...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                File apkFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
                if (apkFile.exists()) {
                    apkFile.delete();
                }

                URL url = new URL(downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();

                // ============ 修复点 2：提取 responseCode 到 final 变量 ============
                final int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (InputStream is = conn.getInputStream();
                         FileOutputStream fos = new FileOutputStream(apkFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }

                    uiHandler.post(() -> {
                        Toast.makeText(context, "下载完成，准备安装...", Toast.LENGTH_SHORT).show();
                        installApk(apkFile);
                    });
                } else {
                    uiHandler.post(() -> Toast.makeText(context, "下载失败: " + responseCode, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "下载APK失败", e);
                uiHandler.post(() -> Toast.makeText(context, "下载异常: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }, "ApkDownloader").start();
    }

    private void installApk(File apkFile) {
        if (!apkFile.exists()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Toast.makeText(context, "请允许本应用安装未知来源应用后，重新点击更新", Toast.LENGTH_LONG).show();
                return;
            }
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri apkUri;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apkFile
            );
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            apkUri = Uri.fromFile(apkFile);
        }

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        context.startActivity(intent);
    }

    public static class VersionInfo {
        public final String versionName;
        public final String updateLog;
        public final String downloadUrl;
        public final boolean forceUpdate;

        public VersionInfo(String versionName, String updateLog, String downloadUrl, boolean forceUpdate) {
            this.versionName = versionName;
            this.updateLog = updateLog;
            this.downloadUrl = downloadUrl;
            this.forceUpdate = forceUpdate;
        }
    }

    public interface UpdateCallback {
        void onUpdateAvailable(VersionInfo info);
        void onLatest();
        void onError(String message);
    }
}
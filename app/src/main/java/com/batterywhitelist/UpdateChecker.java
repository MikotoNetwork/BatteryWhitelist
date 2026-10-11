package com.batterywhitelist;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import android.support.v4.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 自动更新检查器。
 *
 * 职责划分：
 *  - check()：联网获取 version.json 并判断是否需要更新，通过回调通知调用方
 *  - downloadAndInstall()：负责下载 + 校验 + 唤起系统安装器（不弹更新提示，由调用方决定）
 *
 * @author MikotoNetwork
 * @version 1.40
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String UA = "BatteryWhitelist-UpdateChecker";

    private final Context appContext;
    private final String versionJsonUrl;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public UpdateChecker(Context context, String versionJsonUrl) {
        // 用 application context 长期持有，避免泄漏 Activity
        this.appContext = context.getApplicationContext();
        this.versionJsonUrl = versionJsonUrl;
    }

    /** 联网检查更新，结果通过回调在主线程返回。 */
    public void check(UpdateCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(versionJsonUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", UA);

                final int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    final String msg = "服务器响应错误: " + responseCode;
                    uiHandler.post(() -> {
                        if (callback != null) callback.onError(msg);
                    });
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }

                JSONObject json = new JSONObject(sb.toString());
                long remoteVersionCode = json.optLong("versionCode", 0L);
                String remoteVersionName = json.optString("versionName", "未知版本");
                String updateLog = json.optString("updateLog", "无更新日志");
                String downloadUrl = json.optString("downloadUrl", "");
                boolean forceUpdate = json.optBoolean("forceUpdate", false);

                PackageInfo pInfo = appContext.getPackageManager()
                        .getPackageInfo(appContext.getPackageName(), 0);
                long localVersionCode = pInfo.getLongVersionCode();

                if (remoteVersionCode > localVersionCode) {
                    final VersionInfo info = new VersionInfo(
                            remoteVersionName, updateLog, downloadUrl, forceUpdate);
                    uiHandler.post(() -> {
                        if (callback != null) callback.onUpdateAvailable(info);
                    });
                } else {
                    uiHandler.post(() -> {
                        if (callback != null) callback.onLatest();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "检查更新失败", e);
                final String msg = String.valueOf(e.getMessage());
                uiHandler.post(() -> {
                    if (callback != null) callback.onError(msg);
                });
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }, "UpdateChecker-Thread").start();
    }

    /**
     * 下载并安装 APK。会在 UI 上弹进度条，下载完成后自动唤起系统安装器。
     * 由 Activity 在用户确认"立即更新"后调用。
     *
     * @param host        用于弹 Dialog 的 Context，建议传 Activity
     * @param downloadUrl 下载地址
     */
    public static void downloadAndInstall(Context host, String downloadUrl) {
        if (host == null || downloadUrl == null || downloadUrl.isEmpty()) return;

        final Context appContext = host.getApplicationContext();
        final Context dialogContext = host;
        final Handler uiHandler = new Handler(Looper.getMainLooper());

        // 进度 Dialog（仅在 host 是存活的 Activity 时弹）
        final AlertDialog progressDialog;
        if (isActivityAlive(dialogContext)) {
            progressDialog = new AlertDialog.Builder(dialogContext)
                    .setTitle("正在下载更新包")
                    .setMessage("0%")
                    .setCancelable(false)
                    .create();
            progressDialog.show();
        } else {
            progressDialog = null;
        }

        new Thread(() -> {
            HttpURLConnection conn = null;
            File apkFile = null;
            try {
                // 下载目录：必须使用 external-files-path 下已声明的 Download 目录，
                // 否则 FileProvider 会拒绝生成 URI。
                File dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) {
                    final String msg = "外部存储不可用，无法下载更新包";
                    Log.e(TAG, msg);
                    uiHandler.post(() -> {
                        if (progressDialog != null && progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                        if (isActivityAlive(dialogContext)) {
                            Toast.makeText(dialogContext, msg, Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new Exception("无法创建下载目录: " + dir.getAbsolutePath());
                }

                apkFile = new File(dir, "update.apk");
                if (apkFile.exists() && !apkFile.delete()) {
                    Log.w(TAG, "旧 update.apk 删除失败，将尝试覆盖");
                }

                URL url = new URL(downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", UA);
                conn.connect();

                final int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception("下载失败，HTTP " + responseCode);
                }

                final long total = conn.getContentLengthLong();
                long downloaded = 0L;
                int lastPct = -1;

                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(apkFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                        downloaded += len;
                        if (total > 0) {
                            int pct = (int) (downloaded * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final int p = pct;
                                uiHandler.post(() -> {
                                    if (progressDialog != null && progressDialog.isShowing()) {
                                        progressDialog.setMessage(p + "%");
                                    }
                                });
                            }
                        }
                    }
                    fos.flush();
                }

                // —— 校验下载到的 APK ——
                PackageManager pm = appContext.getPackageManager();
                PackageInfo newInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
                if (newInfo == null) {
                    apkFile.delete();
                    throw new Exception("APK 解析失败，文件可能已损坏");
                }
                if (!appContext.getPackageName().equals(newInfo.packageName)) {
                    apkFile.delete();
                    throw new Exception("APK 包名不匹配，可能是恶意文件");
                }

                final File finalApk = apkFile;
                uiHandler.post(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    Toast.makeText(appContext, "下载完成，准备安装...", Toast.LENGTH_SHORT).show();
                    installApk(appContext, finalApk);
                });

            } catch (Exception e) {
                Log.e(TAG, "下载 APK 失败", e);
                final String errMsg = String.valueOf(e.getMessage());
                // 下载了一半的残缺文件清掉，避免下次误安装
                if (apkFile != null && apkFile.exists() && apkFile.length() < 1024) {
                    try { apkFile.delete(); } catch (Throwable ignored) {}
                }
                uiHandler.post(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    if (isActivityAlive(dialogContext)) {
                        Toast.makeText(dialogContext, "下载失败: " + errMsg, Toast.LENGTH_LONG).show();
                    }
                });
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }, "ApkDownloader").start();
    }

    private static void installApk(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists()) return;

        // Android 8.0+ 需要用户允许安装未知来源应用
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
            } catch (Throwable ignored) {}
            Toast.makeText(context,
                    "请允许本应用安装未知来源应用后，重新点击更新",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Uri apkUri;
        try {
            apkUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apkFile
            );
        } catch (Throwable t) {
            Log.e(TAG, "生成 FileProvider URI 失败", t);
            Toast.makeText(context, "安装失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");

        // 显式授予所有候选安装器读取权限，兼容国产 ROM
        List<ResolveInfo> resolvers = context.getPackageManager()
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo r : resolvers) {
            if (r.activityInfo == null) continue;
            try {
                context.grantUriPermission(
                        r.activityInfo.packageName,
                        apkUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Throwable ignored) {}
        }

        try {
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "启动安装器失败", t);
            Toast.makeText(context, "无法启动安装器: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isActivityAlive(Context ctx) {
        if (!(ctx instanceof Activity)) return true;
        Activity a = (Activity) ctx;
        return !a.isFinishing() && !a.isDestroyed();
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
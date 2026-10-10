package com.batterywhitelist;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.io.IOException;

/**
 * 模块的用户界面（UI）与应用选择中心。
 *
 * @author MikotoNetwork
 * @version 1.4.4
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "battery_whitelist_prefs";
    private static final String KEY_LIST = "protected_packages";
    // 记录是否已同意协议
    private static final String KEY_AGREED = "user_agreed_agreement"; 

    private static final String GUARD_SCRIPT_PATH = "/data/adb/service.d/battery_guard.sh";
    private static final String GUARD_SCRIPT_DIR = "/data/adb/service.d";
    private static final String LAUNCHER_ALIAS_NAME = "com.batterywhitelist.LauncherAlias";

    private static final String QQ_GROUP_NUMBER = "274336917";
    private static final String OFFICIAL_WEBSITE = "https://batterywhitelist.fun";
    private static final String SOURCE_CODE_URL = "https://github.com/MikotoNetwork/BatteryWhitelist";
    private static final String VERSION_JSON_URL = "https://batterywhitelist.fun/version.json";

    private static final long SU_TIMEOUT_SEC = 10L;
    private static final long SEARCH_DEBOUNCE_MS = 300L;
    private static final long DEPLOY_DEBOUNCE_MS = 3000L;

    // 配置导入导出请求码
    private static final int REQ_EXPORT_CONFIG = 1001;
    private static final int REQ_IMPORT_CONFIG = 1002;

    private SharedPreferences prefs;
    private ListView listView;
    private EditText searchBar;
    private TextView emptyHint;
    private ImageButton btnMenu;
    private AppListAdapter adapter;

    private final List<AppItem> cachedApps = new ArrayList<>();
    private final List<AppItem> filteredApps = new ArrayList<>();
    private final Set<String> selectedPackages = new HashSet<>();

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private Runnable deployRunnable;

    private boolean savedToastShownOnce = false;
    private boolean isIconHidden = false;

    static class AppItem {
        final String name;
        final String lowerName;
        final String pkg;
        final String lowerPkg;

        AppItem(String name, String pkg) {
            this.name = name;
            this.pkg = pkg;
            this.lowerName = name.toLowerCase();
            this.lowerPkg = pkg.toLowerCase();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 检查用户是否已同意协议
        if (!prefs.getBoolean(KEY_AGREED, false)) {
            showAgreementDialog();
            // 阻止后续初始化，等待用户同意后重新进入
            return;
        }

        initApp();
    }

    /**
     * 初始化应用的主逻辑
     */
    private void initApp() {
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.appList);
        searchBar = findViewById(R.id.searchBar);
        emptyHint = findViewById(R.id.emptyHint);
        btnMenu = findViewById(R.id.btnMenu);

        isIconHidden = isLauncherIconHidden();
        initMenu();
        checkForUpdates();

        Set<String> saved = prefs.getStringSet(KEY_LIST, Collections.emptySet());
        if (saved != null) selectedPackages.addAll(saved);

        adapter = new AppListAdapter(this, filteredApps);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) uiHandler.removeCallbacks(searchRunnable);
                final String q = s.toString();
                searchRunnable = () -> filter(q);
                uiHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filteredApps.size()) return;
            AppItem item = filteredApps.get(position);
            boolean nowSelected = !selectedPackages.contains(item.pkg);

            if (nowSelected) selectedPackages.add(item.pkg);
            else selectedPackages.remove(item.pkg);

            listView.setItemChecked(position, nowSelected);
            savePackages();
            scheduleDeployGuardScript();

            if (!savedToastShownOnce) {
                savedToastShownOnce = true;
                Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
            }
        });

        loadAppsAsync();
    }

   /**
 * 显示首次启动的协议弹窗
 */
private void showAgreementDialog() {
    View dialogView = getLayoutInflater().inflate(R.layout.dialog_agreement, null);
    TextView agreementText = dialogView.findViewById(R.id.agreementText);

    // 从 assets/EULA.txt 读取协议内容
    String agreementContent = loadEulaFromAssets();
    if (agreementContent == null) {
        // 读取失败时兜底，避免弹空白
        agreementContent = "协议文件缺失，请联系开发者。";
    }
    agreementText.setText(agreementContent);

    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("用户许可协议与隐私政策")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("同意并继续", (d, which) -> {
                prefs.edit().putBoolean(KEY_AGREED, true).apply();
                initApp();
            })
            .setNegativeButton("拒绝并退出", (d, which) -> {
                finishAffinity();
            })
            .create();

    dialog.show();
}

/**
 * 从 assets/EULA.txt 读取用户协议文本（UTF-8）。
 *
 * @return 文件内容；读取失败返回 null。
 */
private String loadEulaFromAssets() {
    try (InputStream is = getAssets().open("EULA.txt")) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    } catch (IOException e) {
        Log.e("MainActivity", "读取 EULA.txt 失败", e);
        return null;
    }
}
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (searchRunnable != null) uiHandler.removeCallbacks(searchRunnable);
        if (deployRunnable != null) uiHandler.removeCallbacks(deployRunnable);
    }

    private void initMenu() {
        btnMenu.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(MainActivity.this, v);
            popupMenu.getMenu().add(0, 1, 0, "加入QQ群讨论");
            popupMenu.getMenu().add(0, 2, 1, "去官网");
            popupMenu.getMenu().add(0, 3, 2, "查看源代码");

            String toggleText = isIconHidden ? "恢复桌面图标" : "隐藏桌面图标";
            popupMenu.getMenu().add(0, 4, 3, toggleText);

            // 新增：配置导入导出
            popupMenu.getMenu().add(0, 5, 4, "导出配置");
            popupMenu.getMenu().add(0, 6, 5, "导入配置");

            popupMenu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: joinQQGroup(); return true;
                    case 2: openUrl(OFFICIAL_WEBSITE); return true;
                    case 3: openUrl(SOURCE_CODE_URL); return true;
                    case 4: toggleLauncherIcon(); return true;
                    case 5: exportConfig(); return true;
                    case 6: importConfig(); return true;
                }
                return false;
            });
            popupMenu.show();
        });
    }

    /* ==================== 配置导入导出 ==================== */

    private void exportConfig() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,
                "battery-whitelist-" + System.currentTimeMillis() + ".json");
        try {
            startActivityForResult(intent, REQ_EXPORT_CONFIG);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void importConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/json", "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQ_IMPORT_CONFIG);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == REQ_EXPORT_CONFIG) {
            doExport(uri);
        } else if (requestCode == REQ_IMPORT_CONFIG) {
            doImport(uri);
        }
    }

    private void doExport(Uri uri) {
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", 1);
            root.put("module", getPackageName());
            root.put("timestamp", System.currentTimeMillis());

            JSONArray arr = new JSONArray();
            Set<String> saved = prefs.getStringSet(KEY_LIST, Collections.emptySet());
            if (saved != null) {
                for (String p : saved) arr.put(p);
            }
            root.put("protected_packages", arr);

            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) throw new Exception("无法打开输出流");
                os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            Toast.makeText(this, "导出成功，共 " + arr.length() + " 个应用", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            Log.e("MainActivity", "导出失败", t);
        }
    }

    private void doImport(Uri uri) {
        try {
            String text;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new Exception("无法打开输入流");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                text = bos.toString("UTF-8");
            }

            JSONObject root = new JSONObject(text);

            // 宽松校验 module：为空或匹配本模块才通过
            String module = root.optString("module", "");
            if (!module.isEmpty() && !getPackageName().equals(module)) {
                Toast.makeText(this, "这不是本模块的备份文件", Toast.LENGTH_LONG).show();
                return;
            }

            int schema = root.optInt("schemaVersion", 1);
            if (schema > 1) {
                Toast.makeText(this, "备份版本过高，请升级模块", Toast.LENGTH_LONG).show();
                return;
            }

            JSONArray arr = root.optJSONArray("protected_packages");
            if (arr == null) {
                Toast.makeText(this, "备份文件缺少 protected_packages 字段", Toast.LENGTH_LONG).show();
                return;
            }

            final Set<String> imported = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, null);
                if (p != null && !p.isEmpty()) imported.add(p);
            }

            new AlertDialog.Builder(this)
                    .setTitle("恢复配置")
                    .setMessage("将用备份中的 " + imported.size()
                            + " 个应用覆盖当前白名单（当前 " + selectedPackages.size()
                            + " 个），是否继续？")
                    .setPositiveButton("恢复", (d, w) -> {
                        selectedPackages.clear();
                        selectedPackages.addAll(imported);
                        savePackages();

                        // 刷新列表 UI 的勾选状态
                        filter(searchBar.getText().toString());

                        // 触发守护脚本重新下发
                        scheduleDeployGuardScript();

                        Toast.makeText(MainActivity.this,
                                "已恢复 " + imported.size() + " 个应用，守护脚本同步中…",
                                Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();

        } catch (Throwable t) {
            Toast.makeText(this, "导入失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            Log.e("MainActivity", "导入失败", t);
        }
    }

    /* ==================== 配置导入导出结束 ==================== */

    private boolean isLauncherIconHidden() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName componentName = new ComponentName(this, LAUNCHER_ALIAS_NAME);
            int state = pm.getComponentEnabledSetting(componentName);
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Exception e) {
            return false;
        }
    }

    private void toggleLauncherIcon() {
        if (isIconHidden) {
            setLauncherIconState(true);
            Toast.makeText(this, "桌面图标已恢复", Toast.LENGTH_SHORT).show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("隐藏桌面图标")
                    .setMessage("隐藏后，桌面图标将消失。\n\n" +
                            "由于系统限制，如果您需要再次打开此 App，请使用 Termux 或 ADB 输入以下命令拉起界面：\n\n" +
                            "su -c am start -n com.batterywhitelist/.MainActivity\n\n" +
                            "确定要隐藏吗？")
                    .setPositiveButton("确定隐藏", (dialog, which) -> {
                        setLauncherIconState(false);
                        Toast.makeText(this, "桌面图标已隐藏，请牢记 Termux 拉起命令", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    private void setLauncherIconState(boolean enabled) {
        try {
            PackageManager pm = getPackageManager();
            ComponentName componentName = new ComponentName(this, LAUNCHER_ALIAS_NAME);
            int newState = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

            pm.setComponentEnabledSetting(componentName, newState, PackageManager.DONT_KILL_APP);
            isIconHidden = !enabled;
        } catch (Exception e) {
            Toast.makeText(this, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("MainActivity", "切换图标状态失败", e);
        }
    }

    private void checkForUpdates() {
        new UpdateChecker(this, VERSION_JSON_URL).check(new UpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(UpdateChecker.VersionInfo info) {
                Log.d("MainActivity", "发现新版本: " + info.versionName);
            }

            @Override
            public void onLatest() {
                Log.d("MainActivity", "当前已是最新版本");
            }

            @Override
            public void onError(String message) {
                Log.e("MainActivity", "检查更新失败: " + message);
            }
        });
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "未找到浏览器或链接无效", Toast.LENGTH_SHORT).show();
        }
    }

    private void joinQQGroup() {
        String qqUrl = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin="
                + QQ_GROUP_NUMBER + "&card_type=group&source=qrcode";
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(qqUrl));
            startActivity(intent);
        } catch (Exception e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("QQ群号", QQ_GROUP_NUMBER));
                Toast.makeText(this, "已复制QQ群号：" + QQ_GROUP_NUMBER + "，请打开QQ手动添加", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "请手动添加QQ群：" + QQ_GROUP_NUMBER, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadAppsAsync() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            final List<AppItem> items = new ArrayList<>(packages.size());
            final String selfPkg = getPackageName();
            for (ApplicationInfo info : packages) {
                if (selfPkg.equals(info.packageName)) continue;
                if ("android".equals(info.packageName)) continue;
                String name = info.loadLabel(pm).toString();
                items.add(new AppItem(name, info.packageName));
            }

            uiHandler.post(() -> {
                cachedApps.clear();
                cachedApps.addAll(items);
                filter(searchBar.getText().toString());
                scheduleDeployGuardScript();
            });
        }, "AppListLoader").start();
    }

    private void filter(String query) {
        filteredApps.clear();
        String q = query == null ? "" : query.toLowerCase().trim();

        if (q.isEmpty()) {
            filteredApps.addAll(cachedApps);
        } else {
            for (AppItem item : cachedApps) {
                if (item.lowerName.contains(q) || item.lowerPkg.contains(q)) {
                    filteredApps.add(item);
                }
            }
        }

        adapter.notifyDataSetChanged();

        for (int i = 0; i < filteredApps.size(); i++) {
            listView.setItemChecked(i, selectedPackages.contains(filteredApps.get(i).pkg));
        }

        if (emptyHint != null) {
            emptyHint.setVisibility(filteredApps.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void savePackages() {
        prefs.edit().putStringSet(KEY_LIST, new HashSet<>(selectedPackages)).commit();
    }

    private static class AppListAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private final PackageManager pm;
        private final List<AppItem> data;

        AppListAdapter(Context ctx, List<AppItem> data) {
            this.inflater = LayoutInflater.from(ctx);
            this.pm = ctx.getPackageManager();
            this.data = data;
        }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int i) { return data.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_app, parent, false);
                h = new ViewHolder();
                h.icon = convertView.findViewById(R.id.appIcon);
                h.name = convertView.findViewById(R.id.appName);
                h.pkg = convertView.findViewById(R.id.appPkg);
                h.check = convertView.findViewById(R.id.appCheck);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }

            AppItem item = data.get(position);
            h.name.setText(item.name);
            h.pkg.setText(item.pkg);

            try {
                h.icon.setImageDrawable(pm.getApplicationIcon(item.pkg));
            } catch (Throwable t) {
                h.icon.setImageDrawable(pm.getDefaultActivityIcon());
            }

            h.check.setVisibility(View.VISIBLE);
            return convertView;
        }

        private static class ViewHolder {
            ImageView icon;
            TextView name;
            TextView pkg;
            ImageView check;
        }
    }

    private void scheduleDeployGuardScript() {
        if (deployRunnable != null) uiHandler.removeCallbacks(deployRunnable);
        deployRunnable = this::deployGuardScript;
        uiHandler.postDelayed(deployRunnable, DEPLOY_DEBOUNCE_MS);
    }

    private void deployGuardScript() {
        new Thread(() -> {
            try {
                Set<String> snapshot = new HashSet<>(selectedPackages);

                if (snapshot.isEmpty()) {
                    String existing = runSuCommandForOutput(
                            "test -f " + GUARD_SCRIPT_PATH + " && echo yes || echo no\n", SU_TIMEOUT_SEC);
                    if ("yes".equals(existing)) {
                        runSuCommand("rm -f " + GUARD_SCRIPT_PATH + "\n", SU_TIMEOUT_SEC);
                        toastOnUi("守护脚本已移除（无保护应用）");
                    }
                    return;
                }

                String newScript = buildGuardScript(snapshot);
                String newMd5 = md5Hex(newScript);
                if (newMd5 == null) return;

                String currentMd5 = runSuCommandForOutput(
                        "test -f " + GUARD_SCRIPT_PATH + " && md5sum " + GUARD_SCRIPT_PATH
                                + " | cut -d' ' -f1 || echo none\n", SU_TIMEOUT_SEC);

                if (newMd5.equals(currentMd5)) return;

                String cmd = "mkdir -p " + GUARD_SCRIPT_DIR + "\n"
                        + "cat > " + GUARD_SCRIPT_PATH + " << 'BATTERY_GUARD_EOF'\n"
                        + newScript
                        + "BATTERY_GUARD_EOF\n"
                        + "chmod 755 " + GUARD_SCRIPT_PATH + "\n";

                if (runSuCommand(cmd, SU_TIMEOUT_SEC)) {
                    toastOnUi("守护脚本已更新（" + snapshot.size() + " 个应用）");
                } else {
                    toastOnUi("守护脚本部署失败，请确认已授予 Root");
                }
            } catch (Throwable t) {
                toastOnUi("部署脚本异常: " + t.getMessage());
            }
        }, "GuardScriptDeployer").start();
    }

    private static String buildGuardScript(Set<String> packages) {
        StringBuilder guardCmd = new StringBuilder();
        for (String p : packages) {
            guardCmd.append("  cmd deviceidle whitelist +").append(p).append("\n")
                    .append("  appops set ").append(p).append(" RUN_IN_BACKGROUND allow\n")
                    .append("  appops set ").append(p).append(" RUN_ANY_IN_BACKGROUND allow\n");
        }

        return "#!/system/bin/sh\n"
                + "until [ \"$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 2; done\n"
                + "sleep 15\n"
                + "while true; do\n"
                + guardCmd
                + "  sleep 60\n"
                + "done\n";
    }

    private static boolean runSuCommand(String commands, long timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(true).start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(commands.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            drainStream(p.getInputStream());
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) return false;
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
        }
    }

    private static String runSuCommandForOutput(String commands, long timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(true).start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(commands.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (p.waitFor(timeoutSec, TimeUnit.SECONDS)) return sb.toString().trim();
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
        }
    }

    private static void drainStream(InputStream is) {
        if (is == null) return;
        try (InputStream in = is) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (Throwable ignored) {}
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void toastOnUi(String msg) {
        uiHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }
}
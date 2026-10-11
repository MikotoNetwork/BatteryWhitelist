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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.LruCache;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 模块的用户界面（UI）与应用选择中心。
 *
 * @author MikotoNetwork
 * @version 1.5.0
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "battery_whitelist_prefs";
    private static final String KEY_LIST = "protected_packages";
    private static final String KEY_AGREED = "user_agreed_agreement";
    private static final String KEY_AGREED_HASH = "user_agreed_eula_hash";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check";

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
    // 24h
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; 

    private static final int REQ_EXPORT_CONFIG = 1001;
    private static final int REQ_IMPORT_CONFIG = 1002;

    // 包名合法性校验
    private static final Pattern PKG_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

    // 进程级应用列表缓存（AppItem 不含 Context，不会泄漏）
    private static volatile List<AppItem> sCachedApps = null;

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

    private volatile boolean destroyed = false;

    private boolean savedToastShownOnce = false;
    private boolean isIconHidden = false;

    // 记录上次已部署过的白名单，避免重复 su 部署
    private Set<String> lastDeployed = Collections.emptySet();

    static class AppItem {
        final String name;
        final String lowerName;
        final String pkg;
        final String lowerPkg;

        AppItem(String name, String pkg) {
            this.name = name;
            this.pkg = pkg;
            this.lowerName = name.toLowerCase(Locale.ROOT);
            this.lowerPkg = pkg.toLowerCase(Locale.ROOT);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 读取 EULA 内容并与其 hash 比对，若协议有更新则重新弹窗
        String eula = loadEulaFromAssets();
        if (eula == null) {
            eula = "协议文件缺失，请联系开发者。";
        }
        String eulaHash = sha256Hex(eula);
        String agreedHash = prefs.getString(KEY_AGREED_HASH, "");
        boolean agreed = prefs.getBoolean(KEY_AGREED, false)
                && eulaHash != null
                && eulaHash.equals(agreedHash);

        if (!agreed) {
            showAgreementDialog(eula, eulaHash);
            return;
        }

        initApp();
    }

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
     * 显示协议弹窗。传入协议正文和其 hash，用户同意后写入 prefs。
     */
    private void showAgreementDialog(String content, String contentHash) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_agreement, null);
        TextView agreementText = dialogView.findViewById(R.id.agreementText);
        agreementText.setText(content);

        new AlertDialog.Builder(this)
                .setTitle("用户许可协议与隐私政策")
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton("同意并继续", (d, which) -> {
                    SharedPreferences.Editor e = prefs.edit();
                    e.putBoolean(KEY_AGREED, true);
                    if (contentHash != null) {
                        e.putString(KEY_AGREED_HASH, contentHash);
                    }
                    e.apply();
                    initApp();
                })
                .setNegativeButton("拒绝并退出", (d, which) -> finishAffinity())
                .create()
                .show();
    }

    private String loadEulaFromAssets() {
        try (InputStream is = getAssets().open("EULA.txt")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (IOException e) {
            Log.e("MainActivity", "读取 EULA.txt 失败", e);
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
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
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "battery-whitelist-" + ts + ".json");
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

            // 直接使用内存里的 selectedPackages，避免依赖 prefs 落盘时机
            List<String> sorted = new ArrayList<>(selectedPackages);
            Collections.sort(sorted);

            JSONArray arr = new JSONArray();
            for (String p : sorted) arr.put(p);
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
            int skipped = 0;
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, null);
                if (isValidPkg(p)) imported.add(p);
                else skipped++;
            }

            final int finalSkipped = skipped;
            new AlertDialog.Builder(this)
                    .setTitle("恢复配置")
                    .setMessage("将用备份中的 " + imported.size()
                            + " 个应用覆盖当前白名单（当前 " + selectedPackages.size()
                            + " 个）"
                            + (finalSkipped > 0 ? "\n\n已忽略 " + finalSkipped + " 个非法包名" : "")
                            + "\n\n是否继续？")
                    .setPositiveButton("恢复", (d, w) -> {
                        selectedPackages.clear();
                        selectedPackages.addAll(imported);
                        savePackages();
                        filter(searchBar.getText().toString());
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

    private static boolean isValidPkg(String p) {
        return p != null && p.length() <= 255 && PKG_PATTERN.matcher(p).matches();
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
    // 24 小时限流
    long last = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L);
    if (System.currentTimeMillis() - last < UPDATE_CHECK_INTERVAL_MS) return;
    prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();

    new UpdateChecker(this, VERSION_JSON_URL).check(new UpdateChecker.UpdateCallback() {
        @Override
        public void onUpdateAvailable(UpdateChecker.VersionInfo info) {
            if (destroyed || isFinishing()) return;

            AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("发现新版本 " + info.versionName)
                    .setMessage(info.updateLog)
                    .setPositiveButton("立即更新", (d, w) -> {
                        if (info.downloadUrl != null && !info.downloadUrl.isEmpty()) {
                            // ★ 直接交给 UpdateChecker 下载安装
                            UpdateChecker.downloadAndInstall(MainActivity.this, info.downloadUrl);
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "未配置下载链接", Toast.LENGTH_SHORT).show();
                        }
                    });
            if (!info.forceUpdate) {
                b.setNegativeButton("稍后", null);
                b.setCancelable(true);
            } else {
                b.setCancelable(false);
            }
            b.show();
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
        // 进程级缓存命中，秒开
        List<AppItem> cached = sCachedApps;
        if (cached != null) {
            cachedApps.clear();
            cachedApps.addAll(cached);
            filter(searchBar.getText().toString());
            scheduleDeployGuardScript();
            return;
        }

        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            final List<AppItem> items = new ArrayList<>(packages.size());
            final String selfPkg = getPackageName();
            for (ApplicationInfo info : packages) {
                if (selfPkg.equals(info.packageName)) continue;
                if ("android".equals(info.packageName)) continue;

                String name;
                try {
                    name = info.loadLabel(pm).toString();
                } catch (Throwable t) {
                    name = info.packageName;
                }
                items.add(new AppItem(name, info.packageName));
            }

            // 按名称排序
            try {
                Collections.sort(items, (a, b) -> a.lowerName.compareTo(b.lowerName));
            } catch (Throwable ignored) {}

            sCachedApps = new ArrayList<>(items);

            uiHandler.post(() -> {
                if (destroyed) return;
                cachedApps.clear();
                cachedApps.addAll(items);
                filter(searchBar.getText().toString());
                scheduleDeployGuardScript();
            });
        }, "AppListLoader").start();
    }

    private void filter(String query) {
        filteredApps.clear();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();

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
        // 拷贝一份快照，后台线程异步写盘
        final Set<String> snapshot = new HashSet<>(selectedPackages);
        new Thread(() -> {
            try {
                prefs.edit().putStringSet(KEY_LIST, snapshot).commit();
            } catch (Throwable ignored) {}
        }, "PrefsSaver").start();
    }

    private static class AppListAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private final PackageManager pm;
        private final List<AppItem> data;
        private final LruCache<String, Drawable> iconCache = new LruCache<>(200);

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

            Drawable d = iconCache.get(item.pkg);
            if (d == null) {
                try {
                    d = pm.getApplicationIcon(item.pkg);
                } catch (Throwable t) {
                    d = pm.getDefaultActivityIcon();
                }
                iconCache.put(item.pkg, d);
            }
            h.icon.setImageDrawable(d);

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

                // 与上次部署集合相同，跳过
                if (snapshot.equals(lastDeployed)) return;

                if (snapshot.isEmpty()) {
                    String existing = runSuCommandForOutput(
                            "test -f " + GUARD_SCRIPT_PATH + " && echo yes || echo no\n", SU_TIMEOUT_SEC);
                    if ("yes".equals(existing)) {
                        runSuCommand("rm -f " + GUARD_SCRIPT_PATH + "\n", SU_TIMEOUT_SEC);
                        toastOnUi("守护脚本已移除（无保护应用）");
                    }
                    lastDeployed = Collections.emptySet();
                    return;
                }

                String newScript = buildGuardScript(snapshot);
                String newMd5 = md5Hex(newScript);
                if (newMd5 == null) return;

                String currentMd5 = runSuCommandForOutput(
                        "test -f " + GUARD_SCRIPT_PATH + " && md5sum " + GUARD_SCRIPT_PATH
                                + " | cut -d' ' -f1 || echo none\n", SU_TIMEOUT_SEC);

                if (newMd5.equals(currentMd5)) {
                    lastDeployed = snapshot;
                    return;
                }

                String cmd = "mkdir -p " + GUARD_SCRIPT_DIR + "\n"
                        + "cat > " + GUARD_SCRIPT_PATH + " << 'BATTERY_GUARD_EOF'\n"
                        + newScript
                        + "BATTERY_GUARD_EOF\n"
                        + "chmod 755 " + GUARD_SCRIPT_PATH + "\n";

                if (runSuCommand(cmd, SU_TIMEOUT_SEC)) {
                    toastOnUi("守护脚本已更新（" + snapshot.size() + " 个应用）");
                    lastDeployed = snapshot;
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
            guardCmd.append("  cmd deviceidle whitelist +").append(p).append(" 2>/dev/null\n")
                    .append("  appops set ").append(p).append(" RUN_IN_BACKGROUND allow 2>/dev/null\n")
                    .append("  appops set ").append(p).append(" RUN_ANY_IN_BACKGROUND allow 2>/dev/null\n");
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

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void toastOnUi(String msg) {
        uiHandler.post(() -> {
            if (destroyed || isFinishing()) return;
            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
        });
    }
}
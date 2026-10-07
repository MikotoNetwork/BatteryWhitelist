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

import java.io.BufferedReader;
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

/**
 * 模块的用户界面（UI）与应用选择中心。
 *
 * @author MikotoNetwork
 * @version 1.4.4
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "battery_whitelist_prefs";
    private static final String KEY_LIST = "protected_packages";
    private static final String KEY_AGREED = "user_agreed_agreement"; // 记录是否已同意协议

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
        // 动态加载刚刚创建的滚动布局
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_agreement, null);
        TextView agreementText = dialogView.findViewById(R.id.agreementText);

        String agreementContent = "【用户许可协议】\n" +
                "1. 使用条件：本软件为开源 LSPosed 模块，仅供个人学习、交流及设备优化使用。使用前，您必须确保已了解并接受刷机、Root、LSPosed 所带来的所有风险。\n" +
                "2. 免责声明：本软件通过修改 Android 系统底层来对抗第三方系统的后台冻结机制。由于不同设备厂商的定制系统差异巨大，开发者不对因使用本模块导致的设备变砖、数据丢失、系统无限重启、硬件损坏或保修失效承担任何责任。\n" +
                "3. 开源许可：本项目基于 MIT 协议开源，您可以自由查看、修改源代码，但严禁将本软件用于任何商业倒卖或非法用途。\n\n" +
                "【隐私政策】\n" +
                "我们极其重视您的隐私。本软件属于纯本地运行工具，特此郑重声明：\n" +
                "1. 数据收集：本软件绝对不收集、不上传、不存储您的任何个人隐私数据。\n" +
                "2. 联网权限：本软件仅在启动时请求 batterywhitelist.fun 以获取版本号及安装包，用于检查更新，不包含任何后台静默上报。\n" +
                "3. Root 权限：本软件申请的 Root 权限仅用于读取系统应用列表，并向 /data/adb/service.d/ 写入守护脚本。所有操作均在您的设备本地完成。\n" +
                "4. 本地存储：您所勾选的应用列表仅存储于您设备的本地 SharedPreferences 中。\n\n" +
                "简而言之：您的数据完全属于您自己，我们只是系统底层的搬运工。\n\n" +
                "继续使用即代表您已阅读并完全同意上述条款。";

        agreementText.setText(agreementContent);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("用户许可协议与隐私政策")
                .setView(dialogView)
                // 禁止点击外部或返回键取消
                .setCancelable(false) 
                .setPositiveButton("同意并继续", (d, which) -> {
                    // 记录用户同意状态
                    prefs.edit().putBoolean(KEY_AGREED, true).apply();
                    // 重新初始化界面
                    initApp();
                })
                .setNegativeButton("拒绝并退出", (d, which) -> {
                    finishAffinity(); // 关闭所有 Activity 退出应用
                })
                .create();

        dialog.show();
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

            popupMenu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: joinQQGroup(); return true;
                    case 2: openUrl(OFFICIAL_WEBSITE); return true;
                    case 3: openUrl(SOURCE_CODE_URL); return true;
                    case 4: toggleLauncherIcon(); return true;
                }
                return false;
            });
            popupMenu.show();
        });
    }

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
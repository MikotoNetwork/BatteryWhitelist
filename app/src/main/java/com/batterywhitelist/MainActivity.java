package com.batterywhitelist;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

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
 * <p>主要职责：</p>
 * <ol>
 *   <li>展示所有已安装应用的列表（带搜索和防抖功能）。</li>
 *   <li>允许用户勾选需要保护的应用，并将列表写入 SharedPreferences。</li>
 *   <li>在数据变更后，利用 Root 权限向 {@code /data/adb/service.d/} 部署守护脚本（三位一体防御的第三层）。</li>
 * </ol>
 *
 * @author MikotoNetwork
 * @version 1.20
 */
public class MainActivity extends Activity {

    // ============ 常量 ============
    private static final String PREFS_NAME = "battery_whitelist_prefs";
    private static final String KEY_LIST = "protected_packages";

    private static final String GUARD_SCRIPT_PATH = "/data/adb/service.d/battery_guard.sh";
    private static final String GUARD_SCRIPT_DIR = "/data/adb/service.d";

    /** su 命令执行超时（秒） */
    private static final long SU_TIMEOUT_SEC = 10L;
    /** 搜索防抖延迟 */
    private static final long SEARCH_DEBOUNCE_MS = 300L;
    /** 部署脚本防抖延迟 */
    private static final long DEPLOY_DEBOUNCE_MS = 3000L;

    /**
     * 缓存应用信息的内部类。
     * <p>缓存 lowerName / lowerPkg 用于搜索加速，避免每次 filter 时重复 toLowerCase。</p>
     */
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

    // ============ 视图 ============
    private SharedPreferences prefs;
    private ListView listView;
    private EditText searchBar;
    private TextView emptyHint;
    private AppListAdapter adapter;

    /** 所有应用的缓存（不变） */
    private final List<AppItem> cachedApps = new ArrayList<>();
    /** 当前展示的应用对象（数据源，position 直接对应） */
    private final List<AppItem> filteredApps = new ArrayList<>();

    /**
     * 用户当前已选的应用集合。
     * <p>内存中的唯一真实来源，点击时直接改这个集合，再同步落盘，
     * 避免每次点击都从 SharedPreferences 读一遍。</p>
     */
    private final Set<String> selectedPackages = new HashSet<>();

    // ============ Handler / Runnable ============
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private Runnable deployRunnable;

    /** 是否已经弹过一次"已保存"提示，避免烦人 */
    private boolean savedToastShownOnce = false;

    // ================= 生命周期 =================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        listView = findViewById(R.id.appList);
        searchBar = findViewById(R.id.searchBar);
        emptyHint = findViewById(R.id.emptyHint);

        // 加载已选集合到内存
        Set<String> saved = prefs.getStringSet(KEY_LIST, Collections.emptySet());
        if (saved != null) selectedPackages.addAll(saved);

        adapter = new AppListAdapter(this, filteredApps);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        // 搜索框：300ms 防抖
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

        // 列表点击：切换勾选
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filteredApps.size()) return;
            AppItem item = filteredApps.get(position);
            boolean nowSelected = !selectedPackages.contains(item.pkg);

            if (nowSelected) selectedPackages.add(item.pkg);
            else selectedPackages.remove(item.pkg);

            // 关键：通过 ListView 的 setItemChecked 更新，激活状态会通过 duplicateParentState
            // 自动传递到 item_app.xml 里的 appCheck ImageView
            listView.setItemChecked(position, nowSelected);

            savePackages();
            scheduleDeployGuardScript();

            if (!savedToastShownOnce) {
                savedToastShownOnce = true;
                Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
            }
        });

        // 应用列表放子线程加载（避免 ANR）
        loadAppsAsync();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (searchRunnable != null) uiHandler.removeCallbacks(searchRunnable);
        if (deployRunnable != null) uiHandler.removeCallbacks(deployRunnable);
    }

    // ================= 应用列表加载 =================

    /**
     * 后台线程加载应用列表，完成后回主线程刷新 UI。
     */
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
                // 用当前搜索框内容过滤（处理旋转屏幕等场景）
                filter(searchBar.getText().toString());
                // 首次加载完成后同步脚本
                scheduleDeployGuardScript();
            });
        }, "AppListLoader").start();
    }

    /**
     * 极速搜索过滤，只遍历内存中的缓存。
     *
     * @param query 用户输入的搜索关键词
     */
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

        // 恢复勾选状态（filteredApps 与 position 一一对应）
        for (int i = 0; i < filteredApps.size(); i++) {
            listView.setItemChecked(i, selectedPackages.contains(filteredApps.get(i).pkg));
        }

        // 空状态提示
        if (emptyHint != null) {
            emptyHint.setVisibility(filteredApps.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 把内存中的已选集合写入 SharedPreferences。
     * <p>使用 {@code commit()} 而非 {@code apply()}，保证 system_server 侧的模块能尽快读到
     * （模块通过 createPackageContext 读此 SP，异步落盘会有延迟）。</p>
     */
    private void savePackages() {
        prefs.edit().putStringSet(KEY_LIST, new HashSet<>(selectedPackages)).commit();
    }

    // ================= 自定义 Adapter =================

    /**
     * 应用列表适配器。
     * <p>展示图标、名称、包名，勾选状态由 {@code state_activated} 驱动
     * （子 ImageView 通过 android:duplicateParentState="true" 跟随父状态）。</p>
     */
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

            // 勾选状态由父 view 的 activated 状态驱动（setItemChecked → setActivated）
            // 子 ImageView 设置了 duplicateParentState=true，会自动跟随，
            // 通过 ic_check_selector 的 state_activated 切换图标
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

    // ================= 守护脚本部署 =================

    /**
     * 触发脚本部署（带去抖，避免连续点击时反复写盘）。
     */
    private void scheduleDeployGuardScript() {
        if (deployRunnable != null) uiHandler.removeCallbacks(deployRunnable);
        deployRunnable = this::deployGuardScript;
        uiHandler.postDelayed(deployRunnable, DEPLOY_DEBOUNCE_MS);
    }

    /**
     * 通过 Root 权限把守护脚本部署到 {@code /data/adb/service.d/}。
     *
     * <p>策略：</p>
     * <ul>
     *   <li>已选列表为空 → 删除旧脚本（防止取消勾选后脚本仍保护旧应用）</li>
     *   <li>计算新脚本 md5，与现有脚本对比，一致则跳过（不弹 Toast，不写盘）</li>
     *   <li>所有 su 调用都带超时 + 消费输出流</li>
     * </ul>
     */
    private void deployGuardScript() {
        new Thread(() -> {
            try {
                Set<String> snapshot = new HashSet<>(selectedPackages);

                // 情况一：无保护应用 → 删除旧脚本
                if (snapshot.isEmpty()) {
                    String existing = runSuCommandForOutput(
                            "test -f " + GUARD_SCRIPT_PATH + " && echo yes || echo no\n", SU_TIMEOUT_SEC);
                    if ("yes".equals(existing)) {
                        runSuCommand("rm -f " + GUARD_SCRIPT_PATH + "\n", SU_TIMEOUT_SEC);
                        toastOnUi("守护脚本已移除（无保护应用）");
                    }
                    return;
                }

                // 情况二：有保护应用 → 计算新内容 md5
                String newScript = buildGuardScript(snapshot);
                String newMd5 = md5Hex(newScript);
                if (newMd5 == null) return;

                String currentMd5 = runSuCommandForOutput(
                        "test -f " + GUARD_SCRIPT_PATH + " && md5sum " + GUARD_SCRIPT_PATH
                                + " | cut -d' ' -f1 || echo none\n", SU_TIMEOUT_SEC);

                if (newMd5.equals(currentMd5)) {
                    // 内容一致，静默跳过
                    return;
                }

                // 情况三：内容变化 → 写入新脚本
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

    /**
     * 生成守护脚本内容。
     */
    private static String buildGuardScript(Set<String> packages) {
        StringBuilder guardCmd = new StringBuilder();
        for (String p : packages) {
            // 包名合法性由 Android 保证（[a-zA-Z0-9._]），此处无需转义
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

    // ================= su 工具 =================

    /**
     * 执行 su 命令（不需要输出），返回是否成功。
     */
    private static boolean runSuCommand(String commands, long timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(true).start();

            try (OutputStream os = p.getOutputStream()) {
                os.write(commands.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            // 消费输出，避免 pipe 满阻塞
            drainStream(p.getInputStream());

            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 执行 su 命令并返回 stdout（已 trim）。
     */
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

            if (p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                return sb.toString().trim();
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
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
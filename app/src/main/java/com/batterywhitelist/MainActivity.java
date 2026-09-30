package com.batterywhitelist;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块的用户界面（UI）与应用选择中心。
 *
 * <p>主要职责：</p>
 * <ol>
 *   <li>展示所有已安装应用的列表（带搜索和防抖功能）。</li>
 *   <li>允许用户勾选需要保护的应用，并将列表写入 SharedPreferences。</li>
 *   <li>在用户打开 App 时，利用 Root 权限自动向 {@code /data/adb/service.d/} 部署守护脚本（三位一体防御的第三层）。</li>
 * </ol>
 *
 * @author MikotoNetwork
 * @version 1.18
 */
public class MainActivity extends Activity {

    /**
     * 缓存应用信息的内部类，避免反复调用 loadLabel() 导致搜索卡顿。
     */
    static class AppItem {
        String name;        // 应用显示名称
        String lowerName;   // 小写名称，用于搜索加速
        String pkg;         // 应用包名

        AppItem(String name, String pkg) {
            this.name = name;
            this.pkg = pkg;
            this.lowerName = name.toLowerCase();
        }
    }

    private SharedPreferences prefs;
    private ListView listView;
    private EditText searchBar;
    private ArrayAdapter<String> adapter;
    
    /** 内存缓存的应用列表，避免每次打字都调用系统接口 */
    private final List<AppItem> cachedApps = new ArrayList<>();
    /** 当前列表中展示的内容（包含应用名和包名） */
    private final List<String> displayList = new ArrayList<>();

    /** 搜索防抖 Handler，防止输入时 UI 卡顿 */
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 本地兜底 SharedPreferences
        prefs = getSharedPreferences("battery_whitelist_prefs", MODE_PRIVATE);
        listView = findViewById(R.id.appList);
        searchBar = findViewById(R.id.searchBar);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, displayList);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        // 1. 预缓存所有应用的名字和包名（解决搜索卡顿的核心优化）
        loadAppsToCache();

        // 2. 搜索框加入 300ms 防抖逻辑（用户停止输入后才开始搜索）
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }
                String q = s.toString();
                searchRunnable = () -> filter(q);
                searchHandler.postDelayed(searchRunnable, 300);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // 3. 列表点击监听：勾选/取消应用，并实时保存
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selectedPkg = displayList.get(position).split("\n")[1];
            CheckedTextView textView = (CheckedTextView) view;
            boolean nextState = !textView.isChecked();
            textView.setChecked(nextState);

            Set<String> set = new HashSet<>(prefs.getStringSet("protected_packages", Collections.emptySet()));
            if (nextState) set.add(selectedPkg); else set.remove(selectedPkg);
            prefs.edit().putStringSet("protected_packages", set).apply();
            Toast.makeText(MainActivity.this, "已保存，重启手机后生效", Toast.LENGTH_SHORT).show();
        });

        // 4. 初始化搜索
        filter("");

        // 5. 每次打开 App 时，自动向 root 目录部署守护脚本
        deployGuardScript();
    }

    /**
     * 预加载应用列表到内存，避免搜索时频繁调用系统的 loadLabel() 导致卡顿。
     * <p>这是针对列表搜索优化的关键步骤。</p>
     */
    private void loadAppsToCache() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        cachedApps.clear();
        for (ApplicationInfo info : packages) {
            // 过滤掉自己和系统框架
            if (!info.packageName.equals(getPackageName()) && !info.packageName.equals("android")) {
                String name = info.loadLabel(pm).toString();
                cachedApps.add(new AppItem(name, info.packageName));
            }
        }
    }

    /**
     * 极速搜索过滤，只遍历内存中的缓存。
     *
     * @param query 用户输入的搜索关键词
     */
    private void filter(String query) {
        displayList.clear();
        String q = query.toLowerCase();

        if (q.isEmpty()) {
            for (AppItem item : cachedApps) {
                displayList.add(item.name + "\n" + item.pkg);
            }
        } else {
            for (AppItem item : cachedApps) {
                // 使用缓存的 lowerName 和 pkg 进行搜索，速度极快
                if (item.lowerName.contains(q) || item.pkg.toLowerCase().contains(q)) {
                    displayList.add(item.name + "\n" + item.pkg);
                }
            }
        }

        adapter.notifyDataSetChanged();

        // 恢复勾选状态
        Set<String> saved = prefs.getStringSet("protected_packages", Collections.emptySet());
        for (int i = 0; i < displayList.size(); i++) {
            String pkg = displayList.get(i).split("\n")[1];
            listView.setItemChecked(i, saved.contains(pkg));
        }
    }

    /**
     * 通过 Root 权限把守护脚本部署到 {@code /data/adb/service.d/}。
     *
     * <p>脚本会在每次开机后启动一个无限循环，每 60 秒强制拉一次白名单，
     * 彻底把被系统“杀死”的可能掐断。这是三位一体防御网中的第三层（Root脚本层）。</p>
     */
    private void deployGuardScript() {
        new Thread(() -> {
            try {
                Set<String> saved = prefs.getStringSet("protected_packages", Collections.emptySet());
                if (saved.isEmpty()) return;

                StringBuilder guardCmd = new StringBuilder();
                for (String p : saved) {
                    guardCmd.append("  cmd deviceidle whitelist +").append(p).append("\n")
                            .append("  appops set ").append(p).append(" RUN_IN_BACKGROUND allow\n")
                            .append("  appops set ").append(p).append(" RUN_ANY_IN_BACKGROUND allow\n");
                }

                String script = "#!/system/bin/sh\n" +
                        "until [ \"$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 2; done\n" +
                        "sleep 15\n" +
                        "while true; do\n" +
                        guardCmd +
                        "  sleep 60\n" +
                        "done\n";

                String scriptPath = "/data/adb/service.d/battery_guard.sh";

                // 使用 root 权限写入脚本
                Process process = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(process.getOutputStream());
                os.writeBytes("mkdir -p /data/adb/service.d\n");
                os.writeBytes("cat > " + scriptPath + " << 'EOF'\n" + script + "EOF\n");
                os.writeBytes("chmod 755 " + scriptPath + "\n");
                os.writeBytes("exit\n");
                os.flush();
                process.waitFor();

                runOnUiThread(() -> Toast.makeText(MainActivity.this, "守护脚本已部署", Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "部署脚本失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
package com.batterywhitelist;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private ListView listView;
    private EditText searchBar;
    private ArrayAdapter<String> adapter;
    private final List<ApplicationInfo> allApps = new ArrayList<>();
    private final List<String> displayList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("battery_whitelist_prefs", MODE_PRIVATE);
        listView = findViewById(R.id.appList);
        searchBar = findViewById(R.id.searchBar);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, displayList);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo info : packages) {
            if (!info.packageName.equals(getPackageName()) && !info.packageName.equals("android")) {
                allApps.add(info);
            }
        }

        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

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

        filter("");

        // 🚀 每次打开 App 时，自动向 root 目录部署守护脚本
        deployGuardScript();
    }

    private void filter(String query) {
        displayList.clear();
        String q = query.toLowerCase();
        for (ApplicationInfo info : allApps) {
            String name = info.loadLabel(getPackageManager()).toString();
            if (name.toLowerCase().contains(q) || info.packageName.toLowerCase().contains(q)) {
                displayList.add(name + "\n" + info.packageName);
            }
        }
        adapter.notifyDataSetChanged();

        Set<String> saved = prefs.getStringSet("protected_packages", Collections.emptySet());
        for (int i = 0; i < displayList.size(); i++) {
            String pkg = displayList.get(i).split("\n")[1];
            listView.setItemChecked(i, saved.contains(pkg));
        }
    }

    /**
     * 🚀 通过 root 权限把守护脚本部署到 /data/adb/service.d/
     */
    private void deployGuardScript() {
        new Thread(() -> {
            try {
                // 读取用户勾选的最新保护名单
                Set<String> saved = prefs.getStringSet("protected_packages", Collections.emptySet());
                StringBuilder pkgList = new StringBuilder();
                for (String p : saved) pkgList.append(p).append(" ");
                if (pkgList.length() == 0) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "尚未勾选任何应用，跳过守护脚本部署", Toast.LENGTH_SHORT).show());
                    return;
                }

                String guardCmd = "";
                for (String p : saved) {
                    guardCmd += "  cmd deviceidle whitelist +" + p + "\n" +
                                "  appops set " + p + " RUN_IN_BACKGROUND allow\n" +
                                "  appops set " + p + " RUN_ANY_IN_BACKGROUND allow\n";
                }

                String script = "#!/system/bin/sh\n" +
                        "until [ \"$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 2; done\n" +
                        "sleep 15\n" +
                        "while true; do\n" +
                        guardCmd +
                        "  sleep 60\n" +
                        "done\n";

                String scriptPath = "/data/adb/service.d/battery_guard.sh";

                Process process = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(process.getOutputStream());
                os.writeBytes("mkdir -p /data/adb/service.d\n");
                os.writeBytes("cat > " + scriptPath + " << 'EOF'\n" + script + "EOF\n");
                os.writeBytes("chmod 755 " + scriptPath + "\n");
                os.writeBytes("exit\n");
                os.flush();
                process.waitFor();

                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "守护脚本已部署", Toast.LENGTH_SHORT).show());

            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "部署脚本失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
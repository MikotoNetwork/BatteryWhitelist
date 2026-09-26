package com.batterywhitelist;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BatteryWhitelistModule extends XposedModule {

    private static final String TAG = "BatteryWhitelist";
    private static final String PREFS_NAME = "battery_whitelist_prefs";
    private static final String KEY_LIST = "protected_packages";

    private SharedPreferences prefs;
    private final Set<String> protectedPackages = new HashSet<>();

    private static void writeLog(String msg) {
        Log.e(TAG, msg);
        try {
            FileWriter fw = new FileWriter("/data/system/BatteryWhitelist.log", true);
            fw.write(new Date() + " : " + msg + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    private void initPrefs() {
        if (prefs != null) return;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentAtMethod = atClass.getDeclaredMethod("currentActivityThread");
            currentAtMethod.setAccessible(true);
            Object currentAt = currentAtMethod.invoke(null);

            Method getSystemContextMethod = atClass.getDeclaredMethod("getSystemContext");
            getSystemContextMethod.setAccessible(true);
            Context systemContext = (Context) getSystemContextMethod.invoke(currentAt);
            if (systemContext == null) return;

            Context uiContext = systemContext.createPackageContext(
                    "com.batterywhitelist", Context.CONTEXT_IGNORE_SECURITY);
            prefs = uiContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            writeLog("成功连接 UI 配置！");
        } catch (Throwable ignored) {}
    }

    private void refreshProtectedPackages() {
        initPrefs();
        if (prefs == null) return;
        Set<String> saved = prefs.getStringSet(KEY_LIST, Collections.emptySet());
        protectedPackages.clear();
        protectedPackages.addAll(saved);
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        writeLog("!!! 模块已加载 onModuleLoaded !!!");
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        writeLog("!!! 成功进入系统框架 onSystemServerStarting !!!");
        ClassLoader loader = param.getClassLoader();
        if (loader == null) {
            writeLog("classLoader is null，放弃！");
            return;
        }

        hookDeviceIdleController(loader);
        hookOplusHansManager(loader);
        hookOplusDeviceIdleHelper(loader);
        startGuardianThread(); // 👈 内存兜底线程

        writeLog("防御网布置完毕！");
    }

    private void hookDeviceIdleController(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName("com.android.server.deviceidle.DeviceIdleController", false, loader);
            for (Method m : clazz.getDeclaredMethods()) {
                if ("removePowerSaveWhitelistAppInternal".equals(m.getName())) {
                    hookMethod(m, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            List<Object> args = chain.getArgs();
                            if (!args.isEmpty() && args.get(0) instanceof String) {
                                String pkg = (String) args.get(0);
                                if (protectedPackages.contains(pkg)) {
                                    writeLog("拦截移除 Doze 白名单: " + pkg);
                                    Class<?> rt = m.getReturnType();
                                    if (rt == boolean.class) return Boolean.TRUE;
                                    if (rt == int.class) return 1;
                                    return null;
                                }
                            }
                            return chain.proceed();
                        }
                    });
                }
            }
        } catch (Throwable t) {
            writeLog("hookDeviceIdleController 失败: " + t.getMessage());
        }
    }

    private void hookOplusHansManager(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName("com.android.server.am.OplusHansManager", false, loader);
            for (Method m : clazz.getDeclaredMethods()) {
                String name = m.getName();

                if ("inCachedFreezeKillWhiteList".equals(name)
                        || "inAndroidFreezeKillWhiteList".equals(name)) {
                    hookMethod(m, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            for (Object arg : chain.getArgs()) {
                                if (arg instanceof String && protectedPackages.contains(arg)) {
                                    writeLog("伪造 HansManager 白名单豁免: " + arg);
                                    return Boolean.TRUE;
                                }
                            }
                            return chain.proceed();
                        }
                    });
                }

                if ("handleRemoveTask".equals(name)) {
                    hookMethod(m, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            List<Object> args = chain.getArgs();
                            if (args.size() >= 3 && args.get(2) instanceof String) {
                                String pkg = (String) args.get(2);
                                if (protectedPackages.contains(pkg)) {
                                    writeLog("拦截 HansManager 清理任务: " + pkg);
                                    return null;
                                }
                            }
                            return chain.proceed();
                        }
                    });
                }
            }
        } catch (Throwable t) {
            writeLog("Hook OplusHansManager 失败: " + t.getMessage());
        }
    }

    private void hookOplusDeviceIdleHelper(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName("com.android.server.OplusDeviceIdleHelper", false, loader);
            for (Method m : clazz.getDeclaredMethods()) {
                String name = m.getName();

                if ("getNewWhiteList".equals(name)) {
                    hookMethod(m, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            List<Object> args = chain.getArgs();
                            if (!args.isEmpty() && args.get(0) instanceof ArrayList) {
                                ArrayList<String> list = (ArrayList<String>) args.get(0);
                                refreshProtectedPackages();
                                for (String pkg : protectedPackages) {
                                    if (!list.contains(pkg)) {
                                        list.add(pkg);
                                        writeLog("篡改生死簿: 强制加入 getNewWhiteList -> " + pkg);
                                    }
                                }
                            }
                            return chain.proceed();
                        }
                    });
                }

                if ("whiteListChangedHandle".equals(name)) {
                    hookMethod(m, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            List<Object> args = chain.getArgs();
                            if (!args.isEmpty() && args.get(0) instanceof ArrayList) {
                                ArrayList<String> list = (ArrayList<String>) args.get(0);
                                refreshProtectedPackages();
                                for (String pkg : protectedPackages) {
                                    if (!list.contains(pkg)) {
                                        list.add(pkg);
                                        writeLog("最终防线: 强制注入 whiteListChangedHandle -> " + pkg);
                                    }
                                }
                            }
                            return chain.proceed();
                        }
                    });
                }
            }
        } catch (Throwable t) {
            writeLog("Hook OplusDeviceIdleHelper 失败: " + t.getMessage());
        }
    }

    /**
     * 🔁 内存兜底：每 60 秒主动执行一次 cmd 命令纠正底层状态
     * （system_server 天然有权限，无需 su）
     */
    private void startGuardianThread() {
        new Thread(() -> {
            while (true) {
                try {
                    refreshProtectedPackages();
                    for (String pkg : protectedPackages) {
                        try {
                            Runtime.getRuntime().exec(new String[]{"/system/bin/cmd", "deviceidle", "whitelist", "+" + pkg});
                            Runtime.getRuntime().exec(new String[]{"/system/bin/cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow"});
                            Runtime.getRuntime().exec(new String[]{"/system/bin/cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow"});
                        } catch (Throwable ignored) {}
                    }
                    Thread.sleep(60000);
                } catch (Throwable t) {
                    writeLog("守护线程异常: " + t.getMessage());
                }
            }
        }).start();
        writeLog("内存守护线程已启动");
    }

    /**
     * 反射 Hook 辅助方法（绕过 AAR 编译缺陷）
     */
    private void hookMethod(Method method, XposedInterface.Hooker hooker) {
        try {
            Method hookMethod = XposedInterface.class.getDeclaredMethod("hook", java.lang.reflect.Executable.class);
            hookMethod.setAccessible(true);
            Object hookBuilder = hookMethod.invoke(this, method);
            Method interceptMethod = hookBuilder.getClass().getMethod("intercept", XposedInterface.Hooker.class);
            interceptMethod.setAccessible(true);
            interceptMethod.invoke(hookBuilder, hooker);
        } catch (Throwable t) {
            writeLog("反射 Hook 失败: " + t.getMessage());
        }
    }
}
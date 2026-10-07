package com.batterywhitelist;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LSPosed 模块的核心入口类。
 *
 * @author MikotoNetwork
 * @version 1.4.0
 */
public class BatteryWhitelistModule extends XposedModule {

    private static final String TAG = "BatteryWhitelist";
    private static final String PREFS_NAME = "battery_whitelist_prefs";
    private static final String KEY_LIST = "protected_packages";

    private static final String LOG_PATH = "/data/system/BatteryWhitelist.log";
    private static final long LOG_MAX_BYTES = 1024L * 1024L;

    private static final long GUARDIAN_INTERVAL_MS = 60_000L;
    private static final long GUARDIAN_INITIAL_DELAY_MS = 15_000L;
    private static final long EXEC_TIMEOUT_SEC = 3L;

    private static final long REFRESH_MIN_INTERVAL_MS = 5_000L;
    private static final long INIT_RETRY_BACKOFF_MS = 30_000L;

    private static final String[] CLS_DEVICE_IDLE_CANDIDATES = new String[] {
            "com.android.server.deviceidle.DeviceIdleController",
            "com.android.server.DeviceIdleController",
            "com.android.server.power.DeviceIdleController"
    };
    private static final String M_REMOVE_PS_WL = "removePowerSaveWhitelistAppInternal";

    private static final String CLS_HANS = "com.android.server.am.OplusHansManager";
    private static final String M_IN_CACHED_FK_WL = "inCachedFreezeKillWhiteList";
    private static final String M_IN_ANDROID_FK_WL = "inAndroidFreezeKillWhiteList";
    private static final String M_HANDLE_REMOVE_TASK = "handleRemoveTask";

    private static final Object LOG_LOCK = new Object();
    private static BufferedWriter logWriter;
    private static boolean logOpenFailedOnce = false;

    private static void writeLog(String msg) {
        Log.e(TAG, msg);
        synchronized (LOG_LOCK) {
            try {
                if (logWriter == null) {
                    File f = new File(LOG_PATH);
                    if (f.exists() && f.length() > LOG_MAX_BYTES) {
                        File old = new File(LOG_PATH + ".old");
                        if (old.exists()) old.delete();
                        f.renameTo(old);
                    }
                    logWriter = new BufferedWriter(new FileWriter(LOG_PATH, true));
                }
                logWriter.write(String.valueOf(System.currentTimeMillis()));
                logWriter.write(" : ");
                logWriter.write(msg);
                logWriter.write('\n');
                logWriter.flush();
            } catch (Throwable t) {
                if (!logOpenFailedOnce) {
                    logOpenFailedOnce = true;
                    Log.e(TAG, "物理日志写入失败: " + Log.getStackTraceString(t));
                }
                try { logWriter = null; } catch (Throwable ignored) {}
            }
        }
    }

    private static void writeLog(String msg, Throwable t) {
        writeLog(msg + " | " + Log.getStackTraceString(t));
    }

    private SharedPreferences prefs;
    private long lastInitAttemptElapsed;
    private long lastRefreshElapsed;
    private volatile Set<String> protectedPackages = Collections.emptySet();
    private final AtomicBoolean guardianStarted = new AtomicBoolean(false);
    private final Set<Method> hookedMethods = Collections.synchronizedSet(new HashSet<Method>());

    private static Method sHookMethod;
    private static volatile Method sInterceptMethod;

    private void initPrefs() {
        if (prefs != null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastInitAttemptElapsed < INIT_RETRY_BACKOFF_MS) return;
        lastInitAttemptElapsed = now;

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

        long now = SystemClock.elapsedRealtime();
        if (now - lastRefreshElapsed < REFRESH_MIN_INTERVAL_MS) return;
        lastRefreshElapsed = now;

        try {
            Set<String> saved = prefs.getStringSet(KEY_LIST, Collections.emptySet());
            protectedPackages = Collections.unmodifiableSet(new HashSet<>(saved));
        } catch (Throwable t) {
            writeLog("读取白名单失败", t);
        }
    }

    private void forceRefreshProtectedPackages() {
        lastRefreshElapsed = 0L;
        refreshProtectedPackages();
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
        hookOplusDeviceIdleHelperDefault(loader);
        startGuardianThread();

        writeLog("防御网布置完毕！");
    }

    private void hookDeviceIdleController(ClassLoader loader) {
        Class<?> clazz = null;
        String foundClassName = null;

        for (String className : CLS_DEVICE_IDLE_CANDIDATES) {
            try {
                clazz = Class.forName(className, false, loader);
                if (clazz != null) {
                    foundClassName = className;
                    break;
                }
            } catch (Throwable ignored) {}
        }

        if (clazz == null) {
            writeLog("hookDeviceIdleController 失败: 未找到任何候选类，跳过此 Hook。");
            return;
        }

        writeLog("成功找到 DeviceIdleController 类: " + foundClassName);

        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (!M_REMOVE_PS_WL.equals(m.getName())) continue;

                final Method target = m;
                hookMethod(target, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        refreshProtectedPackages();
                        String pkg = findProtectedPkg(chain.getArgs(), protectedPackages);
                        if (pkg != null) {
                            writeLog("拦截移除 Doze 白名单: " + pkg);
                            return defaultReturnFor(target.getReturnType());
                        }
                        return chain.proceed();
                    }
                });
            }
        } catch (Throwable t) {
            writeLog("hookDeviceIdleController 执行失败", t);
        }
    }

    private void hookOplusHansManager(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName(CLS_HANS, false, loader);
            for (Method m : clazz.getDeclaredMethods()) {
                final String name = m.getName();
                final Method target = m;

                if (M_IN_CACHED_FK_WL.equals(name) || M_IN_ANDROID_FK_WL.equals(name)) {
                    hookMethod(target, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            String pkg = findProtectedPkg(chain.getArgs(), protectedPackages);
                            if (pkg != null) {
                                writeLog("伪造 HansManager 白名单豁免: " + pkg);
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        }
                    });
                }

                if (M_HANDLE_REMOVE_TASK.equals(name)) {
                    hookMethod(target, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            List<Object> args = chain.getArgs();
                            if (args.size() >= 3 && args.get(2) instanceof String) {
                                String pkg = (String) args.get(2);
                                if (protectedPackages.contains(pkg)) {
                                    writeLog("拦截 HansManager 清理任务: " + pkg);
                                    return defaultReturnFor(target.getReturnType());
                                }
                            }
                            return chain.proceed();
                        }
                    });
                }

                if ("hasImpCaseOrKeepAlive".equals(name)) {
                    hookMethod(target, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            String pkg = findProtectedPkg(chain.getArgs(), protectedPackages);
                            if (pkg != null) {
                                writeLog("伪造 HansManager 保活判定: " + pkg + " -> true");
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        }
                    });
                }

                if ("isVisibleApp".equals(name)) {
                    hookMethod(target, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            String pkg = findProtectedPkg(chain.getArgs(), protectedPackages);
                            if (pkg != null) {
                                writeLog("伪造 HansManager 可见性判定: " + pkg + " -> true");
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        }
                    });
                }
            }
        } catch (Throwable t) {
            writeLog("Hook OplusHansManager 失败 (可能系统非 ColorOS 或类名已变更)", t);
        }
    }

    private void hookOplusDeviceIdleHelperDefault(ClassLoader loader) {
        String[] CLS_OPLUS_HELPER_CANDIDATES = new String[]{
                "com.android.server.IOplusDeviceIdleHelper$Default",
                "com.android.server.IOplusDeviceIdleHelper$I",
                "com.android.server.IOplusDeviceIdleHelperImpl",
                "com.android.server.IOplusDeviceIdleHelper"
        };

        Class<?> clazz = null;
        String foundClassName = null;

        for (String className : CLS_OPLUS_HELPER_CANDIDATES) {
            try {
                clazz = Class.forName(className, false, loader);
                if (clazz != null) {
                    foundClassName = className;
                    break;
                }
            } catch (Throwable ignored) {}
        }

        if (clazz == null) {
            writeLog("Hook IOplusDeviceIdleHelper 失败: 未找到任何候选类，跳过此 Hook。");
            return;
        }

        writeLog("成功找到 IOplusDeviceIdleHelper 类: " + foundClassName);
        final String finalFoundClassName = foundClassName;

        try {
            for (Method m : clazz.getDeclaredMethods()) {
                final String methodName = m.getName();
                final Method target = m;

                if ("shouldIgnoreTempWhitelist".equals(methodName)) {
                    hookMethod(target, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            String pkg = findProtectedPkg(chain.getArgs(), protectedPackages);
                            if (pkg != null) {
                                writeLog("终极拦截：强制反忽略临时白名单 -> " + pkg);
                                return Boolean.FALSE;
                            }
                            return chain.proceed();
                        }
                    });
                }

                if ("getInvalidDozeWhitelist".equals(methodName)) {
                    hookMethod(target, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            if (result instanceof List) {
                                injectIntoList((List<?>) result, finalFoundClassName);
                            }
                            return result;
                        }
                    });
                }

                if ("addPowerSaveWhitelist".equals(methodName) || "addPowerSaveWhitelistAllFrom".equals(methodName)) {
                    hookMethod(target, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            if (result instanceof List) {
                                injectIntoList((List<?>) result, finalFoundClassName + "-Add");
                            }
                            if (!chain.getArgs().isEmpty() && chain.getArgs().get(0) instanceof String) {
                                writeLog("系统主动添加 Doze 白名单: " + chain.getArgs().get(0));
                            }
                            return result;
                        }
                    });
                }

                if ("isInited".equals(methodName)) {
                    hookMethod(target, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return Boolean.TRUE;
                        }
                    });
                }
            }
            writeLog("成功 Hook IOplusDeviceIdleHelper 类！");
        } catch (Throwable t) {
            writeLog("Hook IOplusDeviceIdleHelper 类执行失败", t);
        }
    }

    private void startGuardianThread() {
        if (!guardianStarted.compareAndSet(false, true)) return;
        Thread t = new Thread(this::guardianLoop, "BatteryWhitelist-Guardian");
        t.setDaemon(true);
        t.start();
        writeLog("内存守护线程已启动");
    }

    private void guardianLoop() {
        try {
            Thread.sleep(GUARDIAN_INITIAL_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        while (!Thread.currentThread().isInterrupted()) {
            try {
                forceRefreshProtectedPackages();
                Set<String> snapshot = protectedPackages;
                for (String pkg : snapshot) {
                    safeExec("/system/bin/cmd", "deviceidle", "whitelist", "+" + pkg);
                    safeExec("/system/bin/cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow");
                    safeExec("/system/bin/cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow");
                }
                Thread.sleep(GUARDIAN_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                writeLog("守护线程异常", t);
                try { Thread.sleep(GUARDIAN_INTERVAL_MS); } catch (InterruptedException ie) { break; }
            }
        }
        writeLog("内存守护线程退出");
    }

    private static void safeExec(String... cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            drainStream(p.getInputStream());
            drainStream(p.getErrorStream());
            if (!p.waitFor(EXEC_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroy();
            }
        } catch (Throwable ignored) {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored2) {} }
        }
    }

    private static void drainStream(InputStream is) {
        if (is == null) return;
        try (InputStream in = is) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (Throwable ignored) {}
    }

    private static String findProtectedPkg(List<Object> args, Set<String> set) {
        if (set.isEmpty()) return null;
        for (Object arg : args) {
            if (arg instanceof String && set.contains(arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    private static Object defaultReturnFor(Class<?> rt) {
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == short.class) return (short) 0;
        if (rt == byte.class) return (byte) 0;
        if (rt == char.class) return (char) 0;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        return null;
    }

    @SuppressWarnings("unchecked")
    private void injectIntoList(List<?> rawList, String from) {
        if (!(rawList instanceof List)) return;
        try {
            List<Object> list = (List<Object>) rawList;
            refreshProtectedPackages();
            for (String pkg : protectedPackages) {
                if (!list.contains(pkg)) {
                    list.add(pkg);
                    writeLog("篡改生死簿[" + from + "]: 强制注入 -> " + pkg);
                }
            }
        } catch (UnsupportedOperationException ignored) {
        } catch (Throwable t) {
            writeLog("injectIntoList 失败", t);
        }
    }

    private void hookMethod(Method method, XposedInterface.Hooker hooker) {
        if (method == null) return;
        if (!hookedMethods.add(method)) return;
        try {
            Method hookM = sHookMethod;
            if (hookM == null) {
                hookM = XposedInterface.class.getDeclaredMethod("hook", Executable.class);
                hookM.setAccessible(true);
                sHookMethod = hookM;
            }
            Object hookBuilder = hookM.invoke(this, method);

            Method interceptM = sInterceptMethod;
            if (interceptM == null || !interceptM.getDeclaringClass().isInstance(hookBuilder)) {
                interceptM = hookBuilder.getClass().getMethod("intercept", XposedInterface.Hooker.class);
                interceptM.setAccessible(true);
                sInterceptMethod = interceptM;
            }
            interceptM.invoke(hookBuilder, hooker);
        } catch (Throwable t) {
            hookedMethods.remove(method);
            writeLog("反射 Hook 失败: " + method, t);
        }
    }
}
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LSPosed 模块的核心入口类。
 *
 * <p>此类运行在 Android 系统的 {@code system_server} 进程中（即拥有极高层级权限的系统服务），
 * 负责注入并修改 ColorOS (Android 16) 的底层电池优化与后台冻结逻辑。</p>
 *
 * <p>核心防御机制（“三位一体”防御网）：</p>
 * <ol>
 *   <li><b>内存层拦截</b>：Hook 原生 Doze 控制器、ColorOS 的 {@code OplusHansManager} 和 {@code OplusDeviceIdleHelper}，阻止系统把应用移出白名单或冻结后台。</li>
 *   <li><b>内存守护线程</b>：定期（每 60 秒）主动发出 shell 命令，强制把目标应用写入 Doze 白名单。</li>
 *   <li><b>底层 Root 脚本</b>：通过 UI 端部署的 service.d 脚本，在系统启动后以 root 权限无限循环执行强制纠正。</li>
 * </ol>
 *
 * @author MikotoNetwork
 * @version 1.20
 */
public class BatteryWhitelistModule extends XposedModule {

    private static final String TAG = "BatteryWhitelist";
    /** 跨进程通信名称，必须与 MainActivity 中的 getSharedPreferences 保持一致 */
    private static final String PREFS_NAME = "battery_whitelist_prefs";
    /** 存储用户勾选保护应用包名的键值 */
    private static final String KEY_LIST = "protected_packages";

    /** 物理日志文件与轮转阈值 */
    private static final String LOG_PATH = "/data/system/BatteryWhitelist.log";
    private static final long LOG_MAX_BYTES = 1024L * 1024L; // 1 MB

    /** 守护线程循环间隔（毫秒） */
    private static final long GUARDIAN_INTERVAL_MS = 60_000L;
    /** 守护线程首次启动延迟（毫秒），等待系统稳定 */
    private static final long GUARDIAN_INITIAL_DELAY_MS = 15_000L;
    /** exec 子进程等待上限（秒） */
    private static final long EXEC_TIMEOUT_SEC = 3L;

    /** 从 SP 重新拉取白名单的最小间隔（毫秒），避免 Hook 高频刷新 */
    private static final long REFRESH_MIN_INTERVAL_MS = 5_000L;
    /** initPrefs 失败后的重试退避（毫秒） */
    private static final long INIT_RETRY_BACKOFF_MS = 30_000L;

    // ============ Hook 目标类名 / 方法名常量（跨版本升级时集中修改） ============
    private static final String CLS_DEVICE_IDLE = "com.android.server.deviceidle.DeviceIdleController";
    private static final String M_REMOVE_PS_WL = "removePowerSaveWhitelistAppInternal";

    private static final String CLS_HANS = "com.android.server.am.OplusHansManager";
    private static final String M_IN_CACHED_FK_WL = "inCachedFreezeKillWhiteList";
    private static final String M_IN_ANDROID_FK_WL = "inAndroidFreezeKillWhiteList";
    private static final String M_HANDLE_REMOVE_TASK = "handleRemoveTask";

    private static final String CLS_DEVICE_IDLE_HELPER = "com.android.server.OplusDeviceIdleHelper";
    private static final String M_GET_NEW_WHITELIST = "getNewWhiteList";
    private static final String M_WHITELIST_CHANGED_HANDLE = "whiteListChangedHandle";

    // ============ 物理探针日志 ============
    private static final Object LOG_LOCK = new Object();
    private static BufferedWriter logWriter;
    private static boolean logOpenFailedOnce = false;

    /**
     * 物理探针日志。
     *
     * <p>由于 Android 16 的 ColorOS 对 system_server 的 logcat 输出进行了极其严格的拦截，
     * 普通的 Log 无法在 LSPosed 日志中看到，因此采用写入物理文件的方式绕过拦截。</p>
     *
     * <p>使用常驻 BufferedWriter 减少 open/close 开销；文件超过阈值时自动轮转。</p>
     *
     * @param msg 日志内容
     */
    private static void writeLog(String msg) {
        Log.e(TAG, msg);
        synchronized (LOG_LOCK) {
            try {
                if (logWriter == null) {
                    File f = new File(LOG_PATH);
                    if (f.exists() && f.length() > LOG_MAX_BYTES) {
                        File old = new File(LOG_PATH + ".old");
                        if (old.exists()) old.delete();
                        //noinspection ResultOfMethodCallIgnored
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
                // 首次失败记录一次，避免无限刷屏
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

    // ============ 状态字段 ============
    /** 远程 SharedPreferences，用于实时读取 UI 端用户勾选的应用 */
    private SharedPreferences prefs;
    /** 上次 initPrefs 尝试时间（用于退避重试） */
    private long lastInitAttemptElapsed;
    /** 上次刷新白名单时间 */
    private long lastRefreshElapsed;

    /**
     * 动态保护列表，每次触发拦截前都会刷新。
     * <p>使用 volatile + 整体替换（不可变快照），保证 Hook 线程与守护线程读取时的一致性。</p>
     */
    private volatile Set<String> protectedPackages = Collections.emptySet();

    /** 守护线程是否已启动（防止重复启动） */
    private final AtomicBoolean guardianStarted = new AtomicBoolean(false);

    /** 已 Hook 的方法集合，避免重复挂载 */
    private final Set<Method> hookedMethods = Collections.synchronizedSet(new HashSet<>());

    /** 反射缓存：XposedInterface.hook(Executable) */
    private static Method sHookMethod;
    /** 反射缓存：HookBuilder.intercept(Hooker) —— 泛型不同实现可能不同类，用 Method 缓存首次命中的 */
    private static volatile Method sInterceptMethod;

    /**
     * 反射初始化跨进程的 SharedPreferences。
     *
     * <p>由于 system_server 无法直接通过 Context 获取 UI 应用的 SharedPreferences，
     * 这里利用了 ActivityThread 的反射机制，获取系统级别的 Context，再通过
     * createPackageContext 强行读取 UI 应用的数据（绕过 SELinux 隔离）。</p>
     */
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

            // 获取 UI 应用的 Context，忽略安全检查 (CONTEXT_IGNORE_SECURITY)
            Context uiContext = systemContext.createPackageContext(
                    "com.batterywhitelist", Context.CONTEXT_IGNORE_SECURITY);
            prefs = uiContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            writeLog("成功连接 UI 配置！");
        } catch (Throwable t) {
            // 系统启动早期可能无法获取包管理器，静默失败等待下次重试
        }
    }

    /**
     * 刷新受保护的应用列表，从 UI 端的 SharedPreferences 中读取最新配置。
     *
     * <p>通过时间节流避免 Hook 高频调用时反复读取 SP（Hook 方法可能被调用数十次/秒）。</p>
     */
    private void refreshProtectedPackages() {
        initPrefs();
        if (prefs == null) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastRefreshElapsed < REFRESH_MIN_INTERVAL_MS) return;
        lastRefreshElapsed = now;

        try {
            Set<String> saved = prefs.getStringSet(KEY_LIST, Collections.emptySet());
            // 整体替换为不可变快照（线程安全）
            protectedPackages = Collections.unmodifiableSet(new HashSet<>(saved));
        } catch (Throwable t) {
            writeLog("读取白名单失败", t);
        }
    }

    /**
     * 在 Hook 回调中强制刷新一次（比如 UI 端刚刚修改过需要立即生效）。
     */
    private void forceRefreshProtectedPackages() {
        lastRefreshElapsed = 0L;
        refreshProtectedPackages();
    }

    /**
     * 模块加载完成时的回调。运行在 {@code system_server} 的进程中。
     */
    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        writeLog("!!! 模块已加载 onModuleLoaded !!!");
    }

    /**
     * 系统服务启动时的回调，是布置底层 Hook 的黄金入口。
     */
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        writeLog("!!! 成功进入系统框架 onSystemServerStarting !!!");
        ClassLoader loader = param.getClassLoader();
        if (loader == null) {
            writeLog("classLoader is null，放弃！");
            return;
        }

        // 布置防线
        hookDeviceIdleController(loader);
        hookOplusHansManager(loader);
        hookOplusDeviceIdleHelper(loader);

        // 启动内存守护线程（兜底机制）
        startGuardianThread();

        writeLog("防御网布置完毕！");
    }

    /**
     * 拦截原生 Doze 白名单的移除动作。
     */
    private void hookDeviceIdleController(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName(CLS_DEVICE_IDLE, false, loader);
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
            writeLog("hookDeviceIdleController 失败", t);
        }
    }

    /**
     * 绞杀 ColorOS 的后台管理机制 {@code OplusHansManager}。
     */
    private void hookOplusHansManager(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName(CLS_HANS, false, loader);
            for (Method m : clazz.getDeclaredMethods()) {
                final String name = m.getName();
                final Method target = m;

                // 1. 伪造白名单豁免
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

                // 2. 拦截后台清理指令
                if (M_HANDLE_REMOVE_TASK.equals(name)) {
                    hookMethod(target, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            List<Object> args = chain.getArgs();
                            // 参数签名：boolean killProc, int userId, String pkgName
                            if (args.size() >= 3 && args.get(2) instanceof String) {
                                String pkg = (String) args.get(2);
                                if (protectedPackages.contains(pkg)) {
                                    writeLog("拦截 HansManager 清理任务: " + pkg);
                                    // 按返回类型返回安全值，避免 NPE（自动拆箱）
                                    return defaultReturnFor(target.getReturnType());
                                }
                            }
                            return chain.proceed();
                        }
                    });
                }
            }
        } catch (Throwable t) {
            writeLog("Hook OplusHansManager 失败", t);
        }
    }

    /**
     * 篡改 ColorOS 白名单生成逻辑 {@code OplusDeviceIdleHelper}（终极杀招）。
     *
     * <p>策略调整：不再修改入参（原方法可能内部重建列表导致修改丢失），
     * 而是在 {@code chain.proceed()} 之后对返回值再次注入。</p>
     */
    private void hookOplusDeviceIdleHelper(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName(CLS_DEVICE_IDLE_HELPER, false, loader);
            for (Method m : clazz.getDeclaredMethods()) {
                final String name = m.getName();
                final Method target = m;

                // 1. 拦截白名单生成源头
                if (M_GET_NEW_WHITELIST.equals(name) || M_WHITELIST_CHANGED_HANDLE.equals(name)) {
                    hookMethod(target, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            // 先改入参（兼容原方法内部不重建列表的情形）
                            List<Object> args = chain.getArgs();
                            if (!args.isEmpty() && args.get(0) instanceof List) {
                                injectIntoList((List<?>) args.get(0), "入参");
                            }

                            // 执行原方法
                            Object result = chain.proceed();

                            // 再改返回值（防止方法内部重建列表）
                            if (result instanceof List) {
                                injectIntoList((List<?>) result, "返回值");
                            }
                            return result;
                        }
                    });
                }
            }
        } catch (Throwable t) {
            writeLog("Hook OplusDeviceIdleHelper 失败", t);
        }
    }

    /**
     * 把保护列表注入到给定的 List 中（已存在则不重复添加）。
     */
    @SuppressWarnings("unchecked")
    private void injectIntoList(List<?> rawList, String from) {
        if (!(rawList instanceof List)) return;
        try {
            List<String> list = (List<String>) rawList;
            refreshProtectedPackages();
            for (String pkg : protectedPackages) {
                if (!list.contains(pkg)) {
                    list.add(pkg);
                    writeLog("篡改生死簿[" + from + "]: 强制注入 -> " + pkg);
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // 列表不可修改，跳过
        } catch (Throwable t) {
            writeLog("injectIntoList 失败", t);
        }
    }

    /**
     * 内存守护线程（兜底方案）。
     * <p>即使上述 Hook 全部失效，这个线程也会定期执行原生 cmd 命令，
     * 强制把应用加入 Doze 白名单并赋予后台运行权限。</p>
     * <p><b>注意：</b>此处使用 system_server 天然权限执行，无需 su。</p>
     */
    private void startGuardianThread() {
        if (!guardianStarted.compareAndSet(false, true)) {
            writeLog("守护线程已存在，跳过重复启动");
            return;
        }
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
                Set<String> snapshot = protectedPackages; // 读取不可变快照
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
                try {
                    Thread.sleep(GUARDIAN_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        writeLog("内存守护线程退出");
    }

    /**
     * 执行外部命令。
     * <p>必须消费 stdout/stderr 并 waitFor，否则可能产生僵尸进程或子进程因管道阻塞卡死。</p>
     */
    private static void safeExec(String... cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            // 消费输出，避免 pipe 满导致子进程阻塞
            drainStream(p.getInputStream());
            drainStream(p.getErrorStream());
            if (!p.waitFor(EXEC_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroy();
            }
        } catch (Throwable ignored) {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored2) {}
            }
        }
    }

    private static void drainStream(InputStream is) {
        if (is == null) return;
        try (InputStream in = is) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (Throwable ignored) {}
    }

    // ============ 通用工具 ============

    /**
     * 在参数列表中查找受保护的包名。
     */
    private static String findProtectedPkg(List<Object> args, Set<String> set) {
        if (set.isEmpty()) return null;
        for (Object arg : args) {
            if (arg instanceof String && set.contains(arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    /**
     * 根据原方法的返回类型，返回一个“阻止执行”语义的安全值。
     * <ul>
     *   <li>boolean → false（原方法返回 false 通常代表未执行）</li>
     *   <li>int → 0</li>
     *   <li>long → 0L</li>
     *   <li>void → null</li>
     *   <li>引用类型 → null</li>
     * </ul>
     * <p>不同方法的语义可能有差异，此处按最保守的“无操作”值处理，避免自动拆箱 NPE。</p>
     */
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

    /**
     * 反射黑魔法：绕过 AAR 包的编译缺陷进行 Hook。
     *
     * <p>libxposed API 102 的 AAR 包在 Java 编译环境下，由于 XposedInterfaceWrapper 的缺陷，
     * 无法直接调用 {@code hook(method, hooker)}。因此这里通过 Java 反射，
     * 动态调用接口的 hook 方法并传入我们的 Hooker 对象。</p>
     *
     * <p>对 Method 查找结果做静态缓存，避免重复查找；对同一目标方法做去重，避免重复挂载。</p>
     */
    private void hookMethod(Method method, XposedInterface.Hooker hooker) {
        if (method == null) return;
        if (!hookedMethods.add(method)) {
            writeLog("方法已 Hook，跳过: " + method);
            return;
        }
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
            // 失败时从已 Hook 集合中移除，允许后续重试
            hookedMethods.remove(method);
            writeLog("反射 Hook 失败: " + method, t);
        }
    }
}
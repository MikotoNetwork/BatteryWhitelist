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

/**
 * LSPosed 模块的核心入口类。
 *
 * <p>此类运行在 Android 系统的 {@code system_server} 进程中（即拥有极高层级权限的系统服务），
 * 负责注入并修改 ColorOS (Android 16) 的底层电池优化与后台冻结逻辑。</p>
 *
 * <p>核心防御机制（“三位一体”防御网）：</p>
 * <ol>
 *   <li><b>内存层拦截</b>：Hook 原生 Doze 控制器、ColorOS 的 {@code OplusHansManager} 和 {@code OplusDeviceIdleHelper}，阻止系统把应用移出白名单或冻结后台。</li>
 *   <li><b>内存守护线程</b>：定期（每60秒）主动发出 shell 命令，强制把目标应用写入 Doze 白名单。</li>
 *   <li><b>底层 Root 脚本</b>：通过 UI 端部署的 service.d 脚本，在系统启动后以 root 权限无限循环执行强制纠正。</li>
 * </ol>
 *
 * @author MikotoNetwork
 * @version 1.18
 */
public class BatteryWhitelistModule extends XposedModule {

    private static final String TAG = "BatteryWhitelist";
    /** 跨进程通信名称，必须与 MainActivity 中的 getSharedPreferences 保持一致 */
    private static final String PREFS_NAME = "battery_whitelist_prefs";
    /** 存储用户勾选保护应用包名的键值 */
    private static final String KEY_LIST = "protected_packages";

    /** 远程 SharedPreferences，用于实时读取 UI 端用户勾选的应用 */
    private SharedPreferences prefs;
    /** 动态保护列表，每次触发拦截前都会刷新 */
    private final Set<String> protectedPackages = new HashSet<>();

    /**
     * 物理探针日志。
     *
     * <p>由于 Android 16 的 ColorOS 对 system_server 的 logcat 输出进行了极其严格的拦截，
     * 普通的 Log 无法在 LSPosed 日志中看到，因此采用写入物理文件的方式绕过拦截。</p>
     *
     * @param msg 日志内容
     */
    private static void writeLog(String msg) {
        Log.e(TAG, msg);
        try {
            FileWriter fw = new FileWriter("/data/system/BatteryWhitelist.log", true);
            fw.write(new Date() + " : " + msg + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    /**
     * 反射初始化跨进程的 SharedPreferences。
     *
     * <p>由于 system_server 无法直接通过 Context 获取 UI 应用的 SharedPreferences，
     * 这里利用了 ActivityThread 的反射机制，获取系统级别的 Context，再通过
     * createPackageContext 强行读取 UI 应用的数据（绕过 SELinux 隔离）。</p>
     *
     * <p><b>注意：</b>不能在此方法初始化时直接调用 {@code getRemotePreferences}（那是 API 102 的写法，已废弃），
     * 因为该 AAR 包有严重的编译缺陷，且跨进程延迟会导致初始化失败。</p>
     */
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

            // 获取 UI 应用的 Context，忽略安全检查 (CONTEXT_IGNORE_SECURITY)
            Context uiContext = systemContext.createPackageContext(
                    "com.batterywhitelist", Context.CONTEXT_IGNORE_SECURITY);
            prefs = uiContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            writeLog("成功连接 UI 配置！");
        } catch (Throwable ignored) {
            // 系统启动早期可能无法获取包管理器，静默失败等待下次重试
        }
    }

    /**
     * 刷新受保护的应用列表，从 UI 端的 SharedPreferences 中读取最新配置。
     * <p>之所以每次拦截前都要调用此方法，是为了保证 UI 端修改后，底层无需重启系统即可实时生效（热重载）。</p>
     */
    private void refreshProtectedPackages() {
        initPrefs();
        if (prefs == null) return;
        Set<String> saved = prefs.getStringSet(KEY_LIST, Collections.emptySet());
        protectedPackages.clear();
        protectedPackages.addAll(saved);
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
     * <p>ColorOS 16 虽然架空了原生 Doze，但如果系统残留此逻辑依然会做拦截。</p>
     */
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
                                    // 根据返回值类型返回对应的伪造值
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

    /**
     * 绞杀 ColorOS 的后台管理机制 {@code OplusHansManager}。
     * <p>包括：</p>
     * <ul>
     *   <li>拦截白名单判断：伪造 {@code inCachedFreezeKillWhiteList} / {@code inAndroidFreezeKillWhiteList} 的返回值，强行告诉系统这是 VIP。</li>
     *   <li>拦截任务清理：阻止 {@code handleRemoveTask} 干掉我们的目标进程。</li>
     * </ul>
     */
    private void hookOplusHansManager(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName("com.android.server.am.OplusHansManager", false, loader);
            for (Method m : clazz.getDeclaredMethods()) {
                String name = m.getName();

                // 1. 伪造白名单豁免
                if ("inCachedFreezeKillWhiteList".equals(name)
                        || "inAndroidFreezeKillWhiteList".equals(name)) {
                    hookMethod(m, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            for (Object arg : chain.getArgs()) {
                                if (arg instanceof String && protectedPackages.contains(arg)) {
                                    writeLog("伪造 HansManager 白名单豁免: " + arg);
                                     // 直接告诉系统这是白名单应用
                                    return Boolean.TRUE;
                                }
                            }
                            return chain.proceed();
                        }
                    });
                }

                // 2. 拦截后台清理指令
                if ("handleRemoveTask".equals(name)) {
                    hookMethod(m, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            refreshProtectedPackages();
                            List<Object> args = chain.getArgs();
                            // 参数签名：boolean killProc, int userId, String pkgName
                            if (args.size() >= 3 && args.get(2) instanceof String) {
                                String pkg = (String) args.get(2);
                                if (protectedPackages.contains(pkg)) {
                                    writeLog("拦截 HansManager 清理任务: " + pkg);
                                    // 阻止原方法执行
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

    /**
     * 篡改 ColorOS 白名单生成逻辑 {@code OplusDeviceIdleHelper}（终极杀招）。
     * <p>ColorOS 会定期从云端或本地 XML 读取白名单，并强制覆盖系统白名单。
     * 我们拦截其生成逻辑，强行把我们的包名塞进它的内存列表中，做到“源头上改命”。</p>
     */
    private void hookOplusDeviceIdleHelper(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName("com.android.server.OplusDeviceIdleHelper", false, loader);
            for (Method m : clazz.getDeclaredMethods()) {
                String name = m.getName();

                // 1. 拦截白名单生成源头
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
                                    // 强行加进去！
                                        list.add(pkg); 
                                        writeLog("篡改生死簿: 强制加入 getNewWhiteList -> " + pkg);
                                    }
                                }
                            }
                            return chain.proceed();
                        }
                    });
                }

                // 2. 拦截最终处理逻辑
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
     * 内存守护线程（兜底方案）。
     * <p>即使上述 Hook 全部失效，这个线程也会每 60 秒执行一次原生 cmd 命令，
     * 强制把应用加入 Doze 白名单并赋予后台运行权限。</p>
     * <p><b>注意：</b>此处使用 system_server 天然权限执行，无需 su。</p>
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
                    // 每 60 秒循环一次
                    Thread.sleep(60000); 
                } catch (Throwable t) {
                    writeLog("守护线程异常: " + t.getMessage());
                }
            }
        }).start();
        writeLog("内存守护线程已启动");
    }

    /**
     * 反射黑魔法：绕过 AAR 包的编译缺陷进行 Hook。
     *
     * <p>libxposed API 102 的 AAR 包在 Java 编译环境下，由于 XposedInterfaceWrapper 的缺陷，
     * 无法直接调用 {@code hook(method, hooker)}。因此这里通过 Java 反射，
     * 动态调用接口的 hook 方法并传入我们的 Hooker 对象。</p>
     *
     * @param method 要 Hook 的原始方法
     * @param hooker 包含拦截逻辑的回调
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
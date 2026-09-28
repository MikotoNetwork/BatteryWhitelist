# BatteryWhitelist (电池白名单守护者)
[BatteryWhitelist](assets/icon.png)
> “买的设备是自己的，自己拥有对该设备的一切权利，厂商无权干涉。”

一个专为 Android 16 (ColorOS 16 / 一加) 打造的硬核 LSPosed 模块，旨在于底层阻止系统强行重置应用电池优化、拦截 `OplusHansManager` 后台冻结，真正把设备控制权还给用户。

## 📖 背景故事

在 ColorOS 这样的深度定制系统中，用户即使手动将应用（如“小米运动健康” `com.mi.health`）设置为“电池无限制”，系统仍会在重启或 SystemUI 重启后，将其悄悄改回“优化”，甚至通过底层的 `OplusHansManager` 强行冻结应用，导致断连、掉线。

这套机制不接受用户的取舍，纯属系统层面的“爹味”教育。
本项目从零开始，通过分析 `system_server` 底层源码，精准定位了系统杀后台的“命门”，并利用 LSPosed 注入进行无情的反向劫持。

## ✨ 功能特性

*   **拦截原生 Doze 白名单重置**：Hook `DeviceIdleController.removePowerSaveWhitelistAppInternal`，阻止系统把应用踢出电池优化白名单。
*   **绞杀 ColorOS 底层冻结**：深入分析反编译 `OplusHansManager` 与 `IOplusHansManager`，精准 Hook `handleRemoveTask` 和 `inCachedFreezeKillWhiteList`。
*   **动态保护列表**：抛弃写死的包名，内置极简 UI 界面，用户可自行勾选需要保护的 App。
*   **跨进程零延迟通信**：UI 保存后，底层模块通过纯 Java 反射 `ActivityThread` 实现跨进程实时读取配置。
*   **绝对极简的依赖管理**：为绕过 AndroidX 依赖冲突与 Android SDK 36 的 AAR 限制，项目采用纯系统原生 `ListView`，不使用任何第三方依赖（真正的“降维打击”）。
*   **物理探针日志**：不依赖 LSPosed 的日志系统，直接将日志硬核写入 `/data/system/BatteryWhitelist.log`，防止被系统日志过滤拦截。

## 🛠️ 技术原理

本项目基于 **libxposed API 102**，支持 Android 16 的环境。
主要 Hook 逻辑位于 `com.android.server.deviceidle.DeviceIdleController` 和 `com.android.server.am.OplusHansManager`。

由于 AAR 包在某些版本下的编译缺陷（`XposedInterfaceWrapper` 报错），项目创新性地使用了**反射调用**（`hookMethod`），绕过编译器的类型检查，直接动态调用 `XposedInterface.hook`，确保模块在低版本 Android Gradle 插件下也能完美编译。

## 🚀 安装与使用

### 前提条件
*   设备已解锁 Bootloader（如一加 Ace 5系列等）
*   已刷入 KernelSU 或 Magisk
*   已正确安装并激活 Zygisk Next 与 LSPosed (API 102)

### 安装步骤
1. 去 [Actions](https://github.com/MikotoNetwork/BatteryWhitelist/actions) 下载最新的 `BatteryWhitelist-debug` 或 `release` APK。
2. 安装 APK 到手机。
3. 在 **LSPosed 管理器** 中启用模块，并在“作用域”中**仅勾选「Android 系统（android）」**。
4. 重启手机。
5. 打开 App，勾选你需要保护的应用（如“小米运动健康”）。
6. 再次重启手机（或使用 `adb shell pkill -f com.android.systemui` 重启 SystemUI）。

### 验证方法
重启后，用 MT 管理器查看 `/data/system/BatteryWhitelist.log`。
若看到 `伪造 HansManager 白名单豁免: com.mi.health` 等字样，即为成功接管！

## 💻 构建与编译

项目采用 GitHub Actions 云端编译，无需本地配置复杂的 Android 环境。
*   **Gradle 版本**：8.7
*   **AGP 版本**：8.5.2
*   **依赖**：仅 `compileOnly 'io.github.libxposed:api:102.0.0@aar'`

## ⚠️ 免责声明与温馨提示
*   本模块涉及 Android 系统底层的 `system_server` 修改，强烈建议在刷机前做好数据备份。
*   不要轻易尝试使用 `Root` 或 `MT 管理器` 直接修改 `/data/system/` 下的 XML 配置文件（如 `appfrozen_config.xml`），容易导致系统无限重启（软砖）。本模块一切操作均在内存中进行。

---
## 👨‍💻 开发者手记
这个项目诞生于无数个失眠的夜晚与对流氓系统的愤怒之中。从 GitHub Actions 踩坑，到反编译 `OplusHansManager`；从被编译器反复报错折磨，到最后用反射强行破局。这不仅仅是一个模块，更是对抗不公、夺回数字主权的缩影。

**“不好我就骂，得罪人也无所谓。”**

只要设备在我们手里，一切规则就应由我们自己书写。

*(本项目基于 MIT 协议开源)*
---
## 特别鸣谢
>那个被气到无法自已的自己
>某个国内小鱼(AI太好用了你们知道吗)
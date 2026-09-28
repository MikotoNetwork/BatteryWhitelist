# BatteryWhitelist (电池白名单守卫者)
![BatteryWhitelist](assets/ic_launcher.png)
> **“买的设备是自己的，自己拥有对该设备的一切权利，厂商无权干涉。”**
> 
> 一个诞生于对抗流氓系统“爹味”管理的硬核 LSPosed 模块。

## 📖 背景故事

在 Android 16 (ColorOS 16) 的时代，系统对后台应用的管理变得极其霸道。
即使你手动将应用（如“小米运动健康”）的电池优化设置为“无限制”，系统依然会在重启、切换网络、或者云控下发指令时，悄悄把它改回“优化”并强行冻结，导致手表断连、消息收不到。

本模块专为解决这一痛点而生。它不仅在内存中拦截系统底层的篡改行为，还结合了 Root 脚本，形成了**一套**“三位一体”的终极防御体系**，彻底把选择权还给用户。

## ✨ 核心功能

*   **内存拦截 (Xposed Hook)**：精准切入 `system_server`，反编译并拦截 `OplusHansManager` 和 `OplusDeviceIdleHelper` 的篡改逻辑，伪造顶级白名单豁免。
*   **内存守护线程**：在系统框架内部开启守护线程，每 60 秒主动调用系统命令，实时纠正底层状态。
*   **开机自启脚本 (Root)**：通过 UI 自动向 `/data/adb/service.d/` 写入守护脚本，开机后以最高 Root 权限无限循环执行强制纠正。
*   **动态 UI 管理**：抛弃写死的包名，内置极简搜索列表，用户可自行勾选需要保护的应用，配置实时生效。
*   **持久化日志**：绕过系统日志拦截，所有操作直接写入 `/data/system/BatteryWhitelist.log`，排查状态一目了然。
*   **固定签名**：支持 GitHub Actions 云端编译并配置固定签名，升级无需卸载。

## 🛡️ “三位一体”防御体系架构

```

┌─────────────────────────────────────────────────────────────┐
│                     三位一体 赛博防御网                       │
├───────────────────┬───────────────────┬─────────────────────┤
│  第一层：Xposed   │ 第二层：内存守护  │ 第三层：Root 脚本   │
│  内存层拦截       │ 线程主动纠偏      │ 底层死循环强制修正  │
├───────────────────┼───────────────────┼─────────────────────┤
│ 拦截云控 API      │ 每 60 秒执行 cmd  │ 开机自启          │
│ 拦截白名单剔除    │ 强制加入 Doze     │ 权限 755          │
│ 伪造 VIP 豁免     │ 强制允许后台      │ 随模块自动部署    │
└───────────────────┴───────────────────┴─────────────────────┘

```

## 🚀 安装与使用

### 前提条件
*   设备已解锁 Bootloader（如一加 Ace 5 竞速版等）
*   已刷入 **KernelSU**（或 Magisk）并具备 Root 环境
*   已正确安装并激活 **Zygisk Next** 与 **LSPosed** (API 102)

### 安装步骤
1. 前往 [Actions](https://github.com/MikotoNetwork/BatteryWhitelist/actions) 下载最新构建的 `BatteryWhitelist-release.apk`。
2. 在手机上安装该 APK。
3. 打开 Root 管理器（KernelSU / Magisk），在应用列表中找到 `BatteryWhitelist`，**开启超级用户权限开关**（只需点一次，后续静默授权）。
4. 打开 BatteryWhitelist App，在列表中搜索并勾选你需要保护的应用（例如：小米运动健康）。
5. 退出 App 后再次打开，此时 App 会通过 Root 权限自动向 `/data/adb/service.d/` 部署守护脚本。
6. 在 LSPosed 管理器中启用本模块，作用域**只勾选「Android 系统（android）」**。
7. 重启手机。

### 验证成功
重启后，用 MT 管理器检查以下位置：
*   `/data/system/BatteryWhitelist.log` 是否出现 `内存守护线程已启动` 和 `成功连接 UI 配置！`
*   `/data/adb/service.d/battery_guard.sh` 是否存在且权限为 `755`
*   在终端执行 `dumpsys deviceidle whitelist`，查看受保护应用是否在其中。

## 🛠️ 核心技术细节

*   **反射黑魔法**：由于 `libxposed API 102` AAR 包在编译时存在接口缺陷，项目创造性地使用了运行时反射（`hookMethod`），动态调用 `XposedInterface.hook` 绕过编译器的类型检查。
*   **跨进程通信**：Xposed 模块通过反射 `ActivityThread.currentActivityThread().getSystemContext()` 获取系统上下文，再 `createPackageContext` 读取 UI 应用写入的 SharedPreferences。
*   **反编译研究**：深度剖析 `OplusHansManager` 和 `OplusDeviceIdleHelper`，追踪 `getNewWhiteList`、`whiteListChangedHandle` 等关键方法并实施内存篡改。

## ⚠️ 免责声明
*   本项目涉及 Android 底层的 `system_server` 修改，虽然做了严格的异常捕获，但仍建议刷机前做好数据备份。
*   请勿使用 MT 管理器直接修改 `/data/system/` 下的系统 XML 配置文件，以免造成系统无限重启。

---
## 👨‍💻 开发者手记
这个项目诞生于无数个搞机熬夜的夜晚与对流氓系统的愤怒之中。从 GitHub Actions 踩坑，到反编译 `OplusHansManager`；从被编译器反复报错折磨，到最后用反射和 Root 脚本强行破局。这不仅仅是一个模块，更是夺回数字主权的缩影。

只要设备在我们手里，一切规则就应由我们自己书写。

*(本项目基于 MIT 协议开源)*
![BatteryWhitelist](assets/ic_launcher.png)
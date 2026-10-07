# BatteryWhitelist (电池白名单守护者)
![BatteryWhitelist](assets/ic_launcher.png)
[![release](https://github.com/MikotoNetwork/BatteryWhitelist/actions/workflows/release.yml/badge.svg)](https://github.com/MikotoNetwork/BatteryWhitelist/actions/workflows/release.yml)<br>

> “买的设备是自己的，自己拥有对该设备的一切权利，厂商无权干涉。”
> —— 一个诞生于对抗流氓系统“爹味”管理的硬核 LSPosed 模块。

## 📖 背景故事

在 Android 16 (ColorOS) 的时代，系统对后台应用的“爹味”管控极其霸道。即使你手动将应用设为“无限制”，系统依然会在重启、切换网络或云控下发指令时，悄悄把它改回“优化”并强行冻结，导致手表断连、消息收不到、后台任务被杀。

本模块专为解放这一痛点而生。它不仅在内存中拦截系统底层的篡改行为，还结合了 Root 脚本，形成了一套“四位一体”的终极防御体系，彻底把选择权还给用户。

## ✨ 核心功能

*   **内存层动态狙杀 (Xposed Hook)**：精准切入 `system_server`，拦截原生 `DeviceIdleController`，并针对 ColorOS 独有的 `OplusHansManager` 与 `IOplusDeviceIdleHelper` 动态代理进行强制拦截。
*   **内存守护线程**：在系统框架内部开启守护线程，每 60 秒主动发送底层系统命令（`cmd deviceidle whitelist` / `appops`），强行纠正系统偏差。
*   **开机自启脚本 (Root)**：通过 UI 端自动向 `/data/adb/service.d/` 写入守护脚本，在系统启动后以 Root 权限无限循环执行强制纠正。
*   **极致轻量**：拒绝 AndroidX 臃肿生态，纯原生 + Support 库瘦身，APK 体积仅 **812KB**，极致纯净。
*   **动态 UI 管理**：抛弃写死包名，内置极简应用列表，勾选后自动同步至底层并持久化生效。
*   **固定签名与自动化分发**：基于 GitHub Actions 云端编译并固定签名，支持 Cover Install 无感升级。用户端 App 自动请求 `version.json` 检查更新，配合 Cloudflare 绕过缓存，实现秒级下载新版本。

## 🛡️ “四位一体”防御体系

| 防御层级 | 技术实现 | 核心逻辑 |
| :--- | :--- | :--- |
| **第一层：原生拦截** | Hook `DeviceIdleController` | 拦截系统将应用移出 Doze 白名单的底层操作 |
| **第二层：ColorOS 狙杀** | Hook `OplusHansManager` | 伪造保活状态与可见性，拦截后台清理指令 |
| **第三层：动态代理阻断** | Hook `IOplusDeviceIdleHelper$Default/$I` | 拦截动态代理，拒绝忽略临时白名单，强制注入 Doze 白名单 |
| **第四层：底层兜底** | Root 脚本 + 内存守护线程 | 每 60 秒/10 秒强行执行 `appops` 与 `deviceidle` 命令纠正状态 |

## 📲 安装与使用

### 前提条件
*   设备已解锁 Bootloader。
*   已刷入 KernelSU (或 Magisk) 并具备 Root 环境。
*   已正确安装并激活 Zygisk Next 与 LSPosed (API 102)。

### 安装步骤
1. 前往 [Releases](../../releases) 下载最新构建的 `app-release.apk`。
2. 在手机上安装该 APK。
3. 打开 Root 管理器 (KernelSU / Magisk)，在应用列表中找到 `BatteryWhitelist`，开启超级用户权限开关。
4. 打开 BatteryWhitelist App，在列表中搜索并勾选你需要保护的应用（例如：小米运动健康）。
5. 退出 App 后再次打开，此时 App 会通过 Root 权限自动向 `/data/adb/service.d/` 部署守护脚本。
6. 在 LSPosed 管理器中启用本模块，作用域勾选 **“Android 系统 (android)”**。
7. 重启手机。

### 验证成功
重启后，使用 MT 管理器检查以下位置：
*   `/data/system/BatteryWhitelist.log` 是否出现 `内存守护线程已启动` 和 `成功连接 UI 配置`。
*   `/data/adb/service.d/battery_guard.sh` 是否存在且权限为 755。
*   在终端执行 `dumpsys deviceidle whitelist`，查看受保护应用是否在其中。

## 🔬 核心技术细节

*   **反射黑魔法**：由于 libxposed API 102 AAR 包在编译时存在接口缺陷，项目创造性地使用了运行时反射 (`hookMethod()`) 动态调用 `XposedInterface.hook()` 绕过编译器的类型检查。
*   **跨进程通信**：Xposed 模块通过反射 `ActivityThread.currentActivityThread().getSystemContext()` 获取系统上下文，再 `createPackageContext` 读取 UI 应用写入的 `SharedPreferences`。
*   **反向突破**：深度剖析 `OplusHansManager` 和 `OplusDeviceIdleHelper` 的 `getNewWhiteList`、`whiteListChangedHandle` 等关键方法，实现内存篡改。

## ⚠️ 免责声明

*   本项目涉及 Android 底层的 `system_server` 修改，虽然做了严格的异常捕获，但强烈建议刷机前做好数据备份。
*   请勿使用 MT 管理器直接修改 `/data/system/` 下的系统 XML 配置文件，以免造成系统无限重启。

## 👨‍💻 开发者手记

这个项目诞生于无数个搞机熬夜的夜晚与对流氓系统的愤怒之中。从 GitHub Actions 踩坑，到反编译 `OplusHansManager`，最终用反编译和 Root 脚本重塑底线。

它是一个模块，更是夺回数字主权的缩影。只要设备在我们手里，一切规则就应由我们自己书写。

*(本项目基于 MIT 协议开源)*
![BatteryWhitelistplus](assets/ic_launcher.png)

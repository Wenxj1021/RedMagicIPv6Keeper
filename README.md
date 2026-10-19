# 红魔 IPv6 守护助手 (RedMagic IPv6 Keeper)

专为 **红魔电竞平板 Pro（Nubia REDMAGIC Nova / NP03J，Android 16）** 打造的局域网与公网 IPv6 连通性修复与持续保活工具。

通过 **Shizuku** 获得 ADB Shell（UID 2000）原生权限，无需 Root，彻底脱离电脑独立运行。

---

## 🌟 核心特性

1. **🚦 Material Design 红绿灯连通性看板**
   - 打开 APP 即时探测公网 IPv6 双栈连通性与 RTT 延迟。
   - **🟢 绿色（畅通无阻）**：公网单播已分配，Google DNS 握手与 NDP 邻居状态正常。
   - **🟡 黄色（受阻/异常）**：已分配 IP 但公网探针超时，Happy Eyeballs 可能降级。
   - **🔴 红色（掉线/未连接）**：未获取公网 IPv6，处于纯 IPv4 单栈状态。

2. **🚀 功能 1 · 一键深度修复 IPv6**
   - 下发系统级 Wi-Fi 深度休眠优化参数（`wifi_sleep_policy=2`、关闭电量缩限与休眠挂起）。
   - 刷新本地 NDP 邻居缓存（`ip -6 neigh flush`）。
   - 组播唤醒网关 NDP（`ping6 'ff02::2%wlan0'`），250ms 内激活公网 IPv6。
   - 可选联动：一键重启 Chrome 浏览器，重置 Happy Eyeballs 的 IPv4 回退惩罚。

3. **🛡️ 功能 2 · 持续保活守护模式**
   - 基于 Android 前台服务（带常驻状态栏通知与低功耗唤醒锁）。
   - 支持自定义周期：**30秒 / 45秒（推荐） / 60秒**。
   - 定时向网关发送极轻量的 NDP/ICMPv6 探针，将路由器的邻居状态永久刷新为 `REACHABLE`，从根本上彻底阻断 Comcast Xfinity / 光猫 180s RA 掉线与网关硬件流表老化。

4. **⚡ 纯设备端独立运行**
   - 无需常插 USB 数据线或电脑终端。
   - 结合平板上的 Shizuku 服务实现完全自主守护。

---

## 🛠️ 技术规格

- **适配机型**：红魔电竞平板 Pro (NP03J)
- **系统版本**：Android 16 (SDK 36)
- **Minimum SDK**：`minSdk = 36`
- **Target SDK**：`targetSdk = 36`
- **Compile SDK**：`compileSdk = 37`
- **架构依赖**：Material Design 3, ViewBinding, Shizuku API 13.1.5

---

## 📦 快速编译与安装

```bash
# 克隆仓库
git clone https://github.com/Wenxj1021/RedMagicIPv6Keeper.git
cd RedMagicIPv6Keeper

# 编译 Debug APK
./gradlew assembleDebug

# 安装到平板
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License
MIT License

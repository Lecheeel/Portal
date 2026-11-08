# LocationService

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Lecheeel/Portal) · [下载 APK](https://github.com/Lecheeel/Portal/releases) · [更新日志](https://github.com/Lecheeel/Portal/releases)

Android 定位与环境模拟工具，支持单点定位、路线播放和悬浮摇杆，并提供 GNSS/NMEA、WiFi／基站环境和实验性传感器扩展。

基于 [Portal](https://github.com/ella8192/Portal) 演化，支持标准无 Root、Xposed 与 Native + Xposed 模式。定位样本、卫星模型、环境配置和分应用规则由统一场景运行时协调。

## ✨ 功能亮点

### 定位与路线

- 标准无 Root 模拟定位 · Xposed 系统接入
- GPS／Network／Fused 差异化定位画像
- 系统 Fused／Google 融合定位通道（实验性）
- 提交前反射清除模拟标记（实验性）
- 单点定位 · 悬浮摇杆 · 路线规划与手绘
- 平滑加减速 · 循环与往返播放 · 可选物理微漂移
- 位置与场景管理 · JSON／GPX 导入导出

### GNSS 与 NMEA

- GPS／北斗／GLONASS／Galileo／QZSS 多星座模型
- 同源 GnssStatus／NMEA 生成与主动投递
- GGA／RMC／GSA／GSV／GST／ZDA
- Kepler 轨道天空模型（实验性）

### 环境与分应用

- WiFi／基站环境采集、程序化生成与回放（实验性）
- 网格化 AP · 固定种子 · RSSI 路径损耗模型
- 自建 WiFi 指纹库导入与回放（实验性）
- 分应用排除与固定点（实验性）
- 环境文件管理 · 配置热更新与恢复

### 系统与传感器

- libxposed API 102 原生 Hooker／Chain
- Native + Xposed 传感器扩展（实验性）
- SensorFrame 统一驱动 · 双帧插值 · 速度关联步频
- HIDL／AIDL 传感器事件适配（实验性）
- 运行状态 · 授权检查 · Hook 诊断与日志导出

## 🚀 快速开始

1. 从 [Releases](https://github.com/Lecheeel/Portal/releases) 选择安装包，常见手机选择 arm64。
2. 在运行中心选择模式，按提示完成权限与模块配置。
3. 在地图或“模拟 → 位置”中选择坐标，也可从资料库选择路线，开始后可暂停、恢复或停止。
4. 在“模拟 → 卫星”查看卫星状态及相关控制。环境采集与指纹库位于“运行中心 → 开发者工具 → 环境工具”。

### 运行模式

| 模式 | 前置条件 | 主要用途 |
|---|---|---|
| 标准 Mock | 在开发者选项中设为模拟位置应用，并授予定位权限 | 无 Root 单点、路线与摇杆模拟 |
| Xposed | 支持 libxposed API 102 的框架，启用相应系统作用域 | 系统定位、GNSS/NMEA、环境与分应用扩展 |
| Native + Xposed | Root、arm64 设备及已激活的 Xposed | 实验性 Native 传感器扩展 |

首次启用 Xposed 或更新模块后，请按提示重启。Native 模式选择前需确认风险；已加载的 Native Hook 需要重启相关进程或设备才能卸载。

标准无 Root 模式遵循 Android 模拟定位机制，系统仍可能标记其输出。分应用规则主要面向系统定位接口，Google 融合定位通道使用全局场景。实验能力的兼容性取决于设备、ROM 与框架实现。

## 许可证

本项目基于 [Portal](https://github.com/ella8192/Portal) 演化，按 [Apache License 2.0](LICENSE) 分发；上游归属与修改说明见 [NOTICE](NOTICE)。

## 致谢

[@fuqiuluo](https://github.com/fuqiuluo) · [GoGoGo](https://github.com/ZCShou/GoGoGo) · [LSPosed](https://github.com/LSPosed/LSPosed) · [Dobby](https://github.com/jmpews/Dobby)

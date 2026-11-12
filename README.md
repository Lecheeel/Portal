# LocationService

[![Ask DeepWiki](https://raw.githubusercontent.com/Lecheeel/Portal/main/docs/badge.svg)](https://deepwiki.com/Lecheeel/Portal)
[![Release](https://img.shields.io/badge/Release-green)](https://github.com/Lecheeel/Portal/releases)
[![Android](https://img.shields.io/badge/Android-10%20--%2017-blue.svg?style=flat)](https://github.com/Lecheeel/Portal/releases)
[![LICENSE](https://img.shields.io/badge/license-Apache%20License-brightgreen.svg?style=flat)](https://github.com/Lecheeel/Portal/blob/main/LICENSE)

Android 定位与环境模拟工具，支持单点定位、路线播放和悬浮摇杆，并提供 GNSS/NMEA、WiFi/基站环境和传感器扩展。

基于 [Portal](https://github.com/fuqiuluo/Portal) [@fuqiuluo](https://github.com/fuqiuluo)，支持无 Root/Xposed/Native + Xposed 模式。
<!--
### 运行模式

| 模式 | 前置条件 | 主要用途 |
|---|---|---|
| 标准 Mock | 在开发者选项中设为模拟位置应用，并授予定位权限 | 无 Root 单点、路线与摇杆模拟 |
| Xposed | 支持 libxposed API 102 的框架，启用相应系统作用域 | 系统定位、GNSS/NMEA、环境与分应用扩展 |
| Native + Xposed | Root、arm64 设备及已激活的 Xposed | 实验性 Native 传感器扩展，⚠system hook |


### ⚠⚠⚠当你使用Native + Xposed模式， 必看！！！

+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

**此模式会对system_service进行修改，包括不限于** 

**接入传感器事件**

加速度计	TYPE_ACCELEROMETER	/ 陀螺仪	TYPE_GYROSCOPE	

磁力计/地磁	TYPE_MAGNETIC_FIELD	 / 气压计	TYPE_PRESSURE	

步数计数器	TYPE_STEP_COUNTER	/ 单步检测器	TYPE_STEP_DETECTOR


**修改定位数据**

经纬度、海拔高度、速度、运动航向、定位精度及相关字段

GPS/Network/Fused 定位结果


**修改 GNSS 卫星数据** 

卫星数量、编号与所属星座、卫星方位角、仰角

信号强度 CN0、载波频率、参与定位状态

按目标地点与时间生成天空，支持广播星历、历书与虚拟兜底


**接入 NMEA 数据**

GGA/RMC/GSA/GSV/GST/ZDA

位置、海拔、速度、航向、UTC 时间、卫星数量、DOP、卫星状态与 NMEA 主动投递

**NMEA 需要单独开启，并非选择 Native 后全部自动开启**

### ⚠ ⚠ 注意事项和bug ⚠ ⚠ 

传感器目前主要改写设备已有事件，不会凭空增加硬件，也不保证没有硬件事件时仍能持续输出

原始 GNSS 测量、导航电文、天线信息，目前没有完整模拟输出 （**BUG**）

这里修改的是系统软件处理与回调数据，不会刷写 GNSS 芯片固件，也不会改变真实卫星信号！

停止模拟 **不是** 卸载 Native Hook！！ ** 已经加载的 Native Hook，需要重启相关进程或设备才能卸载！！ **

+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

## ✨ 功能亮点

### 定位与路线

- 无 Root/Xposed/Native + Xposed（Root require）
- GPS/Network/Fused 差异化定位画像
- 系统 Fused/Google 融合定位通道（实验性）
- 提交前反射清除模拟标记（实验性）
- 单点定位、悬浮摇杆、路线规划与手绘
- 平滑加减速、循环与往返播放、可选物理微漂移
- 位置与场景管理、JSON/GPX 导入导出

### GNSS 与 NMEA

- GPS/北斗/GLONASS/Galileo/QZSS
- 同源 GnssStatus/NMEA 生成与主动投递
- GGA/RMC/GSA/GSV/GST/ZDA
- 自动、自主仿真、实测回放与关闭模式
- 多系统广播轨道、GPS 历书与离线虚拟天空
- 可见卫星下限与虚拟补齐策略

### 环境与分应用

- WiFi/基站环境采集、程序化生成与回放（实验性）
- 网格化 AP、固定种子、RSSI 路径损耗模型
- 自建 WiFi 指纹库导入与回放（实验性）
- 分应用排除与固定点（实验性）
- 环境文件管理 · 配置热更新与恢复

### 系统与传感器

- libxposed API 102 原生 Hooker/Chain
- Native 传感器扩展（实验性）
- SensorFrame 统一驱动、双帧插值、速度关联步频
- HIDL/AIDL 传感器事件适配（实验性）
-->
## 🚀 快速开始

1. 从 [Releases](https://github.com/Lecheeel/Portal/releases) 安装。
2. 授予权限



## 许可证

见 [NOTICE](NOTICE)。

## 致谢
- [Portal](https://github.com/ella8192/Portal)
- [HideMockLocation](https://github.com/auag0/HideMockLocation)
- [GoGoGo](https://github.com/ZCShou/GoGoGo)
- [LSPosed](https://github.com/LSPosed/LSPosed)
- [Dobby](https://github.com/jmpews/Dobby)
- [shadowhook](https://github.com/bytedance/android-inline-hook)

# LocationService

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Lecheeel/Portal) - [下载 APK](https://github.com/Lecheeel/Portal/releases/latest) - [更新日志](https://github.com/Lecheeel/Portal/releases)

基于 [Portal](https://github.com/ella8192/Portal) [@fuqiuluo](https://github.com/fuqiuluo) 的 Android 定位模拟应用，支持单点定位、路线播放和摇杆控制。

## ✨ 功能亮点

- 无 Root / Root / Xposed 模拟定位
- Root + Xposed Native 扩展 · 传感器模拟（实验性）
- 提交前反射清除模拟标记（实验性）
- 系统 Fused / Google 融合定位通道（实验性）
- WiFi/基站环境采集与回放（实验性）
- per-app 路由与固定点（实验性）
- 单点定位 · 悬浮摇杆
- 路线规划 · 手绘路线 · 循环与往返播放
- 位置与场景管理 · JSON / GPX 导入导出

## 🚀 快速开始

1. 从 [Releases](https://github.com/Lecheeel/Portal/releases/latest) 安装。
2. 无 Root 使用时，在开发者选项中将 LocationService 设为“模拟位置信息应用”，并授予精确位置权限。
3. 选择位置或路线后开始模拟，可在应用内暂停、恢复或停止。

Xposed 模式需启用模块的系统作用域并重启；Native 扩展需 Root 和 arm64 设备。

## 致谢

[@fuqiuluo](https://github.com/fuqiuluo) · [GoGoGo](https://github.com/ZCShou/GoGoGo) · [LSPosed](https://github.com/LSPosed/LSPosed) · [Dobby](https://github.com/jmpews/Dobby)

# LocationService

基于 [Portal](https://github.com/fuqiuluo/Portal) 的 Android 定位模拟应用，支持单点定位、路线播放和摇杆控制。

## 功能亮点

- **位置模拟**：地图选点、地点搜索和坐标输入，支持单点定位与悬浮摇杆。
- **路线编辑**：道路规划、逐点连线、手绘路线，编辑草稿自动保存。
- **路线播放**：单次、循环、往返播放，支持速度预设、平滑运动、暂停与恢复。
- **资料管理**：位置、路线和场景分类检索，支持路线收藏、复制及 JSON 导入导出。
- **轨迹交换**：导入 GPX 轨迹与路线，支持单条或批量导出路线。
- **运行模式**：标准无 Root 模式、Xposed 模式和实验性 Native 扩展。
- **定位诊断**：查看定位来源、模拟标记和运行状态，支持定位通道重建与日志导出。
- **主题适配**：Material 3 界面，支持浅色与深色主题。

## 下载与使用

1. 从 [Releases](https://github.com/Lecheeel/Portal/releases/latest) 下载 APK，常见手机选择 arm64 安装包。
2. 无 Root 使用时，在开发者选项中将 LocationService 设为“模拟位置信息应用”，并授予精确位置权限。
3. 选择位置或路线后开始模拟，可在应用内暂停、恢复或停止。

Xposed 模式需启用模块的系统作用域并重启；Native 扩展还需要 Root 和 arm64 设备。

## 致谢

[Portal](https://github.com/fuqiuluo/Portal) · [GoGoGo](https://github.com/ZCShou/GoGoGo) · [百度地图 SDK](https://lbsyun.baidu.com/faq/api?title=androidsdk) · [LSPosed](https://github.com/LSPosed/LSPosed) · [Dobby](https://github.com/jmpews/Dobby)

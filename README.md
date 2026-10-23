# LocationService

基于 [Portal](https://github.com/fuqiuluo/Portal) 的 Android 定位模拟应用，支持单点定位、路线播放和摇杆控制。

## 功能

- 地图选点、坐标输入与摇杆移动。
- 路线绘制、速度调节、暂停恢复及循环播放。
- 位置、路线和场景管理，支持 JSON 导入导出及 GPX 路线导入导出。
- 标准无 Root 模式、Xposed 模式和实验性 Native 扩展。

## 下载与使用

1. 从 [Releases](https://github.com/Lecheeel/Portal/releases/latest) 下载 APK，常见手机选择 arm64 安装包。
2. 无 Root 使用时，在开发者选项中将 LocationService 设为“模拟位置信息应用”，并授予精确位置权限。
3. 选择位置或路线后开始模拟，可在应用内暂停、恢复或停止。

Xposed 模式需启用模块的系统作用域并重启；Native 扩展还需要 Root 和 arm64 设备。手动输入坐标使用 **WGS84**。

## 开发

使用 Android Studio 打开项目，工具链配置参见 [Gradle 配置](app/build.gradle.kts) 和 [CI 工作流](.github/workflows/build-apk.yml)。

```sh
./gradlew :app:assembleArm64Debug
```

Windows 使用 `gradlew.bat`。地图服务通过环境变量 `AMAP_ANDROID_KEY` 配置；手动输入坐标无需地图 Key。正式发布使用 GitHub Actions 中的签名构建。

更多开发资料：[架构说明](docs/architecture.md) · [界面设计](docs/ui-design.md)。

## 致谢

[Portal](https://github.com/fuqiuluo/Portal) · [GoGoGo](https://github.com/ZCShou/GoGoGo) · [百度地图 SDK](https://lbsyun.baidu.com/faq/api?title=androidsdk) · [LSPosed](https://github.com/LSPosed/LSPosed) · [Dobby](https://github.com/jmpews/Dobby)

# LocationService

基于 [Portal](https://github.com/fuqiuluo/Portal) 的 Android 定位实验应用。同一套单点或路线场景可选择标准 Mock Provider、Xposed 系统后端，或明确启用的实验性 Native 扩展。支持 Android 12 / API 31 及以上；具体 ROM 和定位消费者的行为需要设备验证。

| 运行方式 | 要求 | 已实现的能力 | 限制 |
|---|---|---|---|
| 标准 Mock Provider | 开发者选项选择本应用为模拟位置应用；前台位置权限 | GPS/Network 标准 Provider、单点、路线、暂停/恢复、前台服务、停止清理 | 不需要 Root/LSPosed/Native；保留 Android 模拟标记；未集成 GMS Mock API |
| Xposed | 兼容 Xposed API 的框架；启用系统作用域并重启 | 接收统一定位样本；保留 system_server、电话、融合和已列出的厂商 Hook；系统侧诊断 | 私有 API 和 Hook 命中依赖 Android/ROM；单独勾选目标 App 不提供独立场景能力 |
| Native + Xposed（实验性） | Xposed 条件、Root、arm64、明确选择实验后端 | 基于 Xposed 发布定位，按需安装原有 Dobby 传感器扩展 | 固定系统库路径、符号与结构偏移；不保证兼容；停止禁用行为，卸载已装 Hook 需重启系统 |
| 仅有 Root | 可使用标准 Mock 模式 | Root 本身不是定位发布后端 | 不等价于已安装 Xposed；不会自动关闭 SELinux |

无 Root 模式不是 Xposed 的等价替代。本项目不承诺不可检测、全部应用支持或所有 ROM 兼容。

## 使用

1. 在侧栏打开“后端与运行状态”，选择后端。默认是标准 Mock Provider。
2. 无 Root 模式：在系统开发者选项的“选择模拟位置信息应用”中选择 LocationService，授予精确位置权限，从应用前台启动场景。悬浮窗仅用于首页摇杆，不是启动定位的前置条件。
3. “位置模拟”页面可直接输入 **WGS84 纬度、经度**，无需地图 Key。也可保存到位置库，再从资料库启动。
4. 路线库 → 地图建路线：依次选择起点/终点或搜索 POI，规划步行路线，确认后保存或保存并启动。高德地图显示/规划使用 GCJ02，保存和发布转换为 WGS84。地图与规划需要有效的高德 Key 和网络。
5. 资料库提供路线、场景、位置的 JSON 导入/导出、重命名、复制和删除。路线可收藏，播放模式为单次、闭合循环、往返。导入文件包含完整版本化资料库；GPX UI 暂未实现。
6. 在运行状态、单点或资料库页面暂停、恢复、停止；前台通知也可停止。UI 关闭不会主动停止播放。进程异常退出后报告中断并尝试清理，不自动恢复为“运行中”。Xposed 新发布会话在最后样本的单调时间超过 5 秒后停止模拟。

正在运行的路线是冻结快照。编辑资料库或设置不会更改当前场景；设置页参数用于下次启动，已保存场景使用自身参数。

## 架构

```text
Fragment / Map binding → ViewModel / editor events
                                  ↓
                     ScenarioRuntime（应用进程）
                                  ↓
                 ScenarioController + PlaybackEngine（core）
                                  ↓ LocationSample（WGS84）
                MockProvider / Xposed / Native backend
                                  ↓
                  RuntimeState + DiagnosticEvent → UI
```

`ScenarioService` 持有 tick 循环及有超时的唤醒锁；领域引擎按实际单调经过时间推进，暂停时间不累计为移动距离。Route/Scenario/坐标/播放与 Android、地图 SDK、Xposed 解耦。Xposed 使用经 UID/实际包名认证的版本化 IPC，一次命令传完整样本，辅助进程不重新计算路线。

旧 Float 坐标迁移为 Double 字符串，旧路线 JSON 和收藏位置 CSV 迁入 `schemaVersion=1` 的原子文件资料库。旧偏好保留，无效记录有迁移诊断；历史 Float 已丢失的精度不能恢复。

详见 [架构与边界](docs/architecture.md)、[验收记录](docs/verification.md) 和 [提交清单](docs/implementation-commits.md)。

## 构建与检查

| 工具 | 当前配置 |
|---|---|
| JDK | 25（Gradle daemon 配置与 CI）；Java/Kotlin 字节码目标 17 |
| Gradle / AGP / Kotlin | 9.7.0 / 9.3.1 / 2.4.10 |
| Android SDK | compile/target 37，min 31 |
| NDK / CMake | 28.2.13676358 / 3.31.6 |

`local.properties` 配置本机 SDK。地图 Key 通过 `AMAP_ANDROID_KEY` 环境变量传入，并在高德控制台绑定实际包名和签名。无 Key 可以构建及使用手动单点、已保存路线；地图在线能力不保证可用。

```sh
export JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED
./gradlew test :nmea:test :core:test :app:testArm64DebugUnitTest :xposed:testDebugUnitTest
./gradlew lint --max-workers=1
./gradlew :app:assembleRelease :app:exportReleaseApks -PBUILD_REVISION=YOUR_GIT_SHA
```

Windows 使用 `gradlew.bat`，PowerShell 用 `$env:JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'`。检查顺序与 CI 一致；lint 独立单 worker 执行，避开本次合并调用时出现的 Kotlin FIR 分析器异常，未关闭 lint 检查；异常根因尚未确认。

版本由显式输入确定：`APP_VERSION_NAME` 默认 `1.1.0`、`APP_VERSION_CODE` 默认 `1790000000`、`BUILD_REVISION` 默认 `unknown`。版本码沿用比旧时间戳版本更大的固定起点，后续发布必须递增；不再用版本码推断构建时间。构建配置不查询公网 IP、主机名、工作目录或当前时间，不执行 Git 命令。依赖、SDK 下载仍可能需要网络。未声称跨机器 APK 字节完全相同。

导出目录 `app/build/outputs/distribution/`：`LocationService-v1.1.0-all.apk`、`-arm64.apk`、`-x86_64.apk`。导出使用 AGP 公共 artifacts API。未配置签名时 Release APK 未签名；分发安装请设置 `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。PR CI 不依赖签名或地图 secrets。

## 验证边界与来源

本次已执行源码检查、JVM 单元测试、lint、Debug/Release 构建和 APK ABI 检查；当前没有连接设备。前台服务/电源策略、AppOps 撤销、实际定位消费者、高德在线返回、system_server Binder 与 Native ROM 行为均需真机验收。当前 Bugly x86_64 依赖仍有 16 KB 页对齐 lint 警告，不能宣称所有 16 KB 页设备兼容。

参考项目用于学习领域分层、Provider 生命周期、资料库与诊断设计，本次未复制参考目录中 GPL/AGPL 项目的实现；未引入 SharedMemory、ptrace、Compose 重写或默认噪声。保留 Portal、LSPosed、Dobby 与地图 SDK 的来源和依赖关系。原 README 声明 Apache 2.0；实际再分发仍须核对仓库和各第三方组件的许可证、版权与 NOTICE 要求。

保留原有致谢：[Portal](https://github.com/fuqiuluo/Portal)、[GoGoGo](https://github.com/ZCShou/GoGoGo)、[百度地图 SDK](https://lbsyun.baidu.com/faq/api?title=androidsdk)、[LSPosed](https://github.com/LSPosed/LSPosed)、[Dobby](https://github.com/jmpews/Dobby)。当前地图适配使用高德 SDK。

# 多后端定位架构

## 模块与文件

本次新增 Gradle 模块 `:core`（纯 JVM 领域、运行契约和测试）；保留 `:app`（Android UI/服务/后端）、`:xposed`（Hook/IPC/JNI）、`:nmea`（NMEA 解析与改写）及 `:system-api`（编译用系统 API 桩）。Mock、Xposed、Native 后端是 app 内部包，不是三个新 Gradle 模块。

| 层 | 核心文件 | 责任 |
|---|---|---|
| 领域 | `core/src/main/kotlin/com/system/location/service/core/geo/`、`scenario/`、`playback/PlaybackEngine.kt` | WGS84/GCJ02/BD09 类型、冻结路线、实际经过时间推进、单次/循环/往返 |
| 运行契约 | `core/.../backend/LocationBackend.kt`、`runtime/RuntimeState.kt`、`runtime/ScenarioController.kt` | 唯一运行状态、后端生命周期、失败清理、能力、限量诊断 |
| Android 生命周期 | `app/src/main/java/com/system/location/service/runtime/ScenarioRuntime.kt`、`ScenarioService.kt` | 应用进程持有控制器；服务持有 tick，拒绝超时启动回调，前台通知、唤醒锁、进程中断记录 |
| 标准后端 | `app/.../backend/mock/MockProviderBackend.kt`、`AndroidMockProviderPort.kt` | AppOps 检查、GPS/Network 创建、发布、暂停、清理/回滚、持久化清理记录 |
| Xposed 后端 | `app/.../backend/xposed/XposedBackend.kt`、`service/MockServiceHelper.kt` | 连接及样本协议校验、单样本 IPC、系统状态诊断 |
| Hook | `xposed/.../hook/RemoteCommandHandler.kt`、`LocationTicker.kt`、`utils/FakeLoc.kt`、`scope/` | 验证命令后执行、完整样本共享、5 秒租约、系统监听发布、作用域及安装/命中记录 |
| Native | `app/.../backend/native/`、`xposed/src/main/cpp/main.cpp`、`sensor_hook.cpp` | 明确 opt-in 的 Xposed 装饰器；库安装与符号检查；实验行为开关 |
| 资料库 | `core/.../repository/Repositories.kt`、`app/.../data/repository/LibraryRepositories.kt`、`data/persistence/AtomicDocumentStore.kt` | 稳定 ID、CRUD/copy/import/export、原子提交、旧数据迁移、保留迁移问题 |
| 路线编辑 | `core/.../planning/RouteDraftController.kt`、`app/.../amap/AMapRoutePlanner.kt`、`ui/viewmodel/RouteEditViewModel.kt`、`ui/mock/RouteEditFragment.kt` | 可持久化起终点/相机/路线；异步代次匹配；高德 SDK 仅存在于适配/展示层 |
| 页面 | `ui/viewmodel/PointViewModel.kt`、`LibraryViewModel.kt`、`MockServiceViewModel.kt`、`ui/runtime/RuntimeFragment.kt` | 手动单点、资料库、地图事件、订阅状态、能力与诊断展示 |

表中 `core/...` 展开为 `core/src/main/kotlin/com/system/location/service/core/`，`app/...` 展开为 `app/src/main/java/com/system/location/service/`，`xposed/...` 展开为 `xposed/src/main/java/com/system/location/service/`。

## 运行状态和时间

状态流为 `Idle → Preparing → Ready → Running ↔ Paused → Stopping → Stopped`。任何后端失败进入 Error，尝试 stop/release；清理仍失败时保留后端实例，禁止切换后端，允许用户重试停止。状态含后端、场景 ID/名、路线 ID、当前段、定位样本、进度、开始/最近发布时间及错误。

播放采用 Android `elapsedRealtimeNanos` 驱动，系统时钟只用于样本 wall time。长路线预计算累计地理距离，二分查找当前段；不按回调次数累加固定距离，不加默认随机噪声。暂停时仍发布相同位置的新时间样本且 speed=0，保持 Provider 与 Xposed 租约；恢复不计暂停期间距离。单次路线最后一个样本到达终点后自动清理后端，定位可恢复真实来源。

Loop 包含终点到起点的闭合段；Ping-pong 沿原路线反向。确认/启动/保存边界复制并冻结路线，编辑不会修改正在运行的列表。

## 生命周期和恢复

`ScenarioRuntime` 的应用级作用域接收已提交命令；等待命令的 UI 被销毁不会取消该命令。`ScenarioService` 是非导出的 location 类型前台服务、`START_NOT_STICKY`；不能依赖前台服务绝不被厂商杀死。服务管理有 10 分钟上限且运行时续期的非引用计数唤醒锁，停止/销毁释放。

进程恢复时读取 `scenario_runtime` 中断标记，构造上次后端并清理，显示中断或清理失败。Mock Provider 在创建前持久化所有权记录，用于中途死亡和部分创建失败后的清理；撤销模拟位置权限时，清理可能被系统拒绝，恢复授权后重试。记录和 Native opt-in 不随云备份或设备转移继承。

Xposed 新样本在 App 死亡后不无限发布；每个接收进程依据同一个样本时间计算 5 秒租约，到期禁用模拟。旧协议 `start` 路径仍保留兼容行为，新应用播放入口统一使用 `publish_sample`。真实 Binder 身份传播和到期恢复待真机验证。

## IPC 与作用域

控制器必须匹配 PackageManager 实际 UID 和完整包名；辅助进程只允许明确的系统包集合及系统应用身份。握手失败立即返回；会话随机 256 bit token，按 UID 管理，握手轮换使旧 token 失效。协议版本、递增序号、单调时间有效期和命令白名单在执行前检查；peer 只可 sync_config/set_proxy。不能用包名 substring 判断授权。

新定位样本通过 `SampleWire` 的独立版本号和 Bundle 原始数值字段传输，避免自定义 Parcelable 的跨 ClassLoader 耦合。`FakeLoc.acceptSample` 保留同一不可变样本，接收方不重算路线或重写样本时间。system_server 转发时清除继承的 App Binder 身份、执行后恢复；peer 校验 UID 1000。身份、序号和样本新鲜度均有纯策略测试；真正跨 Binder 测试未执行。

`HookScopeResolver` 精确检查 package/process/UID/SDK；android/system_server、phone、系统 fused、Xiaomi fused、Oplus location 分支均有显式进入条件。框架必须实际选中相应作用域。TARGET_APP 返回明确 skipped，当前不提供独立 App 场景 IPC，Capability.PerAppScenario 为 unavailable。

HookStatusRegistry 分别记录 supported/installed/matched/skipped/failed；installed 不等于 matched，更不等于所有目标消费者有效。单次命中探针失败不会覆盖原 Hook 已安装的事实。页面当前读取 system_server 的摘要及有限明细，辅助进程保留各自本地状态，尚未汇总全部 peer 明细。

## 数据与地图

资料库 `files/library/{routes,scenarios,locations}.json` 使用 schemaVersion 1、kind 和 items，Android AtomicFile 负责失败回滚。未知版本/错误种类/损坏导入拒绝；整份导入先验证，ID 冲突复制为新记录；路线限制 100000 点、资料库限制 10000 条和 2000 万字符。资料库 API 不依赖地图 SDK 或 Hook。

历史 `fused_ext` prefs 保留作迁移来源：路线逐条解析，CSV/旧 JSON 位置转为 Double 字段，有效条目使用稳定 ID 以支持迁移重试。无效记录不伪造修复，问题写入 migration issues。历史 Float 精度无法补回。lastKnownLat/Lng 以十进制 Double 字符串存储；旧值读取时迁移。

草稿 `files/route_draft.json` 持久化起终点、当前选点角色、请求代次、路线和相机。每次重新选点、取消、重新规划使旧结果过期；正在规划的草稿重建后显示中断并等待重新规划。高德 path 是候选路线，选择第一个 path 后拼接其全部 steps，保留所有路段并只去掉相邻重复点，不把多个备选 path 串成一条路线。SDK 对象不进入领域模型。确认弹窗持有独立路线快照。

首页仍使用原 AMap 展示/地址搜索交互，业务播放和位置资料持久化已交给 ViewModel/Repository/Runtime。路线列表和单点页已改用新资料库与状态契约；GNSS 保留旧功能入口，但限制在运行的 Xposed/Native 场景下使用。

## 后续扩展边界

可以增加 GMS 适配器、GPX 转换器或新发布后端，不需要重写播放引擎。当前未集成 GMS mock、GPX UI、按 App 场景、SharedMemory、ptrace。Native 只是在 Xposed 外包一层可选传感器能力，不是无需框架的第三套定位发布器。当前固定偏移实现未做 ROM 白名单认证，始终标为 experimental。

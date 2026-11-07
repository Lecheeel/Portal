# 多后端定位架构

## 模块与文件

Gradle 模块包括 `:core`（纯 JVM 领域、运行契约和测试）、`:app`（Android UI/服务/后端）、`:xposed`（Hook/IPC/JNI）、`:nmea`（NMEA 解析与改写）、`:system-api`（编译用系统 API 桩）和独立诊断应用 `:detector`。Mock、Xposed、Native 后端是 app 内部包，不是三个新 Gradle 模块。正式 Hook 已迁移到 libxposed API 102.0.0，独立迁移原型 `:libxposed-spike` 已移除；兼容与验证说明见 [libxposed 迁移](libxposed-migration.md)。

| 层 | 核心文件 | 责任 |
|---|---|---|
| 领域 | `core/src/main/kotlin/com/system/location/service/core/geo/`、`scenario/`、`playback/PlaybackEngine.kt` | WGS84/GCJ02/BD09 类型、冻结路线、实际经过时间推进、单次/循环/往返 |
| 运行契约 | `core/.../backend/LocationBackend.kt`、`runtime/RuntimeState.kt`、`runtime/ScenarioController.kt` | 唯一运行状态、后端生命周期、失败清理、能力、限量诊断 |
| Android 生命周期 | `app/src/main/java/com/system/location/service/runtime/ScenarioRuntime.kt`、`ScenarioService.kt` | 应用进程持有控制器；服务持有 tick，拒绝超时启动回调，前台通知、唤醒锁、进程中断记录 |
| 标准后端 | `app/.../backend/mock/MockProviderBackend.kt`、`AndroidMockProviderPort.kt` | AppOps 检查、GPS/Network 创建、发布、暂停、清理/回滚、持久化清理记录 |
| Xposed 后端 | `app/.../backend/xposed/XposedBackend.kt`、`service/MockServiceHelper.kt` | 连接及样本协议校验、单样本 IPC、系统状态诊断 |
| Hook | `xposed/.../hook/RemoteCommandHandler.kt`、`LocationTicker.kt`、`utils/FakeLoc.kt`、`scope/` | 验证命令后执行、完整样本共享、5 秒租约、系统监听发布、作用域及安装/命中记录 |
| Hook 运行时 | `xposed/.../hook/PortalModule.kt`、`runtime/HookRuntime.kt`、`runtime/Reflection.kt`、`scope/HookInstaller.kt` | API 102 生命周期、原生 Hooker/Chain 安装、反射及原方法调用、首次命中记录 |
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

`HookScopeResolver` 精确检查 package/process/UID/SDK；android/system_server、phone、系统 fused、Xiaomi fused、Oplus location、Settings provider 分支均有显式进入条件。现代模块的静态 `scope.list` 使用 `system` 表示 system_server，其余五项是对应包名。框架必须实际选中相应作用域。TARGET_APP 返回明确 skipped，当前不提供独立 App 场景 IPC，Capability.PerAppScenario 为 unavailable。

HookStatusRegistry 分别记录 supported/installed/matched/skipped/failed；installed 不等于 matched，更不等于所有目标消费者有效。首次命中在业务拦截器内记录，安装返回前的命中也会保留。页面当前读取 system_server 的摘要及有限明细，辅助进程保留各自本地状态，尚未汇总全部 peer 明细。

## libxposed 生命周期与调用

`PortalModule : XposedModule` 是唯一正式入口，由 `META-INF/xposed/java_init.list` 注册。`onModuleLoaded` 连接 `HookRuntime` 并保存进程身份；系统 Hook 等到 `onSystemServerStarting`，使用框架提供的系统服务 ClassLoader 安装。普通包在 `onPackageReady` 按包身份和作用域判断安装，并按包去重；不只接受首包回调，以免共享进程先报告 `android` 时漏掉后续真正的 phone/fused 包。

活跃 Hook 直接使用 API 102 的 `Hooker` / `Chain`：通过 `chain.proceed()` 保留调用链，通过返回值或异常表达拦截结果。旧式 before/after 回调兼容层已删除。`HookInstaller` 统一设置优先级和异常模式，返回原生 `HookHandle`，并在同一个拦截器内记录首次命中；安装过程中已发生的命中不会被后续 installed 状态覆盖。默认使用 `PROTECTIVE`；需要故意向调用方传播拒绝异常的 Hook 显式使用 `PASSTHROUGH`。

`Reflection` 负责字段查找、重载匹配及普通反射调用，匹配时区分静态/实例方法、null、装箱和基本类型扩宽，并拒绝歧义。连接框架后的普通调用使用默认 `FULL` invoker 保留其他 Hook；明确的原始 listener 投递通过 `HookRuntime.invokeOriginalMethod` 使用 `ORIGIN`，两者都解包目标的 `InvocationTargetException`。

模块配置要求框架支持 API 102，不提供 legacy API 82 回退。自动热重载关闭，`onHotReloading` 也拒绝手动热重载，因为定时器、listener 和 Native 状态仍随目标进程存活。安装或更新模块后需要重启受影响的目标进程；system_server 的变更通过设备重启加载。

## 数据与地图

资料库 `files/library/{routes,scenarios,locations}.json` 使用 schemaVersion 1、kind 和 items，Android AtomicFile 负责失败回滚。未知版本/错误种类/损坏导入拒绝；整份导入先验证，ID 冲突复制为新记录；路线限制 100000 点、资料库限制 10000 条和 2000 万字符。GPX 导入通过仓库 `importItems` 在同一临界区内读取、合并和提交，避免覆盖并发保存。资料库 API 不依赖地图 SDK 或 Hook。

历史 `fused_ext` prefs 保留作迁移来源：路线逐条解析，CSV/旧 JSON 位置转为 Double 字段，有效条目使用稳定 ID 以支持迁移重试。无效记录不伪造修复，问题写入 migration issues。历史 Float 精度无法补回。lastKnownLat/Lng 以十进制 Double 字符串存储；旧值读取时迁移。

草稿 `files/route_draft.json` 持久化起终点、当前选点角色、请求代次、路线和相机。每次重新选点、取消、重新规划使旧结果过期；正在规划的草稿重建后显示中断并等待重新规划。高德 path 是候选路线，选择第一个 path 后拼接其全部 steps，保留所有路段并只去掉相邻重复点，不把多个备选 path 串成一条路线。SDK 对象不进入领域模型。确认弹窗持有独立路线快照。

首页仍使用原 AMap 展示/地址搜索交互，业务播放和位置资料持久化已交给 ViewModel/Repository/Runtime。路线列表和单点页已改用新资料库与状态契约；GNSS 保留旧功能入口，但限制在运行的 Xposed/Native 场景下使用。

## 后续扩展边界

GPX 转换器独立于资料库与地图，保留独立轨迹段和 WGS84；导入导出统一为单路线 100000 点、单文件 200000 点、10000 条路线和 2000 万字符，导出拒绝退化路线及 XML 1.0 非法名称。`MovementProfile.smoothMotion` 默认 false，旧 JSON 无此字段仍兼容；`RouteSpeedPlan` 预计算曲率速度上限、前后向加速度限制、距离／时间表，以二分查找和解析积分推进。摇杆幅度经过纯领域死区／响应曲线，配置上限与实际速度分开。圆周周期独立于摇杆速度，松手更新圆心。

标准 Mock 后端可额外注册系统 fused；`FusedMockBackend` 装饰标准后端并使用 `AndroidGmsMockPort`。GMS 只有一个异步定位提交在途，超时或失败降级；禁用请求不等待可能永久 pending 的定位提交，所有未终结的 enable/disable/补偿操作仍被跟踪。在旧模式操作全部终结之前保留所有权、阻止新启用，避免迟到停止关闭替代会话。所有运行命令共用终态收尾：STOPPED 清除中断标记，ERROR 保留恢复信息，两者均关闭前台服务。运行资源记录不参与备份。`StartupBurst` 捕获场景和启动时间；服务收到重排信号后采用五次 80ms 目标间隔，退出运行态／替换场景作废。

`DiagnosticJournal` 原子保留最多 300 条及 300000 字符，位于 `noBackupFilesDir`。Controller 将诊断轮次批量送入有界 `DiagnosticWriter`，由独立 IO worker 写盘；定位运行锁不等待磁盘。HOOK_POINT 按明细内容去重，普通 stage 保留故障及恢复切换。容量含写入中事件，满队列整批拒绝并唤醒重试；写失败保留已接受事件并在内存报告。停止及导出在运行锁之外 flush，正常 tick 和摇杆命令不等待写盘；强杀进程仍可能丢失尚未落盘的事件。导出通过 SAF，不向外部服务传送。明确缺失的 Provider 可有限恢复，权限／普通异常不会循环重试。

首页搜索使用 300ms 防抖及请求代次校验，清空、选中和销毁视图后旧响应失效；手绘预览逐帧合并并限制为 512 个点，保存保留完整采样。

地图 SDK 在用户确认隐私说明后初始化。Bugly 崩溃上报是独立的默认关闭选项，使用安装级随机标识；授权及标识不参与备份。关闭会停止新增崩溃采集和自动任务，但 Bugly 已启动的独立上传线程及重试可能继续，重新启动应用后完全停用。关闭后同进程再次开启仅保存选择，下次启动生效。授权状态独立于 SharedPreferences 的暂时内存值，持久化及可选 SDK 初始化失败均有独立处理。

当前未提供按 App 场景、SharedMemory、ptrace。Native 是 Xposed 外的一层可选传感器能力，不是无需框架的第三套定位发布器。当前固定偏移实现未做 ROM 白名单认证，始终标为 experimental。

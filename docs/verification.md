# 多后端升级验收记录

验收日期：2026-09-14。目标依据：参考目录中的《Codex Goal：LocationService 多后端定位架构升级、无 Root 模式与核心问题修复》。本轮最终代码检查点为 `6a8ecc6`；随后只提交文档。实现提交见 [提交清单](implementation-commits.md)，核心文件与调用关系见 [架构](architecture.md)。

## 证据等级

- **SOURCE VERIFIED**：检查了实现、配置或控制流，不能代替真实 Android 行为。
- **BUILD VERIFIED**：本机 Gradle 编译、lint 或打包成功，不能证明 Hook 命中或定位消费者接受样本。
- **UNIT TEST VERIFIED**：JVM 测试通过；Android Provider 使用可注入端口替身，身份鉴权使用纯策略测试。
- **DEVICE VERIFIED：未完成任何项目**。`adb devices -l` 没有列出设备，没有执行 instrumentation、模拟器或真机验收。

## Goal 验收映射

表中路径缩写与 [架构](architecture.md) 一致。功能均表示已实现的源码路径，不表示真机效果已认证。

| 要求 | 实现和证据 | 当前边界 |
|---|---|---|
| 同一单点/路线使用多个后端 | `core/.../backend/LocationBackend.kt`、`runtime/ScenarioController.kt`；`ScenarioControllerTest`、`MockProviderBackendTest` | SOURCE / BUILD / UNIT TEST VERIFIED；真实 Xposed 与标准 Provider 对比待设备 |
| 无 Root 开始/暂停/恢复/停止 | `app/.../backend/mock/{MockProviderBackend,AndroidMockProviderPort}.kt`；权限拒绝、部分创建失败、发布失败、清理重试测试 | 后端不调用 Root/Xposed/Native；App APK 仍打包 Xposed 模块；AppOps 和 ROM Provider 行为待设备 |
| 保留 Xposed 核心发布 | `app/.../backend/xposed/XposedBackend.kt`、`xposed/.../hook/{RemoteCommandHandler,LocationTicker}.kt`；`SnapshotTest`、`PublicationLeaseTest` | 编译与样本策略通过；不能据此宣称原有全部 Hook 在设备上无回归 |
| Native 隔离 | `app/.../backend/native/`、`xposed/src/main/cpp/{main,sensor_hook}.cpp` | SOURCE / BUILD VERIFIED；需要 Root + Xposed + arm64 + opt-in；固定符号/偏移仍属实验性 |
| 地图起终点、完整路径、确认与启动 | `AMapRoutePlanner.kt`、`RouteEditViewModel.kt`、`RouteEditFragment.kt`；`RouteDraftTest` | 测试覆盖多步拼接、空结果、过期回调、取消、相机/草稿重建；高德在线结果、点击/旋转/长路线绘制待设备 |
| UI 重建不拥有播放任务 | `ScenarioRuntime.kt`、`ScenarioService.kt`、`ServiceStartGate.kt`；`ScenarioControllerTest`、`ServiceStartGateTest` | 服务持有 tick，应用作用域持有控制器；进程死亡显示中断并清理；真实后台/电源/服务重建行为待设备 |
| Double 坐标和版本迁移 | `CoordinatePreferences.kt`、`LibraryRepositories.kt`、`core/.../repository/Repositories.kt`；`CoordinatePreferencesTest`、`LegacyLibraryMigrationTest`、`RepositoryTest` | Double 精确往返、旧 Float/CSV/JSON 迁移、损坏导入与失败写入保护通过；旧 Float 损失不能恢复 |
| 独立领域模型与播放 | `core/.../{geo,scenario,playback}/`；`PlaybackEngineTest`、`CoordinateTransformTest` | 实际经过时间、冻结快照、单次/闭合循环/往返、跨日期变更线/坐标转换；不加入默认噪声 |
| Hook 作用域与安装诊断 | `xposed/.../hook/scope/`、`FakeLocation.kt`；`HookScopeResolverTest` | 精确进入条件和 supported/installed/matched/skipped/failed；TARGET_APP 明确跳过，PerAppScenario 不可用 |
| 鉴权失败立即返回 | `RemoteCommandHandler.kt`、`security/CommandSecurity.kt`、`utils/BinderUtils.kt`；`CommandSecurityTest` | UID/完整包名、错误身份、失败握手、坏命令、重放/过期、peer 权限测试通过；真实 Binder 冒用测试未执行 |
| 清理、失败与中断一致性 | `ScenarioController.kt`、`ScenarioRuntime.kt`、`MockProviderBackend.kt`；相关 controller/backend tests | 失败清理保留资源实例并阻止切换，允许重试；provider 所有权与 runtime 状态不参与备份迁移 |
| 资料库操作与 UI 分层 | `Repositories.kt`、`LibraryViewModel.kt`、`PointViewModel.kt`、`MockServiceViewModel.kt`；repository/route/location tests | 创建、读写、删除、复制、JSON 导入导出、收藏/重命名/路线模式；GPX 延后；首页仍保留地图展示/地址搜索代码 |
| Capability 与诊断 | `RuntimeState.kt`、`RuntimeFragment.kt`、各 backend `diagnose()`、`LogRateLimiter.kt` | 后端/阶段/结果/原因/建议和限量诊断；页面只汇总 system_server Hook 状态，peer 明细未聚合 |
| 测试与 PR 门禁 | `.github/workflows/{build-apk,build-pr-apk}.yml` | 本机同序命令通过、YAML 解析通过；远端 GitHub Actions 未运行 |
| 确定性构建输入 | `app/build.gradle.kts`、`ReleaseVersionTest` | 不查询公网 IP/hostname/本机路径/时间/Git；使用显式版本输入和 AGP 公共 APK API；依赖下载仍可联网，不承诺逐字节复现 |

## 最终本机检查

在最新代码上按 CI 顺序分别执行，下列三个进程退出码均为 0：

```text
gradlew.bat test :nmea:test :core:test :app:testArm64DebugUnitTest :xposed:testDebugUnitTest --console=plain
gradlew.bat lint --max-workers=1 --console=plain
gradlew.bat :app:assembleRelease :app:exportReleaseApks --console=plain
```

环境：Windows、Android Studio JBR / JDK 25、Gradle 9.7.0、AGP 9.3.1、Kotlin 2.4.10、SDK 37、NDK 28.2.13676358、CMake 3.31.6。设置 `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED`。这些是增量验证，部分未变化任务显示 UP-TO-DATE；不是清空缓存后的构建。

本地日志（`build/` 为忽略目录，不随 Git 提交）：

- `build/final-tests-verification.log`
- `build/final-lint-verification.log`
- `build/final-release-verification.log`
- `build/service-notification-verification.log`：最新服务/备份代码的 Arm64Debug 构建。

| 单元测试报告目录 | 用例 | 失败 / 错误 / 跳过 |
|---|---:|---|
| `core/build/test-results/test/` | 26 | 0 / 0 / 0 |
| `nmea/build/test-results/test/` | 2 | 0 / 0 / 0 |
| `app/build/test-results/testArm64DebugUnitTest/` | 33 | 0 / 0 / 0 |
| `xposed/build/test-results/testDebugUnitTest/` | 22 | 0 / 0 / 0 |
| 合计（不重复累计 flavor） | **83** | **0 / 0 / 0** |

83 包含已有测试及两个模板加法测试，不表示新增 83 个测试。NMEA 原来的 main 入口现在由 Gradle JUnit 测试调用既有句子回归向量，并核对改写后校验和。`test` 也执行 app 的其他 flavor；上表仅以 Arm64Debug 计一次 app 用例。

最终完整 `lint` 生成的 appDebug 报告为 **0 error / 271 warnings**，xposed 为 **0 / 9**，system-api 为 **0 / 0**；均无 fatal。较早的 arm64Debug 报告有 186 warnings，未用于替代最终完整 lint 结果。警告尚未清零，含 Bugly 4.1.9.3 的 x86_64 `libBugly_Native.so` 不满足 16 KB 对齐；因此不能宣称所有 16 KB 页设备兼容。具体见各模块 `build/reports/lint-results-*.xml`。

曾把测试、lint 和构建放入同一 Gradle 调用时，lint Kotlin FIR 分析器抛内部异常；随后独立单 worker lint 成功。CI 已改成顺序三步，未关闭检查。该现象不证明底层异常根因已经解决，也不把失败的合并命令计为通过。

## 构建产物

| 导出文件（`app/build/outputs/distribution/`） | ZIP 中实际 Native ABI | 状态 |
|---|---|---|
| `LocationService-v1.1.0-all.apk` | arm64-v8a、x86_64 | BUILD VERIFIED |
| `LocationService-v1.1.0-arm64.apk` | arm64-v8a | BUILD VERIFIED |
| `LocationService-v1.1.0-x86_64.apk` | x86_64 | BUILD VERIFIED |

已读取 ZIP 内容并断言上述 ABI 集合；不存在 x64 flavor 混入 arm64 的问题。Release 元数据为 versionName `1.1.0`、versionCode `1790000000`。本地没有配置 Release 签名，源产物为 `*-release-unsigned.apk`，导出的三个 APK 也未签名。安装验收应使用已签名产物。此次没有执行发布、推送、安装或系统注入。

## 待设备验收清单

| 场景 | 要验证的结果 |
|---|---|
| 无 Root 标准模式 | 开发者选项选择本应用后，GPS/Network 消费者接收单点与路线；Mock 标记保留；拒权、撤权、停止与失败清理符合提示 |
| 路线与 UI | 起终点/POI/取消/重规划、多步及长路线、高德异常；旋转、Fragment 重建与编辑确认快照一致 |
| 服务生命周期 | 退到后台、熄屏、销毁 Activity、停止通知、系统杀进程、重启应用；状态真实、无旧服务覆盖新会话、无资源残留 |
| Xposed | 升级模块并重启后，握手、统一样本、system_server/phone/fused/vendor 命中；peer UID 1000 身份、重放拒绝、App 死亡后 5 秒租约到期 |
| Native | 按 ROM/ABI 验证安装失败回滚、符号存在与真实行为；停止禁用，系统重启卸载 Hook；不扩展隐藏或检测规避 |
| 数据 | 真实旧安装升级、损坏资料库、SAF 导入导出与备份恢复；无坐标二次量化、草稿和保存路线一致 |
| 兼容性与性能 | Android 12 起的代表系统、厂商电源策略、IPC 延迟/耗电/长路线内存、16 KB 页环境；目前没有量化性能结论 |

## 后续优先级

1. **P0，收益高 / 成本中 / 风险低：设备验收与自动化回归。** 先覆盖标准 Provider 与前台服务，再覆盖 Xposed/Binder；补 Android instrumentation 和长路线基准，把当前“源码支持”升级为设备证据。
2. **P1，收益高 / 成本中 / 风险中：兼容性与依赖治理。** 处理 Bugly 16 KB 对齐和高价值 lint 警告，建立 ROM/Hook 支持表与 peer 诊断汇总；Native 继续显式实验性。
3. **P2，收益中 / 成本中 / 风险中：可选 GMS 与 GPX 适配。** 依托现有 Backend/Repository 增量接入；GMS 需要可用环境和真实消费者验证，GPX 保持明确坐标系及导入限额，不改播放引擎。

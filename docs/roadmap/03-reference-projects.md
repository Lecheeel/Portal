# 03 · 参考项目：学什么、怎么学、别学什么

> 目的：把 10 个项目拆成“可借鉴的算法/结构/功能”和“不能直接拿的代码”。
> 许可证是硬约束，先看第 1 节，再看具体项目。

---

## 1. 借鉴级别与许可证

| 级别 | 含义 |
|---|---|
| `CODE` | 许可证兼容，可直接复用（保留 NOTICE/版权） |
| `ALGORITHM` | 只学算法/公式，自己实现 |
| `IDEA` | 只学产品/架构思路 |
| `NONE` | 不借鉴，或明确不适合 Portal |

| 项目 | 许可证 | 可复用级别 | 备注 |
|---|---|---|---|
| FakeGPS-next | Apache-2.0 | `CODE`（保留 NOTICE） | 最适合直接参考/复用 |
| LocationSpoofer | GPL-3.0 | `ALGORITHM`/`IDEA` | 复制实现会传染 GPL |
| kail_location | GPL-3.0 | `ALGORITHM`/`IDEA` | 原生注入/隐藏可参考，不能直接抄 |
| AnyWhere | GPL-3.0 | `ALGORITHM`/`IDEA` | 隐藏 Hook、detector 清单 |
| HideMockLocation | 无 LICENSE | `IDEA` | 默认保留所有权利，不能抄代码 |
| hlocation-release | 商业闭源 | `IDEA` | 只有数据平面/契约思路 |
| SSLAndriodTest | 无 LICENSE | `IDEA` | 最小 Fused/反射样本 |
| GoGoGo | GPL-3.0 | `IDEA` | 基础款/祖本 |
| location-by-youself | AGPL-3.0 | `NONE`/`IDEA` | 小而干净，但 AGPL |
| Portal | 无 LICENSE | — | 先解决自己的许可证 |

> 结论：**FakeGPS 是唯一可以“看代码并复用”的首选参考。** 其他项目以算法/结构参考为主。

---

## 2. 能力矩阵（只列与 Portal 相关的）

| 能力 | FakeGPS | LocationSpoofer | kail | Portal 现状 | Portal 目标 |
|---|---|---|---|---|---|
| 无 Root Mock | 有 | 无（强制 Root） | 有（Developer 模式） | 有 | 保留 |
| Root/Xposed 系统层 | 可选 LSPosed | 强制 Root+LSPosed | 4 种模式 | 有 Xposed | 保留/增强 |
| per-app 路由 | 有（system_server） | 部分（坐标系/scope） | scope/沙盒 | 无 | P5 做 |
| WiFi 环境 | 采集回放 + BSSID 遮蔽 | 环境库 + IDW | 伪造 | 空列表 | P2 重写 |
| 基站环境 | 采集回放（GSM/WCDMA/LTE/NR） | 完整蜂窝 | 伪造 | CDMA 空小区 | P2 重写 |
| GNSS 状态 | 多星座合成 | 多星座 + NMEA | 多星座 | 随机 BDS | P3 重写 |
| NMEA | 部分 | 全量拼装 | 有 | 双 Hook/不完整 | P3 单一生成器 |
| 传感器 | 步频/加速度 | 无 | native 模拟 | 空/清零 | P4 重写 |
| 相机/NFC | 无 | 无 | 有 | 无 | 非目标 |
| 沙盒 | 无 | 无 | NewBlackbox | 无 | 非目标 |
| 数据平面 | Binder/文件 | 文件 | socket/Binder | Binder | P5 抽象 |
| 设备测试 | 有 CI/部分 | 无 | 无 | 无 | P0 建 |

---

## 3. FakeGPS-next（Apache-2.0，首选参考）

### 3.1 为什么最值得学

- 它和 Portal 的架构最接近：system_server 层做分发，target app 不是主战场。
- 它把“环境一致性”做成了可采集、可回放、可重标定的管线，而不是随手返回假值。
- 它有 per-app 路由、GNSS 合成、物理运动、步频、发布 CI 和自审文档。

### 3.2 具体可借鉴

| 主题 | 参考文件/做法 | Portal 落点 |
|---|---|---|
| per-app 路由 | `FakeGPS-next-main/.../core/data/MultiTargetRepository.kt`、`hook/TargetRuleResolver.kt`：`(PackageName, UserId)` 联合索引 | P5 `RoutingTable` + system_server 分发 |
| 环境指纹回放 | `core/location/fingerprint/FingerprintCollector.kt`、`FingerprintStore.kt`、`FingerprintReplayer.kt`、`FingerprintCoordinator.kt` | P2 采集/回放管线 |
| RSSI 重标定 | `FingerprintReplayer.rescaleRssi()`：`RSSI_B = RSSI_A - 20·log10(d_B/d_A)`，上限 35 dB | P2 回放/程序化合成 |
| 构造器兼容 | `newCellIdentity()` 按实际 arity 选择 9/10 参构造器，处理 Android 13/14 差异 | P2 基站构造 |
| 原子缓存 | 指纹缓存的 `id/at/fp` 一起替换，避免“新坐标 + 旧指纹” | P2 环境快照 |
| GNSS 合成 | `hook/SyntheticGnssProvider.kt`：BDS/GPS/GLONASS 多星座、CN0 随仰角、`GnssStatus.Builder` 反射 | P3 V1 基线 |
| 运动物理 | `core/location/KinematicsEngine.kt`：三点外接圆曲率 + `v ≤ sqrt(a_lat·R)` + OU 海拔 | P4 轨迹物理 |
| 步频 | `core/location/SensorMockEngine.kt`：fractional step accumulation、cadence/accel | P4 传感器模型 |
| Root 桥 | `core/location/RootSuBridge.kt`：`su -c id` 探测、路径回退、命令拆分 | 已有类似，可按需参考 |
| 权限协调 | `core/location/PermissionCoordinator.kt`：所有注入入口统一裁决 | P5 配置/入口统一 |
| 诊断 | `Diag`/`logFailure`、`CODE_REVIEW.md`、`CHANGELOG.md` | 文档/诊断习惯 |

### 3.3 关键差异（不要照抄）

- FakeGPS 用 root 写 `/data/local/tmp` 导出指纹；**Portal 已有认证 IPC，可直接把环境 JSON 送进 system_server，不需要 root。** 这是 Portal 的结构性优势。
- FakeGPS 的 GNSS 仍是“固定星表 + 随机抖动”，不是轨道模型；Portal 可以做得更好（P3 V2）。
- FakeGPS 部分 per-app 能力依赖 target app scope；Portal 应优先 system_server 分发。

---

## 4. LocationSpoofer（GPL-3.0，算法参考）

### 4.1 强项

- 环境指纹最完整：WiFi/基站/BLE + 环境库 + IDW 插值 + WigLE/OpenCellID。
- NMEA 全量拼装、GNSS 多星座、反 Xposed 栈清洗、per-app 坐标系。
- 单文件 `LocationHooker.kt` 约 5,000+ 行，是“检测面覆盖清单”，不是代码模板。

### 4.2 可借鉴的算法/结构

| 主题 | 参考 | 说明 |
|---|---|---|
| OU 漂移 | `utils/TrajectorySimulator.kt`：随机游走 + accuracy/altitude 漂移 + 步频横向抖动 | P4 `PhysicalNoise` 的公式参考 |
| 环境库 | `data/db/EnvironmentDao.kt`、`utils/EnvironmentScanner.kt`、`WigleClient.kt`、`OpenCellIdClient.kt` | P2 `DATABASE` 模式参考 |
| IDW | `viewmodel/MainViewModel.kt` 的 `1.0 / (safeDist^2)` | 注意：它写在 ViewModel，不在 core；Portal 应放进 `:core` |
| NMEA | `LocationHooker.kt` 的 GGA/RMC/GSA/GSV 拼装与 `satellites` extras | P3 生成器覆盖清单 |
| 反检测 | 调用栈清洗、`Class.forName` 拦截、AppOps/Settings 伪造 | 仅在 Portal 进 target app 时才需要 |
| per-app 坐标 | 按包名配置 WGS84/GCJ02/BD09 | P5 可与 per-app 路由合并 |
| OUI | `MacVendorHelper`/厂商前缀 | P2 BSSID 生成 |

### 4.3 不要照抄

- 5,000+ 行单文件：耦合太高，Portal 应拆成 `:core` 模型 + 小 Hook。
- 强制 Root + LSPosed：Portal 保留无 Root 路径。
- 空/随机环境：它的实现也有随机成分，Portal 应确定性优先。
- GPL 代码直接复制：除非 Portal 采用 GPL。

---

## 5. kail_location（GPL-3.0，原生/隐藏参考）

### 5.1 强项

- 4 种注入模式：Developer Mock、Root ptrace、Xposed APK、NewBlackbox 沙盒。
- 原生注入：`ptrace` + `libfakeloc.so` + `inject.dex`（`InMemoryDexClassLoader`）+ socket 控制。
- 原生传感器：`sensor_simulator.cpp`（约 450 行）、`libStepSensor.cpp`（约 240 行）。
- 原生反检测/隐藏：`libantidetect.cpp`（约 450 行）、`RootHideHook`、`RuntimeAntiDetectionHook`、`/proc/self/maps` 清洗。
- 相机/NFC/麦克风模拟、沙盒，但这些不是 Portal 的目标。

### 5.2 Portal 应学什么

| 主题 | 参考 | Portal 落点 |
|---|---|---|
| 原生传感器 | `app/src/main/cpp/native_hook/sensor_simulator.cpp`、`hook.cpp` | P4/P6 重写 `sensor_hook.cpp` |
| 原生隐藏 | `libantidetect.cpp`、`RootHideHook`、`RuntimeAntiDetectionHook` | P6 实验轨道，默认关闭 |
| 注入架构 | `RootDeployer.kt`、`inject64.cpp`、`inject.dex` 构建 | 只在决定做 target app 注入时参考 |
| 崩溃哨兵 | `InjectionCrashSentinel`、部署前检查 | P6 native 实验 |
| 签名校验 | `PackageSignatureVerifier`、AES 控制通道 | 可参考 IPC 安全 |

### 5.3 不要照抄

- 不要默认做 target app ptrace 注入：破坏 Portal 的 system_server-only 优势，风险和 ROM 差异都大。
- 不要引入沙盒/相机/NFC/麦克风：与定位主线无关，维护成本极高。
- 不要复制 GPL 代码；只学原生 Hook 的符号解析、线程安全、回滚策略。

---

## 6. AnyWhere（GPL-3.0，隐藏清单参考）

### 6.1 强项

- `HideMockHook.java` 约 377 行，是一个“最小完整隐藏集”：
  - `Location.isFromMockProvider`/`isMock`/`setMock`/`setIsFromMockProvider`
  - `getExtras`/`setExtras`/`readFromParcel`
  - `Settings.Secure.getString("mock_location")`
  - `getProviders`/`getAllProviders`/`isProviderEnabled` 清理
  - `GnssStatus` 回调模拟
- `test-detector` 是一个可运行的检测清单（Provider、isFromMockProvider、extras、GpsStatus、getLastKnownLocation、反射字段）。

### 6.2 Portal 应学什么

- P1 的隐藏顺序：先 Location 字段/extra，再 Settings，再 Provider 列表，再 GNSS。
- P0 的 detector 检查项：直接照着它的检测项扩展。
- `getProvider()` 归一化：未知 provider 名统一映射到 `gps`。

### 6.3 不要照抄

- 它在 target app 进程里 Hook `Settings.Secure`，需要 target scope；Portal 应优先 settings provider scope。
- GPL 代码不能直接复制。
- 它的 GNSS 模拟是 Handler 循环直接回调，可能与系统回调冲突；Portal 应在系统回调处改写。

---

## 7. HideMockLocation（无许可证，只学清单）

### 7.1 强项

- 约 170 行，单一职责，libxposed API 101，`META-INF/xposed/module.prop` + `java_init.list` + `scope.list`。
- 深挖 `Location` 内部：
  - `HAS_MOCK_PROVIDER_MASK` / `mFieldsMask` 清位
  - `mExtras` 中 `mockLocation` 置 false
  - `getProvider()` 未知 provider 归一化
  - `SettingsProvider.call(GET_secure)` + `Settings.Secure.getStringForUser`
- README 列出了 AppOps/Settings 的完整 Hook 清单。

### 7.2 重要坑

- 代码里 `hookAppOpsService` 和 `hookAppOpsMethods` 两个入口**都是注释掉的**；README 的 Hooked Methods 是“清单”，不是“已启用”。
- 没有 LICENSE 文件，默认保留所有权利；不能复制代码，只能照着清单自己实现。

### 7.3 Portal 应学什么

- P1 的隐藏清单与字段级清洗。
- P6 的 libxposed 模块元数据形态。
- `SettingsProvider` scope 的最小实现。

---

## 8. hlocation-release（商业闭源，架构参考）

### 8.1 强项

- 不是源码仓库：只有公共契约、签名元数据、OpenCellID 数据、发布端点。
- README 的架构值得读：
  - Binder 控制面 + SharedMemory 数据面
  - 版本化快照、配置/位置独立序号
  - system_server 主路径 + 目标应用兜底
  - 5 秒租约、原子快照
  - protected runtime（Portal 不需要）
- `public-contracts` 的 `LocationFacade`/`ScenarioStore` 是“稳定契约”的写法参考。
- cell 数据来自 OpenCellID，CC BY-SA 4.0，带 attribution。

### 8.2 Portal 应学什么

- P5 的 `SampleStore`/`ConfigStore` 抽象、版本化、原子切换。
- 配置和位置分开仲裁，避免路线切换撕裂。
- 数据许可/归属的写法（如果用真实数据）。

### 8.3 不要照抄

- 闭源，不能复制代码。
- SharedMemory 对 Portal 当前规模是过度设计；先 Binder，测量后再决定。
- protected runtime/许可证门禁不是 Portal 的目标。

---

## 9. 其他项目

### SSLAndriodTest（无许可证）

- 1,536 行，三 Provider + GMS Fused Mock + 反射清标记 + 300ms tick + burst push。
- 可看：Fused Mock 的 `setMockMode`/`setMockLocation` 调用顺序、启动 burst、权限拦截弹窗。
- 不能抄：无许可证；实现很薄，缺少长期结构。

### GoGoGo（GPL-3.0）

- 这一类项目的“祖本”，基础款：Mock + 百度地图 + 路线 + 摇杆。
- 不建议借鉴实现；可看产品功能边界与 UI 习惯。

### location-by-youself（AGPL-3.0）

- 4,766 行，无 Root，9 个单测，设计系统文档；结构干净。
- 可看：单点/路线/操场跑步的领域拆分；但 AGPL，不能抄。

---

## 10. 最值得抄的 10 个点（按优先级）

1. FakeGPS 的 `FingerprintReplayer`：采集回放 + RSSI 重标定 + 构造器兼容。
2. FakeGPS 的 `MultiTargetRepository`/`TargetRuleResolver`：`(package, user)` per-app。
3. FakeGPS 的 `SyntheticGnssProvider`：多星座 + CN0 随仰角（作为 V1 基线）。
4. FakeGPS 的 `KinematicsEngine`：曲率限速 + OU 海拔。
5. FakeGPS 的 `SensorMockEngine`：步频 fractional accumulation。
6. LocationSpoofer 的 `TrajectorySimulator`：OU 噪声/精度/海拔漂移。
7. LocationSpoofer 的 NMEA 拼装清单：GGA/RMC/GSA/GSV + satellites extras。
8. AnyWhere/HideMock 的隐藏清单：Location 字段、Settings、Provider 列表。
9. kail 的 `sensor_simulator.cpp`：原生传感器写法。
10. hlocation 的版本化契约/数据平面思路。

---

## 11. 明确不要抄的

| 做法 | 原因 |
|---|---|
| 空 WiFi 列表 | “WiFi 开着 + 零 AP”物理矛盾 |
| `02:00:00:00:00:00` BSSID | AOSP 权限不足哨兵值，本身就是异常 |
| CDMA 降级 + 空小区 + `Int.MAX_VALUE` | 与 SIM/operator/phone type 矛盾 |
| 随机 `satelliteList.shuffled()` | 秒级统计即可识别 |
| 双 NMEA Hook | 行为不确定，容易双写/双屏蔽 |
| 5,000 行单文件 Hook | 不可维护 |
| target app 默认注入 | 破坏 Portal 的架构优势 |
| 沙盒/相机/NFC/麦克风 | 与定位主线无关 |
| 复制 GPL/无许可证代码 | 法律风险 |
| 承诺绕过 Play Integrity/TEE | 做不到，且误导用户 |

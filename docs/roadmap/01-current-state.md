# 01 · 当前状态、优点与缺口

> 基线：`v1.7.6` / `0bfd65e` / 2026-11-06  
> 本文只描述**代码与文档可证明的现状**；未标 `DEVICE VERIFIED` 的行为都不算已验证。

---

## 1. 项目快照

| 项 | 现状 |
|---|---|
| 模块 | `:app`、`:core`、`:xposed`、`:nmea`、`:system-api` |
| 定位 | 基于 `fuqiuluo/Portal` 的 Android 定位模拟应用，主打多后端、路线播放、摇杆、无 Root/Root/Xposed |
| UI | `:app` 使用 Fragment + XML（`ui/**`），不是 Compose |
| 核心 | `:core` 是纯 Kotlin/JVM：坐标、场景、播放、运行状态、仓库接口、诊断 |
| Hook | `:xposed` 是 Android library，最终打进 app APK；legacy Xposed API 82，manifest 声明 `xposedmodule` |
| IPC | `sendExtraCommand("fused_ext", ...)` + Bundle 原始类型；token/sequence/UID 鉴权；5 秒租约 |
| Native | `:xposed` 内的 `liblocationext.so`，Dobby；由 app 用 root 复制到 `/data/local/ext-lib` 后 `System.load` 进 system_server |
| 最低系统 | Android 12 / API 31；target/compile 37 |
| 测试 | 约 196 个 JVM 用例；instrumentation 有代码，但没有设备验收结论 |
| AOSP 参考 | `refs/` 覆盖 Android 7–17，最近核验 2026-10-16 |
| 发布 | GitHub Actions 三套：PR 门禁、Release 构建、UI 验证；Beta/Stable 同 APK 晋升 |
| 许可证 | 仓库内没有 `LICENSE`；上游 `fuqiuluo/Portal` 的许可证未在本地确认 |

---

## 2. 当前运行时结构

```text
┌─────────────────────────────────────────────────────────────┐
│ :app                                                        │
│  Fragment/XML UI → ViewModel → ScenarioController(:core)    │
│      │                                                      │
│      ├─ MockProviderBackend → LocationManager.addTestProvider│
│      ├─ FusedMockBackend   → GMS setMockMode/setMockLocation │
│      ├─ XposedBackend      → CommandClient                  │
│      └─ NativeBackend      → root copy + System.load        │
└───────────────────────────┬─────────────────────────────────┘
                            │ sendExtraCommand("fused_ext")
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ system_server (:xposed)                                     │
│  RemoteCommandHandler → FakeLoc（全局可变状态）              │
│  LocationServiceHook / LocationManagerHook                  │
│  LocationProviderManagerHook / GnssHook / LocationNMEAHook  │
│  WlanHook / TelephonyHook / SystemSensorManagerHook         │
│  TelephonyRegistry / MIUI / OPLUS 适配                      │
└───────────────────────────┬─────────────────────────────────┘
                            │ Location / GnssStatus / NMEA / WiFi / Cell
                            ▼
                     target apps（默认不注入）
```

关键点：

- **坐标主路径**：`ScenarioController` 驱动 `PlaybackEngine` 生成 `LocationSample`，经后端发布；Xposed 后端把样本通过认证 IPC 送进 system_server，由 Hook 改写系统分发结果。
- **环境路径**：WiFi/基站/GNSS/NMEA/传感器各自有 Hook，但**没有统一的环境状态模型**；每个 Hook 自己决定返回什么。
- **target app**：`HookScopeResolver` 明确把 `TARGET_APP` 标记为 `skipped`，当前不在目标应用进程注入。这是 Portal 最大的架构优势之一，必须保留。
- **native**：只作为 Xposed 的“装饰层”，不是独立的定位发布器；目前传感器实现是实验性的空壳。

---

## 3. 代码地图

### 3.1 `:core`（纯 JVM，应该继续扩大）

| 文件 | 职责 |
|---|---|
| `core/.../geo/Coordinates.kt`、`CoordinateTransform.kt` | WGS84/GCJ02/BD09 与坐标类型 |
| `core/.../scenario/Scenario.kt` | `Scenario`、`Route`、`MovementProfile` |
| `core/.../playback/PlaybackEngine.kt` | 确定性播放：实际经过时间、单次/循环/往返、摇杆/圆周 |
| `core/.../playback/RouteSpeedPlan.kt` | 曲率/加速度限制的确定性速度计划 |
| `core/.../runtime/ScenarioController.kt` | 后端生命周期、状态机、失败清理、发布 tick |
| `core/.../runtime/RuntimeState.kt` | 运行状态与能力/诊断结构 |
| `core/.../backend/LocationBackend.kt` | 后端接口、`Capability`、`BackendResult` |
| `core/.../repository/Repositories.kt` | 路线/场景/位置仓库接口 |
| `core/.../runtime/DiagnosticJournal.kt`、`DiagnosticWriter.kt` | 有界诊断与异步落盘 |

### 3.2 `:xposed`（适配层，当前偏重）

| 文件 | 职责 | 备注 |
|---|---|---|
| `xposed/.../hook/FakeLocation.kt` | Xposed 入口、进程作用域分发 | `TARGET_APP` 明确跳过 |
| `xposed/.../hook/hooks/LocationServiceHook.kt` | `ILocationManager` 主 Hook、Provider 注册、GNSS 状态、监听器 | 约 900 行，含 GNSS 模型，职责过重 |
| `xposed/.../hook/hooks/LocationManagerHook.kt` | 客户端 `LocationManager` 回调注入 | 与主 Hook 有重叠 |
| `xposed/.../hook/hooks/provider/LocationProviderManagerHook.kt` | `LocationProviderManager` 分发改写 | 系统层主路径 |
| `xposed/.../hook/hooks/gnss/GnssHook.kt` | GNSS/NMEA 监听器屏蔽 | 与 `LocationNMEAHook` 重叠 |
| `xposed/.../hook/hooks/nmea/LocationNMEAHook.kt` | `onNmeaReceived` 改写 | `enableNMEA` 语义疑似反转 |
| `xposed/.../hook/hooks/wlan/WlanHook.kt` | `WifiServiceImpl` 扫描/连接信息 | 返回空列表 + `02:00:00:00:00:00` |
| `xposed/.../hook/hooks/telephony/TelephonyHook.kt` | 蜂窝/运营商/信号 | 默认 CDMA 降级 + 空小区 |
| `xposed/.../hook/hooks/sensor/SystemSensorManagerHook.kt` | 传感器监听器 | `onSensorChanged` 空实现 |
| `xposed/.../hook/RemoteCommandHandler.kt` | 认证命令处理、配置、样本、Native 加载 | 命令与配置集中在一个文件 |
| `xposed/.../hook/security/*` | token、sequence、租约、样本 wire | 结构清晰，可保留 |
| `xposed/.../hook/scope/*` | 进程作用域、Hook 状态 | 精确，可扩展 |
| `xposed/src/main/cpp/sensor_hook.cpp` | Native 传感器 Hook | 当前把所有值写成 0/-1 |

### 3.3 `:app`

| 目录 | 职责 |
|---|---|
| `backend/` | Mock/Fused/Xposed/Native 四个后端 |
| `runtime/` | 前台服务、启动门、命令生命周期 |
| `ui/` | 首页、单点、路线、摇杆、设置、GNSS、诊断 |
| `data/` | 原子文档存储、坐标偏好、仓库实现 |
| `amap/`、`bdmap/` | 地图适配 |
| `update/` | 版本检查、下载、通道策略 |
| `privacy/` | SDK 同意与隐私开关 |

---

## 4. 现在真正强的部分（不要为了“功能多”丢掉）

1. **单一 system_server 真相源。** 不在 target app 里放 Hook，兼容性和隐蔽性都更好；per-app 也可以在 system_server 做。
2. **`:core` 可测试。** 播放、状态机、坐标、仓库、GPX、诊断都有 JVM 测试；这是其他项目没有的底子。
3. **IPC 安全模型。** `CommandSecurity` 的 UID/完整包名/token/sequence/时限/peer 白名单，比大多数同类项目严谨。
4. **5 秒发布租约。** app 死亡后不会无限期保留假位置；这是很实用的故障隔离。
5. **Hook 作用域与状态。** `HookScopeResolver` 精确到 process/UID/SDK；`HookStatusRegistry` 区分 supported/installed/matched/skipped/failed。
6. **诊断与终态清理。** `ScenarioController` 的失败清理、所有权、恢复标记、诊断日志，是长期维护的基础。
7. **AOSP 参考目录。** `refs/` 覆盖 7–17，且有校验脚本；这是 Hook 兼容性的资料库。
8. **发布流程。** Beta/Stable 同 APK 晋升、签名/ABI/versionCode 校验，比多数同类项目正规。

---

## 5. 现在“说了但没做到”的部分

| 声明 | 实际 | 影响 |
|---|---|---|
| README：“Root + Xposed Native 扩展 · 传感器模拟（实验性）” | `SystemSensorManagerHook` 的 `onSensorChanged` 是空实现；`sensor_hook.cpp` 把值写成 `-1`/0 | 不是“实验性模拟”，而是“把传感器废掉”；必须在文档和 UI 上降级表述 |
| README：“提交前反射清除模拟标记（实验性）” | `MockFlagExperiment` 只改本地对象；Android 提交后仍可能重新标记 | 不能当作“隐藏 mock”；只能算本地实验 |
| “用户勾选 target app 后生效” | `TARGET_APP` 分支明确 `skipped` | 用户勾选 target app 不会生效，UI/文档需要说清楚 |
| “Provider 列表已处理” | `getProviders`/`getAllProviders`/`hasProvider` 被注释 | `fused_ext`/test provider 可能暴露 |
| “NMEA 模块” | `GnssHook` 与 `LocationNMEAHook` 双重 Hook；`enableNMEA=true` 时反而跳过原方法 | 行为不确定，可能双写/双屏蔽 |
| “GNSS 模拟” | 随机取 BDS 子集，全部标成 BEIDOU，每次回调都变 | 物理上不成立，容易被简单统计识别 |
| “无 Root / Root / Xposed” | 无 Root 模式无法隐藏 mock 标记；Root/Native 仍需人工配置 | 需要明确每种模式的能力边界 |

---

## 6. 缺陷与缺口清单

严重度：`S1` 会直接暴露/破坏核心能力；`S2` 明显降低真实性或可维护性；`S3` 长期债务/体验问题。

| ID | 领域 | 问题 | 证据 | 影响 | 计划 |
|---|---|---|---|---|---|
| D01 | 隐藏 | 没有 Hook `AppOpsService`，`OP_MOCK_LOCATION` 可直接查 | `xposed/**` 无 AppOpsService Hook；`MockFlagExperiment` 只改本地对象 | S1：目标 App 一次 `checkOp` 就知道 | P1-02 |
| D02 | 隐藏 | 没有 Hook `Settings.Secure.mock_location` / 开发者选项 | 无 `SettingsProvider` scope、无 `getStringForUser` Hook | S1：同上，且可判断“设备开了开发者选项” | P1-03 |
| D03 | 清洗 | Provider 列表/属性 Hook 被注释 | `LocationServiceHook.kt` 中 `getProviders`/`getAllProviders`/`hasProvider` 注释块 | S1：`fused_ext`/test provider 暴露 | P1-04 |
| D04 | 清洗 | 新建 Location 的 extras/MSL/不确定度不完整 | `LocationSample.toLocation()` 只设置基础字段；无 `satellites`/`meanCn0`/`mslAltitude`/`elapsedRealtimeUncertaintyNanos` | S2：高级检测可看出“GPS 没有卫星 extras” | P1-01 |
| D05 | 清洗 | 没有 `LocationAvailability` 合成 | 代码中无相关 Hook | S2：可用性回调与 provider 状态可能矛盾 | P1-05 |
| D06 | 环境 | WiFi 扫描返回空列表；BSSID 为 `02:00:00:00:00:00` | `WlanHook.kt` | S1：WiFi 开启 + 零 AP 物理矛盾；哨兵值本身就是异常 | P2-04 |
| D07 | 环境 | 默认 CDMA 降级 + 空 CDMA 小区 + `Int.MAX_VALUE` | `TelephonyHook.kt`；`FakeLoc.needDowngradeToCdma = true` | S1：与 SIM/operator/phone type 矛盾 | P2-05 |
| D08 | GNSS | 随机 BDS 子集、全 BEIDOU、每次回调变化 | `LocationServiceHook.kt` 中 `satelliteList.shuffled()`、`CONSTELLATION_BEIDOU` 硬编码 | S1：星座/卫星集合统计即可识别 | P3-01/02 |
| D09 | NMEA | 两套 Hook 叠加；`enableNMEA` 分支疑似反转；GSA/GSV 保留真实 | `GnssHook.kt` 与 `LocationNMEAHook.kt`；`BaseLocationHook.injectNMEA()` 只改 GGA/GNS/RMC | S1：GGA 假、GSV 真，或全部被屏蔽 | P3-03/04 |
| D10 | 传感器 | Xposed 层空实现；native 写 0/-1 | `SystemSensorManagerHook.kt`；`sensor_hook.cpp` | S1：传感器被破坏而不是模拟 | P4-04/05 |
| D11 | Provider | gps/network/fused 发布同一份样本、同一精度/时间 | `MockProviderBackend` 对每个 provider 发布同一 `fix`；`LocationSample` 固定 accuracy/altitude | S1：三路交叉比对即可发现完全一致 | P4-01/03 |
| D12 | 功能 | 没有 per-app 路由；`Capability.PER_APP_SCENARIO` 不可用 | `FakeLocation.kt` TARGET_APP skipped；`Capability` 定义存在但后端未实现 | S2：缺少高价值功能 | P5-01～05 |
| D13 | 隐蔽 | legacy Xposed manifest `xposedmodule` 任意 App 可查 | `app/src/main/AndroidManifest.xml` 的 `xposedmodule`/`xposedscope` | S1：不用 Hook 就能识别“装了 Xposed 模块” | P6-01/02 |
| D14 | 维护 | `LocationServiceHook` 约 900 行，GNSS 模型混在 Hook 里；`FakeLoc` 是全局可变状态 | `LocationServiceHook.kt`、`FakeLoc.kt` | S2：改一处容易影响全部；测试困难 | P0-06/P5-07 |
| D15 | 架构 | 没有统一的环境模型；逻辑散落在 Hook/ViewModel | `WlanHook`、`TelephonyHook`、`LocationServiceHook`、`MainViewModel` 各自实现 | S1：无法保证 WiFi/基站/GNSS/传感器互相自洽 | P2-01/P4-05 |
| D16 | 验证 | 没有 detector / 设备回归基线 | 无 `:detector` 模块；`verification.md` 明确 DEVICE 未完成 | S1：所有“隐藏/伪装”都不可验收 | P0-01～03 |
| D17 | 法务 | 没有 `LICENSE`，上游许可证未确认 | 仓库根目录无 `LICENSE` | S2：无法合法复用/被复用 | P0-05 |
| D18 | 体验 | 实验能力默认值不一致：`experimentalClearMockFlag` 默认 true；Native 默认 off | `Perfs.kt`；`NativeBackend` opt-in | S2：用户以为“已隐藏”，实际没有 | P6-06 |
| D19 | Native | 当前 native 只做“清零传感器”，且安装依赖 root 复制 | `NativeLibraryInstaller.kt`、`sensor_hook.cpp` | S1：实验能力不可用 | P4-04/P6-03 |
| D20 | 体验 | 用户可勾选 target app，但代码明确跳过 | `FakeLocation.kt`；`HookScopeResolver` | S2：行为与 UI 不一致 | P5-05 或文档修正 |
| D21 | 前沿 | 没有网络/IP/时区/locale 一致性 | 代码无相关处理 | S2：服务端风控可直接看出 IP 与 GPS 不一致 | P7-02 |
| D22 | 前沿 | 没有传感器融合/Activity Recognition | 只有空壳传感器 Hook | S2：GPS 在动、计步为 0 等矛盾 | P4-05/P7-03 |
| D23 | 架构 | 数据平面只有 Binder Bundle，没有 `SampleStore` 抽象 | `SampleWire`、`CommandClient` | S3：per-app 多进程扩展时会受限 | P5-06 |
| D24 | 测试 | 测试偏 JVM；没有 Hook 集成/设备回归 | 196 JVM 用例；instrumentation 无设备结论 | S1：Hook 行为没有自动化保护 | P0-02/P6-05 |

---

## 7. 当前证据边界

| 能力 | 源码 | 构建 | 单元测试 | 设备 |
|---|---|---|---|---|
| 标准 Mock Provider | 有 | 通过 | 有（端口替身） | 未完成 |
| GMS Fused Mock | 有 | 通过 | 有（会话/降级） | 未完成 |
| Xposed 系统层 Hook | 有 | 通过 | 仅纯策略 | 未完成 |
| Native 传感器 | 空壳 | 通过 | 无 | 未完成 |
| 环境指纹 | 无 | — | — | — |
| per-app 路由 | 无 | — | — | — |
| detector | 无 | — | — | — |

> 结论：Portal 现在是“**源码/构建/单元测试可信，系统行为未证实**”的状态。任何“已隐藏/已伪装”的说法，在 detector 和设备验证之前都不成立。

---

## 8. 基线指标（用于后续对比）

| 指标 | 当前 |
|---|---|
| JVM 用例 | 约 196（以 `docs/optimization-verification.md` 为准） |
| 设备矩阵 | 0 台有记录 |
| Provider 数 | 2 个必选（gps/network）+ 1 个可选（fused） |
| 默认发布间隔 | 500 ms（`Scenario.intervalMs` 默认 500；`Perfs.reportDuration` 可到 100–1000） |
| 默认精度 | 25 m（`MovementProfile.accuracyMeters`） |
| 默认海拔 | 80 m（固定） |
| GNSS 星座 | 仅 BDS，随机子集 |
| WiFi 环境 | 空列表 + 哨兵 BSSID |
| 基站环境 | CDMA 空小区 |
| 传感器 | 空实现 / 清零 |
| per-app | 无 |
| 环境模型 | 无 |

这些数字不是“目标”，只是后续回归的对照基线。

---

## 9. 2026-11-06 实施进展（P0）

> 本节记录路线图开始实施后的新增内容；上面的快照仍代表 `v1.7.6` 基线。

- **新增 `:detector` 独立应用**：平台 API + 反射，不依赖 `:app`/`:xposed`/`:core`；Run/Results/Export/Settings；C01–C15 全部实现；采样器覆盖 Location、GnssStatus、NMEA、传感器、WiFi、Cell；JSON schema 1。
- **Hook 状态清单**：新增 `HookOwnership` 所有权审计，`get_runtime_status` 返回 `hook_scope`/`hook_expected`/`hook_missing`/`hook_missing_points`；app 诊断增加 `HOOK_OWNERSHIP`；JVM 测试覆盖缺失/失败/完整 scope。
- **仓库护栏**：`scripts/check_module_rules.py` 检查纯 JVM 模块、依赖方向、400 行预算（带存量基线）、文档链接；PR 模板加入任务 ID/证据/许可证检查。
- **P1-01 基础部分提前落地**：`:core` 新增 `LocationFieldPolicy`/`LocationSanitizer`（7 项 JVM 测试），xposed `LocationSample.toLocation()` 补齐垂直/速度/航向精度、elapsedRealtime 不确定度与 satellites/CN0 extras（MSL 仅 Android 14+）。
- **P1-02～P1-05 代码落地（未设备验证）**：`AppOpsHook` 对第三方隐藏 `OP_MOCK_LOCATION`；`SettingsHook` 隐藏 `mock_location`/开发者选项；`ProviderRegistryHook` 过滤 `fused_ext`/test provider；第三方调用方在模拟中看到 `isLocationEnabled=true`（框架无公开 `LocationAvailability`）。
- **P2-01/P2-03 纯 `:core` 基础**：新增 `EnvironmentProfile`/`WifiAp`/`CellTower`/`BleBeacon`/`EnvironmentSource` 与确定性 `ProceduralWifiSource`（约 100 m 网格、固定 seed、RSSI 路径损耗；12 项 JVM 测试）。WiFi Hook（P2-04）尚未接入。
- **P2-04/P2-05 部分落地**：`WlanHook` 已用程序化 AP 生成 `ScanResult`/`WifiInfo`/`DhcpInfo`（仅第三方调用方），并支持 captured profile 回放；CDMA 降级默认关闭，新增 `CellModePolicy` AUTO 策略、`ProceduralCellSource`（LTE/NR 确定性画像）、`CellInfoSynthesis`（第三方 `getAllCellInfo` 反射构造，优先回放捕获 LTE/NR，失败回退原始列表）、`PhoneTypeHooks`（GSM/LTE）、`SimOperatorHooks`（MCC/MNC）与 `simReady=false` 的 NO_SIM/WiFi-only 画像。
- **P2-02 回放源**：新增 `ReplayEnvironmentSource`（RSSI 距离重标定）、`EnvironmentProfileCodec`（JSON 导入导出/schemaVersion）以及 app `EnvironmentCollector`/`EnvironmentStore`（WiFi+Cell+BLE 采集、运行时权限、文件存储、设置页采集/列表/SAF 导出/导入/回放选择）；BLE 回放、删除/重命名、设备验证未做。
- **P2-06 实验库**：新增 `WifiFingerprintDb`（时效、加权质心、不可变更新）与带 source/license/attribution 的 JSON/CSV 导入；真实 WigLE/OpenCellID 下载与开关未做。
- **P3-01/P3-03 纯模型落地**：`:core` 新增确定性多星座 `CatalogGnssSkyModel`；`:nmea` 新增单源 `NmeaGenerator`（GGA/RMC/GSA/GSV/GST/ZDA，校验和与卫星数自洽）。
- **P3-04 部分落地**：`GnssStatus` 与 NMEA 都已从同一 `CatalogGnssSkyModel` 帧生成；`GnssHook` 的重复 NMEA 入口已删除，只剩 `LocationNMEAHook` 一个改写点；raw measurements 仍被屏蔽。
- **P3-05 策略落地**：新增默认关闭的 `RawGnssPolicy`/`RawNavigationPolicy`、`KeplerOrbit`/`DopplerShift` 与 `OrbitalGnssSkyModel`；`GnssStatus`/NMEA 可通过设置页在 ANALYTIC/ORBITAL 间切换，默认 ANALYTIC。Hook 接线未做。
- **P4-01/P4-02 纯 `:core` 基础**：新增 `ProviderProfile`/`StationaryPolicy`/`ProviderMixer`（三路精度/延迟/字段分离、静止无 bearing/speed）与 `PhysicalNoise`（有界可复现 OU 漂移）；`ProviderView` 已可选启用噪声（默认关，设置页开关），传感器未接。
- **P4-03 部分落地**：`ProviderView` / `ProviderMixer` 已接入 Mock、Fused 与 Xposed 三条发布路径，按 provider 输出不同精度/延迟/字段；三路设备交叉验证未做。
- **P4-04 Native 传感器**：`ConvertToSensorEvent` 已按 motion state 生成值并移除 -1/0 清零；仅 arm64 语法检查，未真机/全 ABI 构建。
- **P4-05 纯模型落地**：新增 `MotionMode`/`MotionState`/`SensorFrame`/`SyntheticSensorModel`（静止/步行/跑步/驾车，步数/气压/地磁/陀螺仪确定性生成）；Native 传感器 Hook（P4-04）已改为按 motion state 生成值，但未真机验证。
- **P5-01 纯模型落地**：新增 `RoutingConfig`/`RoutingTable`（package 优先、UID fallback、system 绕过、deny、schemaVersion）；已接 `ListenerRouting` 与 per-app UI 的 deny/固定点。
- **P5-02 解析层落地**：新增 `PackageUidLookup`/`CallerRouter`、`ScenarioSampleStore`、`AppRoute.point` 与 `RoutingTable` 多 package/固定点解析；新增 `ListenerRouting`（denied/scenario/fixed-point）与 per-app deny/固定点 UI；WiFi/基站/GNSS status/NMEA 按固定点生成，后端发布已携带 scenarioId，多控制器仍缺。
- **P5-05 结论**：GMS 只做全局模拟，per-app 依赖 AOSP Provider/固定点；限制见 `08-gms-per-app.md`，未设备验证。
- **P6-04 首轮安全加固**：`APSService` 显式不导出；合并 manifest 仅 launcher 导出；`environment/`、`fused_ext.xml`、`library/` 从备份/换机迁移排除；per-app 列表使用 MAIN/LAUNCHER `<queries>`；debug 命令日志已脱敏坐标/配置；其余 IPC/Bugly 审查未做。
- **P5-03 纯模型落地**：新增 `ConfigEnvelope`/`ConfigCodec`（v2 编解码、v1 迁移、未知字段忽略、版本/重复校验）、`SampleEnvelope`/`SampleCodec` 与 `SampleWire` v1/v2；新增 `applyConfigNow`，per-app/回放配置可在服务运行时热更新。
- **P5-06 纯抽象落地**：新增 `SampleStore`/`ConfigStore` 与内存实现（sequence/租约/clear），Binder/SHM 与接线未做。
- **许可证**：上游 `fuqiuluo/Portal` 2026-11-06 已不可访问（404），本地无 LICENSE；按 ADR-023 暂不添加 LICENSE/NOTICE，先落地代码护栏与依赖清单。
- **仍未完成**：没有任何 `DEVICE VERIFIED`。detector 未在 API 31/33/35 模拟器或真机启动；AppOps/Settings/Provider 列表等 Hook 的实际命中与系统副作用都还没有设备结论。

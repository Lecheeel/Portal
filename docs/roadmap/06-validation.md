# 06 · 验证、Detector 与发布门禁

> 目标：让“隐藏/伪装”从“看起来对”变成“有证据”。  
> 没有 detector 和设备证据的 Hook 改动，不算完成。

---

## 1. 证据模型

沿用 Portal 现有写法：

| 等级 | 能证明什么 | 不能证明什么 |
|---|---|---|
| `SOURCE VERIFIED` | 代码路径存在、逻辑自洽 | 系统真的这样跑 |
| `BUILD VERIFIED` | 编译/lint/打包通过 | Hook 命中 |
| `UNIT TEST VERIFIED` | 纯逻辑正确 | Android/Binder/ROM 行为 |
| `DEVICE VERIFIED` | 指定设备/ROM/版本上通过 | 所有设备 |
| `FIELD VERIFIED` | 真实使用稳定 | 所有场景 |

发布门禁只认 `DEVICE VERIFIED` 及以上。

---

## 2. Detector 应用（`:detector`）

### 2.1 约束

- 独立 Android app，不依赖 `:app`/`:xposed` 的实现类。
- 只用公开 API + 反射；不引用 Portal 的内部类。
- 结果可导出 JSON；可重复运行；失败给证据。
- 默认不上传网络；导出由用户触发。

### 2.2 页面

1. **Run**：一键运行全部检查；显示进度。
2. **Results**：按 PASS/FAIL/UNKNOWN 分组；展开看证据。
3. **Export**：导出 JSON/文本；复制到剪贴板。
4. **Settings**：目标包名、是否测试 GMS、是否读取已安装应用。

### 2.3 输出 schema

```json
{
  "schema": 1,
  "generatedAtMillis": 1790000000000,
  "device": {
    "manufacturer": "Xiaomi",
    "model": "23127PN0CC",
    "android": 15,
    "sdk": 35,
    "rom": "HyperOS 2.0",
    "lsposed": "1.9.2",
    "abis": ["arm64-v8a"]
  },
  "checks": [
    {"id": "C01", "status": "PASS", "detail": "isMock=false, extras.mockLocation absent"},
    {"id": "C02", "status": "FAIL", "detail": "AppOps OP_MOCK_LOCATION=MODE_ALLOWED"}
  ]
}
```

---

## 3. 检查目录（v1）

| ID | 检查 | 期望 | 说明 |
|---|---|---|---|
| C01 | `Location.isMock`/`isFromMockProvider`/反射字段 | false | 覆盖 Android 12–17 字段差异 |
| C02 | `AppOpsManager.checkOp(OP_MOCK_LOCATION)` | 非 ALLOWED | 需要 Hook `AppOpsService` |
| C03 | `Settings.Secure.getString("mock_location")`、开发者选项 | 不可见/0 | 需要 settings provider scope |
| C04 | `getProviders`/`getAllProviders`/`getProviderProperties`/`isProviderEnabled` | 无 `fused_ext`/test provider | 恢复列表 Hook |
| C05 | `Location` extras/`satellites`/MSL/不确定度/`LocationAvailability` | 字段完整且自洽 | 不要空 extras |
| C06 | `GnssStatus` 卫星数/星座/CN0/usedInFix/稳定性 | 多星座、稳定、CN0 合理 | 不能随机 shuffle |
| C07 | NMEA GGA/RMC/GSA/GSV 校验和/卫星数/时间 | 全部自洽 | 与 C06 同源 |
| C08 | WiFi 扫描/连接/DHCP/开关状态 | 非空、BSSID 合法、连接 AP 在列表 | 不能空列表/哨兵 BSSID |
| C09 | CellInfo/SIM/operator/phone type/signal | 自洽 | 不能 CDMA + GSM SIM |
| C10 | 加速度/陀螺仪/地磁/气压/计步 | 与运动状态一致 | 静止不增长 |
| C11 | Activity Recognition / 健康/计步 | 与运动状态一致 | 先保证不矛盾 |
| C12 | 轨迹统计：FFT/瞬移/速度/时间戳 | 无单频峰、无瞬移 | 连续采样 2–5 分钟 |
| C13 | `PackageManager` 的 `xposedmodule`/LSPosed/Portal 包 | 不可见/不可判定 | libxposed 迁移后重点 |
| C14 | `/proc/self/maps`/`TracerPid`/root 痕迹 | 无 `liblspd`/`libdobby`/可疑路径 | 仅在需要时测 |
| C15 | 出口 IP/时区/locale/carrier vs 坐标 | 不矛盾 | 服务端风控相关 |

### 3.1 检查分级

- **阻断级**：C01–C05、C08、C09。C13 在 P6（libxposed/元数据迁移）完成后升级为阻断级。
- **警告级**：C06、C07、C10–C12、C14、C15。可 Beta 观察，但必须记录。
- **研究级**：C14/C15 的部分子项依赖设备/网络，允许 UNKNOWN。

### 3.2 运行条件

- 授予定位、WiFi、电话、通知权限。
- 模拟运行中（单点/路线）。
- 分别记录：模拟前、模拟中、停止后。
- 至少连续采样 2 分钟；轨迹检查 5 分钟。

---

## 4. 设备矩阵

### 4.1 必测系统

| Android | API | 优先级 | 备注 |
|---|---:|---|---|
| 12 | 31 | P1 | minSdk 下限 |
| 13 | 33 | P1 | 权限/通知变化 |
| 14 | 34 | P1 | 前台服务/精确位置 |
| 15 | 35 | P0 | 当前主力 |
| 16 | 36 | P0 | 16 KB 页/LSPosed |
| 17 | 37 | P2 | targetSdk 对齐 |

### 4.2 必测 ROM

| ROM | 优先级 | 重点 |
|---|---|---|
| Pixel/AOSP | P0 | 基线行为 |
| Xiaomi HyperOS | P0 | MIUI fused/blur/权限 |
| OPPO ColorOS | P1 | OPLUS location |
| vivo OriginOS | P2 | 厂商 fused |
| Samsung One UI | P2 | 权限/后台 |
| Huawei（无 GMS） | P2 | 无 GMS 路径 |

### 4.3 环境维度

- LSPosed：legacy 1.9.x / libxposed 兼容版本。
- Root：无 Root / Magisk / KernelSU / APatch。
- ABI：arm64 真机 / x86_64 模拟器。
- 网络：WiFi / 蜂窝 / 飞行模式。
- 屏幕：16 KB 页设备（如支持）。

### 4.4 本轮最小矩阵

P0 阶段至少：

1. 一台 Xiaomi HyperOS（Android 15/16）。
2. 一台 Pixel/AOSP（Android 15/16）。
3. 一个 API 35/37 模拟器（CI）。

后续每个 Phase 至少复测这两台。

---

## 5. 测试金字塔

```text
        ┌─────────────────────┐
        │ Detector / 设备验收 │  少、慢、最可信
        ├─────────────────────┤
        │ Instrumentation     │  Hook/Provider/路由
        ├─────────────────────┤
        │ JVM 单元测试        │  多、快、纯逻辑
        └─────────────────────┘
```

### 5.1 JVM 单元测试（必须）

- `:core`：坐标、播放、Provider 画像、噪声、GNSS 模型、NMEA 生成、路由、环境模型。
- `:nmea`：黄金向量、校验和、异常输入。
- `:xposed`：纯策略（scope、security、lease、hook arguments）。
- `:app`：后端状态机、配置迁移、设置 schema。

### 5.2 Instrumentation（设备/模拟器）

- `MockProviderDeviceTest`：注册/发布/清理/权限撤销。
- `MockFlagExperimentTest`：本地反射边界。
- `LocationSampleTest`：Location 字段/extras。
- 新增：Provider 列表、AppOps、Settings、WiFi/Cell、GNSS/NMEA、per-app。

### 5.3 Detector（端到端）

- 每个 Phase 结束跑一次完整 C01–C15。
- 结果保存到 `docs/roadmap/baselines/<date>-<device>.json`（可脱敏）。
- 对比上一版：新增 FAIL = 阻断。

### 5.4 手动 Smoke

- 高德/百度/Google 地图各一次。
- 微信/钉钉/学习通等（按维护者测试清单）。
- 停止模拟后恢复真实定位。
- 重启、升级安装、权限撤销、服务被杀。

---

## 6. 各 Phase 回归清单

| Phase | 必过检查 | 设备动作 |
|---|---|---|
| P1 | C01–C05 | 模拟中/停止后各跑一次 |
| P2 | C08、C09 | WiFi 开/关、飞行模式、无 SIM |
| P3 | C06、C07 | 室外/室内、GPS 应用、NMEA 工具 |
| P4 | C10–C12 | 静止/步行/跑步/驾车、5 分钟轨迹 |
| P5 | C04 + per-app 场景 | 两个 App 不同坐标、工作资料 |
| P6 | C13、C14 | LSPosed 版本矩阵、重启 |
| P7 | C11、C15 | 网络切换、时区/locale |

---

## 7. 发布门禁

### 7.1 必须满足

1. 阻断级检查（C01–C05、C08、C09；P6 后加 C13）全部 PASS。
2. 新增/修改 Hook 有至少一台目标设备的 `DEVICE VERIFIED`。
3. 现有 JVM 测试、lint、Release 构建通过。
4. 无新增崩溃/ANR（Beta 7 天或 20 台设备）。
5. 实验能力默认关闭，UI 标注。
6. 文档 `01/05/06` 更新；许可证/归属更新。
7. 可回退：feature flag 或已知重启步骤。

### 7.2 阻断条件

- 任一阻断级检查 FAIL。
- `PackageManager` 仍能识别 `xposedmodule`（P6 后）。
- 无法停止/清理，或停止后真实定位不恢复。
- 目标 App 崩溃/ANR 与本次改动相关。
- 许可证不明的新依赖/代码。

---

## 8. 基线报告模板

```text
# Detector 基线

- 日期：
- 设备/ROM/Android/ABI：
- LSPosed/Root：
- Portal 版本/commit：
- 模拟模式：单点 / 路线 / 摇杆
- 运行时长：

| ID | 模拟前 | 模拟中 | 停止后 | 证据 |
|---|---|---|---|---|
| C01 |  |  |  |  |
| ... |  |  |  |  |

结论：
- 阻断级 FAIL：
- 警告级 FAIL：
- 与上一版差异：
- 备注：
```

保存到 `docs/roadmap/baselines/`；如含敏感信息，先脱敏。

---

## 9. 性能与稳定性指标

先测基线，再定目标（Q-06）。建议至少记录：

| 指标 | 采集方式 | 目标（待确认） |
|---|---|---|
| 发布延迟 p50/p95 | `DiagnosticJournal`/日志 | p95 < 10 ms（Binder） |
| CPU（模拟中） | `top`/Perfetto | < 5% |
| 内存 | `dumpsys meminfo` | < 150 MB |
| 电池 | Battery Historian | < 2%/h |
| 启动到首次定位 | 日志 | < 3 s |
| APK 体积 | 构建产物 | 不显著增加 |

性能测试不阻塞功能开发，但 P4/P7 必须有报告。

---

## 10. Detector 隐私

- 默认不上传网络。
- 导出文件由用户触发；提示可能含 WiFi/基站信息。
- 不记录账号、聊天、位置历史。
- 读取已安装应用列表时说明用途；优先 `<queries>`。

---

## 11. 证据记录格式

每个任务完成后，在本文追加：

```text
### <任务 ID> <标题>
- 日期：
- 设备/ROM/Android/LSPosed：
- 变更：
- 检查：Cxx PASS/FAIL；证据路径
- 回归：JVM 用例数；lint；Release 构建
- 结论：DEVICE VERIFIED / 仍有 UNKNOWN
```

只有 `DEVICE VERIFIED` 才能把 [05-work-plan.md](05-work-plan.md) 的状态改为 `DONE`。

---

## 12. P0 实施记录（2026-11-06）

> 本节只记录实际执行过的证据；未执行的设备项明确写“未验证”。

### P0-01 :detector 骨架

- 日期：2026-11-06
- 设备/ROM/Android/LSPosed：无设备（本机 `adb devices` 为空）
- 变更：新增 `:detector` Android 应用（平台 API，无 AndroidX/第三方运行时依赖）；Run/Results/Export/Settings；SAF 导出 + 剪贴板；JSON `schema=1`；采样器覆盖 Location/GnssStatus/NMEA/传感器/WiFi/Cell；`QUERY_ALL_PACKAGES` 仅用于可配置 target 的验证工具。
- 检查：`./gradlew :detector:assembleDebug --offline` → `BUILD SUCCESSFUL`；产物 `detector/build/outputs/apk/debug/detector-debug.apk`，约 3.13 MB。未安装、未启动、未走 API 31/33/35 模拟器。
- 最终回归（2026-11-06）：`./gradlew :core:test :nmea:test :xposed:testDebugUnitTest :app:testArm64DebugUnitTest :detector:assembleDebug --offline` → `BUILD SUCCESSFUL`，236 项 JVM 用例 0 失败（core 82、nmea 2、xposed 30、app 122）。
- 本机限制：`lint` 报表写入和 `:xposed:configureCMakeDebug`（JNA）被 Windows 沙箱拒绝；lint/CMake/Release 由 CI 覆盖，本轮不以本地 lint 作为证据。
- 结论：`BUILD VERIFIED`；设备启动/导出未验证。

### P0-02 detector 检查 v1

- 日期：2026-11-06
- 变更：C01–C15 全部实现；每项返回 PASS/FAIL/UNKNOWN + 可复制 evidence（≤20 行，每行 ≤299 字符）；采样失败/权限不足返回 UNKNOWN，不伪造 PASS。
- 关键口径：
  - C01 mock 标记（isMock/isFromMockProvider/反射字段）；C05 Location extras/MSL/精度/不确定度（MSL 仅 API 34+ 检查）。
  - C02 AppOps 目标包 + 自身；C03 Settings 只读；C04 Provider 列表/属性/不存在 provider 探测。
  - C06 GnssStatus 星座/Jaccard/CN0/仰角相关；C07 NMEA 校验和/GSV burst/GGA/RMC/GSA/talker/与 GnssStatus 对账。
  - C08 WiFi 空列表/哨兵 BSSID/连接 AP；C09 CDMA 与 GSM/LTE 矛盾、SIM 有值但小区为空。
  - C10/C11 传感器量级/方差/计步；C12 瞬移/时间戳/单频抖动；C13 xposed meta-data；C14 `/proc`/root 痕迹（仅 detector 进程）；C15 时区 + 可选 HTTPS 出口 IP。
- 检查：`:detector:assembleDebug` 通过；无设备运行结果。C06/C07 的阈值（Jaccard 0.5、Δ卫星数 1/2、CN0 相关性等）需要在 P0-03 设备基线上校准，真实 NMEA 可能因此产生“预期内 FAIL”。
- 结论：`BUILD VERIFIED` + `SOURCE VERIFIED`；没有任何检测项的 `DEVICE VERIFIED`。

### P0-03 设备基线报告

- 状态：`BLOCKED`。
- 原因：本机无连接设备，SDK 未安装 Emulator/AVD；无法产出 API 31/33/35 或真机的 detector 基线。
- 已准备：[baselines/P0-03-runbook.md](baselines/P0-03-runbook.md) 提供构建、权限、三次运行（模拟前/中/后）、JSON 命名和记录模板。
- 解除条件：接入至少一台目标设备，或安装 API 35/37 Emulator；然后按 runbook 运行并保存到 `docs/roadmap/baselines/`。

### P0-04 Hook 所有权与状态清单

- 日期：2026-11-06
- 设备/ROM/Android/LSPosed：无设备（本机 `adb devices` 为空）
- 变更：新增 `HookOwnership` 审计；`HookStatusRegistry` 记录当前 scope；`get_runtime_status` 返回 `hook_scope`/`hook_expected`/`hook_missing`/`hook_missing_points`；app `XposedBackend.diagnose()` 增加 `HOOK_OWNERSHIP`。
- 检查：`./gradlew :xposed:testDebugUnitTest :app:compileAppDebugKotlin --offline` 通过；xposed 30 项 JVM 用例 0 失败（新增 `HookOwnershipTest` 5 项）。
- 结论：`UNIT TEST VERIFIED` + `BUILD VERIFIED`；Hook 命中/设备行为仍未验证。

### P0-05 仓库护栏与许可证

- 日期：2026-11-06
- 变更：`scripts/check_module_rules.py` 检查 `:core`/`:nmea` 纯 JVM、`:xposed` 不依赖 `:app`、`:app` 不依赖 `:detector`、`:detector` 不引用实现类、400 行预算（存量基线 `docs/roadmap/line-budget-baseline.json`）、`docs/roadmap` 链接；PR 模板加入任务 ID/证据/许可证检查；PR workflow 增加 guardrails step。
- 检查：`python scripts/check_module_rules.py` → `module rules: 0 violation(s)`；`python -m unittest discover -s scripts -p "test_module_rules.py"` 8 项通过；全量 JVM 用例 236 项 0 失败（core 82、nmea 2、xposed 30、app 122）。
- 许可证：上游 `fuqiuluo/Portal` 2026-11-06 返回 404，GitHub API 搜索 0 条，本地无 LICENSE；按 ADR-023 暂不添加 LICENSE/NOTICE。依赖清单见 [THIRD-PARTY.md](THIRD-PARTY.md)。
- 结论：护栏 `UNIT TEST VERIFIED`；许可证子项 `BLOCKED`，不能把 P0-05 标 `DONE`。

### P0-06 Hook 拆分设计

- 日期：2026-11-06
- 变更：新增 [hook-split-plan.md](hook-split-plan.md)，覆盖现状职责、目标文件/行数预算、7 步迁移顺序、验收。
- 检查：纯文档；未改 `LocationServiceHook` 行为。
- 结论：设计稿完成，等待维护者评审；拆分未开始。

---

## 13. P1 实施记录（2026-11-06）

### P1-02 AppOps Hook

- 变更：新增 `AppOpsHook`，按方法名匹配 `AppOpsService.checkOperation*`；第三方跨 UID 查询 `OP_MOCK_LOCATION` 时返回 `MODE_ERRORED`，系统调用方与 controller 自检保持原值；`appops` 加入 system_server 所有权清单。
- 检查：`:xposed:compileDebugKotlin`、`:xposed:testDebugUnitTest` 通过（30 项）。
- 风险：只覆盖 `checkOperation*`，未覆盖 `getOpsForPackage`/`getPackagesForOps`；需要设备确认其他 AppOps 不受影响。
- 结论：`SOURCE VERIFIED` + `BUILD VERIFIED`；未做设备验证。

### P1-03 Settings Provider Hook

- 变更：新增 `HookScope.SETTINGS_PROVIDER` 与 xposed scope `com.android.providers.settings`；`SettingsHook` 对第三方调用方把 `mock_location`/`allow_mock_location`/`development_settings_enabled`/`adb_enabled` 置为 `"0"`；系统调用方不受影响；`query` fallback 返回空 cursor。
- 检查：scope resolver JVM 测试补充 `com.android.providers.settings`；`:xposed` 编译/测试通过；`:app:processArm64DebugResources` 通过。
- 风险：依赖 `SettingsProvider.call` 的命令名与 Bundle 结构；不同 ROM 的 Settings provider 实现需设备确认。
- 结论：`SOURCE VERIFIED` + `BUILD VERIFIED`；未做设备验证。

### P1-04 Provider 列表/属性

- 变更：新增 `ProviderRegistryHook`，过滤 `getAllProviders`/`getProviders` 中的 `fused_ext`/test/mock/portal provider；`hasProvider` 返回 false；`getProviderProperties` 对可疑 provider 抛出与真实缺失一致的 `IllegalArgumentException`；`provider-registry` 加入所有权清单。
- 检查：`:xposed` 编译/测试通过。
- 风险“名称含 test/mock”可能误伤厂商 provider；设备基线上需确认白名单。
- 结论：`SOURCE VERIFIED` + `BUILD VERIFIED`；未做设备验证。

### P1-05 Availability

- 事实核查：Android 31–37 没有公开 `android.location.LocationAvailability`；AOSP 16 `ILocationManager.aidl` / `ILocationListener.aidl` 也没有 availability 回调。原路线图的“LocationAvailability Hook”没有可挂的框架接口。
- 变更：新增 `LocationAvailabilityHook`（从 `FakeLocation` 安装，不扩展 `LocationServiceHook`）；对第三方调用方在模拟启用时合成 `isLocationEnabledForUser` / `isLocationEnabled` 为 true；系统/Settings 调用方保持真实值。
- 检查：`:xposed` 编译/测试通过。
- 结论：原验收项无法按“框架 LocationAvailability”完成；当前为可观察的替代实现，`WIP`，GMS `LocationAvailability` 留待 P4/P7 独立评估。

### P1 回归状态

- 2026-11-06：`:xposed:testDebugUnitTest` 30 项通过；无设备，P1-02～05 均未 `DEVICE VERIFIED`。
- 下一设备动作：按 [05-work-plan.md](05-work-plan.md) P1-06，在小米 + Pixel/AOSP 的 Xposed 模式下跑 detector C01–C05、C08、C09。

---

## 14. P2 实施记录（2026-11-06）

### P2-01 `:core` 环境模型

- 变更：新增 `EnvironmentSourceType`/`CellMode`/`CellType`、`EnvironmentConfig`、`WifiAp`、`CellTower`、`BleBeacon`、`EnvironmentProfile`、`EnvironmentSource`；校验 BSSID/RSSI/频率/信道、MCC/MNC/PCI/RSRP、列表上限、最多一个 connected AP/registered cell。
- 检查：`:core:test` 新增 6 项 `EnvironmentModelsTest`（JSON roundtrip、非法值、列表上限）通过；`:core` 仍无 Android import。
- 结论：`UNIT TEST VERIFIED`；尚未接入任何 Hook。

### P2-03 程序化 WiFi

- 变更：新增 `ProceduralWifiSource`：约 100 m 网格、固定 seed、每格 `apPerCell` 个 AP、真实 OUI、固定信道/频率、距离路径损耗 + 确定性 jitter、最近 AP 标记 connected；同一格内移动时 AP 集合不变、RSSI 平滑。
- 检查：`:core:test` 新增 6 项 `ProceduralWifiSourceTest`（同点同 seed、不同 seed、合法性、邻近平滑、时间不影响 AP、无 cell/BLE）通过。
- 限制：尚未处理跨格插值与 connected AP 切换滞回；P2-04 Hook 未接入，detector C08 尚未验证。
- 结论：`UNIT TEST VERIFIED`。

### P2-02 回放源（部分）

- 变更：新增 `ReplayEnvironmentSource`：接受 REPLAY profile，按原 origin 与目标点距离做确定性 RSSI 重标定，保留 BSSID/SSID/信道与 connected 标记，结果 clamp 到合法范围；无 origin 时不改 RSSI。
- 变更：新增 `EnvironmentProfileCodec`：REPLAY/PROCEDURAL profile 的 JSON 导入导出，`EnvironmentProfile.schemaVersion` 校验，未知字段忽略、未来版本拒绝。
- 变更：新增 app `EnvironmentCollector`（FINE_LOCATION 保护下采集 WiFi scanResults + Telephony `allCellInfo`，映射 LTE/NR/GSM/WCDMA，异常/权限失败返回空列表）与 `EnvironmentStore`（JSON 文件保存/读取/列表/删除，文件名净化、临时文件替换）；BLE 另行扫描。
- 变更：设置页新增“采集当前环境”入口，IO 线程采集并以时间戳命名保存，Toast 回报 WiFi/基站数量；无位置权限时提示。
- 变更：设置页新增“查看已保存环境”，列出 `EnvironmentStore` 中的配置；选择后通过 SAF `CreateDocument` 导出 JSON。
- 变更：设置页可将某个环境标记为 WiFi 回放源；`put_config` 携带 `environment_json`，system_server 解析到 `FakeLoc.replayEnvironment`，`WlanHook` 优先用 `ReplayEnvironmentSource`，无回放配置时回退程序化 AP。
- 变更：`EnvironmentCollector.capture()` 增加 BLE 扫描（`BLUETOOTH_SCAN` 权限、1.5 s 超时、按地址去重、最多 `MAX_BLE`），manifest 增加 `BLUETOOTH_SCAN`；无权限/蓝牙关闭返回空列表。
- 变更：设置页采集前请求 `BLUETOOTH_SCAN`；拒绝时提示并只采集 WiFi/基站，授权后继续采集并包含 BLE。
- 变更：设置页新增“导入环境 JSON”，通过 SAF `OpenDocument` 读取并用 `EnvironmentProfileCodec` 校验，保存到 `EnvironmentStore`。
- 变更：已保存环境操作菜单新增“删除环境”，直接删除 `EnvironmentStore` 文件。
- 检查：`ReplayEnvironmentSourceTest` 5 项（同点保持、远离降低、极远 clamp、无 origin、非 REPLAY 拒绝）通过；`EnvironmentProfileCodecTest` 5 项（roundtrip、未知字段、非法 JSON/未来版本、schema 校验、默认版本）通过；`EnvironmentStoreTest` 3 项（保存/读取/列表/删除、文件名净化、替换）通过。
- 未完成：BLE 回放 Hook、采集 UI 的 BLE 开关、构造器兼容、真机采集验证。
- 结论：`UNIT TEST VERIFIED`（存储/回放）；采集器/列表/导出/回放接线/BLE 仅 `BUILD VERIFIED`；P2-02 仍 `WIP`。
- 全量回归（2026-11-06）：`./gradlew :core:test :nmea:test :xposed:testDebugUnitTest :app:testArm64DebugUnitTest :detector:assembleDebug` → `BUILD SUCCESSFUL`，249 项 JVM 用例 0 失败（core 95、nmea 2、xposed 30、app 122）。

### P2-04 WiFi Hook 重写

- 变更：`WlanHook` 改为从 `ProceduralWifiSource` 生成 `ScanResult`（公开无参构造 + 字段），`getConnectionInfo` 生成 `WifiInfo`（隐藏构造 + setter），`getDhcpInfo` 生成非空 `DhcpInfo`；只改第三方调用方，系统调用方保持真实值；`FakeLoc.environmentSeed` 控制确定性。
- 检查：`:xposed:compileDebugKotlin` / `:xposed:testDebugUnitTest` 通过；无设备。
- 风险：`WifiInfo` 隐藏 setter、`ParceledListSlice` 构造、不同 ROM 的 `getScanResults` 返回类型需设备确认；`getConfiguredNetworks`/`WifiScanner` 尚未覆盖。
- 变更：`WlanHook` 支持 PRIORITY 回放 `FakeLoc.replayEnvironment`（从 `put_config.environment_json` 解析），否则继续程序化生成；同一坐标/seed 仍缓存。
- 结论：`SOURCE VERIFIED` + `BUILD VERIFIED`；detector C08 未验证。

### P2-05 基站画像（部分）

- 变更：`FakeLoc.needDowngradeToCdma` 默认改为 false（ADR-005）；`:core` 新增 `CellModePolicy.resolve`（AUTO → 有 SIM `FULL_REPLAY` / 无 SIM `NO_SIM_WIFI_ONLY`）与 `ProceduralCellSource`（1 个 registered LTE + 3 个 LTE 邻区 + 1 个 NR，MCC/MNC/area/cellId/PCI/RSRP 确定性生成）。
- 变更：`TelephonyHook` 新增 `CellInfoSynthesis`，对第三方 `getAllCellInfo` 用反射构造 LTE `CellInfoLte`/`CellIdentityLte`/`CellSignalStrengthLte`；构造失败或系统调用方保留原始列表，CDMA 旧路径仍由开关控制。
- 变更：新增 `PhoneTypeHooks`，模拟期间对第三方返回 `PHONE_TYPE_GSM` 与 `NETWORK_TYPE_LTE`（含 ForSubscriber 变体），系统调用方保持真实值；CDMA 旧路径后安装，显式开启时仍优先。
- 变更：新增 `SimOperatorHooks`，捕获 profile 存在时第三方 SIM operator numeric 使用首个小区的 MCC/MNC，operator name 返回 `Portal`；无 profile 时保持真实值。
- 变更：`CellInfoSynthesis` 优先回放 `FakeLoc.replayEnvironment.cells` 中的 LTE/NR 小区；无捕获 profile 时回退 `ProceduralCellSource`，LTE 与 NR 分别构造对应 `CellInfo`。
- 变更：新增 `FakeLoc.simReady`（默认 true）；关闭时第三方 `getAllCellInfo` 返回空列表、phone type 为 `NONE`、network type 为 `UNKNOWN`、SIM operator 为空，对应 `NO_SIM_WIFI_ONLY`；设置页新增开关并随 `put_config.sim_ready` 下发。
- 未完成：`TelephonyHook` 仍保留旧 CDMA 降级路径（现在默认不启用）；SIM ready 的 WiFi-only 环境与真实 API 组合需设备确认。
- 检查：`:core:test` 通过（`ProceduralCellSourceTest` 7 项：FULL_REPLAY、AUTO 无 SIM、AUTO 有 SIM、LEGACY、确定性、邻区差异、非法 SIM）；`:xposed` 编译/测试通过；`:xposed:testDebugUnitTest` 通过；无设备。
- 结论：模型 `UNIT TEST VERIFIED`；`CellInfoSynthesis` 仅 `BUILD VERIFIED`，隐藏构造/字段在不同 ROM 上可能不同，需真机确认；P2-05 仍 `WIP`。

### P2-06 WiFi 指纹库（实验，部分）
- 变更：`:core` 新增 `WifiObservation`/`WifiFingerprint`/`WifiFingerprintDb`：BSSID 校验、RSSI 边界、30 天默认时效、按 RSSI 最接近的指纹做距离加权质心、`add`/`remove` 不可变。
- 变更：新增 `WifiFingerprintCatalog`/`WifiFingerprintImporter`：JSON 导入导出、CSV 导入（可选表头、坏行跳过计数）、默认关闭；启用时必须提供 source/license/attribution。
- 检查：`WifiFingerprintDbTest` 6 项（精确匹配、未知、过期、加权、不可变/大小写、非法输入）通过；`WifiFingerprintImportTest` 6 项（默认关闭、启用元数据、JSON roundtrip、CSV 导入/坏行、元数据/空结果、非法 JSON）通过。
- 未完成：WigLE/OpenCellID 实际下载、许可审查与 attribution UI、默认关闭开关接入、目标区域回放。
- 结论：`UNIT TEST VERIFIED`；P2-06 仍 `WIP`。

### P2-07 环境设置（部分）

- 变更：设置页新增“每格 AP 数（1–8，默认 4）”和“基站模式（AUTO/FULL_REPLAY/NO_SIM_WIFI_ONLY/LEGACY_CDMA）”；`put_config` 下发 `environment_ap_count` / `cell_mode`。
- 变更：新增 `EnvironmentSourceMode`（AUTO/PROCEDURAL/REPLAY）与“环境来源”设置；`WlanHook`/`CellInfoSynthesis` 按该模式选择捕获 profile 或程序化来源。
- 变更：AP 数/基站模式/环境来源/物理噪声/SIM/轨道模型设置修改后调用 `applyConfigNow()`，服务已运行时无需重启场景；未连接时回退下次启动。
- 变更：`WlanHook` 按 AP 数动态重建 `ProceduralWifiSource` 并使缓存失效；`CellInfoSynthesis` 使用配置的 `cellMode`，`NO_SIM_WIFI_ONLY` 返回空小区。
- 检查：`:xposed:compileDebugKotlin` / `:xposed:testDebugUnitTest`、`:app:compileAppDebugKotlin` / `:app:testArm64DebugUnitTest` 通过；无设备。
- 未完成：环境来源选择、实验开关完整清单、采集 UI 的高级参数、默认值设备确认。
- 结论：`BUILD VERIFIED`；P2-07 仍 `WIP`。

---

## 15. P3 实施记录（2026-11-06）

### P3-01 确定性 GNSS 模型

- 变更：`:core` 新增 `Constellation`/`GnssSatellite`/`GnssFrame`/`GnssSkyModel` 与 `CatalogGnssSkyModel`；固定目录含 GPS、BDS、GLONASS、Galileo、QZSS；无 `shuffled()`、无未播种随机；时间/位置只平滑影响 az/el/CN0。
- 检查：`GnssSkyModelTest` 6 项（确定性、多星座、集合稳定、边界/去重、方位缓慢变化、CN0-仰角相关性）通过。
- 结论：`UNIT TEST VERIFIED`；Hook 尚未改为读取该模型（P3-04）。

### P3-02 多星座 V1（部分）

- 已覆盖：GPS/BDS/GLONASS/Galileo/QZSS 混合、按星座排序、确定性 usedInFix/CN0、DOP。
- 未覆盖：真实可见性随地理位置/时间的遮挡、星历/年历真实性（V2）、原始测量。
- 结论：V1 模型 `UNIT TEST VERIFIED`；detector C06 未验证。

### P3-03 NMEA 生成器

- 变更：`:nmea` 新增 `NmeaGenerator`（GGA/RMC/GSA/GSV/GST/ZDA），校验和复用 `NMEA.calculateNmeaChecksum`；GGA 卫星数 = 可见卫星数，GSA 只带 usedInFix，GSV 按 4 颗分组并带 systemId。
- 检查：`NmeaGeneratorTest` 5 项（校验和、GGA/RMC 同源、GSV 分组/总数、GSA usedInFix、非法输入）通过；GGA/RMC/GSA/GSV 用现有 `NMEA.valueOf` roundtrip。
- 限制：现有解析器不支持 GST/ZDA（生成器仍输出，detector 用通用解析）；尚未与 `GnssStatus` 共用同一个 Hook 输出。
- 结论：`UNIT TEST VERIFIED`；P3-04 双 Hook 合并未做。
- 全量回归（2026-11-06）：260 项 JVM 用例 0 失败（core 101、nmea 7、xposed 30、app 122）；`:detector:assembleDebug` 通过。

---

## 16. P4 实施记录（2026-11-06）

### P4-01 ProviderProfile + 静止策略

- 变更：`:core` 新增 `ProviderKind`、`ProviderProfile`（GPS 1 Hz/6 m、NETWORK 0.33 Hz/60 m/无 bearing/alt/speed、FUSED 1 Hz/10 m）、`StationaryPolicy`、`ProviderFieldMask`、`ProviderSample`、`ProviderMixer`；`ProviderMixer` 对同一 fix 生成三路不同精度/延迟/字段的样本，静止时去掉 bearing/speed。
- 检查：`ProviderMixerTest` 6 项（三路差异、延迟、静止无 bearing/speed、静止精度漂移、时间不为负、非法 profile）通过。
- 未完成：`P4-03` 后端按 provider 发布、真实 `Location` 字段写入尚未接入。
- 结论：`UNIT TEST VERIFIED`。

### P4-02 PhysicalNoise

- 变更：`:core` 新增 `NoiseProfile`/`PhysicalNoise`：OU 水平/海拔漂移、边界 clamp、固定 seed 可复现；关闭时原样返回；`reset()` 同时重置 RNG。
- 检查：`PhysicalNoiseTest` 5 项（关闭恒等、同 seed 可复现、不同 seed 有差异、边界/有限值、reset 重现）通过。
- 未完成：尚未接入 `ScenarioController`/后端发布，速度与位移一致性需设备轨迹检查（C12）。
- 变更：`ProviderView` 在 `FakeLoc.enablePhysicalNoise` 开启时对 provider 样本应用有界 OU 漂移；`FakeLoc.physicalNoiseSeed` 控制复现；默认关闭。设置页新增“物理漂移噪声”开关，`put_config` 下发。
- 检查：`:xposed:compileDebugKotlin` / `:xposed:testDebugUnitTest`、`:app:testArm64DebugUnitTest` 通过；无设备。
- 结论：`UNIT TEST VERIFIED`（核心模型）+ `BUILD VERIFIED`（接线）；P4-02 仍 `WIP`。
- 全量回归（2026-11-06）：271 项 JVM 用例 0 失败（core 112、nmea 7、xposed 30、app 122）；`:detector:assembleDebug` 通过。

### P4-03 后端按 provider 发布（部分）

- 变更：新增 `ProviderView`（xposed）按 `gps`/`network`/`fused` 生成 provider 特定 `LocationSample`；`BaseLocationHook`、`LocationServiceHook.callOnLocationChanged`、`LocationProviderManagerHook` 全部改走该视图；`MockProviderBackend` 逐 provider 调用 `ProviderMixer`；`FusedMockBackend` 的 GMS 通道使用 `FUSED` 画像。
- 检查：`:xposed:compileDebugKotlin`、`:app:testArm64DebugUnitTest` 通过；`MockProviderBackendTest` 已更新为断言三路精度/时间不同（GPS 6 m/0 ms、Network 60 m/400 ms）。
- 未完成：GMS fused provider 的真实字段/时间、Native 后端、设备端三路交叉（detector C04/C05/C11）未验证。
- 结论：`BUILD VERIFIED` + 部分 `UNIT TEST VERIFIED`；P4-03 仍 `WIP`。

### P4-05 传感器融合模型

- 变更：`:core` 新增 `MotionMode`/`ActivityKind`/`MotionState`/`SensorFrame`/`SensorModel` 与 `SyntheticSensorModel`：静止/步行/跑步/驾车四模式；加速度重力+步态振荡、陀螺仪/地磁/气压随运动变化；步数按 cadence 累积（保留小数余量）；固定 seed 可复现。
- 检查：`SensorModelTest` 8 项（静止重力/无步、步行步数、跑步 cadence/幅度、驾车、气压-海拔、同 seed 可复现、不同 seed、有限值/边界）通过。
- 未完成：Native `SensorEventQueue::write` 尚未读取该模型（P4-04）；Activity Recognition 仅作为 frame 字段，未接系统回调。
- 结论：`UNIT TEST VERIFIED`；P4-05 仍 `WIP`。
- 全量回归（2026-11-06）：279 项 JVM 用例 0 失败（core 120、nmea 7、xposed 30、app 122）；`:detector:assembleDebug` 通过。

---

## 17. P5 实施记录（2026-11-06）
### P5-01 RoutingTable

- 变更：`:core` 新增 `RoutingMode`/`AppRoute`/`RoutingConfig`/`RoutingDecision`/`RoutingTable`；优先级为 GLOBAL 模式 → system/UID<10000 → denied → 精确 package → UID → global scenario → 真实全局；`RoutingConfig` schemaVersion=1、路由/拒绝列表上限、JSON roundtrip。
- 检查：`RoutingTableTest` 9 项（GLOBAL、package 优先且大小写不敏感、UID fallback、system 绕过、deny、disabled fallback、无全局、非法/重复、JSON）通过。
- 未完成：system_server 调用方 UID→package 查询、listener 映射、per-app UI 与配置下发（P5-02/03/04）。
- 结论：`UNIT TEST VERIFIED`；P5-01 仍 `WIP`。

### P5-02 调用方路由解析（部分）

- 变更：`:core` 新增 `PackageUidLookup`/`CallerRouter`；`RoutingTable` 支持多 package（共享 UID）解析，任一 package 命中路由即可；system package 优先绕过；denied 优先。
- 变更：新增 `ListenerRouting`，在 `addLocationListenerInner` 注册时捕获 caller UID；PER_APP 配置下 denied package 不再收到假更新，其它 listener 仍用当前单场景 fake；`put_config.routing_json` 下发配置。
- 变更：新增 `ScenarioSampleStore`（按 scenarioId 隔离、sequence/租约/上限），`publish_sample` 可携带 `scenario_id`；`LocationBackend.publish` 将 `ScenarioController` 的 `scenarioId` 传到后端，`ListenerRouting` 命中 scenario 时优先使用该场景样本，缺失则回退当前 fake。
- 变更：`AppRoute` 新增可选 `point`（与 scenarioId 互斥），`RoutingDecision.Point` + `ListenerRouting.Route.Point` + `ProviderView` 固定点样本；命中时坐标固定、速度/航向归零。
- 变更：`WlanHook` 与 `CellInfoSynthesis` 按调用方 package 解析固定点，WiFi/基站环境跟随该点生成；未命中继续当前场景。
- 变更：新增 `GnssListenerRouting`，`registerGnssStatusCallback` 记录 listener→package/UID；`onSvStatusChanged` 用该调用方的固定点生成 `CatalogGnssSkyModel` 几何。
- 变更：新增 `NmeaListenerRouting`，`registerGnssNmeaCallback` 记录 listener→package/UID；`NmeaBridge` 按固定点生成 GGA/RMC/等句子（速度/航向归零），未命中继续当前场景。
- 检查：`CallerRouterTest` 5 项 + `RoutingTableTest` 11 项 + `ScenarioSampleStoreTest` 5 项通过；`:core:test`/`:xposed`/`:app` 通过；无设备。
- 未完成：多控制器并发、路由配置导入导出/搜索、设备验证；固定点未做传感器/环境联动。
- 结论：`UNIT TEST VERIFIED`（存储/解析）+ `BUILD VERIFIED`（接线）；P5-02 仍 `WIP`。

### P5-04 per-app UI（部分）

- 变更：设置页新增“per-app 路由（实验）”按钮，列出非系统应用多选，保存为 `RoutingConfig` 的 `deniedPackages`，随 `routing_json` 下发。
- 变更：设置页新增“per-app 固定点（实验）”，从已保存位置选一个点，再选应用，生成 `AppRoute(package, point)`；其余应用继续当前场景；保存后尝试热更新。
- 变更：应用列表改为 `queryIntentActivities(MAIN/LAUNCHER)`，manifest 增加对应 `<queries>`，避免 Android 11+ 包可见性过滤导致列表不完整。
- 变更：GMS per-app 结论写入 `08-gms-per-app.md`：GMS 仅全局，per-app 走 AOSP Provider/固定点；默认关闭 GMS，不在 UI 宣称 GMS per-app。
- 检查：`:app:compileAppDebugKotlin`/`:app:testArm64DebugUnitTest` 通过；无设备。
- 未完成：搜索、按场景分配、工作资料、导入/导出 UI；固定点仅坐标/速度/航向，传感器/环境未联动。
- 结论：`BUILD VERIFIED`；P5-04 仍 `WIP`。
- 全量回归（2026-11-06）：395 项 JVM 用例 0 失败（core 228、nmea 7、xposed 35、app 125）；`:detector:assembleDebug` 通过。

### P4-04 Native 传感器（部分）

- 变更：`main.cpp` 新增 motion state atomics 与 `Dobby.setMotionState` JNI；`sensor_hook.cpp` 在 `ConvertToSensorEvent` 中先调用原始转换，再按 sensor type 改写加速度/陀螺仪/地磁/气压/step counter/step detector；删除旧 `-1/0` 清零。
- 变更：`RemoteCommandHandler.publish_sample` 在 native 库已加载时按速度映射 STILL/WALK/RUN/VEHICLE 并下发 motion state。
- 检查：`:xposed:compileDebugKotlin` / `:xposed:testDebugUnitTest` 通过；NDK 30 clang++ `-fsyntax-only`（arm64）通过 `main.cpp` 与 `sensor_hook.cpp`；无设备。
- 未完成：真机 `SensorEventQueue` 行为、4 ABI 完整 CMake 构建、传感器与固定点/GNSS 一致性、C10 验证、sensor 默认关闭策略。
- 结论：`SOURCE VERIFIED` + 局部 `BUILD VERIFIED`；P4-04 仍 `WIP`。

### P5-03 ConfigEnvelope v2

- 变更：`:core` 新增 `ConfigEnvelope`（schemaVersion=2、scenario、routing、environment、noise、providers、revision、updatedAt）与 `ConfigCodec`；v1 配置自动迁移并填默认；未知字段忽略；未来版本/非法 JSON/重复 provider 拒绝；`RoutingConfig` 增加重复 package/UID 校验。
- 检查：`ConfigEnvelopeTest` 6 项（v2 roundtrip、v1 迁移、未知字段、未来/缺失版本、非法 envelope、默认 provider）通过。
- 追加：`SampleEnvelope` v2（provider/fieldsMask/environmentVersion/configRevision）+ `SampleCodec`，原始 v1 `LocationSample` JSON 自动包装；`SampleEnvelopeTest` 6 项通过。
- 追加：`SampleWire` v2（Bundle 字段 provider/fieldsMask/env/config revision），读写接受 v1–v2，app 改用 `isCompatible`；`SampleWireTest` 2 项通过。
- 追加：`ScenarioRuntime.applyConfigNow()` 在服务已连接时把当前 routing/回放配置经 `put_config` 推送，无需重启场景；per-app 保存与“用于 WiFi 回放”均会尝试立即应用。
- 未完成：ConfigEnvelope 持久化迁移、完整配置 UI、字段级 diff/回滚；xposed 侧已支持热更新，但未设备验证。
- 结论：`UNIT TEST VERIFIED`；P5-03 仍 `WIP`。

### P5-06 SampleStore / ConfigStore

- 变更：`:core` 新增 `StoredSample`、`SampleStore`/`InMemorySampleStore`（单调 sequence、elapsed 租约、clear、线程安全）与 `ConfigStore`/`InMemoryConfigStore`（ConfigEnvelope 存取）。
- 变更：新增 `FileConfigStore`：ConfigCodec JSON 落盘，temp + `ATOMIC_MOVE`/`REPLACE_EXISTING`，损坏文件返回 null。
- 检查：`SampleStoreTest` 6 项、`FileConfigStoreTest` 2 项（roundtrip/clear、损坏文件）通过。
- 未完成：Binder/SharedMemory 实现、接入 `RemoteCommandHandler`/`FakeLoc`、SHM 设计。
- 结论：`UNIT TEST VERIFIED`；P5-06 仍 `WIP`。

### P5-07 RuntimeSnapshot（部分）

- 变更：`:core` 新增不可变 `RuntimeSnapshot`（configRevision、environmentVersion、sample、environment、provider、routing、gnssModel）与原子 `RuntimeSnapshotStore`（publish/clear/update）。
- 变更：`FakeLoc` 的样本字段改为 `RuntimeSnapshotStore`，`acceptSample`/`snapshot`/`clear` 通过快照读写；外部坐标/速度等旧字段暂时保留。
- 变更：`routingConfig`/`replayEnvironment`/`gnssModelMode` 也改为 snapshot getter/setter；AP 数/基站模式/SIM/噪声等配置现已全部进入快照，setter 自增 `configRevision`，环境 profile 变更同时自增 `environmentVersion`。
- 变更：纬度/经度/海拔/速度/精度/航向也进入 snapshot，`FakeLoc` 不再保留独立位置字段；Hooks 通过属性读取同一快照。
- 检查：`RuntimeSnapshotTest` 6 项（空快照、原子发布、字段保留、清空样本、非法版本、配置字段）通过；`:xposed` 编译/测试通过。
- 未完成：Hook 仍有少量旧字段读取路径，需逐步替换为直接读 snapshot。
- 结论：配置/样本/环境/路由/GNSS 模式迁移 `BUILD VERIFIED`；P5-07 仍 `WIP`。

### P5-08 SettingsSchema（部分）

- 变更：`:core` 新增 `SettingType`/`SettingSpec`/`SettingsSchema`/`PortalSettingsSchema`，覆盖 altitude/speed/accuracy/report_interval/min_satellites/AP 数/cell_mode/environment_source/gnss_model 等 key，提供默认值、范围、枚举和 `coerce`。
- 变更：新增 `ConfigGate`，`put_config` 在写入 `FakeLoc` 前用 schema 拒绝非法数值/枚举。
- 变更：`put_config` 配置逻辑拆到 `ConfigCommand.apply()`，`RemoteCommandHandler` 只负责分发；行为保持不变。
- 变更：`get_runtime_status` 诊断逻辑拆到 `DiagnosticCommand.status()`，handler 继续只做分发。
- 变更：native 生命周期（load/prepare/status/motion/disable）拆到 `NativeCommand`，`isLoadedLibrary` 状态从 handler 移除。
- 变更：`publish_sample` 与 publication lease/restore/expire 拆到 `SampleCommand`，handler 只保留命令分发。
- 变更：start/stop/查询/开关/简单设值命令拆到 `ControlCommand`，handler 再减少约 100 行。
- 变更：`update_location`/`broadcast_location`/`sync_config` 与坐标更新逻辑拆到 `LocationCommand`，handler 再减少约 80 行。
- 变更：相对 `move` 的归一化也拆到 `LocationCommand.normalizeMove()`，handler 只接收规范化后的绝对坐标。
- 变更：proxy 注册、incoming sequence 校验、跨进程转发拆到 `ProxyCommand`；`RemoteCommandHandler` 现在只做安全校验与命令分发。
- 检查：`SettingsSchemaTest` 5 项（默认值、数值/枚举/布尔校验、null 默认、coerce fallback、重复/非法 spec）通过；`:xposed` 编译/测试通过。
- 未完成：命令拆分后的设备行为回归；旧配置/未知 key 迁移。
- 结论：`UNIT TEST VERIFIED`；P5-08 仍 `WIP`。

### P3-04 Hook 合并（部分）

- 变更：`GnssHook` 删除重复的 `registerGnssNmeaCallback`/`onNmeaReceived` 改写，只保留 measurements/nav/antenna 与 HAL 处理；`LocationNMEAHook` 成为唯一 `onNmeaReceived` 改写点，并修正 `enableNMEA` 语义（关闭时抑制真实 NMEA，开启时由 `NmeaBridge` 用同一 `CatalogGnssSkyModel` 帧生成 GGA/RMC/GSA/GSV/GST/ZDA，按 talker/type 一对一替换，未知类型置空避免泄漏）。
- 变更：新增 `GnssStatusBridge`，`LocationServiceHook` 的 `onSvStatusChanged` 从固定 `CatalogGnssSkyModel` 取帧生成 `GnssStatus` 数组；删除 BDS 星表/`shuffled()`/未播种随机；`LocationServiceHook` 从 1028 行降到 847 行，行数基线下调。新增 `NmeaBridge` 生成 NMEA 并带 500ms 帧缓存。
- 检查：`:xposed:compileDebugKotlin` / `:xposed:testDebugUnitTest` 通过；`check_module_rules` 0 violation。
- 未完成：raw measurements/navigation message 仍被屏蔽（P3-05/P7）；NMEA 依赖真实回调驱动，无设备验证“目标 App 实际收到完整序列”；跨 talker 的 GSV 轮转仅在同类型回调内循环。
- 结论：单入口、统一 GnssStatus 与 NMEA 生成已 `BUILD VERIFIED`；P3-04 仍 `WIP`。

### P3-05 Raw GNSS 策略（部分）

- 变更：`:core` 新增 `RawGnssMode`/`RawGnssMeasurement`/`RawGnssPolicy`：默认 `OFF` 永不输出；`EXPERIMENTAL` 从同一 `GnssFrame.usedInFix` 生成确定性 pseudorange rate/carrier phase/state/multipath，CN0 阈值与数量上限可配。
- 变更：新增 `RawNavigationMessage`/`RawNavigationPolicy`：默认 `OFF`；`EXPERIMENTAL` 从同一帧生成确定性 24 字节 GPS LNAV/BDS D1/GAL F/GLO 消息，数量上限可配。
- 变更：新增 `KeplerElements`/`KeplerOrbit`/`DopplerShift`：二体 Kepler 传播、周期/半径校验、载波多普勒符号；无摄动/钟差。
- 变更：新增 `OrbitalGnssSkyModel`：Kepler→ECI→ECEF→ENU，按观测点生成 elevation/azimuth/CN0/usedInFix/DOP；独立于生产 `CatalogGnssSkyModel`。
- 变更：新增 `GnssModelMode`/`GnssSkyModelProvider` 与设置页“轨道 GNSS 模型（实验）”；`GnssStatusBridge`/NMEA 按 `FakeLoc.gnssModelMode` 选择 ANALYTIC 或 ORBITAL，默认 ANALYTIC。
- 检查：`RawGnssPolicyTest` 5 项 + `RawNavigationPolicyTest` 5 项 + `KeplerOrbitTest` 6 项 + `OrbitalGnssSkyModelTest` 5 项 + `GnssModelProviderTest` 2 项通过。
- 未完成：Kepler/星历/Doppler/钟差模型、`GnssHook` 接线、AntennaInfo、设备与 detector C13 验证。
- 结论：`UNIT TEST VERIFIED`；P3-05 仍 `WIP`。

### P6-01 libxposed API 102（spike）

- 变更：`xposed` 增加 `io.github.libxposed:api:102.0.0` `compileOnly`；新增 `LibXposedSpike : XposedModule`，重写 `onSystemServerStarting` 空实现，验证 API 可与旧 API 82 同时编译。
- 检查：`:xposed:compileDebugKotlin`、`:xposed:testDebugUnitTest` 通过。
- 未完成：模块入口/元数据迁移、system_server scope、热重载、LSPosed 加载验证、旧 API 回退开关。
- 结论：`BUILD VERIFIED`；P6-01 仍 `WIP`。

### P6-04 安全/权限审查（首轮）

- 变更：`APSService` 显式 `android:exported="false"`；`backup_rules.xml` 与 `data_extraction_rules.xml` 排除 `environment/`（采集的 WiFi/基站/BLE 快照），避免云备份/换机迁移带出敏感环境。
- 变更：继续排除 `fused_ext.xml`（坐标/回放/路由设置）与 `library/`（保存的路线/位置），避免备份带出敏感数据。
- 检查：合并 manifest（arm64Debug）中应用自身组件仅 `MainActivity` 导出（launcher 必需）、`LocationProbeActivity`/`ScenarioService`/`APSService` 均不导出；依赖侧仅 `ProfileInstallReceiver` 导出且受 `DUMP` 保护。
- 变更：manifest 增加 MAIN/LAUNCHER `<queries>`，per-app 应用列表改用 `queryIntentActivities`，不申请 `QUERY_ALL_PACKAGES`。
- 变更：`RemoteCommandHandler` 的 debug 命令日志改用 `SensitiveLog`，经纬度/海拔/sample 坐标/environment_json/routing_json/token 等字段输出 `<redacted>`。
- 检查：`SensitiveLogTest` 3 项通过；`:xposed` 编译/测试通过。
- 变更：新增 `09-security-review.md`，汇总导出组件、IPC/命令、日志、Bugly、备份策略与未完成项。
- 未完成：设备端运行时验证、其他 SDK 包可见性/网络行为、`allowBackup` 整体策略。
- 结论：`SOURCE VERIFIED` + `BUILD VERIFIED`；P6-04 仍 `WIP`。

### P6-06 文档/默认值对齐（部分）

- 变更：更新 `01-current-state.md` 的 Native 传感器、Provider 列表、NMEA、GNSS 行，移除“传感器 -1/0”“双重 NMEA”“随机 BDS”等过期描述。
- 检查：文档内容与当前 `sensor_hook.cpp`、`NmeaBridge`、`CatalogGnssSkyModel`、`ProviderRegistryHook` 对照；无构建影响。
- 未完成：README 与设置页的完整实验标记/默认值清单、逐项验收表。
- 结论：`SOURCE VERIFIED`；P6-06 仍 `WIP`。

## 20. P7 实施记录（2026-11-06）

### P7-04 性能探针（部分）

- 变更：`:core` 新增 `PerformanceProbe`：有界环形缓冲（256/4096）、p50/p95/max/mean，纯 JVM 可测。
- 变更：`SampleCommand.publish` 记录端到端发布耗时，`get_runtime_status` 输出 `publish_samples/p50/p95/max/mean` 纳秒值。
- 变更：app `XposedBackend.diagnose` 读取并展示 `PUBLISH_LATENCY`，p95 >100 ms 标记 WARN。
- 检查：`PerformanceProbeTest` 4 项（空、百分位、环形覆盖、clear/校验）通过；`:xposed`/`:app` 编译/测试通过。
- 未完成：CPU/内存/电池设备基线、预算阈值校准、设备展示。
- 结论：`UNIT TEST VERIFIED` + `BUILD VERIFIED`；P7-04 仍 `WIP`。

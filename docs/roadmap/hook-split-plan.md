# P0-06 · LocationServiceHook 拆分设计

> 状态：设计完成，拆分未开始（不改变现有行为）。
> 目标：把 1028 行的 `LocationServiceHook` 拆成“编排 + 单一职责 Hook”，并通过 `HookRegistry` 暴露每个入口的状态。
> 依据：[04-target-architecture.md](04-target-architecture.md) 第 11 节、ADR-015。

---

## 1. 现状职责

| 行范围（约） | 职责 | 目标文件 |
|---:|---|---|
| 41–158 | 北斗星表、常量、`MockGnssData` | `core/gnss/GnssSkyModel`（P3-01） |
| 160–172 | 入口：`invoke` / `onService` | `hook/dispatch/LocationDispatchHook` 编排 |
| 174–192 | 安装子 Hook（Basic/GNSS/Provider/MIUI/NMEA） | `hook/diagnostics/HookRegistry` 编排 |
| 194–220 | `getLastLocation` | `LocationDispatchHook` |
| 223–357 | `requestLocationUpdates` / `removeUpdates` / `register/unregisterLocationListener` | `LocationDispatchHook` |
| 358–383 | `addGnssBatchingCallback` / batch 分发 | `GnssStatusHook` 或 dispatch |
| 384–448 | geofence、地理编码、test provider | 拆到 `LocationDispatchHook` 或独立 `TestProviderHook` |
| 449–597 | `registerGnssStatusCallback` + BDS 随机星表 | `GnssStatusHook` + `core/GnssSkyModel` |
| 598–643 | `start/stopGnssBatch`、`requestListenerFlush` | `LocationDispatchHook` |
| 644–716 | 注释掉的 provider 列表 + `getCurrentLocation` | `ProviderRegistryHook` + dispatch |
| 717–822 | `sendExtraCommand`、location controller 包 | `CommandBridgeHook`（RemoteCommandHandler 一侧） |
| 823–954 | `ILocationListener` 回调、`callOnLocationChanged` | `LocationDispatchHook` |
| 955–1024 | `LocationManagerService` transact 兜底 | `LocationDispatchHook`（legacy fallback） |

---

## 2. 目标结构

| 文件 | 职责 | 预算 |
|---|---|---:|
| `hook/state/RuntimeSnapshot.kt` | 不可变配置/样本/环境快照 | ≤200 |
| `hook/dispatch/LocationDispatchHook.kt` | `getLastLocation` / `requestLocationUpdates` / listener / `getCurrentLocation` / transact fallback | ≤350 |
| `hook/provider/ProviderRegistryHook.kt` | provider 列表/属性/启用状态 | ≤200 |
| `hook/privacy/AppOpsHook.kt` | `OP_MOCK_LOCATION` | ≤150 |
| `hook/privacy/SettingsHook.kt` | `mock_location` / dev options | ≤150 |
| `hook/gnss/GnssStatusHook.kt` | `GnssStatus` / measurements | ≤250 |
| `hook/gnss/NmeaHook.kt` | NMEA 单入口 | ≤150 |
| `hook/environment/WifiHook.kt` | WiFi 扫描/连接/DHCP | ≤300 |
| `hook/environment/CellHook.kt` | 蜂窝/SIM/信号 | ≤300 |
| `hook/sensor/SensorHook.kt` | Xposed 传感器（可选） | ≤200 |
| `hook/diagnostics/HookRegistry.kt` | 状态/命中/失败 | ≤150 |
| `hook/dispatch/LocationServiceHook.kt` | 只做编排（保留类名入口） | ≤100 |

---

## 3. 增量迁移顺序（每步可回退）

1. **抽状态**：新增 `HookRegistry` 薄封装，`LocationServiceHook` 的入口注册到 `HookOwnership`，不改行为。
2. **抽 GNSS**：把星表/随机生成搬到 `:core` 的 `GnssSkyModel`（P3-01/02），`GnssStatusHook` 只读模型；保留旧路径 feature flag。
3. **抽 NMEA**：合并 `GnssHook` 与 `LocationNMEAHook`，只留一个 `onNmeaReceived` 改写点（P3-04）。
4. **抽 provider 列表**：恢复 `getProviders`/`getAllProviders`/`hasProvider`/`getProviderProperties` 到 `ProviderRegistryHook`（P1-04）。
5. **抽 listener/dispatch**：`LocationDispatchHook` 负责 listener 映射与 `callOnLocationChanged`；`LocationServiceHook` 变编排。
6. **抽 transact fallback**：`hookLocationManagerServiceV2` 移到 dispatch，标注 legacy。
7. **删旧代码**：所有行为由新 Hook + 模型覆盖后，删除 `LocationServiceHook` 内的旧实现，并更新行数预算基线。

---

## 4. 拆分约束

- 每一步都必须保持现有 `HookStatusRegistry` key 或明确改名并更新 `HookOwnership`。
- 每一步先跑 `:xposed:testDebugUnitTest`，设备证据按 [06-validation.md](06-validation.md) 记录。
- 不允许在拆分 PR 里同时改 GNSS/NMEA 行为；行为变更单独提交。
- 单文件超过 400 行必须说明原因并加入 `docs/roadmap/line-budget-baseline.json`（只允许存量，不允许新文件）。

---

## 5. 验收

- `LocationServiceHook` 只做编排，≤100 行。
- 每个新 Hook 在 `HookOwnership` 有入口，且 `hook_details` 能看到 method-level 命中。
- 设备回归：system_server 启动、模拟中、停止后各一次，行为与拆分前一致。
- P0-06 评审通过后，才进入 P1 的 Hook 修改。

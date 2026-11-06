# P0-04 · Hook 所有权与状态清单

> 目的：让“某个 Hook 到底装没装、命中没有、为什么跳过/失败”有统一答案。
> 代码：`xposed/.../hook/scope/HookOwnership.kt`、`HookStatus.kt`、`HookInstaller.kt`；app 侧 `XposedBackend.diagnose()`。

---

## 1. 状态模型

`HookStatusRegistry` 以字符串 key 记录每个 Hook 点，状态字段：

| 字段 | 含义 |
|---|---|
| `supported` | 当前进程/系统版本支持这个 Hook |
| `installed` | Hook 已成功安装 |
| `matched` | 至少命中过一次（安装时挂一个一次性观察 Hook） |
| `skipped` | 有明确原因不装（类/方法不存在、进程范围外、TARGET_APP 等） |
| `failed` | 安装抛异常（`reason` 记录异常类名） |

所有通过 `utils/Xposed.kt` 扩展安装的 Hook 都会经过 `HookInstaller`，自动记录 method-level 状态；`FakeLocation` 的 `install()` 记录 top-level 入口状态。

---

## 2. 进程与所有权

| 进程 | scope | 期望入口（`HookOwnership`） | 实现 |
|---|---|---|---|
| system_server | `SYSTEM_SERVER` | `system-location`、`telephony-registry`、`wifi`、`fused`、`sensor`、`system-sdk` | `FakeLocation.startFakeLocHook` |
| com.android.phone | `PHONE_PROCESS` | `phone` | `TelephonyHook` |
| com.android.location.fused | `FUSED_PROCESS` | `fused` | `AndroidFusedLocationProviderHook` |
| 厂商 fused（小米/OPPO） | `VENDOR_LOCATION_PROCESS` | `xiaomi` 或 `oplus` | `ThirdPartyLocationHook` / `OplusLocationHook` |
| 目标 App | `TARGET_APP` | 无（按 ADR-002 明确跳过） | `FakeLocation.handleLoadPackage` |

---

## 3. 当前 Hook 明细

### 3.1 system_server

| 状态 key | 类 | 覆盖点 | 备注 |
|---|---|---|---|
| `system-location` | `LocationServiceHook`（1028 行） | `getLastLocation`、`requestLocationUpdates`、`removeUpdates`、`register/unregisterLocationListener`、`addGnssBatchingCallback`、`requestGeofence`、`getFromLocation*`、`add/remove/setTestProvider*`、`registerGnssStatusCallback`、`start/stopGnssBatch`、`requestListenerFlush`、`getCurrentLocation`、`sendExtraCommand`、`setExtraLocationController*`、`ILocationListener` 回调 | 行数超预算，P0-06 拆分 |
| `telephony-registry` | `TelephonyHook`（551 行） | `TelephonyRegistry` Binder / 蜂窝字段 | 超预算，P0-06/后续拆分 |
| `wifi` | `WlanHook` | `WifiServiceImpl` 扫描/连接/DHCP | 当前返回空列表 + 哨兵 BSSID（D06） |
| `fused` | `AndroidFusedLocationProviderHook` | Android fused provider | |
| `sensor` | `SystemSensorManagerHook` | 传感器监听器 | 当前空实现 / native 清零（D10） |
| `system-sdk` | `ThirdPartyLocationHook` | 系统 SDK 位置的兜底 | |

### 3.2 其他进程

| 状态 key | 进程 | 说明 |
|---|---|---|
| `phone` | `com.android.phone` | 运营商/信号字段 |
| `fused` | `com.android.location.fused` | Android fused provider |
| `xiaomi` | `com.xiaomi.location.fused` | 小米 fused |
| `oplus` | `com.oplus.location` | OPPO fused |

---

## 4. 如何读取

`RemoteCommandHandler` 的 `get_runtime_status` 返回：

- `hook_scope`：当前进程 scope（未知时 `unknown`）
- `hook_expected` / `hook_missing` / `hook_missing_points`：`HookOwnership` 审计结果
- `hook_installed` / `hook_matched` / `hook_failed` / `hook_skipped`：汇总计数
- `hook_details`：最多 128 条 method-level 明细

App 侧 `XposedBackend.diagnose()` 会追加一条 `HOOK_OWNERSHIP` 诊断；运行页的“能力与诊断”可展开查看。

---

## 5. 已知限制

1. **跨进程不可见**：每个进程有独立 registry，app 通过 `sendExtraCommand` 只能读到 system_server 的 registry；phone/fused/vendor 进程的状态要从各自进程日志或后续 IPC 汇总。
2. **子 Hook 归属仍只在 method-level**：`system-location` 是一个大入口，内部任何一个 method Hook 失败时，`system-location` 仍显示 installed；需要看 `hook_details` 的 `Xxx#yyy` 项。
3. **TARGET_APP 永远为空**：这是设计，不是缺陷。
4. **registry 是有界内存表**：2048 条上限，进程重启后清空；不是持久审计日志。

---

## 6. 验收

- JVM：`HookOwnershipTest` 覆盖 `missing`、`failed`、完整 scope、`TARGET_APP`。
- 设备：启动后 `get_runtime_status` 返回非空 `hook_scope`；`hook_expected` 与设备实际 scope 匹配；`hook_missing` 为空或能解释。
- 证据记录到 [06-validation.md](06-validation.md)。

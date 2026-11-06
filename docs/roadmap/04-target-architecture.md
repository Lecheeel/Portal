# 04 · 目标架构

> 目标：在不破坏 Portal 现有优势的前提下，把“坐标一致”升级为“环境一致”，并让结构可长期维护。
> 这不是一次性重写方案；第 6 节给出增量迁移顺序。

---

## 1. 目标与非目标

### 目标

1. **单一真相源**：位置、GNSS、NMEA、WiFi、基站、传感器都从同一个 `RuntimeSnapshot` 派生。
2. **环境自洽**：目标坐标、时间、运动状态决定周边环境，而不是各 Hook 各自返回假值。
3. **可测试**：纯逻辑在 `:core`，JVM 可测；Hook 只做 Android 适配。
4. **可回退**：每个新能力有 feature flag；实验能力默认关闭。
5. **可维护**：Hook 文件变小，职责单一，状态可诊断。

### 非目标

- 不在 target app 默认注入。
- 不做沙盒/相机/NFC/麦克风。
- 不伪造 Play Integrity/TEE。
- 不把 SharedMemory 作为第一版数据平面。
- 不迁移 Compose。

---

## 2. 模块与依赖

```text
:app (Android UI / services / backends)
  ├─ depends on :core
  ├─ depends on :xposed (implementation)
  └─ optional :detector (debug only, not dependency)

:xposed (Android library, hooks + native)
  ├─ depends on :core
  ├─ depends on :nmea
  └─ compileOnly :system-api

:core (pure Kotlin/JVM)
  ├─ geo / scenario / playback / runtime / repository
  ├─ environment / gnss / sensors / providers / routing
  └─ no Android imports

:nmea (pure Kotlin/JVM)
  ├─ parse + generate NMEA
  └─ no Android imports

:system-api (compileOnly stubs)
  └─ system classes for hook compilation

:detector (independent Android app)
  ├─ only public Android APIs + reflection
  └─ no dependency on :app/:xposed internals
```

### 依赖规则（CI 检查）

1. `:core` 不出现 `android.*`、`androidx.*`。
2. `:nmea` 不出现 `android.*`。
3. `:xposed` 不依赖 `:app`。
4. `:app` 不依赖 `:detector`。
5. `:detector` 不依赖 `:app`/`:xposed` 的实现类。
6. 新增模块必须写明依赖方向，并在 `docs/roadmap/README.md` 更新模块表。

---

## 3. 核心模型（`:core`）

### 3.1 场景与运动

保留现有 `Scenario`、`Route`、`MovementProfile`，扩展：

```kotlin
@Serializable
data class ScenarioConfig(
    val scenario: Scenario,
    // 用 List 而不是 Map，避免 Kotlinx JSON 对 data class/enum 作为 map key 的序列化限制
    val providers: List<ProviderProfile>,
    val noise: NoiseProfile
)
```

`MovementProfile` 的 `accuracyMeters`/`altitudeMeters` 保留兼容，但实际输出由 `ProviderProfile` 决定，避免三路共用同一值。

### 3.2 Provider 画像

```kotlin
enum class ProviderKind { GPS, NETWORK, FUSED }

@Serializable
data class ProviderProfile(
    val kind: ProviderKind,
    val intervalMs: Long,              // gps 1000, network 2000-5000, fused 1000
    val accuracyMeters: Float,         // gps 4-8, network 30-100, fused 5-15
    val verticalAccuracyFactor: Float, // 垂直精度通常差于水平
    val latencyMs: Long,               // network 可更旧
    val supportsBearing: Boolean,
    val supportsAltitude: Boolean,
    val supportsSpeed: Boolean,
    val accuracyDriftMeters: Float
)
```

默认：

| Provider | 频率 | 水平精度 | 垂直精度 | 航向 | 海拔 | 备注 |
|---|---:|---:|---:|---|---|---|
| GPS | 1 Hz | 4–8 m | 1.5–3× | 运动时有 | 有 MSL | 主路径 |
| NETWORK | 0.2–0.5 Hz | 30–100 m | 2–3× | 通常无 | 可为 0 | 时间更旧 |
| FUSED | 1 Hz | 5–15 m | 1.5–2× | 运动时有 | 有 | 平滑 |

### 3.3 静止策略

```kotlin
@Serializable
data class StationaryPolicy(
    val speedThresholdMps: Double = 0.3,
    val bearingHoldSeconds: Double = 2.0,
    val accuracyDriftMeters: Float = 0.5f,
    val stepRatePerMin: Int = 0,
    val activity: ActivityKind = ActivityKind.STILL
)
```

规则：

- 速度 < 阈值：`hasBearing=false`，不输出 0° 航向；速度输出 0；计步不增长。
- 静止 10 秒后：精度可小幅漂移，但不要周期振荡。
- 从静止到运动：航向在 1–2 秒内渐变，不瞬间跳变。
- `Location.hasSpeed()`/`hasBearing()` 与实际字段一致，不要“字段有值但 speed=0”。

### 3.4 物理噪声

```kotlin
@Serializable
data class NoiseProfile(
    val enabled: Boolean = true,
    val ouAlphaPerSec: Double = 0.05,
    val ouSigmaMeters: Double = 0.8,
    val maxOffsetMeters: Double = 4.0,
    val altitudeAlpha: Double = 0.04,
    val altitudeSigmaMeters: Double = 0.45,
    val seed: Long = 0L
)
```

离散 OU：

```text
X_{t+Δ} = X_t · e^{-αΔ} + σ · sqrt((1 - e^{-2αΔ}) / (2α)) · Z
Z ~ N(0,1)，X clamp 到 ±maxOffsetMeters
```

- 关闭 `enabled` 时，`PlaybackEngine` 输出必须完全确定。
- 开启时，seed 固定则输出可复现；seed 由 session 生成。
- 精度/海拔各自独立 OU，不要常数。
- 不要用正弦/固定周期抖动；FFT 会看到单频峰。

### 3.5 环境模型

```kotlin
enum class EnvironmentSourceType { PROCEDURAL, REPLAY, DATABASE }
enum class CellMode { AUTO, FULL_REPLAY, NO_SIM_WIFI_ONLY, LEGACY_CDMA }
enum class CellType { GSM, WCDMA, LTE, NR }

@Serializable
data class EnvironmentConfig(
    val source: EnvironmentSourceType = EnvironmentSourceType.PROCEDURAL,
    val apPerCell: Int = 4,
    val cellMode: CellMode = CellMode.AUTO,
    val enableBle: Boolean = false
)

@Serializable
data class WifiAp(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val channel: Int,
    val capabilities: String,
    val standard: Int,
    val connected: Boolean = false
)

@Serializable
data class CellTower(
    val type: CellType,          // GSM/WCDMA/LTE/NR
    val mcc: Int,
    val mnc: Int,
    val areaCode: Int,           // LAC/TAC
    val cellId: Long,            // CID/NCI
    val pci: Int? = null,
    val arfcn: Int? = null,
    val rsrp: Int,
    val rsrq: Int? = null,
    val rssnr: Int? = null,
    val registered: Boolean = false
)

@Serializable
data class BleBeacon(
    val address: String,
    val name: String,
    val rssi: Int,
    val scanRecordHex: String?
)

@Serializable
data class EnvironmentProfile(
    val source: EnvironmentSourceType, // PROCEDURAL / REPLAY / DATABASE
    val origin: Wgs84?,
    val generatedAtMillis: Long,
    val seed: Long,
    val wifi: List<WifiAp>,
    val cells: List<CellTower>,
    val ble: List<BleBeacon>
)
```

`EnvironmentProfile` 是 WiFi/基站/BLE 的**慢变量**；GNSS 是**快变量**，单独建模（见第 5 节）。

```kotlin
interface EnvironmentSource {
    fun profileAt(point: Wgs84, seed: Long, atMillis: Long): EnvironmentProfile
}
```

### 3.6 GNSS 模型

```kotlin
enum class Constellation { GPS, BEIDOU, GLONASS, GALILEO, QZSS, SBAS, IRNSS }

@Serializable
data class GnssSatellite(
    val constellation: Constellation,
    val svid: Int,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val hasEphemeris: Boolean,
    val hasAlmanac: Boolean,
    val carrierHz: Float? = null
)

@Serializable
data class GnssFrame(
    val generatedAtMillis: Long,
    val satellites: List<GnssSatellite>,
    val hdop: Float,
    val vdop: Float,
    val pdop: Float
)

interface GnssSkyModel {
    fun frame(atMillis: Long, observer: Wgs84, altitudeMeters: Double): GnssFrame
}
```

### 3.7 传感器模型

```kotlin
enum class MotionMode { STILL, WALK, RUN, VEHICLE }
enum class ActivityKind { STILL, WALKING, RUNNING, IN_VEHICLE, ON_BICYCLE, UNKNOWN }

@Serializable
data class MotionState(
    val mode: MotionMode,
    val speedMps: Double,
    val bearingDeg: Double,
    val altitudeMeters: Double
)

data class SensorFrame(
    val accelerometer: FloatArray,   // m/s^2
    val gyroscope: FloatArray,       // rad/s
    val magnetometer: FloatArray,    // µT
    val barometerHpa: Float,
    val stepCount: Long,
    val stepCadence: Int,
    val activity: ActivityKind
)

interface SensorModel {
    fun frame(state: MotionState, atMillis: Long): SensorFrame
}
```

### 3.8 路由表

```kotlin
@Serializable
data class RouteKey(val packageName: String, val userId: Int)

@Serializable
data class RoutingRule(val key: RouteKey, val scenarioId: String)

@Serializable
data class RoutingTable(
    val defaultScenarioId: String,
    val rules: List<RoutingRule> = emptyList()
) {
    fun scenarioFor(key: RouteKey): String =
        rules.firstOrNull { it.key == key }?.scenarioId ?: defaultScenarioId
}
```

### 3.9 环境快照

```kotlin
data class EnvironmentFrame(
    val profileVersion: Long,
    val gnssVersion: Long,
    val profile: EnvironmentProfile,
    val gnss: GnssFrame
)
```

Hook 侧只读 `EnvironmentFrame`，不自己生成 AP/卫星。

---

## 4. 数据流

```text
ScenarioController (core)
   │ tick(now, wall)
   ▼
PlaybackEngine (core, deterministic)
   │ LocationSample
   ▼
PhysicalNoise (core, optional)
   │ LocationSample
   ▼
ProviderMixer (core)
   │ Map<ProviderKind, ProviderSample>
   ▼
EnvironmentCoordinator (core)
   │ EnvironmentFrame + NMEA
   ▼
SampleStore / ConfigStore
   │ Binder (now) / SHM (future)
   ▼
system_server RuntimeSnapshot (immutable)
   ├─ LocationDispatchHook  → 目标 App 的 Location 回调
   ├─ ProviderRegistryHook  →  getProviders/getProviderProperties
   ├─ AppOpsHook            →  OP_MOCK_LOCATION
   ├─ SettingsHook          →  mock_location/dev options
   ├─ GnssStatusHook        →  GnssStatus
   ├─ NmeaHook              →  onNmeaReceived
   ├─ WifiHook              →  ScanResult/WifiInfo/DhcpInfo
   ├─ CellHook              →  CellInfo/Telephony
   └─ SensorBridge          →  native SensorEventQueue
```

关键原则：

- `PlaybackEngine` 只负责“几何/时间/速度”，不负责环境。
- `EnvironmentCoordinator` 只负责“同一时刻的环境快照”，不负责 Location 分发。
- Hook 只读快照，不在回调里做网络/磁盘/复杂计算。
- 每个快照带版本；配置切换原子替换，不 stop/start 整个后端。

---

## 5. 环境管线

### 5.1 WiFi 三种来源

```text
EnvironmentSource
  ├─ ProceduralSource (默认)
  ├─ ReplaySource     (采集回放)
  └─ DatabaseSource   (实验，默认关)
```

#### ProceduralSource

- 地球按 geohash/固定网格切分，建议网格约 100 m。
- 每格固定 4 个 AP（可配置 3–8），由 `seed = hash(geohash, salt)` 生成。
- BSSID：真实厂商 OUI + 固定后缀；禁止随机非法 MAC。
- SSID：常见品牌/公共热点/用户自定义池。
- 信道/频率：2.4G/5G 合理组合。
- RSSI：`-30 - 10·n·log10(d) + Xσ`，室内 `n≈2.7`，`σ≈4 dB`；移动时在相邻格之间插值。
- 同一位置、同一 seed 必须生成同一组 AP；跨进程、跨重启一致。

#### ReplaySource

- 采集：真实地点记录 WiFi/基站/BLE，带时间、坐标、RSSI、频率、信道、capabilities。
- 回放：按目标坐标做 RSSI 重标定：

```text
RSSI_B = RSSI_A - 20·log10(d_B / d_A)
```

`d_A` 取室内 AP 典型可见半径（30 m），衰减上限 35 dB，结果 clamp 到合理范围。
- 构造器兼容：`ScanResult`/`CellIdentity` 隐藏构造器随 Android 版本变化，按实际 arity 回退。
- 缓存原子替换：`id/at/profile` 必须一起换，不能出现“新坐标 + 旧 AP”。

#### DatabaseSource（实验）

- WigLE / OpenCellID / 自建采集。
- 只保留目标坐标附近的子集，按距离/时间排序，限制条目数。
- 必须记录数据来源、许可证、抓取时间；默认关闭。
- 真实设备测试前，不作为默认路径。

### 5.2 基站两种画像

| 模式 | 内容 | 适用 |
|---|---|---|
| `FULL_REPLAY` | LTE/NR 主小区 + 邻区 + SIM/operator/service state 一致 | 有 SIM、需要完整蜂窝 |
| `NO_SIM_WIFI_ONLY` | cell 为空、phone type/SIM/operator 一致 | 无 SIM、WiFi-only 设备 |
| `LEGACY_CDMA` | 旧 CDMA 降级 | 仅兼容，默认关，下版本删 |

`AUTO` 默认：有 SIM → `FULL_REPLAY`；无 SIM → `NO_SIM_WIFI_ONLY`。

必须同步的接口：

- `getAllCellInfo` / `getCellLocation`
- `getNetworkOperator` / `getSimOperator` / `getNetworkOperatorName`
- `getDataNetworkType` / `getVoiceNetworkType` / `getPhoneType`
- `getSignalStrength` / `getServiceState`
- `SubscriptionManager.getActiveSubscriptionInfoList`
- `TelephonyCallback` / `PhoneStateListener` 注册路径

### 5.3 环境一致性检查

Detector 至少检查：

- WiFi 开 + 扫描为空 → 失败
- BSSID 是 `02:00:00:00:00:00` → 失败
- `getConnectionInfo` 的 BSSID 不在 `getScanResults` 里 → 失败
- cell 为空但 SIM/operator 有值 → 失败
- `getPhoneType=CDMA` 但 SIM operator 是 GSM/LTE → 失败
- GNSS 卫星数与 NMEA GSV 不一致 → 失败
- 静止但 bearing/step 在变 → 失败

---

## 6. GNSS / NMEA

### 6.1 单一生成器

```text
GnssSkyModel → GnssFrame
   ├─ GnssStatusHook 读取
   ├─ NmeaGenerator 读取
   └─ RawGnssModel（实验）读取
```

禁止：

- `LocationServiceHook` 里再维护一份 BDS 星表。
- `GnssHook` 和 `LocationNMEAHook` 各自改 `onNmeaReceived`。
- 每次回调 `shuffled()`。

### 6.2 三级模型

| 级别 | 内容 | 默认 | 阶段 |
|---|---|---|---|
| V1 | 多星座目录 + 确定性可见性 + CN0 随仰角 | 开 | P3-01/02 |
| V2 | 广播星历/Kepler 天空模型 + az/el/Doppler | 设备验证后开 | P3-05 |
| V3 | `GnssMeasurementsEvent` 原始测量（伪距/多普勒/载波相位/钟差） | 关（实验） | P7-01 |

V1 也要做到：

- GPS/BDS/GLONASS/Galileo 混合，不是全 BDS。
- 同一时刻卫星集合稳定；随时间缓慢变化。
- `usedInFix` 由 CN0/仰角决定，不是随机。
- `hasEphemeris`/`hasAlmanac`/`hasCarrierFrequency` 一致。
- `GnssStatus` 与 NMEA GSV/GSA 同源。

### 6.3 NMEA 生成器

输出：

- `GGA`：时间、纬经度、fix quality、卫星数、HDOP、海拔、大地水准面差距
- `RMC`：时间、状态、纬经度、速度、航向、日期
- `GSA`：fix 类型、卫星 PRN、PDOP/HDOP/VDOP、系统 ID
- `GSV`：每星座多句、卫星数、PRN、仰角、方位、CN0
- `GST`：精度统计（可选）
- `ZDA`：UTC 时间/日期（可选）

要求：

- 校验和正确。
- 卫星数、CN0、usedInFix 与 `GnssStatus` 一致。
- talker ID 与星座匹配（GP/BD/GL/GA）。
- UTC 时间与 `Location.time` 一致。
- 只由一个 Hook 负责写 `onNmeaReceived`。

### 6.4 原始测量（实验）

- `GnssMeasurementsEvent`：clock、measurements（伪距、多普勒、载波相位、C/N0）。
- `GnssNavigationMessage`：星历/历书。
- `GnssAntennaInfo`、`GnssCapabilities`：与设备能力一致。
- 目标：不是“每个 App 都用”，而是“高端检测器看不到明显缺失/矛盾”。
- 先做 feasibility spike，不承诺默认开启。

---

## 7. 传感器与融合

### 7.1 当前问题

- `SystemSensorManagerHook` 的 `onSensorChanged` 空实现。
- `sensor_hook.cpp` 把 `ASensorEvent` 写成 0/-1，等于破坏传感器。
- 没有运动状态到传感器值的模型。

### 7.2 目标

```text
MotionState → SensorModel → SensorFrame
   ├─ accelerometer: 重力 + 步态冲击 + 路面振动
   ├─ gyroscope: 航向变化率 + 噪声
   ├─ magnetometer: 航向 + 磁偏角 + 倾斜
   ├─ barometer: 气压高度公式
   ├─ step counter/detector: cadence + fractional accumulation
   └─ activity: STILL/WALK/RUN/VEHICLE + 迟滞
```

气压公式：

```text
P = 1013.25 · (1 - 2.25577e-5 · h)^5.25588   (hPa, h 米)
```

计步：

```text
stepsToAdd = (cadence / 60) · Δt
fractional += stepsToAdd
取整后累加，余数保留
```

### 7.3 Native 路径

- Hook `SensorEventQueue::write` 或 `SensorService::dispatchSensorEvent`，按 sensor type 改写 `ASensorEvent`。
- 不要在 `convertToSensorEvent` 里写死 -1。
- 符号解析/版本差异参考 Kail `sensor_simulator.cpp`、`hook.cpp`。
- 默认关闭，设备验证后开；Dobby inline hook 的卸载不保证干净，需注明重启。

### 7.4 Activity Recognition

- 按 `MotionState` 生成 `DetectedActivity`：
  - 静止 → `STILL`
  - 步行 → `WALKING`
  - 跑步 → `RUNNING`
  - 驾车 → `IN_VEHICLE`
- 加迟滞，避免 1 秒内反复切换。
- P7 再做 GMS Activity Recognition 的完整适配；先保证“不矛盾”。

---

## 8. Provider 与轨迹物理

### 8.1 ProviderMixer

输入同一个 `LocationSample`，输出三个 `ProviderSample`：

```kotlin
data class ProviderSample(
    val provider: ProviderKind,
    val sample: LocationSample,
    val fieldsMask: Int,
    val verticalAccuracyMeters: Float?,
    val speedAccuracyMps: Float?,
    val bearingAccuracyDeg: Float?,
    val mslAltitudeMeters: Double?,
    val elapsedRealtimeUncertaintyNanos: Long?,
    val satellites: Int?,
    val meanCn0: Float?,
    val maxCn0: Float?
)
```

- GPS：完整字段，1 Hz，精度 4–8 m。
- Network：更旧、更粗，通常无 bearing/alt，2–5 秒一次。
- Fused：平滑，1 Hz，精度 5–15 m。
- 静止：三路都不要输出 0° bearing；速度 0；精度可小幅漂移。
- 时间：network/fused 可有 100–500 ms 延迟；不要三路同一 `elapsedRealtimeNanos`。

### 8.2 物理噪声层

- `PlaybackEngine` 输出确定性几何。
- `PhysicalNoise` 叠加 OU 漂移、精度/海拔漂移、航向门控。
- 噪声不得造成：
  - 瞬移（速度突变超限）
  - 反向/绕圈（航向与位移不一致）
  - 静止时持续漂移出合理范围
- 测试：关闭噪声 → 逐点确定；开启噪声 → 有界、可复现、速度/距离一致。

### 8.3 轨迹增强（按需）

- DEM 海拔：SRTM/Copernicus 离线瓦片，MSL/WGS84 分开。
- 红绿灯：OSM `highway=traffic_signals` 或高德路况；停 5–30 秒，带减速/加速。
- 步频与速度耦合：步行 100–120，跑步 160–180。
- 地图匹配：继续用高德路线几何，避免 waypoint 直线。

---

## 9. per-app 路由

### 9.1 设计

```text
RoutingTable(defaultScenarioId, rules[(packageName,userId) -> scenarioId])
```

system_server 侧：

1. `Binder.getCallingUid()` → `PackageManager.getPackagesForUid()`。
2. `UserHandle.getUserId(uid)` → `(packageName, userId)`。
3. 查 `RoutingTable`，没有则用 default。
4. 选择对应 `RuntimeSnapshot`/`EnvironmentFrame`。
5. 在 `getLastLocation`、`requestLocationUpdates`、`getCurrentLocation`、`LocationProviderManager` 分发处应用。

`requestLocationUpdates` 需要 `listener → route` 映射；`LocationTicker` 按 route 发布。

### 9.2 GMS 限制

- GMS Fused 的调用方是 `com.google.android.gms`，system_server 看不到目标 App UID。
- 选项：
  A. 只保证直接 `LocationManager` 调用的 per-app；GMS 走全局（默认）。
  B. Hook GMS 进程（增加 target/system app 足迹）。
  C. target app 兜底（破坏 ADR-002）。
- 本路线图默认 A，Q-02 再评估 B。

### 9.3 安全

- 只有 controller 能改 `RoutingTable`；peer 只能 `sync_config`。
- 配置带 `configVersion`，原子替换。
- 不允许任意 App 指定别人的 package/user。
- 记录诊断：命中/跳过/失败。

---

## 10. IPC / 配置 / 样本

### 10.1 ConfigEnvelope v2

```kotlin
data class ConfigEnvelope(
    val protocolVersion: Int,
    val configVersion: Long,
    val scenario: ScenarioConfig,
    val environment: EnvironmentConfig,
    val routing: RoutingTable
)
```

- 用 Bundle 原始类型或 JSON 字符串；大环境数据分块。
- 配置变更不 stop/start 后端；`RuntimeSnapshot` 原子替换。
- 旧版本配置可读，未知字段忽略；版本不匹配拒绝并诊断。

### 10.2 SampleWire v2

在现有 `SampleWire` 基础上增加：

- `provider`
- `fieldsMask`
- `verticalAccuracy`
- `speedAccuracy`
- `bearingAccuracy`
- `mslAltitude`
- `elapsedRealtimeUncertaintyNanos`
- `satellites`/`meanCn0`/`maxCn0`
- `environmentVersion`/`gnssVersion`

保持向后兼容：旧 reader 忽略新字段；新 reader 对旧样本用默认值。

### 10.3 数据平面

```kotlin
interface SampleStore {
    fun publish(version: Long, samples: Map<ProviderKind, ProviderSample>)
    fun snapshot(version: Long? = null): SampleSnapshot
}

interface ConfigStore {
    fun publish(config: ConfigEnvelope)
    fun snapshot(): ConfigEnvelope
}
```

- 第一版：Binder + `AtomicReference`。
- 第二版（仅当 per-app/高频证明需要）：SharedMemory + seqlock/版本号。
- 5 秒租约保留；配置版本与样本序号独立。

---

## 11. Hook 拆分

当前 `LocationServiceHook` 约 900 行，目标拆成：

| 文件 | 职责 | 目标行数 |
|---|---|---:|
| `hook/state/RuntimeSnapshot.kt` | 不可变配置/样本/环境快照 | ≤200 |
| `hook/dispatch/LocationDispatchHook.kt` | `getLastLocation`/`requestLocationUpdates`/`getCurrentLocation`/`LocationProviderManager` | ≤350 |
| `hook/provider/ProviderRegistryHook.kt` | provider 列表/属性/启用状态 | ≤200 |
| `hook/privacy/AppOpsHook.kt` | `OP_MOCK_LOCATION` | ≤150 |
| `hook/privacy/SettingsHook.kt` | `mock_location`/dev options | ≤150 |
| `hook/gnss/GnssStatusHook.kt` | `GnssStatus`/`GnssMeasurements` | ≤250 |
| `hook/gnss/NmeaHook.kt` | NMEA 单入口 | ≤150 |
| `hook/environment/WifiHook.kt` | WiFi 扫描/连接/DHCP | ≤300 |
| `hook/environment/CellHook.kt` | 蜂窝/SIM/信号 | ≤300 |
| `hook/sensor/SensorHook.kt` | Xposed 传感器（可选） | ≤200 |
| `hook/diagnostics/HookRegistry.kt` | 状态/命中/失败 | ≤150 |

规则：

- 每个 Hook 只做 Android 适配，复杂策略在 `:core`。
- 每个 Hook 在 `HookRegistry` 注册 supported/installed/matched/skipped/failed。
- 单文件超过 400 行必须拆分或下沉。

---

## 12. 隐蔽性

### 12.1 模块形态

- 迁移 libxposed API 102：
  - `META-INF/xposed/module.prop`
  - `META-INF/xposed/java_init.list`
  - `META-INF/xposed/scope.list`
- 移除 manifest `xposedmodule`/`xposeddescription`/`xposedminversion`/`xposedscope`。
- 可选：拆独立模块 APK，主 App 不再带 Xposed 元数据。

### 12.2 作用域

- 必选：`android`（system_server）。
- 新增：`com.android.providers.settings`（Settings）。
- 现有：`com.android.phone`、`com.android.location.fused`、`com.xiaomi.location.fused`、`com.oplus.location`。
- 不默认加 target app。

### 12.3 痕迹

- 不在 target app 进程加载 Xposed 类。
- 不在高频路径写日志/文件。
- 不查询公网/本机路径。
- Native 实验轨道才考虑 `/proc/self/maps`、`TracerPid`、库名隐藏；默认不做。

### 12.4 包可见性

per-app UI 需要读取已安装应用列表；这可能被目标 App 视为敏感权限。优先 `<queries>` 精确声明，避免 `QUERY_ALL_PACKAGES`；如必须使用，文档标注。

---

## 13. Native 实验轨道

### 13.1 两条线

| 线 | 内容 | 默认 | 风险 |
|---|---|---|---|
| N1 | system_server 内 native：传感器、自身痕迹 | 关 | 中 |
| N2 | target app ptrace/slim dex 注入 | 关 | 高 |

本路线图先做 N1；N2 只在 Q-03 决策后单独立项。

### 13.2 N1 落地

- 保留现有 `/data/local/ext-lib/liblocationext.so` 加载方式；root 复制路径校验/引号处理。
- 重写 `sensor_hook.cpp`：Hook `SensorEventQueue::write`/`dispatchSensorEvent`，按 sensor type 改写。
- 增加 native 诊断：符号找到/未找到、hook 安装/失败、ABI/页大小。
- 默认关闭；设置页明确“需要重启、仅实验”。
- 失败回滚：停止时禁用行为；无法卸载的 hook 需重启。

### 13.3 N2 前置条件

只有同时满足才考虑：

1. N1 在至少 3 个 ROM 上稳定。
2. 有明确的 target app 需求（GMS per-app 或特定检测）。
3. 有崩溃哨兵/回滚方案。
4. 维护者明确同意承担风险。

---

## 14. 长期可维护性规则

1. **单文件预算**：Hook ≤400 行；超过拆分。
2. **纯逻辑下沉**：能在 JVM 测的，不放在 Hook。
3. **状态不可变**：`RuntimeSnapshot` 替代 `FakeLoc` 全局可变字段；逐步迁移。
4. **命令分域**：`RemoteCommandHandler` 拆成 config/sample/control/diagnostic 四类。
5. **设置 schema**：`Perfs.kt` 的散落 key 收敛到 `SettingsSchema`，带默认值/范围/迁移。
6. **错误可诊断**：禁止空 `catch`；所有失败进 `HookRegistry`/`Diag`。
7. **Feature flag**：实验能力有开关、默认关闭、UI 标注。
8. **文档同步**：每个阶段更新 `01/05/06`；新决策写 ADR。
9. **测试门槛**：纯逻辑单测；Hook 变更 detector；发布前设备证据。
10. **依赖治理**：新增依赖写许可证/用途/体积；不引入 GPL 到非 GPL 项目。

---

## 15. 增量迁移顺序

不要一次性重写。建议：

1. 先建 `:detector`，锁定现状。
2. 先修 AppOps/Settings/Provider 列表/Location 字段（小、独立、收益高）。
3. 再抽 `EnvironmentProfile`/`EnvironmentFrame`，WiFi/基站 Hook 改为读模型。
4. 再抽 `GnssFrame`/`NmeaGenerator`，合并双 Hook。
5. 再做 Provider 画像/噪声/传感器。
6. 最后做 per-app、libxposed、native 实验轨道。
7. 每一步保持旧路径可回退；feature flag 默认关闭。

---

## 16. 架构验收

- `:core` 无 Android import。
- `:xposed` 不依赖 `:app`。
- 单个 Hook 文件 ≤400 行（新增/重构）。
- `LocationServiceHook` 拆分后，主文件只做编排。
- GNSS/NMEA 只有一个生成器。
- WiFi/基站/传感器 Hook 只读 `RuntimeSnapshot`。
- 所有实验能力有 flag、默认关闭、UI 标注。
- detector 能覆盖本文第 5.3 节的全部一致性检查。

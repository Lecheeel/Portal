# P0-03 · 设备基线报告 Runbook

> 状态：DONE（2026-11-08，双设备基线见 [2026-11-08-xiaomi-two-device.md](2026-11-08-xiaomi-two-device.md)）。
> 目的：把“模拟前 / 模拟中 / 停止后”的 detector 结果固化成可对比的基线。

---

## 1. 前置

- 安装 Portal（目标设备上已启用对应后端：标准 Mock / Xposed）。
- 构建 detector：

```powershell
$env:GRADLE_USER_HOME="C:\Users\ryanc\Desktop\Portal\.gradle-home"
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :detector:assembleDebug --offline --console=plain
adb install -r detector/build/outputs/apk/debug/detector-debug.apk
```

- 授予权限：

```powershell
adb shell pm grant com.system.location.service.detector android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.system.location.service.detector android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.system.location.service.detector android.permission.READ_PHONE_STATE
adb shell pm grant com.system.location.service.detector android.permission.NEARBY_WIFI_DEVICES
```

（Android 12 无 `NEARBY_WIFI_DEVICES`，跳过。）

---

## 2. 三次运行

对每台设备、每种后端：

1. **模拟前**：Portal 未开始模拟；detector 设置采样 60 秒（轨迹检查可 120 秒）；运行并导出 JSON。
2. **模拟中**：Portal 单点或路线运行 ≥60 秒后再运行 detector；保持模拟不停止。
3. **停止后**：Portal 停止并等待 10 秒；再运行一次。

每次导出文件名：

```text
YYYY-MM-DD-<manufacturer>-<model>-android<API>-<state>.json
```

`<state>` = `before` / `during` / `after`。保存到 `docs/roadmap/baselines/<device>/`，含 WiFi/基站原始字段时先脱敏。

---

## 3. 记录模板

```text
# <设备> detector 基线

- 日期：
- 设备/ROM/Android/ABI：
- LSPosed/Root：
- Portal 版本/commit：
- 后端：MOCK / XPOSED / NATIVE
- 模拟模式：单点 / 路线 / 摇杆
- 采样时长：

| ID | 模拟前 | 模拟中 | 停止后 | 证据 |
|---|---|---|---|---|
| C01 |  |  |  |  |
| ... |  |  |  |  |
| C15 |  |  |  |  |

结论：
- 阻断级 FAIL（C01–C05、C08、C09）：
- 警告级 FAIL：
- 与上一版差异：
- 无法判定（UNKNOWN）及原因：
```

---

## 4. 设备矩阵

P0 至少：

1. Xiaomi HyperOS（Android 15/16）
2. Pixel/AOSP（Android 15/16）
3. API 35/37 模拟器（CI 或本地）

每台记录：ROM、Android、ABI、LSPosed 版本、Root 方案、16 KB 页（如适用）。

---

## 5. 当前阻塞

- 2026-11-08：已接入两台设备并完成基线采集；P0-03 不再阻塞。
- 2026-11-06 的历史记录：本机 `adb devices` 为空，无法产出 `DEVICE VERIFIED`；该条已由双设备基线取代。
- P6-05 仍受限于第二个 Xposed/LSPosed 版本矩阵，不要把本基线当作 P6-05 完成。

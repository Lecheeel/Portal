# `:detector` · Portal 验证应用

独立 Android 应用，用于在设备上回答“Portal 现在到底泄漏了什么”。它不依赖 `:app`/`:xposed`/`:core`，只使用 Android 公开 API + 反射，避免“用被测代码证明被测代码没问题”（ADR-020）。

## 构建

```bash
./gradlew :detector:assembleDebug
adb install -r detector/build/outputs/apk/debug/detector-debug.apk
```

detector 不参与 Portal 主 APK，也不进入 Release 分发。

## 使用

1. 授予定位、电话、WiFi 权限（首次启动会请求）。
2. 设置目标包名（默认 `com.system.location.service`）与采样时长（默认 20 秒）。
3. 分别记录：Portal 模拟前 / 模拟中 / 停止后，各运行一次。
4. 导出 JSON（SAF）或复制到剪贴板；报告默认不上传网络。

## 检查（v1）

| ID | 内容 | 级别 |
|---|---|---|
| C01 | Location mock 标记（含反射字段） | 阻断 |
| C02 | AppOps `OP_MOCK_LOCATION`（目标包 + 自身） | 阻断 |
| C03 | Settings `mock_location` / 开发者选项 | 阻断 |
| C04 | Provider 列表/属性/启用状态 | 阻断 |
| C05 | Location 字段、extras、MSL、不确定度、Availability | 阻断 |
| C06 | GnssStatus 卫星数/星座/CN0/usedInFix/稳定性 | 警告 |
| C07 | NMEA 校验和/卫星数/talker/与 GnssStatus 一致 | 警告 |
| C08 | WiFi 扫描/连接/DHCP/BSSID 合法性 | 阻断 |
| C09 | 蜂窝/SIM/运营商/phone type 自洽 | 阻断 |
| C10 | 传感器量级/变化/NaN | 警告 |
| C11 | 计步与运动状态一致性 | 警告 |
| C12 | 轨迹瞬移/时间戳/单频抖动 | 警告 |
| C13 | 目标包 Xposed meta-data | 警告（P6 后阻断） |
| C14 | root / `/proc` 痕迹（仅 detector 进程环境） | 研究 |
| C15 | 时区/locale/carrier，可选出口 IP | 研究 |

结果只有 `PASS` / `FAIL` / `UNKNOWN`；`UNKNOWN` 表示缺少证据，不等于安全。

## JSON

`schema=1`，字段见 `docs/roadmap/06-validation.md` 第 2.3 节；额外包含 `summary`、`title`、`blocking`、`evidence`。导出文件可能包含位置/WiFi/基站信息，提交到仓库前先脱敏。

## 目录

```text
detector/src/main/java/com/system/location/service/detector/
  MainActivity.kt / SettingsActivity.kt   平台 UI（无 AndroidX 依赖）
  CheckRunner.kt                          顺序执行 + 异常转 UNKNOWN
  DetectorSettings.kt                     SharedPreferences
  sample/CheckSampler.kt                  Location/GNSS/NMEA/传感器/WiFi/Cell 采样
  model/                                  Check 契约、SampleSnapshot、报告 JSON
  checks/                                 C01–C15
```

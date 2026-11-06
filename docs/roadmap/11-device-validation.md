# 设备验证计划（P0-03 / P1-06 / P4-06 / P5-09）

日期：2026-11-06
状态：待设备（当前 `adb devices` 为空，所有条目未执行）

> 没有设备时不得把任何 Hook/传感器/检测结论标为 `DEVICE VERIFIED`。模拟器不能替代厂商 ROM 的 Xposed/system_server 行为。

## 1. 设备矩阵

| 优先级 | 设备/系统 | 目的 |
|---|---|---|
| P0 | Pixel / AOSP 12、14、16 | 基线 AOSP 行为 |
| P0 | 小米 HyperOS / MIUI | 国内 ROM 差异、权限/后台限制 |
| P1 | OPPO ColorOS / vivo OriginOS | 厂商 location/fused 服务 |
| P1 | Samsung One UI | 国际 ROM + GMS |
| P2 | 华为 HarmonyOS/EMUI（无 GMS） | 无 GMS 回退 |
| P2 | Android 12L/13 平板 | 大屏/多用户 |

每台设备记录：型号、ROM、Android API、安全补丁、LSPosed 版本、Root 方案、是否 system_server scope。

## 2. 构建与安装

1. 只使用 CI 签名产物；记录 `versionName`/`versionCode`/commit。
2. 安装主 app + detector；授予精确位置、后台位置、BLE 扫描权限。
3. LSPosed 只启用 system_server scope；重启；检查模块日志。
4. 无 Root 模式单独验证，不与 Xposed 结果混用。

## 3. P0-03 基线

- [ ] 启动单点场景，主 app 状态为 RUNNING。
- [ ] detector 采样 ≥ 30 s，导出报告。
- [ ] 记录 `get_runtime_status`：hook installed/matched/failed/skipped、scope、sample_version。
- [ ] 关闭场景后样本停止；5 s 租约过期。
- [ ] 重启设备后无残留假定位。

## 4. P1-06 隐藏/权限

- [ ] detector C01 mock flag。
- [ ] C02 `OP_MOCK_LOCATION` 对第三方不可见，Portal 自检仍真实。
- [ ] C03 `Settings.Secure.mock_location` / 开发者选项对第三方隐藏。
- [ ] C04 Provider 列表/属性不含 `fused_ext`/test provider。
- [ ] C05 `isLocationEnabled*` 第三方为 true、系统/Settings 为真实值。
- [ ] 系统设置、地图、相机等系统功能不崩。

## 5. P3 GNSS/NMEA

- [ ] 多星座 GnssStatus：星座数、Jaccard、CN0-仰角相关性。
- [ ] NMEA：GGA/RMC/GSA/GSV/GST/ZDA、校验和、UTC、卫星数一致。
- [ ] `enableNMEA=false` 时无真实 NMEA 泄漏。
- [ ] raw measurements/navigation/antenna 按当前策略关闭，detector 不出现明显缺失。
- [ ] 天文/轨道模型切换后重启与热更新行为。

## 6. P4 Provider/传感器

- [ ] gps/network/fused 三路精度/时间/字段差异。
- [ ] 静止无 0° bearing/速度、无计步增长。
- [ ] Native 传感器：加速度/陀螺仪/地磁/气压/计步方向合理，无 -1/0。
- [ ] motion state 与速度/路线一致。
- [ ] 关闭 native 后回退正常。

## 7. P5 命令/per-app

- [ ] 非法 `put_config` 被 `ConfigGate` 拒绝。
- [ ] per-app deny/固定点对目标 app 生效，非目标不受影响。
- [ ] 代理转发/sequence/lease 正常。
- [ ] `restoreLastConfig` 恢复 routing/environment/noise 后场景一致。
- [ ] SharedMemory 实验开关默认关；开启失败自动回退。

## 8. P7 性能

- [ ] `PUBLISH_LATENCY`/`APP_PUBLISH_LATENCY` p50/p95。
- [ ] `APP_CPU`、`APP_MEMORY` 30 min 稳定性。
- [ ] 电池/温升对比（同场景 30 min）。

## 9. 产物

- detector 报告 JSON/Markdown；
- logcat（脱敏）；
- 设备矩阵表；
- 失败项的最小复现步骤；
- 更新 `06-validation.md` 与任务状态；只有全部验收项通过才可标 `DONE` 或 `DEVICE VERIFIED`。

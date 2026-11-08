# 2026-11-08 · 双设备基线

- Portal：1.7.7 / versionCode 1790000024（本地 Debug 测试包，不是正式发布产物）
- Detector：1.0
- 采样：模拟前 10 s、模拟中 30 s、停止后 10 s；重启后复测 10 s
- API：Detector debug bridge `POST /run`，`GET /report`

## 设备

| | A | B |
|---|---|---|
| 型号 | Xiaomi M2102K1C | Xiaomi 22041211AC |
| ROM / Android | HyperOS / Android 14 / API 34 | HyperOS 2.0.10 / Android 14 / API 34 |
| ABI | arm64-v8a | arm64-v8a |
| Root / 框架 | APatch + Vector 2.2 (3118) / libxposed API 102 | KernelSU manager 3.3.1 已安装但 shell 无 root；未启用 Xposed |
| 后端 | XPOSED | MOCK_PROVIDER（标准 Mock） |
| Scope | system / phone / Xiaomi fused / Settings provider | 无 Xposed scope |

## 结果

### 模拟前

| ID | A | B |
|---|---|---|
| C01 | PASS | UNKNOWN |
| C02 | PASS | PASS |
| C03 | PASS | PASS |
| C04 | PASS | PASS |
| C05 | UNKNOWN | UNKNOWN |
| C06 | FAIL | UNKNOWN |
| C07 | FAIL | UNKNOWN |
| C08 | PASS | FAIL |
| C09 | UNKNOWN | UNKNOWN |
| C10 | FAIL | FAIL |
| C11 | UNKNOWN | UNKNOWN |
| C12 | UNKNOWN | UNKNOWN |
| C13 | PASS | PASS |
| C14 | FAIL | PASS |
| C15 | PASS | UNKNOWN |

### 模拟中（30 s）

| ID | A | B |
|---|---|---|
| C01 | PASS | FAIL |
| C02 | PASS | PASS |
| C03 | PASS | PASS |
| C04 | PASS | PASS |
| C05 | FAIL | UNKNOWN |
| C06 | PASS | UNKNOWN |
| C07 | FAIL | UNKNOWN |
| C08 | FAIL | FAIL |
| C09 | UNKNOWN | UNKNOWN |
| C10 | FAIL | FAIL |
| C11 | PASS | UNKNOWN |
| C12 | UNKNOWN | UNKNOWN |
| C13 | PASS | PASS |
| C14 | FAIL | PASS |
| C15 | PASS | PASS |

### 停止后

| ID | A | B |
|---|---|---|
| C01 | PASS | PASS |
| C03 | PASS | PASS |
| C13 | PASS | PASS |
| 汇总 | pass 7 / fail 4 / unknown 4 | pass 7 / fail 2 / unknown 6 |

### 重启后

| 检查 | A | B |
|---|---|---|
| C01 | PASS | PASS |
| C03 | PASS | PASS |
| C13 | PASS | PASS |
| 结论 | 无残留假定位样本 | 无残留假定位样本 |

## 后续复核

2026-11-08 稍后 B 获得 KernelSU root 并安装 Vector 2.2 (3118)；B 的 XPOSED
C01/C03/C13 已全部 PASS，热重载和 phone/fused 重注入也已通过。本文件保留 B
早期“无 Root 标准 Mock”的历史基线，不作为当前 B 的 XPOSED 结论；当前矩阵见
[2026-11-08-two-device-xposed.md](2026-11-08-two-device-xposed.md)。

## 结论

- A 的 XPOSED 路径：C01/C03/C13 全 PASS；system_server 运行时状态测试
  `authenticatedRuntimeStatusComesFromSystemServer` 通过。
- B 的标准 Mock 路径：模拟中 C01 FAIL 是预期的 mock 标记（没有 Xposed 隐藏）；
  停止/重启后 C01 PASS，说明停止后无残留。
- 两台设备的 C03/C13 均 PASS。
- A 的 C14 FAIL 是 detector 自身进程检测到 `/system/bin/su`，是 Root 设备预期证据，
  不代表 target app；B 的 C14 PASS。
- 已知非迁移回归项：
  - A：C05 字段一致性、C07 NMEA、C08 当前 WiFi、C10 传感器；
  - B：C08 空 WiFi 扫描、C10 计步无数据；
  - 两台设备 C12 轨迹检查均因采样不足为 UNKNOWN。
- P0-03 的双设备基线已采集；P6-05 仍未完成：只有 A 有 Vector/libxposed，
  B 是无 Root 标准 Mock 基线，尚缺第二个 Xposed/LSPosed 版本矩阵和 OEM 差异验证。

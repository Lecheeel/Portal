# 07 · 风险、许可证与安全边界

> 这份文档不是“免责声明”，而是工程约束：哪些能做、哪些不能做、哪些必须先解决许可。

---

## 1. 风险登记

| ID | 风险 | 影响 | 概率 | 缓解 | 阶段 |
|---|---|---|---|---|---|
| R-01 | AppOps/Settings Hook 不生效或误伤系统 | 高 | 中 | caller UID 放行 controller；设备矩阵；可关闭 | P1 |
| R-02 | WiFi/基站构造器随 Android 版本变化 | 高 | 高 | 按实际 arity 回退；AOSP refs；多版本测试 | P2 |
| R-03 | 环境数据与真实位置矛盾 | 高 | 中 | detector 一致性检查；确定性模型 | P2 |
| R-04 | GNSS 模型过于简化被统计识别 | 中 | 高 | V1 确定性多星座；V2 天空模型；避免随机 | P3 |
| R-05 | NMEA 双写/校验和错误导致解析失败 | 高 | 中 | 单一生成器；黄金向量；设备 NMEA 工具 | P3 |
| R-06 | 传感器 Native Hook 崩溃/耗电 | 高 | 中 | 默认关；崩溃哨兵；ABI/页大小检查；重启回退 | P4 |
| R-07 | Provider 差异化导致某些 App 不采纳 | 中 | 中 | 可配置；默认接近真实；Beta 观察 | P4 |
| R-08 | per-app 路由在 GMS Fused 不生效 | 中 | 高 | 文档化限制；默认全局；Q-02 再评估 | P5 |
| R-09 | libxposed 迁移引入兼容性回归 | 高 | 中 | spike + 旧 API 回退分支；LSPosed 版本矩阵 | P6 |
| R-10 | Native 隐藏触发系统不稳定/卸载不干净 | 高 | 中 | 默认关；只做 N1；不默认 N2；重启说明 | P6 |
| R-11 | GPL/无许可证代码被误用 | 高 | 中 | 许可证矩阵；PR 检查；只学算法 | 全阶段 |
| R-12 | 上游许可证不明导致衍生作品风险 | 高 | 中 | P0 解决；必要时联系上游 | P0 |
| R-13 | 新权限/包可见性被目标 App 视为风险 | 中 | 中 | 最小权限；`<queries>`；文档标注 | P5/P6 |
| R-14 | Bugly/统计 SDK 影响隐蔽性 | 中 | 中 | 默认关；评估移除；隐私说明 | P6 |
| R-15 | Android 17/16 KB 页兼容 | 高 | 中 | refs；CI；真机；native 对齐 | P0/P6 |
| R-16 | 设备矩阵不足，问题漏到稳定版 | 高 | 高 | Beta + 设备门禁；至少 2 台 P0 | 全阶段 |
| R-17 | 环境数据库许可/隐私 | 高 | 中 | 默认关；来源/许可记录；只存必要数据 | P2-06 |
| R-18 | 性能/耗电回归 | 中 | 中 | 基线；p95；电池测试 | P4/P7 |
| R-19 | 配置迁移破坏旧数据 | 中 | 中 | schemaVersion；迁移测试；回滚 | P5 |
| R-20 | 维护者单人负荷/上下文丢失 | 高 | 高 | 本文档集；任务 ID；ADR；每阶段文档 | 全阶段 |

---

## 2. 许可证矩阵

### 2.1 参考项目

| 项目 | 许可证 | 复制代码？ | 义务/注意 |
|---|---|---|---|
| FakeGPS-next | Apache-2.0 | 可以 | 保留版权/NOTICE；修改文件需说明 |
| LocationSpoofer | GPL-3.0 | 不建议 | 复制会要求 Portal 以 GPL-3.0 发布 |
| kail_location | GPL-3.0 | 不建议 | 同上 |
| AnyWhere | GPL-3.0 | 不建议 | 同上 |
| GoGoGo | GPL-3.0 | 不建议 | 同上 |
| HideMockLocation | 无 LICENSE | 不可以 | 默认保留所有权利；只学思路 |
| hlocation-release | 商业闭源 | 不可以 | 只参考架构 |
| SSLAndriodTest | 无 LICENSE | 不可以 | 只参考调用顺序 |
| location-by-youself | AGPL-3.0 | 不可以 | 网络使用也有义务 |
| AOSP refs | Apache-2.0 | 可以 | 保留 NOTICE；仅参考 |

### 2.2 数据/服务

| 来源 | 许可/条款 | 注意 |
|---|---|---|
| OpenCellID | CC BY-SA 4.0 | 署名 + 相同方式共享；衍生数据也要合规 |
| WigLE | 需查其 API/数据条款 | 默认关；不要把数据打包进 APK 再分发 |
| 高德/百度 SDK | 商业条款 | 地图数据、Key、配额；不要抓取/再分发 |
| Google Play Services | Google 条款 | GMS Mock 仅测试用途 |
| Bugly | 腾讯条款 | 默认关；隐私说明 |

### 2.3 Portal 自身

- 当前仓库**没有 LICENSE 文件**：法律上默认保留所有权利，别人不能合法复用，你也不能随意吸收 GPL 代码。
- 上游 `fuqiuluo/Portal` 的许可证未在本地确认；如果它是 GPL，Portal 作为衍生作品可能已经受 GPL 约束。
- **P0-05 必须先做**：
  1. 确认上游许可证；
  2. 选择 Portal 的许可证（建议与上游兼容）；
  3. 加 `LICENSE`、`NOTICE`、第三方清单；
  4. PR 模板加“代码来源/许可证”检查。

> 在许可证解决前：**只学算法/结构，不复制第三方代码。**

### 2.4 2026-11-06 核查记录

- `https://github.com/fuqiuluo/Portal`：HTTP 404（仓库不可访问）。
- GitHub API `search/repositories?q=Portal+user:fuqiuluo`：0 条结果。
- 本地仓库：无 `LICENSE`、无 `NOTICE`。
- 结论：上游许可证无法确认，本轮不添加 `LICENSE`/`NOTICE`；P0-05 的许可证子项保持 `BLOCKED`，详见 [02-decisions.md](02-decisions.md) ADR-023。
- 可立即执行的部分已落地：`:core`/`:nmea` 纯 JVM、`:xposed` 不依赖 `:app`、`:app` 不依赖 `:detector`、行数预算、文档链接检查（`scripts/check_module_rules.py`）。

### 2.5 依赖清单

运行时与构建依赖的用途/许可证/备注统一记录在 [THIRD-PARTY.md](THIRD-PARTY.md)；新增依赖先更新该表，再进入评审。

---

## 3. 安全清单

### 3.1 IPC

- `CommandSecurity`：UID + 完整包名 + token + sequence + 时限；peer 白名单。
- 新增 settings provider scope 后，确认不会把控制命令暴露给 settings 进程。
- 不允许用包名 substring 判断授权。
- 配置/样本大小限制；分块校验；拒绝未知版本。
- 诊断日志不记录坐标/账号。

### 3.2 Root / Native

- Native 安装只用 `su` 复制固定路径；引号/符号链接校验；不改 SELinux。
- 默认关闭；UI 明确“需要 Root/重启”。
- Dobby inline hook 的卸载不保证干净：停止只禁用行为；彻底恢复需重启。
- 16 KB 页设备：native 库对齐；CI/真机检查。
- 崩溃哨兵：Native 加载/启用前后记录；下次启动提示。

### 3.3 权限

| 权限 | 用途 | 备注 |
|---|---|---|
| `ACCESS_MOCK_LOCATION` | 标准 Mock | signature，仅系统可用 |
| `ACCESS_FINE/COARSE_LOCATION` | 地图/模拟 | 必需 |
| `ACCESS_BACKGROUND_LOCATION` | 后台模拟 | 明确说明 |
| `FOREGROUND_SERVICE_LOCATION` | 前台服务 | 必需 |
| `SYSTEM_ALERT_WINDOW` | 摇杆 | 仅摇杆 |
| `READ_PHONE_STATE` | 基站模拟 | 需要时申请 |
| `QUERY_ALL_PACKAGES` | per-app UI | **尽量避免**，优先 `<queries>` |

### 3.4 包可见性

- per-app 页面需要列出应用；`QUERY_ALL_PACKAGES` 本身可能被目标 App 视为敏感。
- 优先 `<queries>` 声明已知包；如必须全量，设置页说明用途，Beta 观察。

### 3.5 日志/隐私

- 高频路径不写日志/文件；`Diag` 限频。
- 不记录坐标、WiFi、基站到外部服务。
- Detector 导出由用户触发；提示可能含环境信息。
- Bugly 默认关；评估是否移除。

---

## 4. 兼容性风险

| 项 | 风险 | 缓解 |
|---|---|---|
| Android 12–17 | 方法签名/回调变化 | `refs/` + 版本分支 + 设备矩阵 |
| OEM fused | 小米/OPPO/vivo 各自实现 | 分别 Hook + 设备验证 |
| LSPosed legacy/libxposed | API/元数据/加载方式不同 | P6 spike + 回退分支 |
| GMS 版本 | `setMockMode`/`setMockLocation` 行为 | 版本探测 + 降级 |
| 16 KB 页 | native 库/第三方 SDK | 对齐检查 + 真机 |
| x86_64 | 模拟器与真机差异 | CI 模拟器 + 真机 |
| Huawei 无 GMS | 路径不同 | 单独矩阵 |
| Android 17 | 新限制/权限 | refs + Beta |

---

## 5. 法律与伦理边界

- 这类工具是双用途：开发测试、兼容性验证是正当用途；虚假打卡、考试作弊、欺诈等是高风险/违法用途。
- README/关于页保留“仅供开发测试”声明；不提供“保证过检测”的承诺。
- 不伪造 Play Integrity/TEE/硬件 attestation；不协助服务端欺诈。
- 不收集/上传用户位置、WiFi、基站数据；环境库默认关。
- 遵守地图/数据/开源许可证。

---

## 6. 回退策略

| 变更 | 回退方式 |
|---|---|
| 新环境来源 | feature flag 切回 `PROCEDURAL` |
| 新基站模式 | 切回 `AUTO`/旧路径（短期） |
| GNSS V2/V3 | 切回 V1 |
| 传感器 native | 关闭；重启 |
| Native 隐藏 | 关闭；重启 |
| libxposed | 保留旧 API 分支/旧 APK |
| per-app | 关闭；回默认全局 |
| 配置 schema | 版本迁移 + 旧配置备份 |

发布前必须确认：用户能在不卸载的情况下回退（Native 除外，需重启）。

---

## 7. 评审触发条件

以下任一情况，必须先更新本文并评审：

- 新增第三方代码/数据/服务。
- 新增 native 库或 root 操作。
- 新增 Xposed scope/权限/导出组件。
- 把实验能力改为默认开。
- 改变 IPC/配置 schema。
- 引入 target app 注入。
- 改变许可证/分发方式。

---

## 8. 结论

- **技术风险**主要在 WiFi/基站构造、GNSS 物理模型、Native Hook、OEM 兼容。
- **工程风险**主要在设备矩阵不足、单人维护、文档丢失。
- **法律风险**主要在许可证、数据许可、双用途滥用。
- 先解决许可证与 detector，再谈“更前沿”；否则越做越不可维护。

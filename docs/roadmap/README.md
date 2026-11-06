# Portal 长期路线图

> 状态：草案（Draft）  
> 基线：`v1.7.6` / `0bfd65e` / 2026-11-06  
> 维护者：Portal maintainer  
> 用途：把“理解、缺点、借鉴、目标架构、任务顺序、验证、风险”固化成可执行文档。

这套文档不是发布说明，也不是一次性设计稿。它是后续每个阶段的**工作底稿**：

- 每完成一个任务，更新 [05-work-plan.md](05-work-plan.md) 的状态和证据链接。
- 每改变一个关键决策，在 [02-decisions.md](02-decisions.md) 追加 ADR，不直接改写旧 ADR。
- 每次修改 Hook 行为，在 [06-validation.md](06-validation.md) 记录设备验证结论。
- 每引入一个第三方实现或数据，在 [07-risks-and-licensing.md](07-risks-and-licensing.md) 更新许可证矩阵。

---

## 0. 一页结论

- Portal 不是“功能少”，而是**验证少、环境假、隐藏浅**。
- 当前最大硬伤：AppOps / Settings / Provider 列表没有真正隐藏；WiFi/基站/GNSS/NMEA/传感器各自返回假值，互不自洽。
- 最该先做：`:detector` + 设备基线；再修隐藏；再做环境；最后才做 GNSS 大模型和 Native。
- 战略：保持 **system_server-only**；环境模型进 `:core`；GNSS/NMEA **单一生成器**；Provider 差异化 + 独立噪声；per-app 在 system_server；Native 实验默认关。
- 最值得学：**FakeGPS**（Apache-2.0，可复用）；LocationSpoofer（算法）；kail（native/隐藏，GPL）；HideMock/AnyWhere（隐藏清单）；hlocation（数据平面）。
- 执行顺序：**P0 基线 → P1 隐藏 → P2 WiFi/基站 → P3 GNSS/NMEA → P4 Provider/传感器 → P5 per-app/数据平面 → P6 隐蔽/Native → P7 前沿**。
- 不允许：target app 默认注入、伪造 TEE/Play Integrity、空 WiFi/CDMA/随机卫星、直接复制 GPL/无许可证代码。

---

## 1. 文档地图

| 文档 | 回答什么问题 | 什么时候读 |
|---|---|---|
| [01-current-state.md](01-current-state.md) | Portal 现在是什么、哪些是真的、哪些是坏的 | 动手前、评审前 |
| [02-decisions.md](02-decisions.md) | 我们决定了什么、不做什么、还有哪些开放问题 | 有分歧时、改方向时 |
| [03-reference-projects.md](03-reference-projects.md) | 其他 9 个项目各自强在哪、能借鉴什么、不能抄什么 | 设计某个子系统前 |
| [04-target-architecture.md](04-target-architecture.md) | 目标结构、模型、数据流、模块边界 | 写代码前 |
| [05-work-plan.md](05-work-plan.md) | 先做什么、后做什么、每个任务的验收标准 | 每天开工前 |
| [06-validation.md](06-validation.md) | 怎么证明改对了、设备矩阵、发布门禁 | 每个阶段结束前 |
| [07-risks-and-licensing.md](07-risks-and-licensing.md) | 技术/兼容/安全/许可证风险 | 引入新依赖或原生代码前 |

---

## 2. 当前基线

| 项 | 值 |
|---|---|
| 版本 | `1.7.6`（`versionCode 1790000013`） |
| 当前提交 | `0bfd65e` |
| 模块 | `:app`、`:core`、`:xposed`、`:nmea`、`:system-api` |
| Android | `minSdk 31`，`targetSdk 37`，`compileSdk 37` |
| 构建 | AGP 9.3.1、Kotlin 2.4.10、JDK 17/25、NDK 30、CMake 4.1.2 |
| Xposed | legacy API 82（compileOnly）、manifest `xposedminversion 93` |
| 测试 | 约 196 个 JVM 用例（见 `docs/optimization-verification.md`），另有 instrumentation；**尚无设备验收结论** |
| AOSP 参考 | `refs/` 覆盖 Android 7–17，最近核验 2026-10-16 |
| 许可证 | 仓库内没有 `LICENSE` 文件；上游 `fuqiuluo/Portal` 的许可证待确认 |

---

## 3. 不可牺牲的原则

后续所有设计都必须满足这些约束；违反其中一条，方案就退回重做。

1. **system_server 是位置与环境的主边界。** 默认不在 target app 里放 Hook；per-app 路由也在 system_server 做。
2. **`:core` 不依赖 Android。** 环境、GNSS、噪声、Provider 策略、路由表都是纯 Kotlin，可在 JVM 测试。
3. **播放是确定性的。** `PlaybackEngine` 不直接掺随机数；物理噪声是独立装饰层，可关闭、可复现。
4. **GNSS / NMEA / 环境只有一个生成器。** 不允许两套 Hook 各自改写、各自随机。
5. **高频路径不做磁盘、网络、同步文件写入。** 配置用版本化快照，位置用内存/租约。
6. **实验能力默认关闭。** Native、真实环境库、原始 GNSS 测量、CDMA 兼容路径都必须显式开启。
7. **不静默失败。** 每个 Hook 有 `HookStatus`，每个后端有 `Capability`/`Diagnostic`；失败要能解释。
8. **不承诺绕过 Play Integrity / TEE / 服务端风控。** 我们只做本地信号一致性，不做 attestation 伪造。
9. **许可证先行。** 不把 GPL/无许可证代码直接复制进 Portal；参考算法可以，复制实现要先解决许可。
10. **可维护性优先于功能数量。** 单个 Hook 文件设行数预算；纯逻辑必须下沉到 `:core`。

---

## 4. 状态与证据

### 4.1 任务状态

| 状态 | 含义 |
|---|---|
| `TODO` | 未开始 |
| `WIP` | 正在做，已有分支/PR |
| `BLOCKED` | 依赖未满足，写明阻塞原因 |
| `DONE` | 代码合并 + 测试通过 + 文档更新 |
| `DEFERRED` | 明确推迟，写明触发条件 |

### 4.2 证据等级

沿用 Portal 现有验收文档的写法：

| 等级 | 含义 |
|---|---|
| `SOURCE VERIFIED` | 只检查了实现/配置/控制流 |
| `BUILD VERIFIED` | 本机或 CI 编译、lint、打包通过 |
| `UNIT TEST VERIFIED` | JVM 测试通过，不代表 Android 运行时 |
| `DEVICE VERIFIED` | 在指定设备/ROM/Android 版本上实测通过 |
| `FIELD VERIFIED` | 在真实使用场景中稳定，且无回归 |

**只有 `DEVICE VERIFIED` 才能把 Hook 行为标成“可用”。** `SOURCE VERIFIED` 不能替代设备结论。

---

## 5. 每个阶段的完成定义（Definition of Done）

一个阶段算完成，必须同时满足：

1. 该阶段任务全部 `DONE`，或明确 `DEFERRED` 并写明原因。
2. 新增/修改的纯逻辑有 JVM 测试；Hook 行为有 detector 检查。
3. 至少一台目标设备完成 `DEVICE VERIFIED`，记录 ROM、Android 版本、LSPosed 版本、结果。
4. 现有测试、lint、Release 构建不回归。
5. [01-current-state.md](01-current-state.md)、[05-work-plan.md](05-work-plan.md)、[06-validation.md](06-validation.md) 已更新。
6. 新增实验能力有 feature flag，默认关闭，且能在不重启的情况下回退（Native 除外，需注明重启）。

---

## 6. 建议阅读顺序

第一次读：

```text
README.md（本文）
  → 01-current-state.md（先认清现状）
  → 02-decisions.md（知道边界）
  → 03-reference-projects.md（知道去哪学）
  → 04-target-architecture.md（知道改成什么样）
  → 05-work-plan.md（知道先做哪一步）
  → 06-validation.md（知道怎么验收）
  → 07-risks-and-licensing.md（知道哪里会翻车）
```

日常执行只需要看 [05-work-plan.md](05-work-plan.md)；设计评审看 [04-target-architecture.md](04-target-architecture.md)；发版前看 [06-validation.md](06-validation.md)。

---

## 7. 立即可做的三件事

1. 建 `:detector` 模块，跑出当前设备的泄漏基线（见 [05-work-plan.md](05-work-plan.md) 的 `P0-01`～`P0-03`）。
2. 修 AppOps / Settings / Provider 列表三处硬伤（`P1-02`～`P1-04`）。
3. 把 `LocationSample` 的 extras、MSL 高度、不确定度、静止策略定下来（`P1-01`、`P1-05`、`P4-01`）。

在这三件事完成前，不建议动 GNSS 大模型和 Native Hook；否则没有基线，无法判断“变好”还是“只是变了”。

---

## 8. 文档变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-06 | 初版：基线、现状、决策、参考项目、目标架构、任务计划、验证、风险 |

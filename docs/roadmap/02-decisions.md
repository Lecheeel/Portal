# 02 · 决策、边界与开放问题

> 本文记录“为什么这样做”。ADR 一旦写下不直接改写；方向变化时追加新 ADR 并标注 `Supersedes`。

---

## 1. 维护者已明确的方向

以下来自 2026-11-06 的维护者决策，视为本路线图的输入约束。

| ID | 决策 | 落地阶段 |
|---|---|---|
| M-01 | AppOps 与 Settings 是 Portal 当前两个最大硬伤，必须单独 Hook | P1-02 / P1-03 |
| M-02 | Provider 列表 Hook 已写但被注释，必须修复 | P1-04 |
| M-03 | WiFi 要支持“真实采集 + 按目标坐标重标定” | P2-02 / P2-04 |
| M-04 | WiFi 默认走程序化合成：地球切网格，每个网格固定一组（默认 4 个）AP | P2-03 |
| M-05 | WiFi 真实数据库也做，但标记实验性，后期设备实测 | P2-06 |
| M-06 | 基站默认关闭 CDMA 降级；旧路径准备淘汰 | P2-05 |
| M-07 | 基站两条路线都做：完整回放、无 SIM/WiFi-only | P2-05 |
| M-08 | GNSS 不再随机 BDS，要做更好的模型、更完整的计算，并研究前沿做法 | P3-01～05 |
| M-09 | Native 传感器需要大改重构，当前空实现必须修掉 | P4-04 / P4-05 |
| M-10 | 三个 Provider 不能发同一份数据，要分别做精度/频率/时间偏移 | P4-01 / P4-03 |
| M-11 | 静止状态要单独处理（速度、航向、精度、计步、Activity） | P4-01 / P4-05 |
| M-12 | 轨迹需要独立物理噪声层 | P4-02 |
| M-13 | 每个应用路由要做，且用更完善的做法 | P5-01～04 |
| M-14 | 架构要优化，包括数据平面与隐蔽性 | P5-06 / P6 |
| M-15 | Xposed 模块要更好地隐藏 | P6-01 / P6-02 |
| M-16 | GNSS 物理层一致性要做：多普勒、载波相位/钟差等 | P3-05 / P7-01 |
| M-17 | 传感器要做融合，不只是单点伪造 | P4-05 / P7-03 |
| M-18 | 参考 Kail 的 native hook 与隐藏，实验性加入，默认关闭，维护者设备实测 | P6-03 |
| M-19 | 项目结构要能长期维护 | P0-05 / 全阶段 |
| M-20 | 先把理解、TODO、顺序、缺点、借鉴点持久化为 Markdown | 本文档集 |

---

## 2. 架构决策记录（ADR）

### ADR-001 · Detector 先行

**决策**：在改任何隐藏/伪装逻辑前，先建立独立 `:detector` 应用与设备基线。  
**理由**：Portal 目前没有任何 `DEVICE VERIFIED` 项；没有基线就无法判断“变好”还是“只是变了”。  
**后果**：P0 阶段不做大功能；detector 成为后续每个 PR 的验收工具。  
**替代方案**：先写 Hook 再补测试——被否决，容易出现“看起来隐藏了、实际没隐藏”。

### ADR-002 · 保持 system_server-only，默认不进 target app

**决策**：位置、GNSS、NMEA、WiFi、基站、Provider 列表、AppOps、Settings 的 Hook 都优先放在 system_server 或系统 provider 进程；`TARGET_APP` 继续默认跳过。  
**理由**：减少 target app 进程内的 Xposed 痕迹；兼容性和隐蔽性都更好；符合 Portal 现有架构。  
**后果**：per-app 路由必须在 system_server 分发层实现；GMS Fused 的 per-app 需要单独评估。  
**替代方案**：像 Kail 一样注入 target app——暂不作为默认路径，只作为实验分支。

### ADR-003 · 环境模型放在 `:core`

**决策**：WiFi/基站/GNSS/传感器/Provider 策略都抽象成纯 Kotlin 模型，Hook 只做 Android 适配。  
**理由**：可 JVM 测试、可复用、避免继续把逻辑堆进 900 行的 Hook 文件。  
**后果**：新增 `EnvironmentProfile`、`GnssSkyModel`、`ProviderProfile`、`PhysicalNoise`、`RoutingTable` 等模型；Hook 文件行数下降。  
**替代方案**：继续在 Hook 里写——被否决，维护成本不可接受。

### ADR-004 · WiFi 三种来源，程序化默认

**决策**：
1. `PROCEDURAL`（默认）：geohash 网格，每格固定 4 个 AP（可配置 3–8），固定 seed，RSSI 路径损耗，移动时插值。
2. `REPLAY`：真实采集 + 按目标坐标重标定 RSSI。
3. `DATABASE`（实验性，默认关）：WigLE/OpenCellID 等真实数据。
**理由**：默认不依赖用户采集、不依赖外部数据库许可；回放满足高保真需求；数据库作为后期实验。
**后果**：需要 `EnvironmentProfile`、`EnvironmentSource`、采集/回放管线；WiFi Hook 必须支持三种来源的统一输出。  
**替代方案**：只做数据库——被否决，维护和许可成本太高；只做空列表——明确淘汰。

### ADR-005 · 基站默认关闭 CDMA 降级，两条自洽路线

**决策**：
1. `FULL_REPLAY`：完整 LTE/NR 小区 + 邻区 + SIM/operator/服务状态一致。
2. `NO_SIM_WIFI_ONLY`：明确的无 SIM/WiFi-only 设备画像。
3. `LEGACY_CDMA`：保留一个版本，默认关闭，标记 deprecated，后续删除。
**理由**：当前 CDMA + 空小区 + `Int.MAX_VALUE` 与 SIM/operator 自相矛盾。  
**后果**：`needDowngradeToCdma` 默认改为 false；TelephonyHook 重写；需要设备矩阵验证。  
**替代方案**：继续 CDMA——被否决。

### ADR-006 · GNSS 单一生成器，确定性多星座

**决策**：
- 一个 `GnssSkyModel`/`GnssFrame` 生成器同时产出 `GnssStatus`、NMEA、可选原始测量。
- 不再 `shuffled()`；卫星集合在秒级稳定，按时间和位置演化。
- V1：多星座目录 + 确定性可见性；V2：星历/Kepler 天空模型；V3：原始测量（实验）。
**理由**：随机 BDS 子集在统计上即可识别；单一生成器避免双写/双屏蔽。  
**后果**：移除 `LocationServiceHook` 内的 BDS 常量和随机逻辑；新增 `:core` 模型与 `:nmea` 生成器；`GnssHook` 与 `LocationNMEAHook` 合并。  
**替代方案**：继续随机——被否决。

### ADR-007 · Provider 画像与静止策略

**决策**：`gps`、`network`、`fused` 分别有精度、频率、时间偏移、字段可用性策略；静止时关闭 bearing/step，速度归零，精度做小幅漂移。  
**理由**：三路完全一致是最简单的交叉检测；真实设备不会这样。  
**后果**：`ProviderProfile` 进入 `:core`；后端按 provider 发布不同样本；`LocationSample` 可能需要 `provider`/`fieldsMask`。  
**替代方案**：只改精度数值——不够，频率和时间也要分离。

### ADR-008 · 物理噪声是独立装饰层

**决策**：`PlaybackEngine` 保持确定性；`PhysicalNoise` 在其外层做 OU 漂移、精度/海拔漂移、航向门控，可关闭、可固定 seed。  
**理由**：保留现有可测试性；同时避免“零噪声”成为指纹。  
**后果**：新增 `NoiseProfile`；测试需要覆盖“关闭噪声=确定性”“开启噪声=有界/可复现”。  
**替代方案**：把随机数塞进 PlaybackEngine——被否决，会破坏现有测试。

### ADR-009 · per-app 路由在 system_server，GMS 单列

**决策**：`RoutingTable` 以 `(packageName, userId)` 为键；在 `ILocationManager`/`LocationProviderManager` 分发层按调用方 UID 选择场景；默认全局场景。  
**理由**：符合 ADR-002；无需 target app Hook；支持工作资料/多用户。  
**后果**：需要 listener→scenario 映射、配置通道 v2、UI；GMS Fused 调用方是 GMS 进程，per-app 需要单独评估。  
**替代方案**：在 target app 里 Hook——不作为默认；可作为实验分支。

### ADR-010 · libxposed 迁移优先，模块拆分可选

**决策**：优先把 legacy Xposed API 82 迁移到 libxposed API 102，使用 `META-INF/xposed/module.prop`、`java_init.list`、`scope.list`；模块拆分 APK 作为备选。  
**理由**：manifest `xposedmodule` 任意 App 可查；libxposed 元数据不在 PackageManager 里，且更适配 Android 16/17。  
**后果**：入口、Hook 辅助、scope 声明都要改；工作量 1–2 周；需要 LSPosed 版本矩阵。  
**替代方案**：继续 legacy + 拆分模块——更重，但可作为过渡。

### ADR-011 · Native hook/hide 是独立实验轨道

**决策**：参考 Kail 的 native hook/隐藏，但作为**默认关闭的实验能力**；先做 system_server 内的传感器 native Hook 与 Portal 自身痕迹隐藏，不默认做 target app ptrace 注入。  
**理由**：Native 风险高、ROM 差异大；Portal 的优势是 system_server-only。  
**后果**：`NativeBackend` 继续 opt-in；需要设备实测、崩溃哨兵、回滚说明；Dobby inline hook 的卸载不保证干净，需注明重启。
**替代方案**：直接照搬 Kail 的 ptrace + slim dex——风险和工作量都太大，暂不默认。

### ADR-012 · 不承诺绕过 Play Integrity / TEE / 服务端风控

**决策**：只做本地信号一致性和环境自洽；不伪造硬件 attestation，不承诺绕过服务端轨迹校验。  
**理由**：技术上不可行，且会把项目引向不可维护的对抗军备竞赛。  
**后果**：文档和 UI 明确能力边界；把精力放在“减少矛盾”而不是“承诺隐身”。

### ADR-013 · 许可证先行

**决策**：在复制任何第三方实现前，先确认 Portal 与上游 `fuqiuluo/Portal` 的许可证；GPL/无许可证代码只学算法，不直接复制。  
**理由**：FakeGPS 是 Apache-2.0；Kail/LocationSpoofer/AnyWhere/GoGoGo 是 GPL-3.0；HideMock 无许可证；hlocation 闭源。  
**后果**：P0-05 先补 `LICENSE`/`NOTICE` 策略；代码评审检查来源。  
**替代方案**：先抄后补——不可接受。

### ADR-014 · 设备证据是发布门禁

**决策**：任何改变 Hook 行为的 PR，必须有至少一台目标设备的 `DEVICE VERIFIED` 记录；detector 失败即阻断发布。  
**理由**：JVM 测试无法证明 Binder/系统服务/ROM 行为。  
**后果**：发布节奏变慢，但避免“源码通过、设备翻车”。

### ADR-015 · 模块与文件预算

**决策**：
- `:core` 不依赖 Android。
- `:xposed` 不依赖 `:app`。
- 单个 Hook 文件目标 ≤ 400 行；超过必须拆分或下沉纯逻辑。
- 纯策略/模型必须有 JVM 测试。
**理由**：当前 900 行 `LocationServiceHook` 已经难以维护。  
**后果**：P0-05 加 CI/评审规则；重构分阶段进行，不做大爆炸。

### ADR-016 · 兼容范围与 AOSP 参考

**决策**：继续 `minSdk 31`，覆盖 API 31–37；`refs/` 作为签名/回调差异的第一手资料；不凭版本号猜行为。  
**理由**：Portal 已有 AOSP 参考目录和校验脚本。  
**后果**：每个 Hook 变更要标注目标 API 范围；新增 API 37 适配任务。

### ADR-017 · 实验能力默认关闭

**决策**：Native、真实环境库、原始 GNSS、CDMA 兼容、per-app（完成前）全部默认关闭；设置页明确标注实验性。  
**理由**：避免用户误以为“已启用/已隐藏”。  
**后果**：`experimentalClearMockFlag` 的默认值要改为 false；README/设置文案同步。

### ADR-018 · 数据平面先抽象，后 SHM

**决策**：先引入 `SampleStore`/`ConfigStore` 抽象与版本化快照；Binder 实现先跑；只有 per-app 规模证明 Binder 不够时，才上 SharedMemory。  
**理由**：避免过早优化；hlocation 的 SHM 设计值得参考，但 Portal 当前只有 3 个系统进程。  
**后果**：`SampleWire` v2、配置/位置序号分离；未来可替换实现。

### ADR-019 · UI 继续 Fragment/XML

**决策**：本路线图不迁移 Compose；只加必要的设置页、per-app 页面、环境采集页面。  
**理由**：UI 迁移与定位/环境主线无关，会稀释工作量。  
**后果**：新页面遵循现有 ViewBinding/Material 风格。

### ADR-020 · Detector 是独立应用

**决策**：`:detector` 不依赖 `:app`/`:xposed` 的内部实现，只依赖 Android 公开 API + 反射；可独立安装、独立运行。  
**理由**：避免“用被测代码证明被测代码没漏”。  
**后果**：新增模块；CI 构建 debug APK；设备手动/自动化运行。

### ADR-021 · 配置版本化、原子切换

**决策**：`ConfigEnvelope` 带 `configVersion`；位置样本带 `sampleSequence`；配置切换用原子替换，不 stop/start 整个后端。  
**理由**：当前路线切换有短暂空档；per-app 后配置更复杂。  
**后果**：`RemoteCommandHandler` 拆分命令与配置；`ScenarioController` 支持热切换。

### ADR-022 · 环境确定性优先

**决策**：同一位置、同一 seed 必须生成同一组 AP/基站/卫星；跨进程、跨重启一致；只有时间/运动导致演化。  
**理由**：随机环境本身就是指纹；确定性也便于测试和回放。  
**后果**：所有环境生成器使用显式 seed/geohash；禁止 `Random()` 无 seed。

---

### ADR-024 · 许可证确定（Apache-2.0）

**决策**：2026-11-07 确认上游 Portal 仓库 `ella8192/Portal` 为 **Apache-2.0**（GitHub API `license.spdx_id=Apache-2.0`，LICENSE 文件可访问）。据此：

1. 仓库根目录添加 `LICENSE`（Apache-2.0 全文）与 `NOTICE`（上游 Portal 归属、链接、许可证）；
2. README 增加许可证与来源说明；
3. 不吸收 GPL/无许可证实现；新增依赖继续记录在 `THIRD-PARTY.md`；
4. P0-05 的许可证子项解除 `BLOCKED`。

**理由**：上游许可证已可验证，Apache-2.0 允许分发/修改，需保留版权、许可证与 NOTICE。

**后果**：本仓库按 Apache-2.0 分发；下游复用时必须保留 `LICENSE`/`NOTICE` 与修改说明。原 ADR-023 的“暂缓决策”保留为历史记录。

### ADR-023 · 许可证暂缓决策与第三方边界（历史）

**决策**：2026-11-06 未能确认上游 `fuqiuluo/Portal` 的许可证（上游 GitHub 仓库返回 404，GitHub API 搜索无结果，本地仓库也没有 `LICENSE`）。在维护者给出明确选择前：

1. 不添加 `LICENSE`/`NOTICE` 文件，避免作出无依据的法律声明；当前状态仍是“保留所有权利”。
2. 不复制 GPL/无许可证项目的实现，只参考算法；新增代码保持原创。
3. 不引入 GPL 运行时依赖；第三方依赖清单在 P0-05 的护栏里逐步补齐。
4. P0-05 的 CI 护栏先行落地：模块依赖方向、`:core`/`:nmea` 纯 JVM、行数预算、文档链接。

**理由**：Upstream 许可证无法查证时，任何单方面许可证声明都可能错误；但代码护栏与“不复制第三方实现”可以立即执行。

**后果**：P0-05 的许可证子项保持 `BLOCKED`，阻塞点是“上游许可证不可查 + 维护者未选择 Portal 许可证”。解除需要维护者提供上游许可证，或明确选择 Portal 的许可证（建议与上游兼容，且不吸收 GPL 实现）。

**替代方案**：直接按 GPL-3.0 发布——被否决，因为上游是否 GPL 无法证明；按 Apache-2.0 发布——同样无依据。

---

## 3. 非目标（本路线图不做）

1. 默认在 target app 内注入 Hook。
2. 做应用沙盒/多开容器（Kail NewBlackbox 那类）。
3. 做相机/NFC/麦克风模拟。
4. 做 ptrace 注入 target app（除非 Native 实验轨道单独立项并通过评审）。
5. 伪造 Play Integrity/TEE/硬件 attestation。
6. 支持 Android 12 以下。
7. 把 UI 迁移到 Compose。
8. 维护全球 WiFi/基站指纹库作为默认能力。
9. 承诺“所有 App 都能过检测”。
10. 为了功能数量牺牲 `:core` 可测试性。

---

## 4. 默认值与 Feature Flag

| 能力 | 默认 | Flag/开关 | 备注 |
|---|---|---|---|
| WiFi 来源 | `PROCEDURAL` | `wifi_source` | `REPLAY`/`DATABASE` 可选 |
| WiFi 每格 AP 数 | 4 | `wifi_ap_per_cell` | 建议范围 3–8 |
| 基站模式 | `AUTO` | `cell_mode` | 有 SIM→FULL_REPLAY；无 SIM→NO_SIM_WIFI_ONLY |
| CDMA 旧路径 | 关闭 | `legacy_cdma` | deprecated，下一版本删除 |
| GNSS 模型 | V1 确定性多星座 | `gnss_model` | V2 天空模型在设备验证后开 |
| 原始 GNSS 测量 | 关闭 | `gnss_raw` | 实验性 |
| 传感器 native | 关闭 | `native_sensor` | 实验性，需重启 |
| Native 隐藏 | 关闭 | `native_hide` | 实验性，需重启 |
| 真实环境库 | 关闭 | `wifi_db` | 实验性 |
| per-app 路由 | 关闭 | `per_app_routing` | 完成后默认开 |
| 模拟标记反射实验 | 关闭 | `mock_flag_experiment` | 当前默认 true，需改 |
| 数据平面 SHM | 关闭 | 内部 | Binder 先行 |

> 原则：实验能力默认关闭；UI 必须显示“实验性/需要重启/仅特定 ROM”。

---

## 5. 开放问题

| ID | 问题 | 影响 | 建议决策时点 |
|---|---|---|---|
| Q-01 | 上游 `fuqiuluo/Portal` 的许可证是什么？Portal 准备采用什么许可证？ | 能否复用 GPL/Apache 代码 | P0 开始前 |
| Q-02 | per-app 是否要覆盖 GMS Fused？ | 需要 GMS 进程 Hook 或 target app 兜底 | P5 设计前 |
| Q-03 | Native 轨道只做 system_server，还是也要 target app 注入？ | 风险/工作量差一个量级 | P6 开始前 |
| Q-04 | 真实环境库用 WigLE、OpenCellID，还是自建采集？ | 许可、隐私、维护成本 | P2-06 前 |
| Q-05 | Detector 是否随正式版分发，还是只做 debug/CI？ | 用户可自查 vs 包体/暴露 | P0-01 前 |
| Q-06 | 性能/耗电预算：发布频率、CPU、内存、电池目标？ | 影响 Provider 频率与 SHM 决策 | P4 前 |
| Q-07 | OEM 优先级：小米/OPPO/vivo/三星/Pixel/华为？ | 设备矩阵与 Hook 适配顺序 | P0-03 前 |
| Q-08 | 模块拆分 APK 是否值得？ | 安装/升级/用户体验 | P6-01 后 |
| Q-09 | 是否保留 Bugly？ | 隐蔽性/隐私/包体 | P6-04 |
| Q-10 | Android 17/API 37 的正式支持时间？ | 兼容性测试范围 | P0-03 前 |

---

## 6. 变更控制

- 新决策 → 追加 ADR，编号递增；不复写旧 ADR。
- 方向变化 → 在新 ADR 写 `Supersedes: ADR-xxx`。
- 任务范围变化 → 更新 [05-work-plan.md](05-work-plan.md)，并在 [01-current-state.md](01-current-state.md) 记录原因。
- 许可证/第三方代码 → 更新 [07-risks-and-licensing.md](07-risks-and-licensing.md)。
- 任何“默认值”变化 → 同步本文第 4 节和设置页文案。

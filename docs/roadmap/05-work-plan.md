# 05 · 执行计划与任务清单

> 这是日常执行的主文档。  
> 规则：任务状态只在有证据时改变；Hook 行为没有 `DEVICE VERIFIED` 不能标 `DONE`。

---

## 1. 使用方式

1. 每次只做一个 Phase；Phase 内按任务 ID 顺序，除非依赖允许并行。
2. 开分支/PR 时，标题带任务 ID，例如 `feat(P1-02): AppOps hook`。
3. 完成后更新本表 `状态`，并在 [06-validation.md](06-validation.md) 追加证据。
4. 如果发现新问题，先加到 [01-current-state.md](01-current-state.md) 的缺口表，再决定是否插入当前 Phase。
5. 不把多个无关任务压进一个提交；每个任务可独立回退。

### 规模

| 标记 | 含义 |
|---|---|
| `S` | < 1 天 |
| `M` | 1–3 天 |
| `L` | 1–2 周 |
| `XL` | > 2 周，需要拆分 |

### 状态

`TODO` / `WIP` / `BLOCKED` / `DONE` / `DEFERRED`

---

## 2. 阶段总览

| Phase | 主题 | 目标 | 退出条件 | 预估 |
|---|---|---|---|---|
| P0 | 基线与护栏 | detector、Hook 状态、仓库规则 | 有设备基线报告；所有 Hook 可诊断 | 1 周 |
| P1 | 清洗与隐藏 | AppOps/Settings/Provider/Location 字段/Availability | detector 的隐藏类检查通过 | 1–2 周 |
| P2 | 环境：WiFi/基站 | 采集回放 + 程序化 + 实验库；两种基站画像 | 环境一致性检查通过 | 2–3 周 |
| P3 | GNSS/NMEA | 单一生成器、多星座、完整 NMEA | GNSS/NMEA 检查通过 | 2–3 周 |
| P4 | Provider/噪声/传感器 | 三路差异化、静止策略、物理噪声、传感器模型 | 三路交叉检查通过；传感器设备通过 | 3–4 周 |
| P5 | per-app 与数据平面 | 路由表、配置 v2、SampleStore | 直接 LocationManager 的 per-app 通过 | 2–3 周 |
| P6 | 隐蔽与 Native | libxposed、元数据、Native 实验 | 模块元数据不可见；Native 设备通过 | 2–3 周 |
| P7 | 前沿 | 原始 GNSS、网络/IP、Activity、性能 | 研究结论 + 可选能力 | 持续 |

> 时间是相对估计，不是承诺；设备/ROM 问题可能让某一阶段翻倍。

### 2.1 前 20 个任务（按执行顺序）

| 顺序 | ID | 任务 | 为什么现在做 |
|---:|---|---|---|
| 1 | P0-01 | detector 骨架 | 没有它，后面所有隐藏都无法验收 |
| 2 | P0-02 | detector 检查 v1 | 把“泄漏”变成可重复的 PASS/FAIL |
| 3 | P0-03 | 设备基线报告 | 锁定当前真实状态 |
| 4 | P0-04 | Hook 状态清单 | 知道每个 Hook 是否真的命中 |
| 5 | P0-05 | 许可证/模块护栏 | 避免后续返工/法律风险 |
| 6 | P1-01 | LocationSanitizer | 先清 Location 字段/extras |
| 7 | P1-02 | AppOps Hook | 最大硬伤之一 |
| 8 | P1-03 | Settings Provider Hook | 最大硬伤之二 |
| 9 | P1-04 | Provider 列表/属性 | `fused_ext`/test provider 暴露 |
| 10 | P1-05 | LocationAvailability | 补系统状态一致性 |
| 11 | P1-06 | Phase 1 设备回归 | 证明隐藏真的生效 |
| 12 | P2-01 | 环境模型 | WiFi/基站共同基础 |
| 13 | P2-02 | 采集/回放管线 | 支持真实采集 + 重标定 |
| 14 | P2-03 | 程序化 WiFi | 默认路径，替代空列表 |
| 15 | P2-04 | WiFi Hook | 让默认路径真正生效 |
| 16 | P2-05 | 基站画像/Hook | 去掉 CDMA 矛盾 |
| 17 | P3-01 | GNSS 模型抽取 | 结束 Hook 里的随机星表 |
| 18 | P3-02 | 多星座 V1 | 不再全 BDS/随机 |
| 19 | P3-03 | NMEA 生成器 | 让 GGA/GSV/GSA 同源 |
| 20 | P3-04 | GNSS/NMEA Hook 合并 | 去掉双 Hook/反语义 |

后续按 Phase 表继续；不要跳过 P0/P1 直接做 GNSS 或 Native。

---

## 3. 关键路径

```text
P0 基线
  → P1 隐藏
  → P2 环境
  → P3 GNSS
  → P4 Provider/传感器
  → P5 per-app
  → P6 隐蔽/Native
  → P7 前沿
```

可并行：

- P1 完成后，P2（WiFi/基站）与 P3（GNSS/NMEA）可由不同人并行。
- P4 的传感器 native 可与 P5 的 per-app 并行，但都需要设备窗口。
- P6 的 libxposed spike 可以在 P4 期间提前做，不阻塞主线。

---

## 4. Phase 0 · 基线与护栏

| ID | 任务 | 产出 | 验收 | 依赖 | 规模 | 状态 |
|---|---|---|---|---|---|---|
| P0-01 | 新建 `:detector` Android 应用 | 可安装的 detector；首页/结果页/导出 JSON | 能在 API 31/33/35 模拟器启动；能导出报告 | 无 | M | TODO |
| P0-02 | 实现 detector 检查 v1 | 15 项检查（见 06 文档） | 每项有 PASS/FAIL/UNKNOWN；失败给证据 | P0-01 | L | TODO |
| P0-03 | 设备基线报告 | 至少 2 台设备（建议小米 + Pixel/AOSP） | 记录 ROM/Android/LSPosed/结果；保存到 `docs/roadmap/baselines/` | P0-02 | M | TODO |
| P0-04 | Hook 所有权与状态清单 | 每个 Hook 对应 `HookStatus` 点 | 启动后能列出 supported/installed/matched/skipped/failed | 无 | M | TODO |
| P0-05 | 仓库护栏 | `LICENSE`/`NOTICE` 决策、模块依赖检查、文件行数预算、文档索引 | CI 能检查 `:core` 无 Android import、`:xposed` 不依赖 `:app`；PR 模板含任务 ID | 无 | M | TODO |
| P0-06 | Hook 拆分设计 | `LocationServiceHook` 拆分表 + 空文件骨架 | 评审通过；不改变行为 | 无 | S | TODO |

**Phase 0 退出**：detector 基线报告 + Hook 状态清单 + 护栏 CI 全部可用。

---

## 5. Phase 1 · 清洗与隐藏

| ID | 任务 | 产出 | 验收 | 依赖 | 规模 | 状态 |
|---|---|---|---|---|---|---|
| P1-01 | `LocationSanitizer` | 统一清理 mock 字段/extras/provider 名；补 MSL/不确定度/satellites | 单元测试覆盖 Android 12–17 字段差异；detector 通过 | P0-04 | M | TODO |
| P1-02 | AppOps Hook | `AppOpsService.checkOperation(Impl)` 等；按 caller UID 放行 controller | detector 的 AppOps 检查通过；不破坏其他 AppOps | P1-01 | M | TODO |
| P1-03 | Settings Provider Hook | 新增 `com.android.providers.settings` scope；Hook `SettingsProvider.call` GET_secure/GET_global | `mock_location`/dev options 对非系统调用不可见；系统功能不崩 | P1-01 | M | TODO |
| P1-04 | Provider 列表/属性 | 恢复 `getProviders`/`getAllProviders`/`hasProvider`/`getProviderProperties` | `fused_ext`/test provider 不暴露；`isProviderEnabled` 一致 | P1-01 | M | TODO |
| P1-05 | `LocationAvailability` | 合成 available=true，与 provider 状态一致 | detector 的 availability 检查通过 | P1-04 | S | TODO |
| P1-06 | Phase 1 设备回归 | 小米 + AOSP/Pixel；Xposed 模式 | 隐藏类检查全 PASS；记录证据 | P1-02～05 | M | TODO |

**Phase 1 退出**：Xposed 模式下 AppOps/Settings/Provider/Location 字段/Availability 不再明显泄漏；无 Root 模式的能力边界写清楚。

---

## 6. Phase 2 · 环境：WiFi 与基站

| ID | 任务 | 产出 | 验收 | 依赖 | 规模 | 状态 |
|---|---|---|---|---|---|---|
| P2-01 | `:core` 环境模型 | `WifiAp`/`CellTower`/`BleBeacon`/`EnvironmentProfile`/`EnvironmentSource` | JVM 序列化/校验测试；无 Android import | P1 | M | TODO |
| P2-02 | 采集/回放管线 | 采集 WiFi/基站/BLE；存储/导入导出；RSSI 重标定；构造器兼容 | 采集→回放→detector 一致性通过；无 root 走 IPC | P2-01 | L | TODO |
| P2-03 | 程序化 WiFi 合成 | geohash 网格、每格 4 AP、OUI、RSSI 路径损耗、插值、固定 seed | 同点同 seed 稳定；移动 RSSI 平滑；detector 通过 | P2-01 | L | TODO |
| P2-04 | WiFi Hook 重写 | `getScanResults`/`getConnectionInfo`/`getConfiguredNetworks`/`getDhcpInfo`/`WifiScanner` | 不返回空；BSSID 合法；连接 AP 在扫描列表；detector 通过 | P2-03 | L | TODO |
| P2-05 | 基站画像/Hook | `FULL_REPLAY`、`NO_SIM_WIFI_ONLY`；CDMA 默认关 | SIM/operator/phone type/cell 自洽；detector 通过 | P2-01 | L | TODO |
| P2-06 | 真实环境库（实验） | WigLE/OpenCellID/自建导入；来源/许可/attribution；默认关 | 开关关闭时无影响；开启时目标区域可回放 | P2-02 | L | TODO |
| P2-07 | 环境设置 UI | 来源选择、采集入口、AP 数、基站模式、实验开关 | 设置持久化；默认值符合 ADR-004/005 | P2-03/05 | M | TODO |
| P2-08 | Phase 2 设备回归 | 小米/OPPO/Pixel 至少 2 台 | WiFi/基站 detector 一致性通过 | P2-04/05 | M | TODO |

**Phase 2 退出**：不再出现空 WiFi/CDMA 空小区；采集回放与程序化两种默认路径可用；数据库实验开关可回退。

---

## 7. Phase 3 · GNSS 与 NMEA

| ID | 任务 | 产出 | 验收 | 依赖 | 规模 | 状态 |
|---|---|---|---|---|---|---|
| P3-01 | 抽取 GNSS 模型 | `GnssFrame`/`GnssSatellite`/`GnssSkyModel` 进 `:core` | `LocationServiceHook` 不再持有 BDS 星表；JVM 测试 | P1 | M | TODO |
| P3-02 | 多星座 V1 | GPS/BDS/GLONASS/Galileo；确定性可见性；CN0 随仰角；usedInFix | detector 看不出随机 shuffle；星座混合合理 | P3-01 | L | TODO |
| P3-03 | NMEA 生成器 | GGA/RMC/GSA/GSV/GST/ZDA；校验和；UTC；卫星数一致 | 黄金向量测试；GSV 与 GnssStatus 一致 | P3-01 | L | TODO |
| P3-04 | Hook 合并 | `GnssHook` + `LocationNMEAHook` → 单入口；修正 `enableNMEA` 语义 | 只有一个 `onNmeaReceived` 改写点；detector 通过 | P3-03 | M | TODO |
| P3-05 | V2 天空模型/原始测量可行性 | 星历/Kepler、Doppler、载波相位/钟差 spike；能力/限制报告 | 有可运行的模型/结论；默认关 | P3-02/03 | XL | TODO |
| P3-06 | Phase 3 设备回归 | 至少 2 台；GPS 应用 + detector | GNSS/NMEA 检查通过；无定位丢失/崩溃 | P3-04 | M | TODO |

**Phase 3 退出**：一个生成器、多星座、NMEA 自洽；V2/raw 有明确结论和开关。

---

## 8. Phase 4 · Provider、噪声、传感器

| ID | 任务 | 产出 | 验收 | 依赖 | 规模 | 状态 |
|---|---|---|---|---|---|---|
| P4-01 | `ProviderProfile` + 静止策略 | gps/network/fused 精度/频率/延迟/字段；静止规则 | 三路数据不同；静止无 0° bearing/计步增长 | P3 | M | TODO |
| P4-02 | `PhysicalNoise` | OU 噪声、精度/海拔漂移、航向门控；seed | 关闭=确定；开启=有界/可复现；速度一致 | P4-01 | M | TODO |
| P4-03 | 后端按 provider 发布 | MockProvider/Fused/Xposed 分别发布 `ProviderSample` | detector 三路交叉检查通过 | P4-01/02 | L | TODO |
| P4-04 | Native 传感器重写 | `SensorEventQueue::write`/`dispatchSensorEvent`；按 type 改写；默认关 | 设备上加速度/陀螺仪/计步合理；无 -1/0 清零 | P4-01 | XL | TODO |
| P4-05 | 传感器融合模型 | `MotionState`→`SensorFrame`；Activity 策略 | 静止/步行/跑步/驾车四组测试；不矛盾 | P4-04 | L | TODO |
| P4-06 | Phase 4 设备回归 | 至少 2 台；传感器 + 三路 Provider | 三路/静止/传感器检查通过 | P4-03/05 | M | TODO |

**Phase 4 退出**：Provider 不再“三份一样”；静止自然；传感器不再是空壳；Native 默认关但可用。

---

## 9. Phase 5 · per-app 与数据平面

| ID | 任务 | 产出 | 验收 | 依赖 | 规模 | 状态 |
|---|---|---|---|---|---|---|
| P5-01 | `RoutingTable` | `(package,userId)→scenario`；校验/序列化 | JVM 测试；非法输入拒绝 | P4 | M | TODO |
| P5-02 | system_server 路由 | 调用方 UID→package→route；listener 映射 | 两个 App 同时不同坐标；未配置走默认 | P5-01 | L | TODO |
| P5-03 | ConfigEnvelope v2 / SampleWire v2 | 版本化配置、provider 字段、环境版本 | 旧客户端兼容；配置切换无 stop/start | P5-01 | L | TODO |
| P5-04 | per-app UI | 应用列表、搜索、分配场景、工作资料 | 选择/清空/重启后持久化 | P5-02 | M | TODO |
| P5-05 | GMS per-app 评估 | 结论：全局/ GMS Hook/ target 兜底 | 文档化限制；默认全局 | P5-02 | M | TODO |
| P5-06 | `SampleStore`/`ConfigStore` | 抽象 + Binder 实现；SHM 设计 | 无行为变化；可替换实现 | P5-03 | M | TODO |
| P5-07 | `RuntimeSnapshot` 替代 `FakeLoc` | 不可变配置/样本/环境快照 | 逐步迁移；旧字段兼容 | P5-03 | L | TODO |
| P5-08 | 命令/设置收敛 | `RemoteCommandHandler` 拆 config/sample/control/diagnostic；`SettingsSchema` | 行为不变；旧配置可读 | P5-03 | M | TODO |
| P5-09 | Phase 5 设备回归 | 2 台；per-app + 配置切换 | 路由/切换/回退通过 | P5-02/04 | M | TODO |

**Phase 5 退出**：per-app 对直接 `LocationManager` 生效；GMS 限制明确；配置/样本可版本化升级。

---

## 10. Phase 6 · 隐蔽与 Native 实验

| ID | 任务 | 产出 | 验收 | 依赖 | 规模 | 状态 |
|---|---|---|---|---|---|---|
| P6-01 | libxposed 迁移 spike | API 102 入口/元数据/scope；可编译运行 | LSPosed 能加载；旧 API 可回退 | P4 | L | TODO |
| P6-02 | 元数据迁移/模块拆分 | 移除 manifest `xposedmodule`；`META-INF/xposed/*` | `PackageManager` 查不到 Xposed 元数据 | P6-01 | M | TODO |
| P6-03 | Native 隐藏实验 | 参考 Kail：符号解析、`/proc/self/maps`/痕迹；默认关 | 目标检测项改善；无崩溃；可重启回退 | P6-01/P4-04 | L | TODO |
| P6-04 | 安全/权限审查 | 导出组件、IPC、日志、Bugly、包可见性 | 无高危项；权限最小化 | P5 | M | TODO |
| P6-05 | Phase 6 设备回归 | 2 台 + LSPosed 版本矩阵 | 加载/隐藏/回退通过 | P6-02/03 | M | TODO |
| P6-06 | 文档/默认值对齐 | README、设置页、实验标记 | 不再宣称未实现能力；实验默认关 | 全阶段 | S | TODO |

**Phase 6 退出**：模块元数据不可见；libxposed 可用；Native 实验默认关且可回退；文档不再夸大。

---

## 11. Phase 7 · 前沿与长期

| ID | 任务 | 产出 | 验收 | 依赖 | 规模 | 状态 |
|---|---|---|---|---|---|---|
| P7-01 | 原始 GNSS 生产化 | `GnssMeasurements`/`NavigationMessage`/`AntennaInfo` 策略 | 高端 detector 不出现明显缺失；默认关 | P3-05 | XL | TODO |
| P7-02 | 网络/IP/时区一致性 | per-app 代理/VPN、时区/locale/carrier 检查 | 出口 IP 与坐标不矛盾；可回退 | P5 | XL | TODO |
| P7-03 | Activity Recognition | GMS/AOSP 活动识别适配 | 运动状态不矛盾；默认关 | P4-05 | L | TODO |
| P7-04 | 性能/耗电 | 发布延迟、CPU、内存、电池基线 | 有量化报告；不超预算 | P4 | M | TODO |
| P7-05 | OEM 矩阵 | 小米/OPPO/vivo/三星/Pixel/华为 | 兼容性表；已知问题清单 | P2/P6 | L | TODO |

---

## 12. 横向任务

| ID | 任务 | 产出 | 验收 | 状态 |
|---|---|---|---|---|
| X-01 | 每阶段更新文档 | `01/05/06` 同步 | 阶段结束前完成 | TODO |
| X-02 | 发布门禁 | detector + 设备证据 + Release 校验 | 缺一不发 | TODO |
| X-03 | 许可证/归属 | LICENSE、NOTICE、第三方清单 | 每次引入依赖更新 | TODO |
| X-04 | 依赖治理 | 新依赖的用途/许可证/体积 | PR 模板检查 | TODO |

---

## 13. 第一个迭代（建议 2 周）

### 第 1 周

- `P0-01` detector 骨架
- `P0-02` 检查 v1（先做 AppOps/Settings/Provider/Location 字段）
- `P0-04` Hook 状态清单
- `P0-05` 许可证/模块规则

### 第 2 周

- `P1-01` LocationSanitizer
- `P1-02` AppOps Hook
- `P1-03` Settings Provider Hook
- `P1-04` Provider 列表
- `P1-06` 第一轮设备回归

**交付物**：一份“当前设备泄漏基线 + 修复后对比”报告；这是后续所有阶段的参照。

---

## 14. Feature Flag 与发布策略

| 能力 | 默认 | 发布方式 |
|---|---|---|
| WiFi 程序化 | 开 | Beta 先验证；稳定版默认开 |
| WiFi 回放 | 可选 | 默认关，用户选择 |
| WiFi 数据库 | 关 | 实验标记；设备验证后再评估 |
| 基站 FULL_REPLAY | AUTO | 有 SIM 默认 |
| 基站 NO_SIM | AUTO | 无 SIM 默认 |
| CDMA 旧路径 | 关 | deprecated；下一版本删 |
| GNSS V1 | 开 | Beta 验证后稳定 |
| GNSS V2 | 关 | 设备验证后开 |
| GNSS raw | 关 | 实验 |
| 传感器 native | 关 | 实验；需重启 |
| Native 隐藏 | 关 | 实验；需重启 |
| per-app | 完成后开 | Beta 先验证 |
| libxposed | 迁移后 | 保留旧 API 回退分支 |

发布规则：

1. 每个 Phase 先出 Beta；至少 7 天或 20 个测试设备。
2. Beta 期间 detector 不得新增 FAIL。
3. 稳定版前更新 `06-validation.md` 的设备证据。
4. 实验能力在设置页显示“实验性/需要重启/仅特定 ROM”。

---

## 15. 推迟/不做

| 项 | 原因 | 触发条件 |
|---|---|---|
| target app 默认注入 | 破坏架构优势 | 只有 N2 通过评审 |
| 沙盒/多开 | 与主线无关 | 不做 |
| 相机/NFC/麦克风 | 与主线无关 | 不做 |
| 全球指纹库默认 | 许可/维护成本 | 只有实验模式 |
| SharedMemory 第一版 | 过早优化 | Binder 延迟/规模证明需要 |
| Compose 迁移 | 与主线无关 | 不做 |
| Play Integrity 绕过 | 不可行 | 不做 |

---

## 16. 状态更新模板

任务完成时，在 `06-validation.md` 追加：

```text
### P1-02 AppOps Hook
- 日期：2026-11-xx
- 设备：Xiaomi 14 / HyperOS 2 / Android 15 / LSPosed 1.9.x
- 结果：detector AppOps 检查 PASS；系统 AppOps 其他操作正常
- 证据：detector 报告路径/截图/日志
- 回归：现有 196 JVM 用例 + 新增 N 项通过
- 备注：无 Root 模式不适用
```

然后回到本表把 `状态` 改为 `DONE`。

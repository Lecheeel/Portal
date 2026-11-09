# UI Compose 重构路线图

> 状态：**IN PROGRESS**
> 目标：把现有 XML View + Fragment 的 UI 逐步迁移为统一的声明式 Compose UI，同时保持地图、定位、Xposed 和 Native 能力不变。

## 1. 目标与边界

### 目标

- UI 框架统一为 **Jetpack Compose**。
- 建立可复用的 **Portal Design System**，统一颜色、字体、形状、间距、组件和动效。
- 页面状态从 Fragment/ViewBinding 逐步迁移到 **ViewModel + StateFlow + Compose State**。
- 现有地图 SDK、定位后端、Xposed/Native 能力不改写。
- 每个阶段都能单独构建、运行和回退。

### 非目标

- 不重写高德/腾讯 MapView，地图继续通过 `AndroidView` 互操作。
- 不一次性删除全部 Fragment 和 XML。
- 不引入 iOS HIG 风格或全局毛玻璃。
- 不为了 UI 改动定位、GNSS、传感器或隐蔽逻辑。
- 不在本阶段引入新的定位后端。

## 2. 技术决策

| 决策 | 结论 |
|---|---|
| UI 框架 | Jetpack Compose |
| 设计系统 | Material 3；后续评估 Material 3 Expressive |
| 导航 | 过渡期保留 Navigation Component；最终迁移到 Navigation 3 |
| Activity | 最终收敛为单个 `ComponentActivity` |
| 状态 | ViewModel + StateFlow，Compose 侧 collectAsStateWithLifecycle |
| 设计系统载体 | 新增 `:designsystem` Android Library |
| 地图 | `AndroidView` 包装现有 MapView |
| 依赖版本 | 以当前 AGP/Kotlin 可解析的稳定 BOM 为准，不照抄其他项目 |

## 3. 阶段计划

### Phase 0 · 基础与验证

- [x] 验证 Compose / Material3 依赖在当前构建环境可用（首次在线解析，之后可离线）。
- [x] 新建 `:designsystem` 模块。
- [x] 建立 `PortalTheme`：颜色、字体、形状、间距。
- [x] 建立基础组件：Card、SectionTitle；Button/Pill/Sheet 持续补充。
- [ ] 增加 Compose UI 测试依赖与截图测试。
- [x] 构建最小 Compose 页面做冒烟验证。

### Phase 1 · 运行中心（第一个正式页面）

- [x] 用 Compose 重建「运行中心」。
- [x] 覆盖运行状态、设备授权、后端选择、Native 风险确认、基础诊断、日志。
- [x] 接入 `ScenarioRuntime.state` / `diagnostics` / `RuntimeEnvironment`。
- [x] 保留「环境工具」「位置探针」「导出日志」入口。
- [x] 新增 `RuntimeComposeFragment` 并替换 `nav_runtime`；旧 `RuntimeFragment` 暂留作回退。
- [ ] Compose UI 测试与截图基线。
- [ ] 运行中心完整迁移后删除旧 Fragment 与 `fragment_runtime.xml`。

### Phase 2 · 设置与环境工具

- [ ] 迁移设置页到 Compose。
- [ ] 迁移环境采集、WiFi 指纹、回放配置到 Compose。
- [ ] 统一表单控件、折叠区、开关、说明文字和危险操作确认。
- [ ] 保留现有 SharedPreferences / 配置文件读写。

### Phase 3 · 模拟页

- [ ] 迁移「位置 / 卫星」双 Tab。
- [ ] 统一输入框、保存位置、开始/暂停/停止、状态卡片。
- [ ] 保持现有 GNSS/NMEA 状态数据源。
- [ ] 补充 Compose 状态测试。

### Phase 4 · 地图壳与导航收口

- [ ] 首页地图控制层 Compose 化：搜索、FAB、底部目标卡片、摇杆入口。
- [ ] 地图本身继续使用 `AndroidView`。
- [ ] 单个 `ComponentActivity` + Compose Navigation。
- [ ] 逐页替换 `NavHostFragment`。
- [ ] 删除只被 Compose 替代的 Fragment / layout / binding。

### Phase 5 · 清理与交付

- [ ] 统一删除过期 XML、Fragment 和 ViewBinding。
- [ ] 设计系统组件有独立预览。
- [ ] Compose 测试、截图测试、构建门禁。
- [ ] 更新 README、截图和发布说明。

## 4. 页面迁移矩阵

| 页面 | 现状 | 目标 | 依赖 | 状态 |
|---|---|---|---|---|
| 运行中心 | Fragment + XML | Compose 首屏已接入 | Runtime state、诊断、Native 风险 | 进行中 |
| 设置 | Fragment + XML | Compose | Settings/Perfs、Hook 状态 | 待开始 |
| 环境工具 | Fragment + XML | Compose | SAF、环境存储、指纹导入 | 待开始 |
| 模拟（位置/卫星） | Fragment + XML | Compose | Location/Route/GNSS 状态 | 待开始 |
| 资料库/路线 | Fragment + XML + Adapter | Compose | 路线存储、GPX/JSON | 后续 |
| 首页地图 | Fragment + XML + MapView | Compose 壳 + AndroidView | 高德/腾讯地图 | 最后 |
| 悬浮摇杆 | View overlay | Compose/AndroidView 混合 | WindowManager | 后续 |

## 5. 设计系统约束

- 只使用一套 `PortalTheme`，不在页面里散落颜色和圆角。
- 页面只组合组件，不直接写重复的 Material 样式。
- 颜色走 Material color roles，不硬编码十六进制业务色。
- 间距使用统一 token；卡片圆角、边框、阴影统一。
- 动效只用于状态变化和层级切换，不堆装饰。
- 不引入 Haze 全局毛玻璃，不模仿 iOS HIG。
- 深色/浅色、动态色、Edge-to-Edge 从第一阶段就纳入。

## 6. 验证策略

每个阶段至少包含：

```text
./gradlew :app:assembleArm64Debug
./gradlew :app:testDebugUnitTest
./gradlew :designsystem:testDebugUnitTest
```

页面阶段额外包含：

- Compose UI test（语义节点、点击、状态切换）。
- 真机截图检查（A/B 设备至少一台）。
- 现有 detector / 定位回归不退化。
- 页面回退到旧 Fragment 的开关或提交边界。

## 7. 进度记录

- 2026-11-09：创建路线图；验证 Compose 构建环境；创建 `:designsystem`；完成运行中心 Compose 首屏并接入 `nav_runtime`。本地 `:app:assembleArm64Debug` 与 Compose 编译通过，A 设备实际打开运行中心验证。
- 当前构建注意：本机首次解析 Compose 依赖需要联网；解析完成后 `.tmp/build.ps1` 的离线构建可用。`:designsystem` 需要显式 `buildToolsVersion = "37.0.0"`。

## 8. 风险

- 当前本机 Gradle 使用离线缓存，Compose 依赖可能尚未缓存，需要先验证依赖解析。
- 地图 SDK 是 View 体系，必须保留 `AndroidView` 互操作，不能为了纯 Compose 重写 SDK。
- 现有运行中心逻辑与 Fragment 生命周期耦合，迁移时要避免状态重复收集。
- Material3 Expressive 仍可能依赖 alpha/rc 版本，优先稳定版，表现层能力按可用性渐进启用。

# GMS per-app 评估（P5-05）

状态：`DONE`（结论已形成；设备验证转入 P5-09）
日期：2026-11-06

## 结论

**GMS Fused 只做全局模拟，不做 per-app 隔离。**

- AOSP `LocationManager` 的 per-app 路由（`RoutingTable` / `ListenerRouting`）继续生效。
- `FusedMockBackend` 与 GMS 测试 Provider 仍是全局通道；不要向用户宣称 GMS 能按 App 返回不同坐标。
- 需要 per-app 不同坐标时，使用 AOSP Provider 或 `AppRoute.point` 固定点，不依赖 GMS。

## 原因

1. GMS Fused 的请求/回调发生在 Google Play services 进程，system_server 侧只能看到测试 Provider 的全局位置，无法可靠拿到“最终是哪个 App 消费了这次 fused 结果”。
2. 现有 `FusedMockBackend` 通过 `setTestProviderLocation("fused")` 提交，一个全局坐标；再按 App 拆分需要修改 GMS 进程或目标 App，超出 ADR-002（system_server 单点 Hook）的边界。
3. 在 system_server 对 fused 结果做二次过滤，无法覆盖 GMS 自己的缓存、批处理和高精度融合路径，容易产生“看似隔离、实际泄漏”的假象。

## 产品行为

- GMS 开关默认关闭；开启时明确标注“全局”。
- GMS 与 per-app 固定点同时存在时：
  - AOSP `gps/network` 仍按 App 路由；
  - GMS fused 使用当前全局场景；
  - 文档与 UI 不承诺 GMS 的 per-app 差异。
- 关闭 GMS 后，App 走 AOSP provider，per-app 路由完整生效。

## 后续验证

- 设备上分别用两个 App 读取 `FusedLocationProviderClient` 与 `LocationManager`，确认 GMS 全局、AOSP per-app 的差异。
- detector C04/C05/C11 记录 GMS 场景下的实际坐标来源。
- 若未来必须做 GMS per-app，另立 ADR，评估 GMS 进程 Hook / 目标 App Hook 的兼容性与风险，不在当前范围。

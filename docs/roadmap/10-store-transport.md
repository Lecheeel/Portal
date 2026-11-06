# P5-06 配置/样本传输设计（Binder 与共享内存）

日期：2026-11-06
状态：`WIP`（设计完成，Binder/SHM 实现未落地）

## 约束

- Hook 回调路径必须 O(1)，不能读磁盘；
- app 与 system_server 分属不同进程/UID；
- 5 s publication lease 必须跨进程一致；
- 失败必须回退到现有 Binder 字节样本，不能阻断定位；
- 任何共享内存都必须有版本、长度、校验和边界检查。

## 现状

- `SampleWire`：Binder `Bundle` 字节数组，版本 + 最小兼容版本；
- `RuntimeSnapshot`：进程内不可变快照；
- `FileConfigStore`：app 侧 active config 原子持久化；
- `ConfigStore`/`SampleStore`：接口 + 内存实现。

## 方案 A：继续 Binder（默认）

- 样本：现有 `publish_sample` Bundle，字段固定；
- 配置：`put_config` + `ConfigGate`；
- 优点：无需新权限/共享内存生命周期；已有 lease/sequence/token；
- 缺点：每次发布一次 Binder 事务；大配置（routing/environment JSON）重复传输；
- 适用：当前场景（1–10 Hz）。

## 方案 B：Binder 配置 + 共享内存样本

- app 创建 `ashmem`/`SharedMemory`，写入双缓冲：
  - header：magic、version、capacity、writeIndex、sequence、elapsedNanos、length、CRC32；
  - slot：固定大小样本（经纬度、精度、速度、航向、时间、provider、flags）；
  - 写者更新 sequence（奇数写入中，偶数完成），读者重试；
- 通过 Binder 只传 fd + 版本 + 容量；
- system_server `mmap` 只读，校验 magic/version/length/CRC 后读取；
- 生命周期：app 死亡 fd 关闭，system_server 检测到失效后回退；
- 安全：
  - 只接受 app 进程 fd；
  - fd 只读映射；
  - 限制容量（≤64 KiB）；
  - 不信任 sequence/长度，全部校验；
- 回退：SHM 不可用时自动回到 `SampleWire`。

## 方案 C：配置也进共享内存

- 配置 JSON 较大且变更低频，收益低；
- 继续 Binder + `ConfigCodec`，避免多写者一致性问题。

## 决策

1. 当前版本保持方案 A，先完成 `ConfigStore` 的 app 映射与恢复；
2. 只有 profile 证明 Binder 发布延迟 p95 超预算时，才启用方案 B；
3. 方案 B 默认关闭，设置页不暴露，作为实验开关；
4. 任何 SHM 实现必须保留 `SampleWire` 回退与协议版本协商。

## 待办

- `SampleWire` 版本协商测试；
- `SharedMemory` 生命周期/权限验证（设备）；
- 双缓冲 seqlock 的 JVM 模拟测试；
- 性能对比：Binder vs SHM 的 p50/p95；
- 安全审查：fd 传递、越界、TOCTOU。

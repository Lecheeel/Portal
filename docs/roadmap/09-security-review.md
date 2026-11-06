# 安全/权限审查记录（P6-04）

日期：2026-11-06
状态：`WIP`（静态审查完成，运行时/设备验证未做）

## 导出组件

合并 manifest（arm64Debug）中应用自身组件：

| 组件 | exported | 说明 |
|---|---|---|
| `MainActivity` | true | launcher 必需 |
| `LocationProbeActivity` | false | 独立进程诊断 |
| `ScenarioService` | false | 前台服务 |
| `APSService` | false | 高德定位服务，显式锁定 |

依赖侧仅 `ProfileInstallReceiver` 导出，受 `android.permission.DUMP` 保护。

## IPC / 命令

- `CommandSecurity`：
  - 调用方 UID + 完整 package 名；
  - controller 包名固定；
  - peer 白名单；
  - token + 单调 sequence + 30 s 时限 + protocol version；
  - peer 仅允许 `sync_config` / `set_proxy`。
- `ProxyCommand`：
  - 只接受 UID 1000；
  - `proxy_sequence` 单调 + 时限；
  - 转发以 system_server 身份执行。
- `load_library`：
  - 只接受固定路径 `/data/local/ext-lib/liblocationext.so`；
  - 只允许 system_server 进程。
- `ConfigGate`：`put_config` 在写入 `FakeLoc` 前校验数值/枚举。

## 日志

- `SensitiveLog` 对经纬度、海拔、sample 坐标、environment_json、routing_json、token/key 输出 `<redacted>`；
- debug 日志默认关闭。

## Bugly / 隐私

- 崩溃上报默认关闭；
- 开启时 `setCollectPrivacyInfo(false)`，使用本地随机 installation id；
- 关闭时 `closeBugly()`，并关闭 Bugly 开关；
- 备份规则排除 Bugly 数据库/SharedPreferences。

## 备份/迁移

- `environment/`、`library/`、`fused_ext.xml`、ownership/runtime/sdk privacy 等已从 cloud backup 与 device transfer 排除。

## 未完成

- 设备端导出组件/命令面验证；
- 其他 SDK 的包可见性/网络行为；
- `allowBackup` 整体策略；
- 运行时注入/动态加载攻击面测试。

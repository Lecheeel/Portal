# LocationService

> 基于 [Portal](https://github.com/fuqiuluo/Portal) 的二次开发版本，用于 Android 定位检测技术研究。

---

## 项目简介

本项目是对 Portal（一个基于 LSPosed 的系统级虚拟定位模块）进行的深度二次开发。原项目通过 Hook 系统服务实现虚拟定位，本分支在此基础上系统性地清除了所有静态与直接可探测指纹，以便于研究更细粒度的 Android 定位检测技术，并为后续添加测试挡位功能预留了扩展空间。

本项目仅用于开发者调试定位相关程序，以及学习和研究 Android 定位机制。

---

## 与原项目的主要变更

### P0 — 运行时直接指纹（已清除）

这类指纹可被任意 App 以极低成本主动探测。

| 项目 | 原始值 | 变更后 |
|---|---|---|
| 虚拟 Provider 名称 | `"portal"` | `"fused_ext"` |
| `sendExtraCommand` 通信命令名 | `"portal"` | `"fused_ext"` |
| `isProviderEnabled` 探测判断 | `provider == "portal"` | `provider == "fused_ext"` |
| 系统属性注入标志 key | `"portal.injected_${pkg}"` | `"_lp.${hex(pkg.hashCode())}"` |
| 随机会话 key 前缀 | `"portal_" + Random` | 纯 hex 随机数，无前缀 |

**影响**：原版任何 App 只需调用 `LocationManager.sendExtraCommand("portal", ...)` 或遍历 `System.getProperties()` 查找 `portal.*` 前缀即可确认模块存在。清除后这两条探测路径均失效。

### P1 — 静态指纹（已清除）

这类指纹在 APK 扫描、包管理器枚举、反编译等场景下可被识别。

| 项目 | 原始值 | 变更后 |
|---|---|---|
| `applicationId` / 包名 | `moe.fuqiuluo.portal` | `com.system.location.service` |
| Xposed 模块包名 | `moe.fuqiuluo.xposed` | `com.system.location.service.hook` |
| JNI/Dobby 包名 | `moe.fuqiuluo.dobby` | `com.system.location.service.jni` |
| Xposed 入口类 (`xposed_init`) | `moe.fuqiuluo.xposed.FakeLocation` | `com.system.location.service.hook.FakeLocation` |
| Xposed 模块描述 | `基于 Xposed 实现 虚拟位置定位服务` | `Location Service Extension` |
| 原生库名 | `libportal.so` | `liblocationext.so` |
| 原生库运行时路径 | `/data/local/portal-lib/libportal.so` | `/data/local/ext-lib/liblocationext.so` |
| CMake 项目名 | `Portal` | `LocationExt` |
| JNI 导出函数符号 | `Java_moe_fuqiuluo_dobby_Dobby_setStatus` | `Java_com_system_location_service_jni_Dobby_setStatus` |
| 构建输出 APK 名 | `Portal-v*.apk` | `LocationService-v*.apk` |

所有 `.kt`、`.java`、`.xml`、`.cpp`、`.kts` 共 **70 个文件**均已批量同步更新，无遗漏。

### P2 — 运行时可观测行为指纹（已清除）

这类指纹需要调用方具备一定分析能力才可发现，但并不复杂。

| 项目 | 原始行为 | 变更后 |
|---|---|---|
| `Location.extras` 写入 `"portal.enable"` | 非 hide 模式下写入 Bundle，直接暴露模块标识 | 完全移除，任何模式下均不写入 |
| `Location.extras` 写入 `"is_mock"` | 非 hide 模式下写入 Bundle | 完全移除，通过 `location.isMock` 标准字段表达 |
| Binder 接口描述符 | `moe.fuqiuluo.portal.service.${from}Helper` | `android.location.service.${from}Provider` |
| BinderUtils 包名白名单过滤 | 硬编码 `moe.fuqiuluo.portal` | 同步更新为新包名 |

---

## 架构说明

```
LocationService/
├── app/                        # 主应用（用户界面、配置管理、地图交互）
│   └── com.system.location.service
├── xposed/                     # Xposed 模块（系统服务 Hook）
│   ├── com.system.location.service.hook
│   └── com.system.location.service.jni  (Dobby 原生传感器 Hook)
├── nmea/                       # NMEA 句子解析与注入
└── system-api/                 # 编译用系统 API 桩
```

**通信机制**：App 通过 `LocationManager.sendExtraCommand("fused_ext", sessionKey, bundle)` 与 system_server 进程内的 Hook 进行 IPC 通信，会话 key 在每次启动时随机生成，防止第三方枚举探测。

---

## 功能列表

- [x] 任意场景下强制替换 GPS / 网络 / 融合定位坐标
- [x] Hook GNSS 卫星数据（颗数、信噪比、NMEA 句子）
- [x] Hook 基站 / 电话信息（TelephonyRegistry、PhoneInterfaceManager）
- [x] Hook Wi-Fi 扫描结果与连接信息
- [x] Hook 三方定位 SDK（高德、百度、腾讯）
- [x] 支持路线录制与回放
- [x] 摇杆实时移动位置
- [x] 可配置速度、高度、精度、航向
- [x] 传感器 Hook（Dobby 原生库，需 root）
- [x] 隐藏 `isMock` 标志（Android 12+）
- [ ] 测试挡位模式（规划中，将支持逐层开启各类 Hook）

---

## 使用要求

- Android 8.0 (API 26) 及以上
- 已安装 [LSPosed](https://github.com/LSPosed/LSPosed) 框架
- Root 权限（传感器 Hook 功能需要）
- 在 LSPosed 中激活模块，作用域选择 `android`、`com.android.phone` 及目标应用

---

## 如何检测本模块（研究参考）

原版 Portal 存在以下几条低成本探测路径，本分支已对其进行清除：

```kotlin
// 原版：直接探测 portal provider（已清除）
val bundle = Bundle()
val exists = locationManager.sendExtraCommand("portal", "exchange_key", bundle)

// 原版：枚举系统属性（已清除）
val isInjected = System.getProperties().keys.any {
    it.toString().startsWith("portal.")
}

// 原版：读取 Location extras（已清除）
val isMocked = location.extras?.getBoolean("portal.enable") == true
```

清除以上路径后，检测难度进入下一层级（设备特征、时序分析、卫星数据一致性等），这也是本二次开发的研究目标所在。

---

## 致谢

- [fuqiuluo/Portal](https://github.com/fuqiuluo/Portal) — 原始项目
- [GoGoGo](https://github.com/ZCShou/GoGoGo)
- [Baidu Map SDK](https://lbsyun.baidu.com/faq/api?title=androidsdk)
- [LSPosed](https://github.com/LSPosed/LSPosed)
- [Dobby](https://github.com/jmpews/Dobby)

---

## 免责声明

1. 本项目基于 Apache 2.0 许可证开放，可用于任何符合法律的目的，包括商业和非商业用途，特别鼓励用于学习和研究。
2. 使用者须遵守当地相关法律法规，因使用本软件导致的任何后果由使用者自行承担，与本项目开发者无关。
3. 如发现任何人利用本项目进行违法活动，请收集证据并向有关部门举报。
4. 根据 Apache 2.0，再分发时需保留原始版权声明、NOTICE 文件、许可证副本，并说明所做的重大修改。

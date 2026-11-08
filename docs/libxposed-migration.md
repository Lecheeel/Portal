# libxposed API 102 迁移

更新：2026-11-07。正式 Xposed 模块使用 `io.github.libxposed:api:102.0.0`，活跃 Hook 直接实现原生 `Hooker` / `Chain`。此文记录当前接入与验证边界，详细模块关系见 [架构说明](architecture.md)。

## 框架与兼容性

Xposed 模式要求框架实际支持 libxposed API 102；仅支持 legacy API 的框架不能加载此次现代入口。模块不提供 API 82 回退，也不以某个 LSPosed 版本名称推断 API 102 支持情况。标准无 Root Mock 后端仍有自己的权限和能力边界；Native 仍是依赖 Root、Xposed 和目标架构的实验扩展。

API 依赖在正式模块使用 `compileOnly`，由框架在目标进程提供；仅 JVM 测试使用 `testImplementation`。旧 `de.robv.android.xposed` 依赖、before/after 兼容层、`assets/xposed_init`、manifest 模块 metadata、旧 Maven 仓库及 `:libxposed-spike` 原型均已移除。libxposed API 的许可证为 [Apache-2.0](https://github.com/libxposed/api/blob/102.0.0/LICENSE)，依赖清单见 [THIRD-PARTY.md](roadmap/THIRD-PARTY.md)。

## 入口、生命周期与作用域

正式资源位于 `xposed/src/main/resources/META-INF/xposed/`：

| 文件 | 内容与用途 |
|---|---|
| `java_init.list` | 唯一入口 `com.system.location.service.hook.PortalModule` |
| `module.prop` | `minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`、`autoHotReload=true` |
| `scope.list` | 六个静态作用域，见下表 |

| 作用域 | 目标 |
|---|---|
| `system` | system_server；现代 scope 使用此标识，进程身份仍按 `android` / `system_server` 解析 |
| `com.android.phone` | 电话进程 |
| `com.android.location.fused` | 系统 fused 定位进程 |
| `com.xiaomi.location.fused` | Xiaomi fused 定位进程 |
| `com.oplus.location` | Oplus 定位进程 |
| `com.android.providers.settings` | Settings provider 进程 |

`onModuleLoaded` 连接 `HookRuntime` 并记录进程身份；系统安装在 `onSystemServerStarting` 使用框架传入的 ClassLoader，不提前使用普通系统 ClassLoader 替代。普通包在 `onPackageReady` 按包身份和作用域判断，并按包去重。共享进程可能先报告 `android`、随后才报告真正的 phone/fused 包，因此不以 `isFirstPackage` 过滤这些回调；系统进程仍由专用生命周期安装。框架作用域与 `HookScopeResolver` 的包名、进程名、UID、SDK 条件同时约束实际安装。

system_server 支持热重载：`autoHotReload=true`，模块通过 `libxposed:service` 绑定并暴露 targets/hot reload 调试接口。`onHotReloading` 只接受 system_server 且未加载 Native Dobby 的进程，先停止 `LocationTicker`，保存可跨 classloader 的进程状态；`onHotReloaded` 卸载旧句柄、重建 Hook 和 SettingsProvider hook。phone/fused 进程拒绝热重载，仍需重启目标进程；Native Hook 一旦加载也必须重启。运行场景的开始、停止和参数调整仍使用现有运行控制协议。

### 真机调试循环

- Java-only 改动（system_server + phone/fused）：

  ```powershell
  python scripts/deploy_vector_module.py <apk> --hot-reload --restart-targets
  ```

  流程：安装 APK → 修 Vector 缓存路径 → system_server 自动热重载（PID 不变）→ kill phone/fused 使其由系统重新拉起并重新注入。v24 实测三个 target 均为 `UP_TO_DATE`，system_server PID 保持不变。

- Native `.so` 改动：

  ```powershell
  python scripts/deploy_vector_module.py <apk> --native-lib <liblocationext.so>
  ```

  Dobby inline hook 无法卸载；脚本推送 `.so` 后强制重启 zygote，不尝试热重载。已实测该路径：system_server PID 改变，重启后三个 target 均为新 versionCode/`UP_TO_DATE`。

- 只改位置/配置/场景参数：直接走调试 API `/location`、`/scenario/*`、`/config`，不需要安装 APK 或重启进程。

## 原生 Hook 与反射

`HookInstaller` 通过框架 `hook(...).setPriority(...).setExceptionMode(...).intercept(...)` 安装，返回原生 `HookHandle`。首次命中记录与业务逻辑共用拦截器，处理注册尚未返回时即发生调用的竞态；installed 与 matched 保持独立含义。

业务 Hook 通过 `Chain` 读取接收对象和参数、继续调用或直接返回结果。默认异常模式是 `PROTECTIVE`；需要向调用方故意抛出拒绝异常的分支明确选择 `PASSTHROUGH`，避免框架保护机制改变拒绝行为。迁移不保留旧式结果、throwable 状态转换。

`Reflection` 处理父类私有成员、可访问性、null、静态/实例重载、基本类型装箱及扩宽；更具体的参数签名优先，无法唯一匹配时拒绝调用。普通方法/构造器通过默认 `FULL` invoker 保留 Hook；明确绕过 Hook 的原 listener 投递通过 `ORIGIN` invoker。目标异常会从 `InvocationTargetException` 解包，保留原异常身份。

## 检查与当前证据

源码检查：

```text
python scripts/check_xposed_module.py
python -m unittest discover -s scripts -p 'test_xposed_module.py'
```

实际 APK 检查：

```text
python scripts/check_xposed_module.py --apk-dir app/build/outputs/distribution
```

检查器验证现代入口资源、API 版本、六个 scope 和热重载元数据，拒绝旧入口、旧 manifest metadata 及 compat 回归；APK 检查读取 DEX 类定义，确认入口存在且未打包框架 API / legacy compat 类。合法 API 类型引用不会被误认为打包了 API 实现。两条构建工作流已接入源码和 APK 检查，正式发布校验脚本也执行相同入口检查。

本轮已完成以下本地验证；历史文档中的测试数量不作为本次结果。

| 检查 | 结果与覆盖范围 |
|---|---|
| Kotlin 编译与 JVM 回归 | 编译通过，481 项 JVM 测试通过（xposed 77 / core 269 / nmea 8 / app arm64Debug 127）；包含生命周期、热重载策略、反射、Hook 安装、原方法调用和既有业务回归 |
| Python 回归 | 29 项测试通过；包含现代模块检查器和既有发布校验 |
| 完整 CMake 构建 | `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 四个 ABI 通过 |
| 实际 APK | 本轮 ARM64 Debug APK 构建完成，现代入口、配置、scope 与 DEX 类定义检查通过；此包仅用于开发验证 |
| lint | `:app`、`:xposed` 两个模块的本轮 lint 检查通过 |

本次设备为小米 11 Ultra，Android 14 / HyperOS 2，Vector 2.2 (3118) / API 102，Root 方案为 APatch。启动日志已证明 system 收到正确的系统服务 ClassLoader、phone 与 Xiaomi fused 的生命周期及安装路径执行；system 出现 GNSS 安装完成及 SubProxyBinder 实际调用。phone 后续日志的 `hooks=14` 来自 `HookStatusRegistry.snapshot().size`，仅表示 14 个状态条目，不是 14 个成功安装或已完成设备验收的 Hook。此前启动日志缺失属于环形日志 buffer 的观测窗口问题，现有证据不支持“框架生命周期失败”的结论。

用户已正常安装测试包并授予后台精确位置，原安装限制已解除。扩展 instrumentation 四项结果为 **3 passed、1 skipped、0 failed**：认证 runtime status 的 installed/matched、发布样本的 GPS 缓存和同一连续 listener 两次回调、Measurements 注册与注销均通过。Measurements 在 inactive 注册原 callback 后计数为 1，active 增加第二个 callback 后仍为 1，active 注销原 callback 后降到 0，再停止。

单次 current 用例在用户确认手机放置窗边/室外后，非模拟状态下累计等待 120 秒仍未收到真实 GPS 基线，因而跳过，尚未验证该回调路径。停止验证仅确认 `is_start=false` 和 cleanup，未证明物理真实定位已恢复。Measurements 路径也不能扩展为 Navigation、Antenna 或所有 NMEA 类型均通过；其他作用域、厂商实际 SDK 回调、ROM/OEM 和独立接收端仍待验证。

此轮只扩展两个 AndroidTest 文件，生产代码未改；相关测试 APK 已构建通过，上表已有 JVM/Python/lint/Native 结果不变。

从该设备只读提取的 Xiaomi fused APK（`com.xiaomi.location.fused` 4.0.4 / code 14004）DEX 已确认高德 `onSendExtraCommand(String, Bundle): boolean`、`requestLocationUpdates(long, LocationListener): void`、`updateOffLocEnable(boolean): void` 和百度 `requestNLPNormal(String, int): BDLocation`。`ThirdPartyLocationHook` 已据此补齐四处精确签名；该增量已通过相关编译、JVM、lint 和打包，并用最新开发包重启复核生命周期与安装路径。实际 SDK 回调及其他 ROM/OEM 尚未验证。

远端 CI、push、release 和正式签名交付均未执行且未获授权，不属于本轮代码迁移完成的必需操作。未来若交付正式 APK，本地 Debug 包与构建结果不能替代目标提交的成功 CI 产物和正式签名校验。

## 官方参考

- [API 102.0.0 发布](https://github.com/libxposed/api/releases/tag/102.0.0)与 [接入说明](https://github.com/libxposed/api/blob/102.0.0/README.md)
- [现代模块开发指南](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
- [官方模块示例](https://github.com/libxposed/example)
- [LSPosed 模块 scope 读取](https://github.com/LSPosed/LSPosed/blob/master/app/src/main/java/org/lsposed/manager/util/ModuleUtil.java)与 [system_server scope 查询](https://github.com/LSPosed/LSPosed/blob/master/daemon/src/main/java/org/lsposed/lspd/service/ConfigManager.java)

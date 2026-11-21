# #11、#21 日志分析与初步修复

分析日期：2026-11-21。先分析报告者日志，再在 Redmi K50 插卡回测；尚未在报告者的设备上验证修复。

## #11：作用域、定位与软重启需要分别判断

- [问题与讨论](https://github.com/Lecheeel/Portal/issues/11)：一加 13，ColorOS 17 / Android 17，LSPosed 7944。
- 新附件：`LSPosed_20261120_130653.zip`、`KernelSU_bugreport_2026-11-20_13_07.tar.gz.zip`。
- 13:04:44.786 的模块日志确认 SettingsProvider 安装成功；13:04:48.710–712 确认 Fused 的 `chooseBestLocation` 与两个回调安装成功。软重启后也有对应成功记录。因此，上一轮的共享系统包补装已经执行，不能继续把这次定位失败归因于 Fused 没有加载。
- 13:05:24.259、13:06:03.590 的框架日志仍在删除 SettingsProvider 的作用域条目。这是框架清理行为，Portal 的补装逻辑不改变该行为；条目取消勾选不等于共享系统进程中的 Hook 未安装。
- 13:06:09 的 `system_server_crash` 明确为 `ScanResult.getInformationElements()` 空指针，调用方是 Oplus Wi-Fi 路由器能力采集器。随后系统进程重启。这给出了软重启的直接崩溃点。
- Portal 的 Wi-Fi 代码存在匹配该崩溃的缺陷：只按包名过滤系统调用，没有排除 UID 1000，`android` 也没有命中原包名过滤；生成的 `ScanResult` 没有初始化 `informationElements`。目前没有禁用模块的对照采样，因此不把日志中扫描对象的来源认定为已证实。

修复：按 UID 的 appId 排除系统服务调用，显式排除 `android`；生成扫描结果时把信息元素初始化为空数组，使读取与 Parcel 往返满足平台契约。

这些改动处理了一个与崩溃匹配的明确代码缺陷，但不代表定位修改已经端到端验证成功。需要在包含修复的构建上重新检查运行诊断、发布状态和接收端坐标。

## #21：电话服务启动失败，具体等待位置仍待实测

- [问题与讨论](https://github.com/Lecheeel/Portal/issues/21)：Redmi K70，HyperOS 2 / Android 15，LSPosed 7769，Portal v2.3.6。
- 附件：`issue-logs-redacted.zip`。使用讨论中最新上传的副本。
- 日志持续出现 `com.android.phone ... failed to complete startup`、ANR、重新启动，以及 `telephony service is null`；状态快照中的 baseband、SIM 属性均为空。结合报告者的模块开关对照，可确认这是需要优先处理的电话服务故障，不能把空属性直接等同于基带固件被改写。
- 日志包含电话包 ready 和 `PHONE_PROCESS` 安装记录，但没有能定位本轮故障的电话主线程堆栈。部分启动尝试仍继续创建了 TelephonyProvider，因此不能声称每次都永久卡在同一个位置。
- 代码中，电话包 ready 回调会同步查询 LocationManager、交换凭据、注册代理，并最多等待 5 秒的首次状态回传；发现服务时还存在循环等待。该路径发生在电话应用启动完成之前，是实际存在的启动阻塞风险。
- 同一进程还安装了 `CarrierClientHook`，它让本地 TelephonyManager / SubscriptionManager getter 同步查询系统定位服务；定位服务的运营商解析又需要查询电话订阅信息。这个调用方向可能形成循环 Binder 等待，尤其不适合电话基础服务自身的初始化。

修复：先完成 Hook 安装与应用绑定，再由后台任务执行连接与状态同步；服务尚未 ready 或连接抛异常时退避重试，合并重复请求并限制重试次数。未收到认证状态前，模拟运行状态保持关闭。电话进程移除客户端查询 Hook，继续保留供外部应用调用的电话服务端 Hook。

这属于针对已发现风险的初步修复，尚未证明它涵盖 #21 的全部原因。#11 的新附件另外包含 IMS/RCS 初始化空指针，不能仅凭这些堆栈将其与 #21 的启动 ANR 归为同一原因。

## 验证与回测

- 本机 Xposed 单元测试覆盖 Wi-Fi 调用隔离、后台连接不阻塞调用方、连接失败重试、重复请求合并、成功后停止重试和重试上限。
- 新增 Android 测试覆盖真实 `ScanResult.getInformationElements()` 读取及 Parcel 往返；本轮只编译，未运行设备测试。
- 本轮通过 222 个 Xposed 单元测试、47 个 Python 测试、模块边界检查、`:xposed:lintDebug`、arm64 Debug APK 构建及 Android 测试编译。构建成功不替代厂商真机验证。
- 电话服务回测应先在插卡设备重启，确认 baseband / SIM 状态恢复、电话进程稳定，再启动与停止定位模拟，检查信号及模拟状态。
- 一加回测需同时检查 Wi-Fi 保持连接、无新的系统崩溃、模块运行诊断中的连接与发布状态，以及接收端的最后、单次、持续定位样本。作用域是否掉勾单独记录。
- 报告者设备尚未覆盖；本轮不发布新版本，也不将两个 issue 标记为已解决。

## 同类问题排查与修复

同日追加检查覆盖调用方识别、应用内轮询、合成 Wi-Fi 数据和定位结果对象的复用。

- 系统调用识别此前直接比较完整 UID，导致副用户的系统 appId 可能进入普通应用路径。现按 appId 判断，并让路由核心和监听器路由保持一致；共享 UID 的包名逐个判断，Fused 的例外不再覆盖同 UID 中其他包的系统身份，未知调用方保留原行为。
- 系统包过滤补上 `android`，使用完整包名或命名空间边界；第三方包名中间含有 `com.android` 不再误命中系统包。
- Wi-Fi 连接信息原先向 `setSSID` 传入 String、调用不存在的 `setIpAddress(Int)`，错误被忽略。现在使用平台 Builder 设置 UTF-8 SSID，以 InetAddress 设置 IPv4。扫描结果同时填充 WifiSsid，并通过正确方法设置 Wi-Fi standard。
- 传感器状态查询此前运行在应用的传感器 Handler / 主线程；实验性检测开关轮询也在主线程执行 Binder。现在复用平台工作池，每类轮询最多一个请求，慢调用不积压后续轮询；传感器响应还检查订阅代次，避免停止后迟到的状态恢复旧授权。
- `LocationResult.writeToParcel` 原先永久替换对象内的坐标列表。现在在序列化期间临时替换，并在成功或异常退出时恢复原列表；同一对象的写入串行处理。Provider 转发则构造独立结果和列表，避免改写发送方仍可能复用的对象。

新增单元测试覆盖副用户、共享 UID、包名边界、慢查询合并与失败恢复、独立结果副本、异常和嵌套序列化的恢复。Wi-Fi 的 Android 测试增加 SSID / IPv4 / standard 与 Parcel 往返断言，仍未在设备上执行。

追加全量验证通过 Core 845、Xposed 232、应用 248 个单元测试，以及 47 个 Python 测试；arm64 Debug 构建、Xposed lint、Android 测试编译、模块边界与 APK 入口检查一并核对。厂商真机、插卡启动和实际接收端效果仍按前述条件回测。

## Redmi K50 插卡回测（2026-11-21）

设备为 Redmi K50 / HyperOS 2 / Android 14（SDK 34），Vector API 102，插入中国移动卡。以下结果仅覆盖这台设备和本轮 Xposed 测试；没有验证一加 Android 17、K70 Android 15、标准无 Root 或 Native 后端。

| 检查 | 实测结果 |
| --- | --- |
| 原 2.3.6 启用模块时的初始状态 | 两槽报告 `ABSENT`；基带版本存在 |
| 关闭 Portal 模块并重启 | 恢复 `LOADED,ABSENT`、中国移动、`NR_SA` |
| 第一轮候选包启用并重启 | 电话进程在 RCS 初始化时反复崩溃，SIM 仍不可用 |
| 补充硬件订阅修复后两次重启 | SIM 正常，电话服务稳定；第二轮候选观察约十分钟，电话进程和系统进程 PID 未变 |
| Wi-Fi / 定位结果平台对象 | 4 项 Android 测试通过，包括 Parcel 往返和原结果列表恢复 |
| 运营商平台对象 | 9 项 Android 测试通过 |
| 最终定位与生命周期集成 | 6 项通过，包括认证、先订阅后启动、最后定位、持续回调、去重、取消订阅、Native 保持未加载 |
| 独立包名和 UID 接收端 | 最后定位与持续回调匹配测试点；时间戳未倒退；未取得控制凭据；SIM 始终就绪 |
| 单次定位 | 一轮匹配，后续轮次回调为空；未通过稳定性验证 |
| 停止后的定位缓存 | `is_start=false` 且没有持续模拟回调，但最后定位和单次定位曾返回旧测试点；未通过立即恢复真实位置验证 |

这次真机测试补充了两处修复：

1. `CarrierHardwareSubscriptionHook` 在 RCS 初始化的 `getActiveSubscriptionIdList()` 中，无条件使用另一订阅查询结果替换原列表，未开启模拟时也执行。K50 堆栈明确经过此 Hook 后在 `RcsFeatureManager.invalidate()` 崩溃。现在只在启用了模拟及虚拟运营商身份时才允许额外物理订阅查询；开机、停止和仅实体 SIM 模式保留平台原列表。新增两项策略测试，修复后插卡启动恢复。
2. 定位监听器仅在模拟已经开启时才加入模块注册表，导致“先打开接收应用、后启动模拟”的订阅遗漏。现在在框架通过权限及 Provider 检查后保存订阅，实际模拟分发仍由运行状态控制。旧候选包的注册生命周期测试失败，修复后的去重、取消注册及先订阅后发布测试通过。

单次定位仍依赖框架是否提供非空样本，当前 Hook 保留空回调；停止后平台也可能保留此前 Provider 报送的模拟缓存。本轮模块关闭时没有取得真实 GPS 新样本，因此没有验证真实新样本到来后的恢复。这些结果不能被最后定位或持续定位通过所替代，#11 仍不能标为完全解决。

传感器只核对了未加入模块作用域的独立接收端能正常取得有限的加速度事件（每轮约百个），其 Java sensor protection 为关闭。未运行实际注入目标的 Java 轮询压力测试，也未启用 Native。

同签名 Debug 候选包来自临时分支 `test/k50-issues-20261121`，最终 [CI run 44876421713](https://github.com/Lecheeel/Portal/actions/runs/44876421713) 成功，代码目标为 `122cc07f`；签名与原安装包一致，测试应用使用相同 versionCode 以便保留数据安装和恢复。这些包仅供设备测试，没有创建 Release，临时工作流没有合入 main。主工作区对应修复为 `c2cde0e4`、`1065bbdb`，新增设备测试为 `04f92761`、`4e67266f`；最终本机 234 个 Xposed 单元测试和 Xposed lint 通过。

测试结束确认模拟停止、Native 未加载且未启用，再恢复原 2.3.6 APK、偏好、保存的运行配置、权限及原测试 runner，卸载临时接收端和平台测试 APK。原版本已出现插卡故障，恢复时保持 Portal 模块关闭并重启，作用域列表不改动；最后核验 SIM、信号、APK 摘要和设置恢复结果。原始日志及含设备信息的备份仅保留在本机忽略目录。

恢复后已重新核验：原 APK SHA256 一致，核心偏好与备份相同，原作用域保留，临时权限恢复、新增测试包移除，SIM 为 `LOADED,ABSENT`、网络为 `NR_SA`，SELinux 为 Enforcing。没有留下运行中的模拟或已加载的 Native Hook。

## 同机 LSPosed 7944 回测（2026-11-21）

用户随后把 K50 切换为报告者使用的 LSPosed 2.2.1-log（7944），再次使用前述 CI 候选包测试。设备仍为 Android 14 / HyperOS 2 / 中国移动插卡；这次结果不代替一加 Android 17 或 K70 Android 15 的验证。

接入时框架守护进程因 `SQLiteException: no such table: lspd_configs` 退出：本机遗留 Vector schema 5 数据库，而 7944 使用 schema 101。完整备份后，按同版本框架表结构迁移本机模块、作用域及配置，保留本机配置值；重启后确认 7944 / API 102 守护进程正常。这是本机切换框架后的环境修复，没有修改框架二进制，也不是 Portal 的代码修复。

| 检查 | 7944 下的实测结果 |
| --- | --- |
| 系统端加载与诊断 | `SYSTEM_SERVER`，166 个 Hook 安装，失败数为 0 |
| 定位与生命周期集成 | 5 项通过：认证、最后/持续定位与停止、先订阅后启动、去重、拒绝 Provider 后清理 |
| 独立接收端持续定位 | 56 次回调全部匹配测试点，时间戳未倒退；最后定位匹配 |
| 独立接收端单次定位 | 回调到达但位置为空，未通过 |
| 停止后独立接收端 | 无持续模拟回调，最后位置为空；本轮没有重现旧测试点残留，但不证明上一轮缓存问题已修复 |
| Wi-Fi 对象读取 | 模拟时 4 条扫描的信息元素数组可读；停止后恢复真实扫描及非空信息元素，无对应崩溃 |
| 电话服务 | SIM 就绪，停止后网络为 NR；电话与系统进程 PID 保持不变，采样日志未发现电话崩溃或 ANR |

作用域掉勾在 7944 下复现。14:58:55.685 与 14:59:30.808 的框架日志明确记录删除 `com.android.providers.settings` 和 `com.android.location.fused`，包含 `removing obsolete package` / `removeAppWithoutCache`。实际保留的作用域为 `system`、`com.android.phone`、`com.xiaomi.location.fused`、`com.android.systemui`。这不是 Portal 改写勾选状态；无需反复强行写回这两个条目。

同一次启动中，SettingsProvider 已经由共享系统进程补装，日志为 `call=1 query=1`、`installed=true`；电话及小米 Fused 进程也建立了运行连接。因此不能仅由这两个条目掉勾判断所有 Hook 失效。独立接收端持续定位成功提供了实际接收证据，但不覆盖单次定位失败，也不证明其他 ROM 上的 Fused 路径有效。

独立接收端未加入模块作用域、未取得控制凭据，Java sensor protection 为关闭；收到了 106 条有限加速度事件。Native 全程未启用。首次接收端测试遇到 MIUI 初始化撤回临时定位权限，重新核验授权后才取得上述样本，不将未授权轮次算作通过。

结束前系统端再次确认 `is_start=false`、`native_loaded=false`、`native_enabled=false`。本轮没有新增 Portal 源码修复或创建 Release；单次空回调、上一轮的缓存残留及报告者设备兼容性仍为未解决项。

收尾恢复原 APK 与原测试 runner（摘要一致）、本轮初始权限及应用设置基线，移除临时接收端与设备测试文件。保留用户当前四项作用域和健康的 7944 / schema 101，Portal 模块保持关闭；重启后确认 SIM 为 `LOADED,ABSENT`、网络为 `NR_SA`、SELinux 为 Enforcing。没有回滚到导致 7944 守护进程退出的旧框架数据库。

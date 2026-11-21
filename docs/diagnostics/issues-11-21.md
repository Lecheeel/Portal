# #11、#21 日志分析与初步修复

分析日期：2026-11-21。本轮为离线分析，尚未在报告者的设备上验证修复。

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

# Canary 真机验证（2026-11-11）

## 范围与结论

设备为 Xiaomi M2102K1C，Android 14 / API 34，Vector API 102。测试对象为当时工作区的未提交新实现，基础提交为 `efd0646`；本次没有修改这些业务实现。独立 detector 使用另一包名、另一 UID；实验测试同时配置框架作用域和传感器目标列表，重新启动目标进程。

| 检查 | 结果 | 实际观察 |
| --- | --- | --- |
| 无定位权限 requestLocationUpdates | 通过 | 使用明确的主线程 Looper，抛 SecurityException |
| 无定位权限 getScanResults | 通过 | 返回空列表 |
| 无定位权限 getConnectionInfo | 通过 | SSID 为 unknown，BSSID / MAC 均为系统脱敏值 |
| 无定位权限 getAllCellInfo | 通过 | 抛 SecurityException |
| 无 READ_PHONE_STATE 的 getDataNetworkType | 通过 | 抛 SecurityException |
| 系统定位关闭后请求 GPS / network 更新 | **失败** | 前台目标分别在 3.5 秒内收到 25 / 15 次回调；关闭模块并重启的对照均为 0 |
| force_xtra_injection 正常返回 | 通过 | 模拟开启和关闭模块的基线均返回 true，无异常；此结论仅针对返回行为 |
| 前台 coarse-only last-known / 持续更新 | 通过 | 请求 gps，收到 provider=network、accuracy=2000m |
| 前台 coarse-only getCurrentLocation | 无样本 | 分别请求 gps、network，各等 15 秒均无回调，不能验收返回位置的精度/provider |
| 实验性反检测原生库加载 | 通过 | CodecExt 记录 enabled=1；外部 root 读取目标 maps 确认 libcodec.so (deleted) 已映射 |
| 开启实验后目标读取自己的 maps | **失败** | 库名消失，但返回的是 /proc/self/status 内容（51 行、Name: 开头），并非合法 maps |
| 原生库加载失败的隔离 | 通过（加载阶段） | 暂时撤去库文件读权限，目标出现 EACCES，maps 保持正常、无库映射，合成定位仍可读取；权限已恢复 |
| SELinux mprotect / execmod / execmem 拒绝 | 本机未见 | SELinux 为 Enforcing；检查 logcat 和 dmesg，未发现相关拒绝，有库文件 execute granted 记录 |
| 传感器回调堆栈清洗 | **部分有效，未通过完整要求** | Portal 帧被移除，但 Throwable / Thread 栈均保留随机包名 HookBridge、Vector_ 和混淆后的框架调度帧 |
| getStackTraceDepth 与数组长度比较 | 本机不适用 | Android 14 的 Throwable 没有该零参数方法，不能声称复现长度不一致 |
| 标准 Mock 停止后 network last-known 恢复 | 通过 | 同一独立接收进程先读到 mock fix，停止后读到时间戳更新的非 mock 真实 network fix |
| 标准 Mock 停止后 GPS last-known 恢复 | 未验证 | 移除 test provider 后为 null，真实 GPS 请求超时；室内未取得真实 GPS 样本 |

因此，权限边界和前台粗略定位的两条主要通道已经成立；关闭定位仍投递、maps 内容被替换、框架堆栈残留是本轮明确复现的问题。不能按全部验收通过处理。

## 关键证据与复现条件

### 系统开关与粗略定位

权限逐轮由主机授予/撤销，每轮新建 instrumentation 进程。前台复测使用不请求权限的专用 Activity，避免 detector 主页面自动申请权限，也排除 while-in-use AppOps 的后台限制。小米 ROM 拦截过 instrumentation 的后台启动 Activity，主机使用 root 启动同一 Activity 后才采信结果。

关闭系统定位后，先断言 `isProviderEnabled(provider)==false`，再注册 listener 并等待 3.5 秒。框架关闭模块并重启的相同检查没有回调，开启模拟的相同检查仍有合成回调。

`LocationListenerDelivery.deliver()` 当前只检查模拟开关与路由，没有按真实系统开关或对应 provider 状态阻断合成投递。仅让注册流程执行原系统逻辑，不能约束后续模块主动调用 listener 的行为。

后台还有一项额外发现：仅 coarse 权限、AppOps 为 foreground、目标没有前台 Activity 时，last-known 和持续更新可取得 gps / 6.5m 的合成位置；前台则为 network / 2000m。`LocationCallerGuard.access()` 仅把 MODE_ALLOWED 认作许可，而 `isCoarse()` 将 DENIED 与 FINE 都表示成 false，后续分支可能按精确定位处理。该后台结果不能代替前台验收，也不应忽略，需要后续结合调用身份与投递链排查。

### 实验原生库与堆栈

maps 检查同时记录控制开关、格式、是否包含库名和是否像 status，防止将“根本未加载”或“返回错误文档”误判成成功。源码中的路径重写确实把 maps 改成 status，观察结果与实现一致。

加载失败注入仅改变库文件权限，使用 finally 恢复原权限 755。捕获到 AntiDetect.load 的 EACCES 后，目标定位仍为预设合成坐标，且 maps 仍可正常读取。该检查没有覆盖 JNI 已加载但所有 inline hook 安装失败的分支；也没有复现 SELinux 拒绝 mprotect，不能推广为所有 ROM 都可加载。

失败时实验偏好仍为 true，控制器每秒重试并记录 EACCES；“原生效果保持关闭”成立，“设置开关自动变为关闭”没有发生。

堆栈探针类使用 `dev.portal.canary` 包名，避免被当前 `com.system.location.service.` 前缀过滤器误删。过滤器还可能误删独立 detector 自己使用该前缀的业务栈帧。Vector 当前混淆/随机包名的框架帧仍可观察到，不能只搜索字面量 libxposed 就宣布通过。

### 标准 Mock flush

关闭整个 Portal 模块并重启后，使用 MOCK_PROVIDER 后端，原生和 Xposed 均不参与定位改写。独立接收端持续读取 last-known，接收端只读缓存，没有主动请求真实定位；停止由另一进程操作。

设备 uptime 288.40 秒开始停止，288.90 秒时停止调用返回。接收端的关键序列：

| 接收端读取时间（开机秒） | GPS | network |
| --- | --- | --- |
| 286.335 | mock=true，accuracy=2m | mock=true，accuracy=20m |
| 289.348 | null | mock=false，accuracy=30m，fix 时间 288.812 秒 |
| 292.350 | null | mock=false，accuracy=30m，fix 时间 289.910 秒 |

第二个真实 network fix 明确晚于停止返回，排除了停止前缓存的旧真实位置。控制器最终记录 gps:TIMEOUT、network:FRESH。该结果验证的是当前完整停止恢复流程，不能单独归因于新增 flush：已有恢复组件也会发起真实定位请求。

## 构建与测试入口

core、app、xposed 的 JVM 测试使用 rerun-tasks 重跑：722 + 183 + 165，共 1070 项，失败和跳过均为 0。主应用、detector 及 instrumentation APK 构建成功；CMake 四种 ABI 构建成功，原生源码四种 ABI 语法检查通过；设备使用 arm64 库。测试包仅用于开发验证。

补充的可重复入口：

- `PermissionBoundaryDeviceTest`：逐项权限检查、精确定位基线及 extra command。
- `LocationStateCanaryDeviceTest`：关闭 provider、coarse last/current/update。
- `RuntimeSurfaceDeviceTest`：目标内 maps、传感器回调栈、last-known 连续样本。
- `ExperimentFixtureDeviceTest`：只修改显式传入的实验偏好。

detector 测试必须显式传 `canaryDevice=true`；主机负责权限、框架作用域和系统开关，缺少前置条件不能算通过。已复现失败的 state / runtime 用例保留在工作区，尚未作为通过的回归用例提交。测试基架与通过的权限用例按小提交保留。

原始日志、开关/权限备份、源码指纹、APK 和逐项仪器测试输出保存在本机忽略目录 `.tmp/canary-acceptance-20261111/`；不提交包含设备真实定位或应用偏好的原始数据。最终重启后已确认：测试改动的偏好键和权限恢复，Mock AppOp 为原先的 deny，框架恢复原五个系统作用域，系统定位开启、常亮设置为 0。应用空闲、后端 XPOSED，native_opt_in=false，system_server maps 中没有 locationext。保留测试版本安装，未发布正式包。

最后空闲状态下 runtime_status 返回 unavailable，因此没有把该接口缺失字段记成 native_enabled=false；Native 未加载结论来自实际进程 maps 和持久化偏好。对 xposed/src/main 的前后源码指纹比较没有差异。

另发现 Xposed 周期诊断反复记录 `Conversion = '·'`，但定位发布继续成功。`XposedBackend` 将带百分号的电池电量插值后再调用 format，可能导致格式化异常；本轮仅记录，未修改该业务实现。

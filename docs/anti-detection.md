# 反检测实验特性

这些能力默认关闭，仅用于自有设备或明确授权的测试环境。它们不能对抗硬件证明、服务端关联或具备 root/shell 权限的扫描器。

## 模拟标记开关

`hide_mock`（设置 → 系统 Hook → 隐藏模拟标记）默认开启。关闭后 Xposed/Native 模式会向应用返回真实的 `isMock`，用于验证目标应用的降级逻辑；标准 Mock 模式由系统打标，不受此开关影响。

## 原生路径改写（实验）

设置 → 系统 Hook → 实验性反检测（原生路径改写）。

- 仅对“传感器目标应用”中已选择、且已在 LSPosed 作用域中启用的应用进程生效，需要重启目标应用。
- 目标进程首次收到开启指令后，将 `libcodecext.so` 从模块 APK 复制到应用私有缓存、加载并删除磁盘文件；随后挂钩 libc 的 `open/openat/fopen/access/faccessat/stat/lstat/readlink/opendir`。
- 启用期间把 `/proc/*/maps` 读取改写为 `/proc/self/status`，把 `/data/adb` 改写为不存在路径，把 `/su`、`/sbin` 段改写为不存在的 `su_f` 与 `/etc`。
- 不能拦截直接 `syscall` 的扫描器；`mprotect` 被 SELinux 拒绝时加载会失败并保持关闭。该功能会向目标进程引入一个原生库映射，只是在其生效期间从本进程的 maps 读取中被隐藏。

## 全局堆栈清洗（实验）

设置 → 系统 Hook → 实验性堆栈清洗。

- 过滤 `Throwable.getStackTrace()` 与 `Thread.getStackTrace()` 中 `com.system.location.service.*`、libxposed、Xposed、LSPosed 帧，并合并由入口替换产生的重复 `dispatchSensorEvent` 帧。
- 不改变 `Throwable.getStackTraceDepth()`；连续抓栈可能观察到“深度与数组长度不一致”，这是本特性固有的可检测代价。
- 仅建议在需要验证目标应用栈检测逻辑时开启。

## 停止标准 Mock 后的真实定位刷新

标准 Mock 停止时会移除测试 Provider，并向已启用的真实 `gps`、`network`、`passive` Provider 请求一次当前位置，避免应用继续读到上一次假 fix。该行为不改变真实定位开关，也不在无定位权限时执行。

## 验证边界

- 以上行为只经过 JVM 测试、编译与 APK 静态校验；权限/状态 canary、目标应用内的原生加载与栈清洗需要在真机上验证。
- 不同 ROM 的 SELinux、LSPosed 作用域和 Provider 实现可能使原生路径改写不可用；失败时保持关闭，不影响定位主链路。

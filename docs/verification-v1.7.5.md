# v1.7.5 验证记录（2026-10-23）

## 已完成的本地检查

- `gradlew.bat test :nmea:test :core:test :app:testArm64DebugUnitTest :xposed:testDebugUnitTest`：core 50、nmea 2、app arm64 46、xposed 25，共 123 项 JVM 用例，无失败、错误或跳过。app 的其他 flavor 不重复计数。
- `python -m unittest discover -s scripts -p 'test_*.py'`：3 项用例通过，覆盖未签名/错误签名、包名/版本及 ABI 不符时拒绝发布。
- `gradlew.bat lint --max-workers=1`：通过。appDebug 为 0 error / 289 warnings，xposed 为 0 / 9，system-api 为 0 / 0；未新增 lint baseline 或关闭检查。
- 编译 x64 Debug 与 AndroidTest APK；Release 本地用于编译检查，正式安装包由 GitHub Actions 使用既有 Secrets 签名。
- 所有工作流 YAML 解析通过，`git diff --check` 通过。

本地日志在忽略的 `build/v1.7.5-*.log`，不包含签名密钥。

## 新增的重要回归

- Hook 未启用时透传；GNSS 批量参数保留 Boolean 标志、条目数及原输入数组，未知签名不改写。
- 提交前和两种 Provider 提交之间撤销授权，不能报告成功或重新注册；清理失败保留所有权，恢复授权后可重试。
- 重建通道保留场景 ID、开始时间、路线进度和暂停状态；清理失败不注册下一通道。
- 接收日志区分缓存/持续样本，保留模拟标记和样本年龄；空样本不是“非模拟”，日志条数有界。
- `MockProviderDeviceTest` 在模拟器验证：经系统往返的位置保留 Mock 标记、AppOps ignore 状态下不能丢失清理记录，以及独立接收进程不触发主进程中断恢复或删除其 Provider。定位总开关与 Mock AppOp 在测试结束恢复。
  首轮设备运行暴露了测试本身的前台条件及进程附着同步问题：往返测试现保持 Activity 前台，隔离测试等待进程附着及诊断页可见，不把任务列表出现当作进程初始化完成。最终结果以修正后的提交报告为准。

## 签名与发布校验

GitHub 仓库存在 `SIGN_KEYSTORE_BASE64`、`SIGN_KEYSTORE_PASSWORD`、`SIGN_ALIAS`、`SIGN_KEY_PASSWORD` 和 `AMAP_ANDROID_KEY`。仅核对名称，不读取 Secret 值。正式流水线使用签名材料并验证 APK。

已下载公开的 v1.7.4 arm64 APK，通过 `apksigner verify --verbose --print-certs` 核对其公开证书 SHA-256：

```text
fa4547514e795b2daca48bd05e9a5f9348e62531d791d746e0ffe93bb90247a3
```

`verify_release_apks.py` 要求三个 APK 的证书均匹配此值，签名 v2 验证通过，包名为 `com.system.location.service`，版本为 `1.7.5` / `1790000012`，ABI 与文件名一致；全部通过后生成 `SHA256SUMS` 和 `verification.json`。

## 验证范围

远端主分支流水线执行测试、lint、签名和产物校验；UI 工作流在 Android 35 模拟器执行现有 UI 回归及新增系统定位测试。实际结果以对应提交的 Actions 报告及 Release 附带验证报告为准。

本地未连接测试手机。小米 Android 17、真实 Xposed/Native Hook 命中、网友检测 App 的判定及 Bugly 已有 16 KB 页兼容性警告，均不能用 JVM/模拟器结果代替。独立接收进程与主应用仍共享 UID；无 Root 标准 Provider 不承诺隐藏模拟标记。

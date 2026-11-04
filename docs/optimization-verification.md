# 稳定性与交互优化验证

## 变更

- 运行命令和 tick 共用终态收尾。STOPPED 清除中断标记；ERROR 保留后端恢复信息，但关闭前台通知与服务。
- GMS 停止不再依赖 pending 的位置提交；保留未终结的模式操作，阻止旧请求迟到后误关替代会话。
- GPX 通过仓库原子追加导入，统一导入导出数量限制，拒绝非法 XML 名称，保留合法 Unicode 及名称中的 CR。
- 首页搜索 300ms 防抖，每请求独立身份；清空、选中、视图销毁使旧响应失效。
- 手绘预览按帧合并，最多 512 个预览点，完整采样仍用于保存。
- 诊断日志通过有界异步队列批量持久化。普通诊断保留故障重现/恢复事件，满队列仍可唤醒失败批次重试；停止和导出在锁外刷新。
- 地图 SDK 在真实同意后初始化；可选 Bugly 上报默认关闭，使用安装级随机标识。confirmed 状态隔离 SharedPreferences 写失败，初始化失败不导致可选 SDK 启动崩溃。

## 执行检查

Windows JDK 25，Gradle 9.7.0，离线依赖缓存：

```text
cmd.exe /d /c gradlew.bat :core:test :app:testArm64DebugUnitTest :xposed:testDebugUnitTest :nmea:test --offline --console=plain
cmd.exe /d /c gradlew.bat lint --max-workers=1 --offline --console=plain
cmd.exe /d /c gradlew.bat :app:assembleArm64Debug :app:assembleX64DebugAndroidTest --offline --console=plain
python -m unittest discover -s scripts -p 'test_*.py'
bash -n scripts/run-ui-verification.sh
git diff --check
```

- JVM XML 汇总：core 75、app arm64 94、xposed 25、nmea 2，共 196 项，0 失败、0 错误、0 跳过。
- 发布校验脚本：3 项通过。
- lint：0 errors；保留已有及当前提示，不新增 baseline，不关闭规则。
- arm64 Debug 应用及 x64 AndroidTest APK 构建通过，包含界面测试代码编译。
- UI 验证脚本语法与 diff 空白检查通过。
- 独立审查发现的终态收尾、永久 pending、迟到 mode 结果、日志状态切换/满队列恢复、GPX XML 字符及隐私存储失败场景已补行为回归。

## 设备验证与 SDK 边界

本机无连接设备、无可用 Android emulator，未执行真机/模拟器界面与 GMS 测试，未测量帧率或耗电收益。CI 的 `scripts/run-ui-verification.sh` 在普通 UI 测试及截图导出后，重新安装测试 APK、清空指定测试应用数据，以 `freshPrivacy=true` 在新进程执行隐私测试，并将跳过或失败判为失败。

Bugly 4.1.9.3 的 `closeBugly()` 会停止新增崩溃监控及自动任务，但不终止已经启动的独立上传线程和重试；设置中的中日文说明已标明。关闭后同进程再次开启仅保存选择，下次启动生效。若关闭选择保存失败，界面提示重试，不能假定下次启动仍保持关闭。没有承诺即时中止所有上传，也未通过强杀主应用实现撤回。

未更改版本、签名身份，未 commit、push 或发布正式 APK。本地 Debug 构建仅用于开发验证。

# 发布就绪审计（2026-11-07）

状态：**未就绪**。本文件记录当前可交付性与剩余门禁，不代表授权发布。

## 1. 当前结论

- roadmap：52 个任务，**37 DONE / 2 WIP / 12 TODO / 1 BLOCKED**；
- 加权进度约 **87%**；代码/模型约 **98%**，设备验收约 **10%**；
- JVM 用例 **434 项 0 失败**；
- `:detector:assembleDebug`、`:libxposed-spike:assembleDebug` 通过；
- 模块/备份/Manifest/许可证 assets 守卫均 0 violation；
- **没有任何 `DEVICE VERIFIED`**；
- 未推送、未发布、未生成正式签名产物。

## 2. 可交付代码

已关闭：

- P0-01/P0-02/P0-04/P0-05/P0-06；
- P1-01～05；
- P2-01～07；
- P3-01～05；
- P4-01～03、P4-05；
- P5-01～08；
- P6-01/P6-04/P6-06。

剩余 WIP：

- P4-04 Native 传感器：四 ABI 语法/链接通过，缺完整 CMake 与真机；
- P7-04 性能/耗电：诊断已接线，缺设备基线。

## 3. 发布硬门禁

| 门禁 | 状态 |
|---|---|
| 设备基线 P0-03 | ❌ BLOCKED（无设备） |
| P1-06 隐藏/权限 detector | ❌ 未跑 |
| P4-06 传感器设备回归 | ❌ 未跑 |
| P5-09 per-app/命令设备回归 | ❌ 未跑 |
| 高质量 release 签名 APK | ❌ 未构建（本地 CMake 沙箱限制；需 CI） |
| 许可证/来源 | ✅ Apache-2.0，LICENSE/NOTICE 已入 APK assets |
| 安全静态审计 | ✅ 无高危项；CI 守卫 |
| 文档同步 | ✅ 01/05/06/07/09/11 已更新 |

## 4. 发布前必须完成

1. 至少 2 台设备按 `11-device-validation.md` 跑 P0-03；
2. 通过 P1-06（C01–C05/C08/C09）、P4-06（C10/C11）、P5-09（C12/C13）；
3. CI 构建并校验签名 APK（`scripts/verify_release_apks.py`）；
4. 确认 target/compile SDK、ABI、许可证、versionCode；
5. 明确维护者授权后才可 push/tag/release。

## 5. 已知风险

- 无设备：Hook/传感器/GMS 行为均未证实；
- Native：只有语法/链接，未完整 CMake；
- Raw GNSS：模型完成但未接 Hook；
- GMS：只全局，不承诺 per-app；
- 本地 Windows 沙箱无法跑 lint/CMake，依赖 CI。

**结论：可以继续代码/文档迭代，但不能作为正式版本发布。**

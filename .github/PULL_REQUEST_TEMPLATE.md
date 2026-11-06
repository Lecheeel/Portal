# 变更说明

## 任务

- 任务 ID（如 `P0-02`）：
- 对应文档：[05-work-plan.md](docs/roadmap/05-work-plan.md)

## 改了什么

- 

## 证据

- 证据等级：`SOURCE VERIFIED` / `BUILD VERIFIED` / `UNIT TEST VERIFIED` / `DEVICE VERIFIED`
- detector 检查（如适用）：`C01` PASS/FAIL/UNKNOWN；证据文件：
- 设备 / ROM / Android / LSPosed（Hook 变更必填）：

## 回归

- [ ] `./gradlew :core:test :nmea:test :app:testArm64DebugUnitTest :xposed:testDebugUnitTest --offline`
- [ ] `python3 scripts/check_module_rules.py`
- [ ] `./gradlew lint --max-workers=1`
- [ ] Release/Debug 构建通过（按改动范围）
- [ ] 文档 `01/05/06` 已同步（任务结束时）

## 边界与回退

- 是否新增实验能力：是 / 否
- 默认值：关闭 / 开启（说明理由）
- 回退方式：
- 是否改变 IPC / 配置 schema / Xposed scope：是 / 否

## 许可证与来源

- [ ] 没有复制 GPL / 无许可证项目的实现
- [ ] 新增依赖已在 `docs/roadmap/07-risks-and-licensing.md` 记录用途/许可证/体积
- [ ] 第三方数据/服务已记录来源和许可

## 风险

- 已知风险/未验证项：

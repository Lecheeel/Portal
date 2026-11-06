# Detector 基线报告目录

这里保存每台设备、每个 Portal 版本的 detector 结果，用于对比“修改前/修改后”。

## 命名

```text
YYYY-MM-DD-<manufacturer>-<model>-<android>.json
YYYY-MM-DD-<manufacturer>-<model>-<android>.md
```

示例：

```text
2026-11-08-xiaomi-23127PN0CC-android15.json
2026-11-08-google-pixel8-android16.md
```

## 要求

- 记录设备、ROM、Android、ABI、LSPosed/Root、Portal 版本/commit。
- 记录模拟前/模拟中/停止后三种状态。
- JSON 用 [06-validation.md](../06-validation.md) 的 schema。
- 含 WiFi/基站/位置等敏感信息时先脱敏，再提交。
- 不提交完整日志；只保留结论与必要证据路径。

## 模板

```text
# <设备> detector 基线

- 日期：
- 设备/ROM/Android/ABI：
- LSPosed/Root：
- Portal 版本/commit：
- 模拟模式：
- 运行时长：

| ID | 模拟前 | 模拟中 | 停止后 | 证据 |
|---|---|---|---|---|
| C01 |  |  |  |  |
| C02 |  |  |  |  |
| C03 |  |  |  |  |
| C04 |  |  |  |  |
| C05 |  |  |  |  |
| C06 |  |  |  |  |
| C07 |  |  |  |  |
| C08 |  |  |  |  |
| C09 |  |  |  |  |
| C10 |  |  |  |  |
| C11 |  |  |  |  |
| C12 |  |  |  |  |
| C13 |  |  |  |  |
| C14 |  |  |  |  |
| C15 |  |  |  |  |

结论：
- 阻断级 FAIL：
- 警告级 FAIL：
- 与上一版差异：
- 备注：
```

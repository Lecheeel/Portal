# 2026-11-08 · 双设备 Xposed 矩阵

后续复核：B 已获得 KernelSU root 并安装 Vector，下表的 B 结果取代同日早期“无 Root 标准 Mock”的基线记录。

## 设备与框架

| | A | B |
|---|---|---|
| 型号 | Xiaomi M2102K1C | Xiaomi 22041211AC |
| ROM / Android | HyperOS / Android 14 / API 34 | HyperOS 2.0.10 / Android 14 / API 34 |
| Root | APatch | KernelSU v3.3.1 |
| 框架 | Vector v2.2 (3118) / libxposed API 102 | Vector v2.2 (3118) / libxposed API 102 |
| Scope | system、phone、fused、Settings provider | system、phone、fused、Settings provider |
| Portal | 1.7.7 / 1790000024 Debug | 1.7.7 / 1790000024 Debug |

## 加载结果

A / B 的 Vector target 均包含：

```text
system / pid ...
com.android.phone / pid ...
com.xiaomi.location.fused / pid ...
state = UP_TO_DATE
versionCode = 1790000024
```

B 的 XposedServiceBridge 返回 `serviceBound=true`；系统服务日志可见
`HookInstaller`、`LocationServiceHook`、`ProxyCommand` 的 Hook 调用栈。

## Detector（XPOSED，10 s）

| ID | A | B |
|---|---|---|
| C01 | PASS | PASS |
| C03 | PASS | PASS |
| C13 | PASS | PASS |
| 汇总 | pass 6 / fail 4 / unknown 5 | pass 8 / fail 4 / unknown 3 |

B 从 `MOCK_PROVIDER` 切换到 `XPOSED` 后，C01 从标准 Mock 的 FAIL 变为 PASS；
C03/C13 两台均 PASS。

## 调试工作流

A：

```text
--hot-reload --restart-targets
system_server PID 不变
phone/fused 自动重启并重新注入
三个 target 均 UP_TO_DATE
```

B：

```text
POST /hot_reload -> hot reload SUCCEEDED
system_server PID 1780 不变

--serial MFZ... --hot-reload --restart-targets
system_server PID 1780 -> 1780
phone/fused 新 PID，target 均 UP_TO_DATE
```

Native `.so` 更新仍走 `--native-lib` + zygote 重启；A 已实测，B 未重复跑
Native 传感器 hook。

## 结论

- 两台设备均能在 Vector 2.2 / API 102 下加载 PortalModule。
- 两台设备的 C01/C03/C13 均 PASS。
- 两台设备的 system_server 热重载和 phone/fused 自动重启重注入均通过。
- **P6-05 仍不能标 DONE**：框架版本矩阵只有同一个 Vector v2.2 (3118)，
  没有第二个 Vector/LSPosed 版本；“回退”场景也还需要独立验证。

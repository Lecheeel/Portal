# 实施提交清单

基线：`96c0fbb`。下表按主题归组；完整时间顺序以 `git log --reverse --oneline 96c0fbb..HEAD` 为准。各功能与修复分开提交，后续验证发现的问题继续作为独立修复，未压成单个架构大提交。最终文档提交不在本表自引用。

## P0 与基础领域

| Commit | 主题 |
|---|---|
| `379486b` | 鉴权失败立即退出、UID/完整包名、会话与负向测试 |
| `f64d0cf` | Double 坐标持久化与旧值迁移 |
| `6b16595` | 显式 Hook 作用域、可达分支与安装状态 |
| `7a048cf` | 提取纯 JVM 坐标、路线、场景和播放领域 |

验证范围：CommandSecurity、HookScopeResolver、CoordinatePreferences、PlaybackEngine、CoordinateTransform 测试与对应模块编译。

## Runtime 与发布后端

| Commit | 主题 |
|---|---|
| `916fc22` | 与后端无关的控制器、状态流和生命周期 |
| `beed214` | 前台服务持有播放循环，移出 ViewModel |
| `8513173` | 独立标准 Mock Provider 后端 |
| `0a60706` | 后端选择与运行控制页面 |
| `f5b257f` | 进程中断后的资源清理和状态一致性 |
| `3545d22` | 统一样本经认证 IPC 发布和共享 |
| `46d379e` | Native 隔离为显式实验后端 |

验证范围：ScenarioController、MockProviderBackend、Snapshot、PublicationLease、CommandSecurity 测试；Android 编译、Debug/Release 构建含 Native 编译。所有系统实际行为仍待设备。

## 资料库、地图与页面

| Commit | 主题 |
|---|---|
| `f22d9b5` | 版本化 Route/Scenario/Location repositories |
| `c53d174` | 修复保存空位置占位值和坐标截断 |
| `d86c4b2` | 原子文件资料库及旧数据迁移 |
| `40ed4fb` | 持久化路线草稿、异步旧结果保护 |
| `8ad0bcc` | 地图起终点、规划、完整展示、确认/保存/启动 |
| `3144066` | 资料库管理、可复用场景与 JSON 导入导出 |
| `2aba203` | 旧设置遵守后端能力和下次启动参数边界 |
| `0fd1390` | 不依赖地图服务的手动 WGS84 单点页面 |
| `aeb8715` | 首页保存位置交给 ViewModel/Repository |

验证范围：Repository、RouteDraft、RouteJson、HistoricalLocation、LegacyLibraryMigration、CoordinatePreferences 和 app 单元测试；Arm64Debug 构建。高德网络规划与真实 UI 操作未执行。

## 诊断与独立稳定性修复

| Commit | 主题 |
|---|---|
| `0b1dcf5` | Hook 日志限频、默认关闭详细日志 |
| `211d9c0` | 避免 bearing 转 Float 后舍入到 360 度 |
| `c55f4e3` | 结构化后端与 Hook 诊断 |
| `4443e93` | 原子资料库读取保留 Android 12 API 兼容性 |
| `2b8e6db` | 拒绝过期服务启动、有超时和续期的唤醒锁 |
| `d835f21` | system_server 转发 peer 命令时正确管理 Binder 身份 |
| `136be2a` | 命中探针失败不抹掉 Hook 已安装事实 |
| `8211b7f` | 终态恢复与旧服务迟到销毁回调保护 |
| `6a8ecc6` | 运行资源所有权不随备份迁移，通知重启服务正确清理 |

验证范围：LogRateLimiter、Snapshot、ServiceStartGate、ScenarioController、HookScopeResolver 及相关安全测试；app/xposed 编译与 Arm64Debug 构建。Binder、唤醒锁与系统服务行为不以 JVM 测试代替设备结论。

## 构建与 CI

| Commit | 主题 |
|---|---|
| `9677fb0` | 显式确定性版本输入、移除公网 IP/机器信息查询、使用 AGP 公共 artifacts API |
| `d65d6bf` | PR 增加单元测试、lint 和 Release 门禁 |
| `acc9bc1` | 修正 flavor ABI 混入问题 |
| `18bb48d` | NMEA 回归向量纳入 Gradle/JUnit 和 CI |
| `196ba85` | CI 顺序分开执行测试、lint 和打包 |

验证范围：ReleaseVersion / NMEA 测试、workflow YAML 解析、独立完整 lint、三种 Release 导出、APK ZIP 中 ABI 集合断言。远端 CI 未运行，未执行网络发布或推送。

## 最终验证与回滚边界

最新代码的测试、lint、Release 按顺序全部通过；计数、命令、日志位置与设备缺口详见 [验收记录](verification.md)。文档提交前检查 Markdown 链接、代码路径和 `git diff --check`，提交后检查工作区、历史及最近提交边界。

各提交只描述独立主题；后续架构提交可能依赖前序接口。回退时应先核对依赖，在新分支按逆序使用 `git revert` 并复测，不能假定任意单个架构提交可在最新代码中无冲突地撤销。本次未重写历史、未使用 `git reset --hard`。

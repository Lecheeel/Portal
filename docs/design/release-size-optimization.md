# Release APK 体积优化记录

## 结果与口径

2026-11-17，在 `b03eb4d1` 的应用源码基础上启用 R8、资源裁剪及 Release DEX 压缩。
本地构建产物未使用正式发布签名，以下大小均按十进制 MB（1 MB = 1,000,000 bytes）计算。

| 构建 | APK 大小 |
| --- | ---: |
| 同源码 arm64 未优化基线 | 64.97 MB |
| 优化后 arm64 | 28.93 MB |
| 优化后 all（arm64 + x86_64） | 33.33 MB |
| 优化后 x86_64 | 19.66 MB |

arm64 同源码对比减少 36.04 MB（55.5%）。作为参考，已发布 v2.3.5 的
arm64 APK 为 64.28 MB；该版本与当前源码有差异，不能用来单独衡量本次优化收益。

主要收益来自代码：DEX 从 5 个、未压缩合计 39,987,468 bytes，变为
2 个、未压缩合计 11,133,232 bytes，压缩存入 APK 后为 5,097,102 bytes。
APK 下载体积减少不等于安装占用同比减少，压缩 DEX 需要安装阶段处理；本次没有进行启动性能基准测试。

## 实现范围

- 仅 Release 启用代码优化、混淆、资源裁剪和 DEX 压缩；保留原有 ABI 产品及 native 打包方式。
- 主应用显式声明 libxposed API 的 `compileOnly` 依赖，让 R8 获得框架接口层级；框架 API 不打入 APK。
- 保留 PortalModule 生命周期、Hooker 的 `intercept`、JNI 方法名及模块包名前缀。
  仅在 xposed 模块声明 `compileOnly` 无法为最终应用的 R8 提供完整类型信息，会导致回调实现被误删。
- 地图依赖、版本和功能没有调整。高德聚合 JAR 没有 consumer rules，保守保留其全部类和成员；
  腾讯沿用 AAR 自带的规则。仅忽略高德现有可选缺类的两项精确警告。
- 对比同源码 arm64 基线，全部 7 个 native 条目及 239 个普通 assets 逐字节一致；
  assets 比较不包含构建生成的 DEX profile 和自研加密 native 载荷。
- CI 新增直接解析 APK DEX 定义的检查，验证实际回调实现、JNI 定义和 DEX 压缩方式，
  并保留 R8 mapping 供崩溃还原使用。该检查区分方法引用与真正的方法定义。

## 已执行验证

- 三种架构的 Release 构建及导出成功；三包均通过 Xposed 元数据、框架 API 未打包、回调、JNI、DEX 压缩检查。
- app arm64 Debug、xposed、core、nmea 的相关 JVM 测试任务成功，其中部分任务使用已有缓存。
- 新增检查的 7 个测试及既有 Xposed 检查的 14 个测试通过。
- 小米 11 Ultra、Redmi K50（均 Android 14）使用开发签名的优化 Release 测试：
  - Xposed 冷加载、系统模块通信、GPS 缓存与持续回调的定位往返通过，结束后停止模拟。
  - 标准无 Root 路径的优化 Provider 实现可注册、启用并清理独立测试 Provider；
    缓存及回调中的坐标、系统模拟标记检查通过，临时 mock AppOp 已恢复。
  - 加密 native 载荷解码、Dobby 与 AntiDetect 的禁用状态 JNI 调用通过；
    未启用实验性 native Hook，此结果不代表 Native 后端在两种 ROM 上的完整兼容性。
- 设置、资料库和地图页面完成基本界面检查。优化包能显示腾讯地图底图；
  原安装包与优化包均出现腾讯 Key 鉴权失败提示，因此在线地图服务不计为完整验证通过。
  未切换到高德进行在线功能验证。
- 两机已恢复测试前的应用版本并保留数据；临时 instrumentation 应用及测试文件已清理。

## 尚存限制

Python 全量 47 个测试中 46 个通过，仓库 guardrails 测试仍因既有的三项问题失败：
`LocationServiceHook.kt`、`TelephonyHook.kt` 超出文件行数预算，以及缺少 `docs/roadmap`。
修改前的 CI 已存在这些错误，本次没有扩大范围处理。它们仍会阻挡当前工作流进入正式构建步骤。

高德 SDK 中的 final 资源 ID 会触发 R8 资源分析警告，尚未升级或改写地图 SDK。
本次未推送或发布；正式交付仍须修复既有 CI 阻塞，使用 CI Secrets 签名并通过既有版本、架构及证书校验。

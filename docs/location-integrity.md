# Location Integrity

独立安装的 Android 定位观测与实验对比应用，包名 io.github.lecheeel.locationintegrity，Android 8 / API 26 起。
与 Portal 模拟器共用仓库，但 APK 不依赖 app、xposed、system-api、nmea、地图 SDK、GMS SDK 或 Native 库。

## 第一版

- 概览：会话、手动实验标签、250/1000/3000ms 请求间隔、备注、权限及本进程 PSS/单核口径 CPU 观测。
- 信号：GPS/Network/Passive/系统 Fused 订阅，缓存与实时样本分离，来源与接收时间、Mock、精度、速度、方位字段。
- GNSS：卫星状态、NMEA、原始测量及 GNSS 时钟快照；逐项显示未授权、注册结果和实际回调。
- 传感器：约 5Hz 请求加速度计、陀螺仪；显示最新向量、计数、时间。不声称完成运动真实性识别。
- 证据：系统模拟标记、旧样本、时间倒退/超前、高速跳变、来源差异。重复坐标仅为信息。阈值为未校准的实验规则，不输出真实性分数。
- 实验：本地保存、手动时间线标记、选择两次对比、通过系统文件选择器导出带 schemaVersion 的 JSON。

近似定位权限可以开始观测；GNSS 不订阅，位移/来源差异推断关闭。精确定位权限改变后结束当前实验，重新开始以避免混用权限条件。
本应用无需设为“模拟位置应用”，不申请后台位置、运动识别、全应用查询或网络权限，不执行 Root/Xposed 操作。
前台由用户显式启动 location 类型服务，通知可停止；不持有唤醒锁，不自动重启。系统休眠、后台策略、权限变化或终止仍可能中断采集。

## 数据与边界

订阅渠道 requestedProvider 与位置对象报告的 provider 分别记录，避免 Passive/GPS 重复投递污染同一渠道的间隔统计。
每次实验保存总计数，但仅保留最新 2000 个定位样本、每渠道 1000 个回调间隔用于 P50/P95、300 个时间线事件和 80 条 NMEA。
卫星/原始测量最多各 128 条，为最新快照；传感器为最新快照，不是连续原始波形。非有限 GNSS/传感器数值导出为 null，不伪造零值。
观测期间每 15 秒异步原子写快照，停止时写最终报告；进程被直接杀死可能丢失最近 15 秒或更长（系统调度延迟）的数据。最多保留最近 20 次实验，每份 JSON 上限 12MB。
数据位于应用不备份目录，无网络上传；卸载会清除。导出的文件由用户选择保存位置，包含精确坐标、设备型号和系统版本。

未发现异常不证明定位真实。GNSS/传感器缺失不证明模拟。模拟标记只描述本应用收到的对象。
系统或本进程被 Hook 时，观测数据也可能被改变；针对其他 App 的独立分流可能对本应用不可见。
系统 Fused 不等于 GMS FusedLocationProviderClient；没有 Wi-Fi/基站地理数据库、独立硬件真值、RF 欺骗检测或目标应用内 SDK。
无误报/漏报率或厂商兼容率承诺，尚需独立实机数据集。

## 实验方法

1. 在同一手机、同一地点先录制真实定位基线，手动记录室内/室外和权限。
2. 停止并保存。打开 Portal 或参考项目，选择一个后端和固定参数，重新开始实验。
3. 用时间线标记开始模拟、切换单点/路线、暂停、恢复和停止的操作时刻。
4. 每次只改一个变量；在实验页选择两次记录对比。标签不参与规则判断。
5. 对比具体证据及原始字段，同时记录系统版本、框架作用域、请求间隔、实际回调间隔和传感器可用性。

## 构建与发布

    ./gradlew :integrity-core:test
    ./gradlew :integrity-app:lint --max-workers=1
    ./gradlew :integrity-app:assembleDebug :integrity-app:assembleRelease
    python -m unittest discover -s scripts -p test_verify_integrity_apk.py

单独 SDK 配置沿用根目录 local.properties。默认 Release 未签名，Debug 使用调试身份并带 .debug 包名。
首次显式配置：python scripts/integrity_signing.py provision --repo Lecheeel/Portal。
该脚本生成独立 RSA-3072 密钥，将四个 INTEGRITY_KEY* 值通过 stdin 写 GitHub Secrets，密码不出现在命令参数或日志。
本机备份位于 LOCALAPPDATA/LocationIntegrity/signing，密码用当前 Windows 用户的 DPAPI 保护，目录限制为当前用户。
密钥文件不进入 Git；公开证书 SHA-256 固定在 integrity-app/release-certificate.sha256。已有身份缺少本机备份时拒绝隐式轮换。
本机签名构建：python scripts/integrity_signing.py build。请保留当前 Windows 用户的恢复能力；DPAPI 备份不等于可跨机器导入的备份。

GitHub Actions 工作流 Location Integrity 对独立模块执行 JVM、发布检查器、lint、Release 构建和 API35 模拟器 UI/近似权限服务测试。
location-integrity-v0.1.0 tag 在上述门禁通过后签名，核对 APK 包名/版本/权限/证书、生成校验和并发布 Release。
发布不改变 Portal 原有签名 Secrets，也不覆盖 Portal 的 latest 发布入口。

平台接口依据：
- https://developer.android.com/reference/android/location/Location
- https://developer.android.com/reference/android/location/LocationManager
- https://developer.android.com/develop/sensors-and-location/sensors/gnss
- https://developer.android.com/develop/background-work/services/fgs/service-types#location

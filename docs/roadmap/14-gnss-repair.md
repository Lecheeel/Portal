# GNSS 真实画像与输出一致性修复

本轮目标是删除固定卫星模拟来源，以真实采集画像或完整广播星历驱动输出；修复设置、数学模型、投递生命周期和验收工具。不能用切回固定 31 颗、凑足在用卫星或伪造有限 DOP 作为完成条件。

## 已落地的基础实现

- 观测帧、设备/系统/时间/位置元数据、星座身份、数量/仰角/CN0 分布和在用比例。
- 原始测量及接收机时钟字段、导航电文原始载荷独立保存。收到电文不等于已有完整可传播星历。
- 卫星页精确位置权限申请、60 秒真实采集、取消、本机原子存储；原始采集不进入 Android 备份。
- 采集器仅在场景停止时工作；模拟启动或收到 mock Location 会废弃采集，所有监听器在 finally 注销。
- 画像有效性检查：帧数、观测跨度、坐标、有效期、地平线。
- 星座比例采用累计该星座卫星样本数除以累计全部卫星样本数。置信区间用 10 秒时间块 bootstrap，短于三个块时返回完整区间，不将相邻帧当成独立伯努利样本。区间仅描述采集窗口，不能外推到其他地区、时段和设备。
- 统计回放使用实测帧的联合分布；正反向回放避免首尾跳变，跨北方向按最短圆弧插值。CN0 来自实测配对值，不使用人为正弦曲线。
- 核心模型支持 0 自动和正数卫星下限；只可从邻近实测帧补充真实身份，容量不足时记录诊断。已接入生产 runtime；App、Schema 和运行时默认值均为 0，保留已保存用户值。
- 几何 DOP 用局部 ENU 视线和各星座独立接收机钟差列，Householder QR 计算协方差。少于所需观测、秩不足或条件数过大时无解；W=I 为等方差几何 DOP，不把 CN0 当作未经标定的伪距方差。
- 带序列号的共享帧缓存，按秒、观测点、海拔、速度、方向、卫星下限隔离。
- 认证配置通道可下发有界压缩状态画像，压缩载荷限长且解压限量；原始测量/导航电文留在本机。

生产 GnssStatus、NMEA 和 GPS Location extras 已切换到接收机帧。旧固定 5 颗/31 颗模型、工厂和状态桥接器已删除；当前设置页的旧轨道开关已移除。旧配置枚举仍保留兼容，不能选择或恢复旧模型。以上仅经 JVM 检查和 Kotlin 编译，尚不构成真机验收，完整目标仍未完成。

## 已接通：生产链路（仍需设备验收）

- [x] 状态和 NMEA 主动投递使用接收机画像；没有有效帧时停止模拟投递，保留真实回调。
- [x] GGA 使用在用数，GSV 使用可见数；GSA 使用实际几何 DOP，支持多系统和超过 12 个在用身份。
- [x] 无解时 GGA/RMC/GSA 的 quality/status/fix type 与空 DOP 一致；不生成缺少误差模型依据的 GST。
- [x] GSV 解析支持有/无可选信号字段，不再丢失最后一颗卫星的 CN0。
- [x] GPS、北斗、Galileo、GLONASS、QZSS、NavIC、SBAS 的 Android/NMEA 身份映射统一。无法明确对应 NMEA 编号的扩展身份跨输出统一排除并诊断，原始记录保留。
- [x] 不伪造基带 CN0；现代状态对象用公开 Builder，旧数组签名按实际 5–7 参数适配。
- [x] 状态和 NMEA 只保留主动投递；调用 ORIGIN 绕过真实回调抑制 Hook。NMEA 回调时间戳为帧 UTC 毫秒。
- [x] 每个投递周期对同一路由仅取一次 publication，避免 Location 在回调期间更新导致状态/NMEA 两套样本。
- [x] GPS Location extras 从实际 provider sample 对应 publication 派生；无帧时不写卫星字段，network 不写 GPS extras。卫星数为在用数，CN0 仅统计在用卫星，无解时不伪造信号均值。
- [x] 无大地水准面模型时不把椭球高冒充 MSL 高度。
- [x] 注册在系统调用成功后入表，原始调用返回 false 或抛异常时不记录；注册时模拟关闭也安装回调 Hook，以支持后续开关。
- [x] 状态/NMEA 注册表按 Binder 去重、linkToDeath、显式注销和清理；覆盖 LocationManagerService 和 GnssManagerService NMEA 入口。
- [x] 主动投递读取每个监听器的固定点及 deny 路由；Location extras 使用已经路由后的实际样本坐标。
- [x] 线程保存直接引用，停止时中断并有界等待，不再按线程名搜索；回调异常按监听器隔离并限频记录。
- [x] 最少卫星数设置为 0 自动、正数下限；当前 UI 与 Schema 均为 0–128，部分配置下发不重置已保存值。
- [x] 卫星页展示全部观测星座；Android CN0 0–63 dB-Hz 范围可进入画像，修复原有 60 上限导致有效观测被丢弃。

## 待完成：生产与数学闭环

- [ ] 等待采集/画像过期状态、当前实测池容量和无法满足下限的诊断展示；当前诊断已有模型输出，尚未全部呈现在 UI。
- [ ] 移动、噪声、100ms Location 和 1Hz GNSS 输出的序列/时间窗集成验收。相同完整样本共享缓存，但同一秒不同坐标/速度仍可能产生多个序列，不能声称所有移动回调都已同帧。
- [ ] 注册/注销与正在执行的 Binder 回调竞态、停用中断、热重载后现有监听器恢复的设备验证。
- [ ] 采集多频同星记录的保留及唯一身份统计策略审查，不能把简单去重当作完整原始状态证据。
- [ ] 完整广播星历解码、分页完整性/校验、卫星健康、IOD、系统时标/周跳/有效期和轨道传播。GLONASS 状态向量不能套 Kepler，北斗 GEO 不能套通用 MEO 旋转。
- [ ] 传播路径使用标准对应坐标系与时间约定；补独立权威数据向量对照、地平线、ECEF/ENU、地球自转和钟差校正测试。统计回放不能称为精确轨道，收到导航电文不代表星历完整。
- [ ] 在非零最少数量约束下验证分布和诊断；自动模式的实测分布验收与用户强制下限须分别评价。
- [ ] 彻底整理旧模型配置键、旧偏好和 legacy UI，当前只保留兼容字段不再改变生产模型。

## 待完成：同类缺陷与回归

- [ ] AGPS 真实语义与原始测量/导航/围栏/batching Hook 策略分离。
- [ ] Wi-Fi 扫描开关语义修正并保留旧偏好含义。
- [ ] 启动突发的后端支持或适用范围提示。
- [ ] 反定位拉回配置的真实执行路径、死字段整理。
- [ ] accuracy 默认值和 AP 数范围统一。
- [ ] 旧 GNSS UI/死代码/未接线 raw 策略处理（卫星页全部星座展示已完成）。
- [ ] detector：负仰角、定位有效条件、同时间窗可见/在用/Location extras 对账、画像分布偏差。不能把真实室内低卫星数简单等同造假。
- [ ] 新生产 Hook 的 Android/适配层集成测试及最终完整回归。Core/NMEA/Xposed JVM 测试、App Kotlin 编译和模块规范检查已在本阶段通过，仍须随后续改动验证。
- [ ] 真实 Xposed 设备开启前后对比（当前 ADB 可见 M2102K1C，尚未执行本轮设备测试）。
- [ ] 标准 Mock Provider 真机对比，单独记录实际能力；不将 Xposed 结果外推到标准后端。
- [ ] 文档更新和逐项最终验收。

## 数学参考与验证

- [ESA Navipedia：Positioning Error](https://gssc.esa.int/navipedia/index.php/Positioning_Error)：Q=(HᵀH)⁻¹，ENU 下 HDOP/VDOP 与协方差对角线的关系。
- `GnssGeometryTest` 使用四个水平正交方位加天顶卫星的独立解析协方差 Qee=Qnn=1/2、Quu=5/4、Qtt=1/4 验证数值结果，并覆盖退化几何与多系统钟差。
- `ReceiverGnssSkyModelTest` 对一个完整回放遍历核对实际样本数量分布，检查下限不足、过期、确定性和跨北插值。
- `GnssFrameStoreTest` 检查相同完整样本同秒共享 publication、分应用坐标隔离，以及旧 Location 时间戳不能使过期画像或缓存重新有效。
- `ReceiverTickFramesTest` 检查一个周期内各传输/监听器对同一路由共享 publication，缺失帧也只查询一次。
- `GnssCallbackRegistryTest` 覆盖 Binder 去重、死亡、注销、清空；`ReceiverDeliveryWorkerTest` 覆盖重复启动、停止、重启和在途回调不与同一 worker 的替代线程重叠。
- `GnssLocationExtrasTest` 区分可见与在用、验证 CN0 统计集合和无解时空值；`GnssSatelliteValidationTest` 验证 Android CN0 边界与画像直方图不截断。
- [NovAtel GSV](https://docs.novatel.com/OEM7/Content/Logs/GPGSV.htm) 与 [GSA](https://docs.novatel.com/OEM7/Content/Logs/GPGSA.htm)：核对 talker、卫星编号、可见/在用区别及 DOP 字段。
- Android 本机 SDK 37 的 `GnssStatus.java`、`OnNmeaMessageListener.java` 用于核对公开 Builder、CN0 范围和 UTC 毫秒时间戳。

每组模型逻辑、每个 Hook 和文档分别提交，提交前执行最小相关验证。完整目标仍未完成，不发布 APK 或声称真机已通过。

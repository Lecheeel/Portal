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

- [x] 设置页、采集卡和卫星页展示等待采集/画像过期、邻近实测池容量、当前可见数、目标下限和不足诊断；随界面生命周期每秒刷新，本地预览明确标注统计回放。
- [ ] 移动、噪声、100ms Location 和 1Hz GNSS 输出的序列/时间窗集成验收。相同完整样本共享缓存，但同一秒不同坐标/速度仍可能产生多个序列，不能声称所有移动回调都已同帧。
- [ ] 注册/注销与正在执行的 Binder 回调竞态、停用中断、热重载后现有监听器恢复的设备验证。
- [x] 本机保存逐信号状态、载频和可用基带 CN0，不再 distinctBy 后丢弃多频证据；画像/几何按唯一星座与 SVID 统计。投影优先保留在用信号，再按载频和稳定字段选取整条记录，统计载频的选择不是伪距加权模型。逐信号证据不进入 Binder 配置载荷。真实多频设备回归仍待完成。
- [ ] 多系统完整广播星历与生产输出。GPS LNAV 已实现逐字奇偶校验/数据反转、子帧 1/2/3 同发送帧拼接、IODC/IODE、健康/告警/URA、10 位周号与可信完整时刻解析、toe/toc 和拟合期；有界组帧拒绝冲突重复电文。已能从本机采集的接收机时钟证据解码 GPS 星历，但尚未接入生产天空输出。Galileo 已有独立 GST 类型、广播传播、Android I/NAV 与 F/NAV 页 CRC 和参数解码；仍缺有界组帧、捕获时钟关联、有效期来源及生产集成。北斗和 GLONASS 仍未实现；GLONASS 状态向量不能套 Kepler，北斗 GEO 不能套通用 MEO 旋转。
- [ ] 多系统传播与设备闭环。GPS LNAV 传播、相对论钟差、L1 C/A TGD、真空飞行时间、接收时刻 ECEF 旋转、WGS 84/ENU 观测几何已通过独立数值向量。仍缺多系统、真实原始电文样本、完整天空/CN0/在用策略与生产集成。统计回放不能称为精确轨道，收到导航电文不代表星历完整。
- [ ] 在非零最少数量约束下验证分布和诊断；自动模式的实测分布验收与用户强制下限须分别评价。
- [ ] 彻底整理旧模型配置键、旧偏好和 legacy UI，当前只保留兼容字段不再改变生产模型。

## 待完成：同类缺陷与回归

- [x] 旧 AGPS 存储与配置键保留，代码和当前 UI 改称允许真实原始 GNSS 数据；从围栏、地理编码、test provider、batching 中移除 AGPS 旁路。NMEA 不再控制 raw 注册。
- [x] Wi-Fi 开关改称模拟 Wi-Fi 环境，说明扫描/连接信息替换，保留旧存储键和布尔含义。
- [x] 启动突发说明仅标准 Mock Provider 支持、下次场景启动生效。
- [x] 删除没有消费者的 Hook loopBroadcastLocation 字段；原偏好保留，当前 UI 改为按设定间隔广播，说明 Xposed/Native、关闭时最小 500ms 的实际行为。
- [x] 未保存的 accuracy 默认值统一为公共 25m，保留用户已存数值；AP 数在 Schema/Runtime/旧配置归一化/命令/调试入口/场景启动统一为 EnvironmentConfig 的 3–8。
- [ ] 遗留清理继续进行：旧 GNSS、Settings、Environment、Simulation Fragment 与布局已删除；三个未接线且使用伪数据的 raw 测量、导航、天线策略及占位测试和 HAL 死方法已删除。旧模型配置兼容字段仍待清理。
- [ ] detector 最终对账和画像偏差仍待完成。已修复 C06 对负仰角、非有限方位/CN0 的漏报；少星、单星座、无解和 CN0 相关性只作为证据不足，不判定造假。C07 区分可见与在用、按 UTC 毫秒限定配对时间窗、GST/ZDA 可选、多频 GSV 分组及唯一卫星计数；新增 GSA 身份重复/非法编号检查，并与同时间窗 GSV/GnssStatus usedInFix 身份对账。GN 仅在明确 systemId 时映射，跨来源差异或编号不明为 UNKNOWN。C05 只验证已提供的可选字段，GPS extras 与同时间窗唯一在用身份对账，network/陈旧状态不混比。画像分布和最终跨来源设备验收仍需继续。
- [x] 修复 GnssNative 单参数 Location[] 批量回调被 args.size>1 漏过的问题，替换时复制数组，不修改 Provider 持有的原批次；已通过 Xposed JVM 回归，厂商实际回调仍需设备验收。
- [ ] raw GNSS 运行中与厂商适配验收：新注册按 API 明确返回 false 或不支持异常；已有监听器逐回调执行实时策略，保留 Binder 死亡/注销/卸载清理；在没有有效画像时真实透传。状态与导航的 NOT_SUPPORTED 回调、分应用排除路由已接线并有 JVM 覆盖，仍需真实框架回归。
- [ ] 新生产 Hook 的 Android/适配层集成测试及最终完整回归。Core/NMEA/Xposed JVM 测试、App Kotlin 编译和模块规范检查已在前阶段通过；本阶段针对新增逻辑的 Core/Xposed/Detector JVM 测试、App Kotlin 编译及 arm64 开发包/测试包构建已通过，仍须随后续改动验证。
- [ ] 真实 Xposed 设备开启前后对比。M2102K1C / Android 14 已执行一次 60 秒真实采集尝试，认证状态确认模拟停止，但未收到有效卫星，未形成画像，不能验收星座比例或传播；已向用户询问接收环境。新 Hook 尚未经系统进程重载/重启验收。开发包更新前已核对相同证书并在本机保存原 APK/配置备份；未卸载应用、未修改生产画像。空采集新增引擎启动/停止、测量批次/电文数和无效字段原因诊断，便于下一次定位链路排查。
- [ ] 标准 Mock Provider 真机对比，单独记录实际能力；不将 Xposed 结果外推到标准后端。
- [ ] 文档更新和逐项最终验收。

## GPS 星历实现与后续验收

GPS LNAV 的纯 Kotlin 数学和本机采集解码已落地，生产输出仍使用统计回放。位序/字校验按 Table 20-XIV，组帧要求同一卫星、同一个 30 秒发送帧和完整 IOD；扩展拟合期按 Table 20-XII，保留区间拒绝。完整周号由明确可信的 GPS 时刻解析，不硬编码 GPS−UTC 秒数；接收机时间采用整数/十进制先减偏差再转秒，避免绝对纳秒转 Double 丢精度。

原始采集新增时钟不确定度、漂移、闰秒证据、接收机事件单调时刻与回调到达时刻，兼容缺少这些字段的旧画像。GPS 捕获解码要求已知时间不确定度、附近一致且未重置的接收机时钟；回调到达时刻只用于周号/HOW 关联，不冒充精确发送时刻。缺证据、告警、陈旧、冲突或分页不完整时返回诊断，不由 hasEphemeris 标志推断星历。

广播参数是为 ECEF 拟合的参数，不可将中间轨道坐标当成普通 ECI 再额外旋转。应包含 Δn、六个二次谐波项、IDOT、Ωdot 和 toe 地球自转项；GPS LNAV 的 μ=3.986005×10¹⁴，与通用二体试验常量不相同。计算发射时刻位置，并处理信号传播期间地球自转；钟差多项式、相对论项和 TGD 单独按对应观测信号处理。周跨越修正不能让陈旧一周的星历重新有效，须先以完整绝对时刻检查有效期。

GPS 接收几何通过有界真空光程方程求发射时刻，再将发射 ECEF 坐标旋转到接收时刻的 ECEF 轴。拟合期检查针对实际发射时刻；接收时刻稍晚于拟合期末但发射仍有效的情况可以成立。该模型不含电离层、对流层、天线和接收机偏差，不能据此生成伪距或载波相位。ENU 仰角采用与 asin(up/range) 等价且数值稳定的 atan2；天顶/天底方位角明确未定义，不制造数值。

Galileo、北斗 MEO/IGSO/GEO、GLONASS 必须分别核对接口规范和系统时标；不以 GPS 代码通过代替其他系统验证。每个解码器、每个传播器分别提交，使用独立权威导航向量和可追溯数值结果，补单元/时间/地平线/过期/分页切换等边界测试后才接生产路径。

## Galileo 星历基础与缺口

按 OS SIS ICD 2.2 实现独立完整 GST、GTRF 广播传播和双频参考钟差，I/NAV E1/E5b 与 F/NAV E1/E5a 参考显式区分。模型不把 GPS 与 GST 直接互换，不默认沿用 GPS 四小时有效期；调用方必须提供有依据的绝对有效区间。BGD 和逐信号 SHS/DVS 独立保留，不能将数学轨道可计算等同于信号可用。

Android I/NAV 采用 228 位偶/奇页组合，CRC 覆盖前 196 位，末尾 SSP 不在覆盖范围内；F/NAV 采用 214 位信息和 24 位 CRC。解码前核对载荷长度、填充位、页类型和 CRC24Q。I/NAV words 1–5 与 F/NAV pages 1–4 分别要求身份、IOD、完整周号和发送时刻一致，拒绝 SISA 保留值与 NAPA。当前样本为真实 RINEX 参数独立重建的合成页，不是真机原始页。

传播用四条同一 E24 卫星的真实记录，在两个钟差参考下对照 12 组固定 RTKLIB C 结果；覆盖范围不代表其他 Galileo 卫星已验收。CRC 用独立多项式长除法生成并经 RTKLIB C 校验；I/NAV 全参数另与 RTKLIB 解码器对照，F/NAV 参数按源 RINEX 和标准广播量化间隔核对，尚无独立 C 参数解码器对照。OSNMA 认证、有效期证据、GGTO、捕获组帧及生产天空输出仍待完成。

## 数学参考与验证

- [Galileo OS SIS ICD 2.2 官方现行文档](https://www.gsc-europa.eu/sites/default/files/sites/all/files/Galileo_OS_SIS_ICD_in_force.pdf)：Tables 30–33、38、42–46、67–68、91，核对页布局、CRC、轨道、钟差、BGD 与 SISA。
- [固定版本 Galileo 混合 RINEX](https://github.com/geospace-code/georinex/blob/8d1210a0f1ada71ff7b8d0484cfaf22ff154a38e/src/georinex/tests/data/BRDC00IGS_R_20201360000_01D_MN.rnx)：`GalileoBroadcastPropagatorTest` 的 E24 真实输入；`GalileoNavigationPagesTest`、I/NAV 与 F/NAV 解码测试覆盖位破坏、身份/IOD/时刻不一致、缺页和信号状态。
- [IS-GPS-200N 官方归档](https://archive.gps.gov/technical/icwg/IS-GPS-200N.pdf)：核对 20.3.3/20.3.4/20.3.5，Table 20-II/III/IV/XII/XIV，GPS 参数、拟合期、坐标系、飞行光程与逐字奇偶校验。
- [固定版本 RTKLIB 导航输入](https://github.com/tomojitakasu/RTKLIB/blob/71db0ffa0d9735697c6adfd06fdf766d0e5ce807/test/data/rinex/07590920.05n)、[eph2pos](https://github.com/tomojitakasu/RTKLIB/blob/71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/ephemeris.c)、[decode_frame](https://github.com/tomojitakasu/RTKLIB/blob/71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/rcvraw.c) 与 [decode_word](https://github.com/tomojitakasu/RTKLIB/blob/71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/rtkcmn.c)：三个真实 RINEX 轨道、9 个传播时刻和9组接收几何数值。90 个测试电文字由这些参数按 ICD 独立重建、经 C 实现解码对照；它们是合成字级测试样本，**不是真实接收机原始电文**。微米级数值一致性仅用于检查代码，不代表广播轨道精度。
- `GpsLnavWordsTest` 覆盖每一位的单比特破坏；`GpsLnavEphemerisDecoderTest` 对照全部参数，覆盖 IOD、健康、时标、拟合期和非法字段；`GpsLnavAssemblerTest` 覆盖乱序、重复、冲突、缓存上限与完整周隔离；`GpsCaptureEphemeridesTest` 覆盖时钟证据缺失、不确定度和重置。
- `GpsReceptionGeometryTest` 对照独立 C 光程/接收 ECEF/ENU 数值并检查拟合期边缘和跨周；`GnssObserverGeometryTest` 使用赤道/两极/日期变更线、ENU 正交向量和天顶解析值。
- [ESA Navipedia：Positioning Error](https://gssc.esa.int/navipedia/index.php/Positioning_Error)：Q=(HᵀH)⁻¹，ENU 下 HDOP/VDOP 与协方差对角线的关系。
- `GnssGeometryTest` 使用四个水平正交方位加天顶卫星的独立解析协方差 Qee=Qnn=1/2、Quu=5/4、Qtt=1/4 验证数值结果，并覆盖退化几何与多系统钟差。
- `ReceiverGnssSkyModelTest` 对一个完整回放遍历核对实际样本数量分布，检查下限不足、过期、确定性和跨北插值。
- `GnssFrameStoreTest` 检查相同完整样本同秒共享 publication、分应用坐标隔离，以及旧 Location 时间戳不能使过期画像或缓存重新有效。
- `ReceiverTickFramesTest` 检查一个周期内各传输/监听器对同一路由共享 publication，缺失帧也只查询一次。
- `GnssCallbackRegistryTest` 覆盖 Binder 去重、死亡、注销、清空；`ReceiverDeliveryWorkerTest` 覆盖重复启动、停止、重启和在途回调不与同一 worker 的替代线程重叠。
- `GnssLocationExtrasTest` 区分可见与在用、验证 CN0 统计集合和无解时空值；`GnssSatelliteValidationTest` 验证 Android CN0 边界与画像直方图不截断。
- [NovAtel GSV](https://docs.novatel.com/OEM7/Content/Logs/GPGSV.htm) 与 [GSA](https://docs.novatel.com/OEM7/Content/Logs/GPGSA.htm)：核对 talker、卫星编号、可见/在用区别及 DOP 字段。
- Android 本机 SDK 37 的 `GnssStatus.java`、`OnNmeaMessageListener.java` 用于核对公开 Builder、CN0 范围和 UTC 毫秒时间戳。

raw/NMEA/最少卫星数实时策略字段已使用跨线程可见存储；配置批次的完整原子发布仍需后续审查。

每组模型逻辑、每个 Hook 和文档分别提交，提交前执行最小相关验证。完整目标仍未完成，不发布 APK 或声称真机已通过。

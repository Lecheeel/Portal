# 运营商身份与 NR SA 接入进度

## 已确认范围

- 运营商身份总开关默认关闭，配置保留。
- 启用后默认全局；可切换为仅分应用。全局包括系统电话栈和状态栏。
- 支持两个真实 SIM 槽独立配置；允许用户显式启用无卡虚拟 SIM，用于无 SIM 测试机，不自动虚构未配置的卡。
- ISO 3166-1 国家/地区选择、运营商预设及手动 MCC/MNC；不承诺预设穷尽所有运营商。
- NR SA 为默认无线画像；LTE 可配置。身份覆盖不会把真实调制解调器变成另一张 SIM 或远端网络。
- 不修改 README。

## 2026-11-17 一致性修复

- 已修正 n20/n26/n30/n48 下行范围及 FR1 100 kHz 栅格校验；新增边界测试，core/Xposed 回归与 App 构建通过。参考 TS 38.101-1 表 5.4.2.3-1。
- 虚拟 SubscriptionInfo 改由平台 Builder 构造，取消 Unsafe 和静默字段赋值；小米/红米完整对象 Parcel、equals/hashCode 与 Builder 复制回归各 1 项通过。红米首次执行因 USB 中断未通过，重连后重试通过。
- 红米遗留的 `hidden_api_policy=1` 已在重连后删除并核对为 `null`；后续对象测试 finally 也成功删除，双机临时策略均恢复。
- 客户端按槽 SIM 状态改用 phoneIndex 解析，SIM2 不再被首张虚拟卡遮蔽；默认订阅保留有效平台值并支持仅 SIM2 配置。本地编译通过，实际目标应用路径待冷启动复核。
- carrierId 改用 AOSP 核实的中/美/澳主要 MNO canonical ID，不再依赖卡槽计算；未知 PLMN 返回 -1。此表不是完整 MVNO 识别器，GID/SPN 子运营商细分不做无依据推断。来源：https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/refs/heads/main/assets/latest_carrier_id/carrier_list.textpb
- 已加入每槽持久化 identitySeed，旧配置在控制器同步前生成并保存；编辑草稿和重复保存保留种子。core 验证身份在显示名/频段修改后稳定、不同种子身份不同、Luhn/长度及序列化一致；小米/红米各 1 项独立临时偏好迁移回归通过。不代表已验证冷启动后的全部电话进程消费路径。
- ICCID 不再统一固定 8944 前缀，中/美/澳等使用国家电话代码；这些只是模拟格式，不是获分配的发行方 IIN/真实入网凭证。未收录国家使用中性模拟前缀，仍需完善发行方资料和用户自定义策略。
- 固定 900000001/2 订阅 ID 仍待替换；不能因为 IMSI/ICCID 加了种子就声称所有固定特征已消除。
- 虚拟 ServiceState 统一构造 HOME/WWAN/PS 数据注册项及 PLMN、NR/LTE 制式，设置数据注册与顶层状态，不复制真实无卡/紧急注册列表。小米/红米各 4 项 ServiceState 对象测试通过（含双槽 NR/LTE Parcel 往返、原对象不变）；临时 hidden API 策略均恢复。对象验证不代表系统广播、数据连接、信号或状态栏链路已闭环。
- ServiceState 回调取消解析失败后取首张虚拟卡的回退；初始状态仅发给订阅 ServiceState 事件的监听器。Xposed 回归和 App 编译通过，实际 Binder 双卡监听结果待冷启动验证。
- 统一虚拟订阅拓扑查询：phone/slot/subId、活跃标志和 ID 列表；最大卡槽能力不再叠加虚拟卡数量。core 覆盖未知 ID、仅 SIM2、隐藏配置和映射往返。
- 两台设备冷启动加载后，扩展双卡端到端各 1 项通过：活跃列表与数量为 2，每卡活跃标志 true、订阅 ID→槽位正确，按槽/按 ID 查询均与列表同值，电话栈返回 ICCID 与控制器迁移保存的种子配置同值。此测试经过实际 Xposed/system_server 通道，不依赖仪器 hidden API 放行；没有验证状态栏显示。
- MIUI 运营商文本适配：从设备 APK 确认 `MiuiCarrierTextControllerImpl.updateCarrierText()` 及双槽输入数组，只 Hook 该渲染入口；在渲染期间临时替换名称/可用状态，finally 恢复 ROM 缓存引用。离开全局或停止时在主线程调用原更新，由 ROM 通知视图；Binder 快照在后台读取。
- 新增 2 项渲染单测通过（双槽、单 SIM2、不覆盖实体缓存、异常恢复）。两台无卡真机真实 SystemUI 可访问性文本测试各 1 项通过：控制中心同时显示 Portal Alpha/Portal Beta，切分应用后消失，切回全局后出现，关闭后消失，再开启后出现，停止场景后消失；finally 恢复偏好、后端、可访问性标志并收起面板。测试不放开 hidden API。这是 MIUI 控制中心运营商文字证据，不代表锁屏所有布局、5G 图标、信号强度或真实入网均已验证。
- 每槽“无卡时模拟此 SIM”开关已加入编辑器并在草稿/保存/同步中保留；原配置 virtual=false 不会因拔卡自动虚拟化，virtual=true 的已保存虚拟配置保留行为。实体订阅优先。core 覆盖关闭虚拟、停用槽、有实体卡、分应用指定 SIM2 且请求槽未配置；core/Xposed 测试和 App 编译通过。新开关 UI 真机交互回归待补；运行时关闭虚拟后的订阅删除已由下述扩展测试验证。
- CarrierConfig 空 Bundle 始终保持为空；subset 不新增未请求的覆盖字段，原对象不修改。订阅列表 null 不扩成虚拟列表，getAccessibleSubscriptionInfoList 保留 UICC 权限过滤。两台设备真实 Bundle 回归各 1 项通过；不代表 ICCID/IMSI 等完整权限矩阵已验收。
- 扩展逐槽删除回归暴露框架缓存问题：列表删除后 getSlotIndex 仍返回旧值。现于系统/电话进程接受身份变化后调用平台订阅缓存失效方法，启动/停止和异步 transfer 均接入，位置样本不变更身份时不失效。电话 peer 同步为异步，设备测试要求五秒内收敛，不能把命令 ACK 当作全部完成。
- 缓存修复回归期间小米发生 system_server 崩溃，堆栈为 NetworkPolicyManagerService→getMergedImsisFromGroup 对不存在实体 SubscriptionInfo 解引用。已为虚拟订阅接入该特权入口的单卡 IMSI 集合；READ_PRIVILEGED_PHONE_STATE 未获准或非虚拟调用继续走原方法。修复后冷启动两台各 3 项通过：空 Bundle/subset、双卡逐槽删除含 slotIndex/活跃/计数、控制中心显示与恢复。该路径此前未被订阅列表测试覆盖，不隐瞒其崩溃。
- 每槽关闭虚拟后的实际订阅删除双机已通过；新开关 UI 交互回归仍待补。测试后检查两台当前运营商偏好均非本轮双虚拟卡测试种子，不额外清空原有配置。
- 公开 caller-scoped 身份快照移除 identitySeed；IMSI/ICCID 只有显式请求且 Binder 调用持有 READ_PRIVILEGED_PHONE_STATE 时单独返回，普通画像即使特权调用也不带标识。请求中预置的回复字段每次先清除。客户端受限 getter 不再从公开 profile 推导标识，订阅对象无特权时 ICCID 为空。
- 两台无卡设备冷启动后各 2 项通过：扩展双卡/逐槽删除测试内对比普通 READ_PHONE_STATE 与特权读取、断言种子不出快照/伪造回复字段被清除/特权显式请求标识同值/普通请求移除字段；控制中心双名与模式/停止恢复继续通过。不依赖 hidden API 放行。虚拟卡没有真实 carrier privilege、SMS role 或 device-owner 委托，不能据此宣称覆盖所有真实卡授权路径。
- 尚待修复/验证：完整 carrierId/MVNO 策略、订阅与注册状态闭环、其余权限返回路径、双卡回调及其他状态栏/锁屏布局。不能将已有订阅可见测试视为这些项目已通过。

## 进度

- [x] 核心配置、ISO/MCC/MNC 校验与运行时传输。
- [x] 页面保存/运行时共用 65,536 字符有界编解码器；配置和启用开关一次事务保存。core 测试覆盖长应用规则超限、损坏输入和运行时同格式解码。
- [x] 异步全局请求单独反馈失败；即使旧覆盖所有权存在，也不能把本次拒绝误判为成功。已有基线和待恢复记录继续保留。
- [x] 双卡与共享 UID 调用方路由解析及 JVM 测试。
- [x] 设置入口与独立编辑页；关闭保存、停用卡配置保留、后台发送配置。
- [x] 页面草稿保存和导航返回的应用选择合并。
- [x] 电话进程运营商、国家码和网络类型读取 Hook。
- [x] CarrierConfig 读取覆盖，活跃 SubscriptionInfo 副本覆盖。
- [x] 既有临时 CarrierConfig override 的字段备份与精确恢复实现；仅恢复仍由 Portal 持有的键，保留其他写入方修改。私有 helper 不支持时拒绝全局写入，实际设备路径待验证。
- [x] 覆盖执行确认及页面状态回传：区分等待场景、无匹配 SIM、写入完成、失败和待恢复。
- [ ] 切换全局/分应用、停止场景、模块重载的身份生命周期验证；已接入启动/停止，并交接 system_server 热重载待恢复记录，实际设备联动待测。
- [x] ServiceState 副本清除内部真实 CellIdentity、重绑注册 PLMN、对齐已注册 WWAN PS 制式，并保留未注册/空字段；两台真机 Android 对象测试各 3 项通过，不代表已验证 Binder Hook 命中。
- [x] 客户端属性 getter 的最小只读身份快照与目标应用/SystemUI 接入；不共享控制器令牌、全量路由或真实 SIM 标识。未知网络/空字段保留原值，实际 Hook 命中待验证。
- [x] 新身份模式保留真实 ISub 卡槽和活跃订阅拓扑，不走旧无卡式返回策略。
- [x] TelephonyRegistry 的 ServiceState 回调按原调用 UID/订阅绑定；注册前建立映射、异常回滚、注销/死亡时释放。已编译验证，实际 Binder 回调命中待测。
- [x] 身份关闭或无法解析时，仍独立执行受保护第三方调用的 ServiceState 小区清理；两台设备对象回归通过。
- [x] 系统 ServiceState/CellInfo 监听器交接调用 UID、订阅和 Binder，并释放旧死亡通知；单次 ICellInfoCallback 在电话进程按实际 UID/订阅保护，失败返回空结果。
- [x] SystemUI 已加入推荐作用域；全局 CarrierConfig 还原时不叠加旧电话进程快照。
- [ ] 系统属性/缓存和异步回调联动的有卡实际设备验证。
- [x] 导入/回放小区按 NR/LTE 身份筛选并重绑 MCC/MNC/名称，统一 serving 标记；身份解析失败不放开原有位置保护。
- [x] 修正 NR Android 构造器与连接状态；小米/红米各通过 1 项 NR 对象和 Parcel 字段测试，仪器进程临时放开 hidden API 后已恢复设置。
- [x] 独立确定性 NR SA 生成器、双卡频段/ARFCN 校验与页面编辑；覆盖中国、美国、澳洲常用 n1/n3/n5/n8/n28/n40/n41/n66/n71/n77/n78/n79 等频段。LTE 仅参考相对信号及数量，不复制 LTE 无线标识；频段不等同于目标国家实际部署。
- [x] Android NR bands 字段的对象与 Parcel 回归，小米/红米各 1 项通过，临时 hidden API 设置均恢复。
- [x] 物理 phoneId、订阅 ID 和默认订阅哨兵独立解析；core 测试覆盖双卡、不存在的槽、无效默认值及有效 subId=0。
- [ ] 系统注册状态/显示状态和异步回调联动验证。
- [x] 无卡端到端测试：显式虚拟 SIM 配置会生成稳定 subId、MCC/MNC/ICCID、正数 carrierId 的虚拟订阅，并进入 `dumpsys isub` 的默认、可用和可选订阅列表；目标应用作用域实测 `simState=5`、`simOperator=310260`、`simOperatorName=T-Mobile`、`simCountry=us`、`networkType=NR`、`networkOperatorName=T-Mobile USA`、`ServiceState=IN_SERVICE`、`serviceOperator=310260`、`phoneType=GSM`、`carrierId=7031`，且活跃订阅同值。未配置/关闭身份时不虚构订阅。测试后恢复运营商偏好并停止场景。
- [x] 两台无卡设备扩展端到端测试通过：全局→分应用→关闭→停止后待启动状态符合预期；单次小区更新返回空数据或错误，未暴露非空小区列表。测试后恢复偏好。
- [x] 两台设备冷启动后实际 system_server→phone Binder 能力握手通过：电话进程已安装精确恢复 Hook；该探测不写 CarrierConfig、不构造虚拟 SIM，也不代表有卡覆盖/状态栏已验收。
- [x] 重复应用前重新建立外部同名字段的恢复基线；测试覆盖外部更新/删除/null，物理 phoneId 和配置层绑定拒绝换槽后复用旧记录。换卡实际行为待测。
- [x] 两台 Android 设备运行实际覆盖 Hook 回调的执行夹具，各 1 项通过：真实 PersistableBundle、假电话服务排队和 merge，覆盖重新写入/外部值恢复/无关键保留/平台失败/订阅冲突与状态反馈。测试不 Hook ART、不写真实 CarrierConfig、不创造 SIM。
- [x] 无卡双槽虚拟订阅真机验证：槽 1 美国 T-Mobile、槽 2 澳洲 Telstra 同时进入活跃订阅，subId、槽位、MCC/MNC/ICCID 均一致。
- [x] 无卡 MIUI 控制中心双运营商文字及全局/分应用/关闭/停止恢复，双机实际视图文本通过。
- [ ] 真机验证两张真实卡槽、有卡覆盖与还原、锁屏其他布局、信号/5G 图标及应用隔离完整矩阵；控制中心文字结果不能外推到这些项目。
- [x] Debug 专用无地图导航宿主，编辑双卡未保存草稿、应用选择返回和 Activity 重建测试在小米通过；正式包不包含宿主。
- [ ] 红米 UI 回归及真正进程死亡恢复：MIUI 空白启动确认页阻挡，未判为通过；已停止测试并恢复临时权限，清理确认属于测试的种子配置。

## 验证记录

模型、配置解码及双卡路由已通过 core JVM 测试；新增电话进程 Hook 已通过 Xposed JVM 测试和 Kotlin 编译。编译成功不能证明厂商系统上相应方法被实际调用；最终验收需记录真实设备的 API 返回、状态栏和还原结果。

应用选择策略测试覆盖：保留已有 SIM 目标、移除取消勾选项、清空列表、大小写去重和数量上限。

UI 回归宿主直接使用正式导航图与运营商编辑页，不初始化首页地图。小米临时将 MIUI 应用操作 10020/10021 从 ignore 改为 allow 后通过，测试后已恢复；红米仍被空白 ConfirmStartActivity 阻挡，不能将其结果外推为业务恢复失败或成功。

无卡端到端回归通过的是实际 Xposed/system_server 通道，不要求隐藏 API 放行。红米的普通 shell 运行时授权被系统拒绝，使用 root 预先授予已声明的位置/通知权限后通过；框架模块与 system/phone/SystemUI 作用域需先启用。

覆盖还原策略测试覆盖：删除原先不存在的字段、恢复原有字段、保留不相关键、保留其他写入方后续修改及显式 null 的区别。2026-11-16 两台已连接测试机没有真实 SIM；显式无卡虚拟配置下 `dumpsys isub` 的默认/可用/可选订阅均出现虚拟 subId，目标应用作用域读取到虚拟 MCC/MNC、运营商名、NR 网络类型和 GSM 电话类型。真实双卡和有卡 CarrierConfig 写入仍待验证；MIUI 控制中心双运营商文字及恢复已于 2026-11-17 双机通过。

## 当前验收前提

2026-11-16 再次核对：`4c8aae04` 与 `MFZDWOAIBA8DGIC6` 没有真实 SIM，`gsm.sim.state` 均为 `ABSENT,ABSENT`。显式无卡虚拟 SIM 路径已验证默认/可用/可选订阅和目标应用核心字段，MIUI 控制中心双运营商文字及恢复也已通过；有卡双槽、有卡 CarrierConfig 写入和关闭恢复仍需至少一张真实 SIM 做单槽验证、两张真实 SIM 做双槽验证。

未结项内容仍包括其他无卡状态栏/锁屏布局和信号图标、有卡系统/状态栏端到端结果、红米编辑页厂商确认流程及真正进程死亡恢复；不能用假服务执行夹具或对象转换测试代替。

普通 `getActivePhoneType` 查询按订阅/default 解析，只有 `ForPhone`/`ForSlot` 明确入口按零起始物理槽号解析，已补 core 回归。

## 实现约束

全局写入前应保留原有 override，关闭时只恢复本功能拥有的变更。不能把完整的生效 CarrierConfig 当作原有 override：生效配置还包含系统默认值和运营商配置，混淆两者会冻结后续运营商更新。

受限接口沿用框架原有权限检查；查询不到真实槽位时不猜测。分应用规则按实际 UID 所属包解析，不能信任调用参数自报的包名。

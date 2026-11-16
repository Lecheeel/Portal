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
- 虚拟 SubscriptionInfo 改由平台 Builder 构造，取消 Unsafe 和静默字段赋值；小米完整对象 Parcel、equals/hashCode 与 Builder 复制回归通过。红米未完成：仪器运行停滞后 USB 断开，不计为通过。
- **待设备清理**：红米 `MFZDWOAIBA8DGIC6` 第二次对象测试临时设置 `hidden_api_policy=1` 后断连，finally 删除设置失败。重连后优先执行 root `settings delete global hidden_api_policy`，恢复测试前默认值；小米策略已恢复。
- 尚待修复/验证：每设备稳定身份、carrierId 的真实语义、显式虚拟插卡策略、订阅与注册状态闭环、权限返回一致性、双卡回调及 SystemUI 开启/关闭刷新。不能将已有订阅可见测试视为这些项目已通过。

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
- [ ] 真机验证两张真实卡槽、无卡虚拟订阅的状态栏、全局与分应用隔离以及关闭恢复；已验证无卡双槽订阅、电话栈核心字段和目标应用读取，MIUI 状态栏仍待补显示链路。
- [x] Debug 专用无地图导航宿主，编辑双卡未保存草稿、应用选择返回和 Activity 重建测试在小米通过；正式包不包含宿主。
- [ ] 红米 UI 回归及真正进程死亡恢复：MIUI 空白启动确认页阻挡，未判为通过；已停止测试并恢复临时权限，清理确认属于测试的种子配置。

## 验证记录

模型、配置解码及双卡路由已通过 core JVM 测试；新增电话进程 Hook 已通过 Xposed JVM 测试和 Kotlin 编译。编译成功不能证明厂商系统上相应方法被实际调用；最终验收需记录真实设备的 API 返回、状态栏和还原结果。

应用选择策略测试覆盖：保留已有 SIM 目标、移除取消勾选项、清空列表、大小写去重和数量上限。

UI 回归宿主直接使用正式导航图与运营商编辑页，不初始化首页地图。小米临时将 MIUI 应用操作 10020/10021 从 ignore 改为 allow 后通过，测试后已恢复；红米仍被空白 ConfirmStartActivity 阻挡，不能将其结果外推为业务恢复失败或成功。

无卡端到端回归通过的是实际 Xposed/system_server 通道，不要求隐藏 API 放行。红米的普通 shell 运行时授权被系统拒绝，使用 root 预先授予已声明的位置/通知权限后通过；框架模块与 system/phone/SystemUI 作用域需先启用。

覆盖还原策略测试覆盖：删除原先不存在的字段、恢复原有字段、保留不相关键、保留其他写入方后续修改及显式 null 的区别。2026-11-16 两台已连接测试机没有真实 SIM；显式无卡虚拟配置下 `dumpsys isub` 的默认/可用/可选订阅均出现虚拟 subId，目标应用作用域读取到虚拟 MCC/MNC、运营商名、NR 网络类型和 GSM 电话类型。真实双卡、有卡 CarrierConfig 写入和 MIUI 状态栏仍待验证。

## 当前验收前提

2026-11-16 再次核对：`4c8aae04` 与 `MFZDWOAIBA8DGIC6` 没有真实 SIM，`gsm.sim.state` 均为 `ABSENT,ABSENT`。显式无卡虚拟 SIM 路径已验证默认/可用/可选订阅和目标应用核心字段，但 MIUI 状态栏显示链路仍在补；有卡双槽、有卡 CarrierConfig 写入和关闭恢复仍需至少一张真实 SIM 做单槽验证、两张真实 SIM 做双槽验证。

未结项内容仍包括无卡状态栏、有卡系统/状态栏端到端结果、红米 UI 厂商确认流程及真正进程死亡恢复；不能用假服务执行夹具或对象转换测试代替。

普通 `getActivePhoneType` 查询按订阅/default 解析，只有 `ForPhone`/`ForSlot` 明确入口按零起始物理槽号解析，已补 core 回归。

## 实现约束

全局写入前应保留原有 override，关闭时只恢复本功能拥有的变更。不能把完整的生效 CarrierConfig 当作原有 override：生效配置还包含系统默认值和运营商配置，混淆两者会冻结后续运营商更新。

受限接口沿用框架原有权限检查；查询不到真实槽位时不猜测。分应用规则按实际 UID 所属包解析，不能信任调用参数自报的包名。

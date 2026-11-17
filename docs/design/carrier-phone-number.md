# SIM 模拟号码

每槽号码是可选、显式保存的模拟资料。入口为“环境 → SIM 与蜂窝环境 → SIM 详情 → 本机号码”。两槽可分别设置；总开关、GLOBAL/PER_APP 路由与既有运营商身份共用，不另建运行开关。

## 生成与保存

- 中国：选择中国运营商预设，搜索号段归属城市，再点击“随机填充号码”。按对应城市、原分配运营商的七位手机前缀加随机四位尾号生成，使用 +86 格式。没有匹配数据时明确提示，不自动换城市或运营商。
- 国外：按照当前国家/地区调用码和完整号码规则随机生成，使用国际格式。优先移动号码；没有移动规则的地区采用普通固定号码规则。无规则时保留手工填写。
- 支持手动编辑、清空；保存后号码固定，只有再次随机填充或编辑才改变，不在 getter 中随机。
- 草稿、切槽、Activity 重建与保存载入保留号码和城市。切国家清除旧号码；中国自动号码在手改 MCC/MNC 或切换不匹配预设时清除，保存时再次校验城市/原运营商归属。手动编辑号码会去掉城市自动生成标记。
- 留空不替换原号码返回行为。原配置缺字段时默认留空，不擅自给旧配置生成号码。

## 接口与权限

电话进程覆盖真实 PhoneSubInfoController 的 line-1、PhoneInterfaceManager 的 display-line-1，以及 SubscriptionController/SubscriptionManagerService 的新号码 getter。所有路径先执行框架原方法，保留包名归属、权限和无效来源异常。

- 新 `SubscriptionManager.getPhoneNumber()` 使用号码读取权限及 AppOp；特权电话权限允许读取。指定来源只填 UICC，不伪造 IMS 或运营商下发号码记录。
- 旧 line-1 保留平台合法的 READ_PHONE_NUMBERS、READ_SMS、旧 target≤29 的 READ_PHONE_STATE、默认短信角色等路径，并检查对应 AppOp。模拟卡不授予 carrier privileges。
- SubscriptionInfo 中号码按权限单独脱敏；普通公开画像快照始终移除号码与城市，不借公开快照绕过号码权限。
- 号码与两槽身份及路由共用；未选的 PER_APP 调用保留原值。关闭/清空不写真实 UICC 或号码数据库。

## 数据来源

离线派生自 [Google libphonenumber 固定提交](https://github.com/google/libphonenumber/tree/36931eec42d4f57335ebf0360d6cb7eec1300d50)，包含：

- [国际号码元数据](https://github.com/google/libphonenumber/blob/36931eec42d4f57335ebf0360d6cb7eec1300d50/resources/PhoneNumberMetadata.xml)：245 个地区的移动或普通号码规则。
- [中国城市映射](https://github.com/google/libphonenumber/blob/36931eec42d4f57335ebf0360d6cb7eec1300d50/resources/geocoding/zh/86.txt)及[原运营商映射](https://github.com/google/libphonenumber/blob/36931eec42d4f57335ebf0360d6cb7eec1300d50/resources/carrier/en/86.txt)：筛出 110917 条明确七位手机前缀、340 个不同城市/地区数据标签。存在更长地理例外的七位前缀整项排除，避免生成到其他城市。

逐文件为 Apache-2.0；许可证与来源说明随 core 资源打包。转换脚本见 [build_carrier_phone_metadata.py](../../scripts/build_carrier_phone_metadata.py)。保留上游城市原名称，可能包含历史地名；不将该名单声称为所有现行行政城市或实时号段分配表。携号转网后实际运营商可能改变。

生成值仅保证离线格式/号段规则，不保证号码未被分配、已开通、可呼叫或接收验证码。没有向随机号码拨号、发短信或请求验证码。对于无号码权限、真正依赖基带/短信验证、或自身未处理空值的软件，不能承诺不会崩溃。

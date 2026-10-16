# Android 定位接口参考

本目录保存 Android 定位接口的历史参考文件和 AOSP 源码入口，供比较不同版本的方法签名、回调与系统实现。当前参考范围为 Android 7.0 至 Android 17，最后核验日期为 **2026-10-16**。

这些资料不参与项目编译、打包或运行。项目当前最低支持 Android 12 / API 31；更早版本的资料作为历史记录保留。目录覆盖到某个版本，不代表应用或 Xposed / Native 后端已通过该版本的兼容性验证。

## 版本索引

源码链接使用 AOSP 固定发布标签，便于复现比较；主版本采用首发 `r1` 基线，已有带修订号的历史目录保留原基线。API 列对应所列基线，不单列 Android 8.1、12L 等中间版本。

| Android | API | AOSP 基线标签 | 参考目录 |
|---|---|---|---|
| 7.0 | 24 | `android-7.0.0_r1` | [7.0.0_r1](7.0.0_r1/README.md) |
| 7.1.1 | 25 | `android-7.1.1_r6` | [7.1.1_r6](7.1.1_r6/README.md) |
| 8.0 | 26 | `android-8.0.0_r4` | [8.0.0_r4](8.0.0_r4/README.md) |
| 9 | 28 | `android-9.0.0_r3` | [9.0.0_r3](9.0.0_r3/README.md) |
| 10 | 29 | `android-10.0.0_r1` | [10.0.0](10.0.0/README.md) |
| 11 | 30 | `android-11.0.0_r1` | [11.0.0](11.0.0/README.md) |
| 12 | 31 | `android-12.0.0_r1` | [12.0.0](12.0.0/README.md) |
| 13 | 33 | `android-13.0.0_r1` | [13.0.0](13.0.0/README.md) |
| 14 | 34 | `android-14.0.0_r1` | [14.0.0](14.0.0/README.md) |
| 15 | 35 | `android-15.0.0_r1` | [15.0.0](15.0.0/README.md) |
| 16 | 36 | `android-16.0.0_r1` | [16.0.0](16.0.0/README.md) |
| 17 | 37 | `android-17.0.0_r1` | [17.0.0](17.0.0/README.md) |

Android 7–9 的本地 `.aidl` 是历史参考文件，可能包含维护注释；对应 README 中的 AOSP 固定标签是核对原始内容的依据。Android 10 起沿用源码链接形式，避免将不参与编译的系统源码复制进仓库。

## 本地源码快照

Android 16 和 Android 17 另外提供了 `aosp/` 本地快照，并在同目录的 `sources.json` 中记录固定标签、AOSP 提交、抓取日期、文件清单和 SHA-256 校验值。快照范围是定位相关的 AIDL、公开定位类，以及 `LocationManagerService` 和相关服务类；它不是完整的 `frameworks/base` 镜像，也不参与构建。

```text
refs/17.0.0/aosp/location/java/android/location/
refs/17.0.0/aosp/services/core/java/com/android/server/location/
refs/17.0.0/sources.json
```

使用 `python scripts/sync-android-refs.py --verify` 可离线验证当前快照。需要更新固定标签时使用 `--version 17.0.0` 或其他已支持版本重新抓取；脚本会校验 Gitiles blob ID，并拒绝覆盖被手工修改的快照文件。源码版权和 Apache 2.0 许可声明随 AOSP 文件保留；重新分发前仍需核对 AOSP 的 NOTICE 要求。

## 后续维护

1. 从 [AOSP frameworks/base 发布标签](https://android.googlesource.com/platform/frameworks/base/+refs) 确认目标版本的实际标签；新增版本目录并更新上表，不根据版本号猜测分支或标签。
2. 核对 `ILocationManager`、定位 / GNSS / NMEA 回调接口，以及 `LocationManager` 和 `LocationManagerService` 的实现；将比较结论限定到实际检查的文件和标签。
3. 记录固定标签、核验日期和所需的源码链接。涉及 QPR、安全补丁或厂商 ROM 时，另行记录准确基线，不把首发 AOSP 当作全部设备实现。
4. 结合 [Android 版本与行为变更文档](https://developer.android.com/about/versions) 核对公开 API 和运行行为；在设备上验证后，再更新项目的兼容性说明。

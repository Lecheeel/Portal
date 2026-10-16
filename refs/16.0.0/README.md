# Android 16 / API 36

AOSP 基线：`android-16.0.0_r1`，对应 `frameworks/base` 提交 `99b01a65cc4c104933788b3143285ab6bae65827`。最后核验：2026-10-16。

## 源码入口

- [定位框架源码](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/location/java/android/location/)
- [ILocationManager.aidl](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/location/java/android/location/ILocationManager.aidl)：定位服务 Binder 接口。
- [ILocationListener.aidl](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/location/java/android/location/ILocationListener.aidl)：定位结果、Provider 状态与 flush 回调。
- [IGnssStatusListener.aidl](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/location/java/android/location/IGnssStatusListener.aidl)：GNSS 状态回调。
- [IGnssNmeaListener.aidl](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/location/java/android/location/IGnssNmeaListener.aidl)：NMEA 回调。
- [LocationManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/location/java/android/location/LocationManager.java)：客户端 API 与监听器封装。
- [LocationManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/location/LocationManagerService.java)：系统定位服务实现。
- [Build.VERSION_CODES](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/os/Build.java)：`BAKLAVA = 36`。

## 版本核对

本次逐字节对照 `android-15.0.0_r1`，`ILocationManager.aidl` 内容相同。其他文件与运行行为需要分别核对，不能由这一个文件推断整体兼容性。

该目录以 Android 16 首发源码为基线；QPR 和安全更新应选择相应的固定标签重新比较。

- [Android 16 官方说明](https://developer.android.com/about/versions/16)
- [影响所有应用的行为变更](https://developer.android.com/about/versions/16/behavior-changes-all)
- [以 Android 16 为目标的行为变更](https://developer.android.com/about/versions/16/behavior-changes-16)

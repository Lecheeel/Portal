# Android 17 / API 37

AOSP 基线：`android-17.0.0_r1`，对应 `frameworks/base` 提交 `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`。最后核验：2026-10-16。

## 源码入口

- [定位框架源码](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/location/java/android/location/)
- [ILocationManager.aidl](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/location/java/android/location/ILocationManager.aidl)：定位服务 Binder 接口。
- [ILocationListener.aidl](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/location/java/android/location/ILocationListener.aidl)：定位结果、Provider 状态与 flush 回调。
- [IGnssStatusListener.aidl](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/location/java/android/location/IGnssStatusListener.aidl)：GNSS 状态回调。
- [IGnssNmeaListener.aidl](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/location/java/android/location/IGnssNmeaListener.aidl)：NMEA 回调。
- [LocationManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/location/java/android/location/LocationManager.java)：客户端 API 与监听器封装。
- [LocationManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/services/core/java/com/android/server/location/LocationManagerService.java)：系统定位服务实现。
- [Build.VERSION_CODES](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/core/java/android/os/Build.java)：`CINNAMON_BUN = 37`。

## 与 Android 16 基线的比较

对照标签为 `android-16.0.0_r1` 与 `android-17.0.0_r1`，比较范围如下。

| 文件 | 本次核对结果 |
|---|---|
| `ILocationManager.aidl` | 文件内容逐字节相同。 |
| `ILocationListener.aidl` | 接口声明相同；`{@hide}` 注释改为 `@hide`。 |
| `IGnssStatusListener.aidl` | 接口声明相同；`{@hide}` 注释改为 `@hide`。 |
| `IGnssNmeaListener.aidl` | 接口声明相同；`{@hide}` 注释改为 `@hide`。 |
| `LocationManager.java` | 文件内容有变化，需单独比较客户端实现。 |
| `LocationManagerService.java` | 文件内容有变化，需单独比较服务端实现。 |

上述结果只覆盖列出的文件和固定标签，不等于运行行为、权限规则或厂商 ROM 实现相同。项目已将 `compileSdk` / `targetSdk` 配置为 37，但本次维护的是参考资料，未新增设备或后端兼容性验证。

- [Android 17 官方说明](https://developer.android.com/about/versions/17)
- [影响所有应用的行为变更](https://developer.android.com/about/versions/17/behavior-changes-all)
- [以 Android 17 为目标的行为变更](https://developer.android.com/about/versions/17/behavior-changes-17)

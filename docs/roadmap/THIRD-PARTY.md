# 第三方依赖与许可证清单

> 用途：P0-05 / X-03 的依赖治理。新增依赖必须先更新本表，再进入代码评审。
> 约束：不引入 GPL 运行时依赖；不复制 GPL/无许可证项目的实现；不确定许可证时标 `需复核`，并阻止合并。

## 0. 上游项目

| 项目 | 许可证 | 用途 | 义务 |
|---|---|---|---|
| [ella8192/Portal](https://github.com/ella8192/Portal) | Apache-2.0 | 本仓库基于其代码/UI 演化 | 保留 `LICENSE`/`NOTICE` 与版权声明；修改文件需标注 |

## 1. 构建与语言

| 依赖 | 版本 | 用途 | 许可证 | 备注 |
|---|---|---|---|---|
| Android Gradle Plugin | 9.3.1 | 构建 | Apache-2.0 | |
| Kotlin / Kotlin Serialization | 2.4.10 | 语言/序列化 | Apache-2.0 | |
| Gradle | 9.7.0 | 构建 | Apache-2.0 | wrapper 分发 |
| JUnit 4 | 4.13.2 | JVM 测试 | EPL-1.0 | 仅 test |

## 2. AndroidX / Material

| 依赖 | 用途 | 许可证 |
|---|---|---|
| androidx.core:core-ktx | 兼容/工具 | Apache-2.0 |
| androidx.appcompat | UI 基础 | Apache-2.0 |
| androidx.constraintlayout | 布局 | Apache-2.0 |
| androidx.lifecycle:* | 生命周期/ViewModel | Apache-2.0 |
| androidx.navigation:* | 页面导航 | Apache-2.0 |
| androidx.recyclerview | 列表 | Apache-2.0 |
| androidx.test.ext:junit、espresso-core | 设备测试 | Apache-2.0 |
| com.google.android.material:material | Material 组件 | Apache-2.0 |

## 3. 运行时功能依赖

| 依赖 | 用途 | 许可证 | 备注 |
|---|---|---|---|
| net.sf.geographiclib:GeographicLib-Java 2.1 | 测地线/距离 | MIT | `:core` 使用 |
| org.jetbrains.kotlinx:kotlinx-coroutines-core 1.10.2 | 协程 | Apache-2.0 | `:core` |
| org.jetbrains.kotlinx:kotlinx-serialization-json | JSON | Apache-2.0 | |
| io.github.vvb2060.ndk:dobby 1.2 | inline hook | Apache-2.0 | native，实验能力 |
| io.github.libxposed:api 102.0.0 | 原生 Xposed 模块 API | [Apache-2.0（官方 102.0.0 LICENSE）](https://github.com/libxposed/api/blob/102.0.0/LICENSE) | 正式模块 compileOnly；JVM 测试 testImplementation；API 类由框架提供，不打入 APK；已移除 legacy API 82 |
| com.tencent.bugly:crashreport 4.1.9.3 | 崩溃上报 | 腾讯商业条款 | 默认关闭；P6-04 评估移除 |
| com.amap.api:3dmap-location-search | 高德地图/定位/搜索 | 高德商业条款 | 需要 Key/配额；不抓取/再分发地图数据 |
| com.google.android.gms:play-services-location 21.3.0 | GMS Fused Mock | Google 条款 | 仅测试用途；无 GMS 设备走系统路径 |

## 4. 数据与服务

| 来源 | 用途 | 许可/条款 | 状态 |
|---|---|---|---|
| OpenCellID | 基站数据库（实验） | CC BY-SA 4.0 | 未引入；P2-06 前评估 |
| WigLE | WiFi 数据库（实验） | 需查 API/数据条款 | 未引入 |
| 高德/百度 SDK | 地图/路线 | 商业条款 | 已引入 SDK，数据不打包 |
| AOSP `refs/` | Hook 兼容参考 | Apache-2.0 | 仅参考，保留 NOTICE |

## 5. 维护规则

1. 新依赖提交前：用途、许可证、体积、可替代性四件事写进 PR。
2. 许可证为 `需复核` 或 GPL/AGPL/无许可证的依赖默认不合并。
3. 移除依赖时同步删除本表条目。
4. 本表与 [07-risks-and-licensing.md](07-risks-and-licensing.md) 的许可证矩阵保持一致。

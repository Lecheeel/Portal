# Stable / Beta 发布流程

## 通道规则

- 正式版：GitHub Release 的 `prerelease=false`。Portal 的正式通道只检查这些版本。
- Beta / Canary：GitHub Release 的 `prerelease=true`。Portal 的 Beta 通道检查 Beta 和正式版，并选择版本最高的可用 Release。
- Draft Release 不参与更新检查。
- Beta 和正式版必须使用相同 `applicationId`、相同历史签名证书，以及全局递增的 Android `versionCode`。
- 推荐继续使用现有数字 tag 格式，例如 `v1.8.0.r12.abcdef0`，通过 GitHub Release 的 Pre-release 标志区分通道。构建也支持 `-beta.N`、`-canary.N` 和 `-rc.N`；版本名称不替代 Android `versionCode` 的升级判断。

## 推荐发布步骤

1. 在同一个提交上构建并签名三个 ABI APK。
2. 使用全局递增的 `APP_VERSION_CODE`，运行现有签名、证书、版本和 ABI 校验。
3. 创建 GitHub Release，上传本次校验通过的三个 APK，先勾选 **Set as a pre-release**。
4. 自己在 Portal 设置中选择“Beta 测试版”，检查并安装该 APK，验证核心功能、升级、数据迁移和设备兼容性。
5. 测试通过后，在 GitHub Release 页面取消 **Set as a pre-release**，将同一个 Release 晋升为正式版。不要重新构建或替换 APK。
6. 稳定通道用户只会看到正式 Release；Beta 用户会看到 Beta，Release 晋升后仍识别为同一构建，不会重复安装。

## 版本号注意事项

Android 不允许安装较低 `versionCode` 的 APK 覆盖较高版本。因此如果 Beta 使用了 `versionCode=1790000020`，后续正式版必须使用同一个或更高的 versionCode；最稳妥的做法是直接把同一个 Release 晋升为正式版。

Beta 用户切回正式通道不会自动降级。如果正式通道当前版本低于已安装 Beta，应用不会提示安装旧正式版；等正式版 versionCode 追上后再更新。

## 本地构建

```text
cmd.exe /d /c gradlew.bat :app:assembleRelease :app:exportReleaseApks -PAPP_VERSION_NAME=1.8.0 -PAPP_VERSION_CODE=1790000020 -PBUILD_REVISION=<commit>
python scripts/verify_release_apks.py --version 1.8.0 --version-code 1790000020 --channel beta
```

正式签名只在 GitHub Actions 使用既有 Secrets 完成。当前工作只实现应用通道和本地/CI 校验，不自动创建 Release、上传资产或修改 GitHub 远端状态。

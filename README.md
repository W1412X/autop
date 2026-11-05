# autop

Android 手机自动化 Agent：无障碍 + 通知监听 + LLM 编排，跑在手机上替你点手机的执行体。

## 构建

- 环境：JDK 21（`gradle/gradle-daemon-jvm.properties` 指定）、Android SDK（compileSdk 37）
- Debug：`./gradlew :app:assembleDebug`
- Release：`./gradlew :app:assembleRelease`
  - 签名读仓库根 `keystore.properties`（`storeFile/storePassword/keyAlias/keyPassword`，已 gitignore）
  - 缺该文件时产物为未签名包，不影响构建

## CI

push 到 `main` 触发 `.github/workflows/release.yml`：质量门禁（test / detekt / ktlint）→ 构建 release APK → 上传 artifact（**只含最终 APK**）。

签名靠仓库 Secrets：`ANDROID_KEYSTORE_BASE64`（keystore base64）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；缺失时降级为未签名包并告警。

## 现成 APK

http://39.101.160.55/apk/

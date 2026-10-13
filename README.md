# 双文对照（TwoTextCompare）

一款轻量、离线的 Android 双文本核对工具。默认上下双栏，支持切换左右双栏；两个文本区可独立编辑、滚动和粘贴。

## V1.0 当前范围

- 文本 A / 文本 B 两个独立的多行输入区。
- 上下与左右布局手动切换；设备旋转时自动切换为适合当前方向的布局。
- 每个文本区独立粘贴、清空，显示字符数量。
- 交换两段文字；全部清空前二次确认。
- 文字放大/缩小、左对齐/居中。
- 旋转屏幕时保留两段文字和显示设置。
- 本地处理，无网络权限、无账号、无第三方运行时依赖。

自动相似度百分比、彩色差异标记、历史记录、云端同步和文件导入不在此版本范围内。

## 在 Android Studio 中构建

1. 使用 Android Studio 打开此目录。
2. 使用 JDK 17，安装 Android SDK Platform 35 和 Build Tools 35.0.0。
3. 等 Gradle Sync 完成后，运行 `:app:assembleDebug`。
4. Debug APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`。

构建配置使用 Android Gradle Plugin 8.7.3 和 Gradle 8.9。此项目不包含 Gradle Wrapper 二进制文件。

## 通过 GitHub Actions 构建 APK

把此项目内的所有文件直接上传到 GitHub 仓库根目录，默认分支使用 `main` 或 `master`。工作流文件位于 `.github/workflows/build-apk.yml`，推送相关源码后会自动构建；也可以在 GitHub 仓库的 **Actions → Build Android APK → Run workflow** 手动启动。

构建成功后，打开对应工作流运行记录，在 **Artifacts** 区下载 `twotext-compare-debug-apk`，解压即可得到可安装的 Debug APK。Debug APK 由 Android Gradle 插件自动使用调试签名，不等同于用于 Google Play 发布的正式签名包。

## 应用权限与数据

Manifest 未声明网络权限。用户输入的文本不上传、不自动保存为永久历史记录。文本仅作为当前界面状态保留，并在系统重建 Activity（例如旋转屏幕）时恢复；字体大小、对齐和布局偏好保存在应用本地设置中。

## 技术说明

- 原生 Android Java Activity；不使用 WebView。
- `minSdk 23`，`targetSdk 34`，`compileSdk 35`。
- 包名：`com.noteduo.textcompare`（Debug 构建增加 `.debug` 后缀）。
- 当前版本只做人工核对，尚未实现自动文字差异算法。

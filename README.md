# 抖音沉浸播放

<img src="docs/douxianren-icon.png" alt="图标" width="160">

面向抖音 Android 客户端的 LSPosed Modern API 102 沉浸式播放模块。播放视频时只保留视频画面，
通过悬浮“退/进”按钮随时退出或进入沉浸模式。

## 主要功能

- “沉浸式播放”开关：关闭后保留抖音原界面。
- 播放视频时隐藏视频画面以外的抖音界面和系统栏，视频画面扩展至全屏。
- 沉浸时屏蔽隐藏按钮及其子控件的触摸响应，保留侧面长按原生快进／快退和上下滑动切换视频。
- 中间长按不再拦截：长按屏幕中间时自动退出沉浸，正常弹出抖音原生操作菜单。
- 暂停／播放状态与沉浸模式互不影响：暂停视频不会恢复界面，继续播放也无需重新进入。
- 右上角悬浮圆形按钮“退／进”一键切换；切换视频时立即移动一次，平时每隔随机 10～20 秒
  向下移动半个按钮高度，到底后再逐步向上往返；按钮文字仅 5% 透明度，避免烧屏。

## 兼容范围

| 项目 | 当前支持 |
| --- | --- |
| LSPosed API | Modern API 102 |
| Android | 9（API 28）及以上 |
| 默认作用域 | `com.ss.android.ugc.aweme` |
| 模块包名 | `com.wum.dyimmersive` |
| 模块版本 | 2.0.0（独立版本，不随上游仓库更新） |

抖音升级后界面层级可能变化，届时需要重新适配。

## 安装

1. 从 [`dist`](dist) 目录下载 [`douyin-immersive-2.0.0.apk`](dist/douyin-immersive-2.0.0.apk)。
2. 在手机上安装 APK。
3. 在 LSPosed 中启用本模块，保持默认作用域“抖音”。
4. 强制停止并重新打开抖音。

> 本模块使用独立包名 `com.wum.dyimmersive` 和独立版本号，与原项目互不覆盖，
> 模块管理器不会提示上游更新。如曾安装旧版 `com.zz.douyin`，请先在 LSPosed 中禁用或卸载旧版，
> 再启用新版，避免两个模块同时作用于抖音。

## 使用方式

- “沉浸式播放”默认开启。
- 进入视频播放页自动进入沉浸模式；右上角悬浮按钮显示“退”。
- 点击“退”恢复完整抖音界面（视频继续播放），按钮变为“进”；点击“进”重新隐藏界面。
- 上滑切换到下一个视频时自动重新进入沉浸模式，按钮同时下移半个按钮高度；
  平时每隔随机 10～20 秒移动一次，向下逐步移动到底后再逐步向上。
- 长按屏幕中间会退出沉浸并弹出抖音原生菜单；侧面长按仍是原生快进／快退。
- 设置页开关通过 LSPosed Remote Preferences 同步到抖音进程；未立即生效时强制停止抖音后重开。

## 本地构建

构建环境：

- JDK 17
- Android SDK 37
- PowerShell 或其他可运行 Gradle Wrapper 的终端

执行完整检查和发布构建：

```powershell
.\gradlew.bat clean lintRelease test assembleRelease
```

Gradle 中间产物写入系统临时目录下的 `douyin-immersive-gradle/app`，最终构建产物位于该目录的
`outputs/apk/release` 下。仓库中可直接安装的版本保存在 [`dist`](dist) 目录。

## 项目结构

```text
app/src/main/java/com/zz/douyin/
├── MainActivity.java
├── ModuleApplication.java
├── ModulePreferences.java
└── hook/
    ├── DouyinModule.java
    ├── ImmersiveTouchGuard.java
    ├── ImmersiveTouchPolicy.java
    └── ImmersiveUi.java
```

运行日志标签为 `DouyinImmersive`，可通过以下命令查看：

```shell
adb logcat -s DouyinImmersive
```

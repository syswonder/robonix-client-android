# Robonix Android Client

![Robonix 犀牛图标](assets/robonix-mark.svg)

[![Latest release](https://img.shields.io/github/v/release/syswonder/robonix-client-android?label=%E4%B8%8B%E8%BD%BD%20APK)](https://github.com/syswonder/robonix-client-android/releases/latest)

Robonix 的 Android 手机客户端：和机器人对话、下发任务，查看感知画面、RTDL 计划、音频和硬件健康状态。

## 下载安装

1. 打开 **[最新 Release](https://github.com/syswonder/robonix-client-android/releases/latest)**，下载 `robonix-client-android-v*.apk`。
2. 在手机上打开 APK。系统提示时，允许浏览器或文件管理器「安装未知来源应用」。
3. 首次打开时允许麦克风、相机和通知权限。语音对话需要麦克风权限。

要求 Android 8.0（API 26）及以上。历史版本在 [Releases](https://github.com/syswonder/robonix-client-android/releases) 页面。

> 升级时直接安装新版本 APK 即可，设置和本机保存的会话会保留。如果提示「签名不一致」或「与现有软件包冲突」，说明旧版本是用别的签名装的，需要先卸载（会清掉本机数据）再安装。

## 连接机器人

1. 手机和机器人连到同一个局域网（同一 WiFi）。
2. 进入「设置 → 连接」，在「机器人地址」填机器人的 IP。「Atlas 端口」默认 `50051`，一般不用改。
3. 点「连接」。Liaison、Executor 等其他服务的地址由 Atlas 自动发现，不需要手填。

连不上时，先确认机器人上的 Atlas 已经启动，并且手机能 ping 通机器人的 IP。

## 功能页面

| 页面 | 用途 |
| --- | --- |
| 对话 | 文字或语音和机器人对话、下发与干预任务 |
| 感知 | 相机、深度等感知画面 |
| RTDL | 查看当前执行的计划和步骤 |
| 音频 | 选择麦克风、扬声器、ASR/TTS 节点 |
| 健康 | 硬件健康状态和 3D 部件视图 |
| 设置 | 连接地址、语言、开发者日志等 |

反馈问题时，可在「设置 → 开发者日志」中复制日志一起附上。

## 从源码构建

只想使用的话不需要构建，直接下载 Release 即可。开发需要：

- JDK 17
- Android SDK（compileSdk 34），或 Android Studio Hedgehog (2023.1.1) 及以上
- Windows 上可以运行 `.\setup.ps1` 一键安装 JDK 和 SDK

```bash
./gradlew assembleDebug     # 输出 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # 输出 app/build/outputs/apk/release/app-release.apk
```

`.proto` 文件在构建时由 protobuf 插件自动编译。首次构建要下载较多依赖，耗时会比较长。

### Release 签名

签名配置放在 `keystore/release.properties`，这个文件不进仓库：

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

没有这个文件时，`assembleRelease` 会改用 debug 签名。这样打出来的包和 Release 页面上的签名不同，已安装正式版的手机不能覆盖安装。

### 发布新版本

1. 修改 `app/build.gradle.kts` 中的 `versionCode`（加 1）和 `versionName`。
2. 用正式签名运行 `./gradlew assembleRelease`，把 APK 重命名为 `robonix-client-android-v<版本>.apk`。
3. 在 GitHub 创建 tag 为 `v<版本>` 的 Release，并上传 APK。

## 项目结构

```
app/src/main/
├── proto/                    # gRPC 接口定义
└── java/com/robonix/client/
    ├── data/
    │   ├── grpc/             # Atlas / Liaison / Executor 客户端
    │   ├── audio/            # 录音、播放、音频桥 + 前台服务
    │   ├── local/            # DataStore 设置持久化
    │   └── model/            # 数据模型
    ├── domain/               # Repository 层
    └── ui/                   # Compose 页面：chat / perception / audio / vitals / settings …
```

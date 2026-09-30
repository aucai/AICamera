# AI拍照助手

一个安卓相机 App，拍照时实时给出**构图、姿势、光线**建议。AI 模型（MediaPipe 人体姿态识别）在手机本地运行，不联网。

## 功能

| 模式 | 做什么 |
| --- | --- |
| 智能 | 所有提示一起看，取最重要的两条 |
| 构图 | 九宫格 / 黄金分割 / 中心网格；水平仪（俯拍变成气泡水平仪）；标出人物该放的位置并画箭头；头顶留白、切到膝盖或脚踝、全身照显腿长提示 |
| 姿势 | 显示人体骨架；提示肩膀放平、身体侧转 30°、手臂离开身体、重心放一条腿 |
| 光线 | 太暗、大片过曝（红色斜纹标出）、逆光（一键对人物测光）、阴阳脸、偏暖/偏冷；亮度直方图和曝光补偿滑条 |

其他：快门按钮外圈显示实时评分；语音播报提示；3 秒 / 10 秒定时；音量键拍照；点击对焦测光；双指缩放；照片保存在相册 `Pictures/AICamera`。

需要 Android 10 及以上。

## 安装

在 GitHub 仓库的 **Releases → latest** 下载 `AICamera.apk`，用手机打开安装（需要允许"安装未知来源应用"）。
每次构建都用仓库里同一个签名，新版本可以直接覆盖安装。

> `keystore/debug.keystore` 是公开的调试签名，只适合自己用，不要拿去上架应用商店。

## 自己编译

需要 JDK 17+ 和 Android SDK（compileSdk 36）。

```bash
./gradlew testDebugUnitTest   # 规则引擎单元测试
./gradlew assembleRelease     # 输出 app/build/outputs/apk/release/app-release.apk
```

国内网络如果下载依赖慢，可以在 `settings.gradle.kts` 的 `repositories` 里加上阿里云镜像。

## 代码结构

- `app/src/main/java/com/aucai/aicamera/core/`：纯 Kotlin 的规则引擎（构图、姿势、光线、水平、提示防抖、评分），有单元测试
- `camera/`：CameraX 帧分析、MediaPipe 姿态识别、重力传感器
- `ui/`：取景叠加层、评分快门按钮、语音播报
- `MainActivity.kt`：界面和相机控制

模型 `pose_landmarker_lite.task` 来自 [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)（Apache 2.0）。

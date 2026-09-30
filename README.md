# AI拍照助手

一个安卓相机 App，拍照时实时给出**构图、姿势、光线**建议。AI 模型（MediaPipe 人体姿态识别）在手机本地运行，不联网。

## 功能

- **AI 自动取景**：识别主体（人物、猫狗、食物、花草等 80 类物体、风景地平线），按三分法 / 黄金分割 / 中心构图算出最佳裁剪，取景器里实时显示（框外变暗），拍下来保存的就是框内画面。可选 3:4、1:1、9:16 或不裁剪。
- **说明为什么这样取景**：例如"人物朝右看，放在左侧三分线，前方留出视线空间""地平线在下三分线，突出天空"。
- **检查清单**：构图、主体完整、水平、光线、姿势、手机稳定，逐项 ✓ / ✗，快门上显示几项达标。
- **只在裁剪救不回来时才让你动手机**：人太靠边、头顶或脚被切、底边切在膝盖等。
- **姿势建议**：肩膀放平、身体侧转 30°、手臂离开身体、重心放一条腿。
- **光线分析**：太暗、过曝（红色斜纹）、逆光（一键对人物测光）、阴阳脸、偏暖 / 偏冷；曝光补偿滑条。
- **清晰度**：最高画质拍摄，按快门先对焦主体、等手机稳住再拍；支持厂商画质增强（HDR / 夜景）的手机会自动启用；拍完检查是否糊了。
- **App 内相册**：左右滑看照片、分享、删除，查看每张照片拍摄时的识别结果、取景理由和检查清单。
- 其他：语音播报、3 秒 / 10 秒定时、音量键拍照、点击对焦、双指缩放。照片保存在相册 `Pictures/AICamera`。

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

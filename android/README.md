# 隔空手控（AirGesture）Android App（v0.1.0）

独立运行的 Android 原生 App：手机前置摄像头采集 → MediaPipe 端内识别 → 捏合手势状态机
→ 无障碍服务注入触摸（操作任意 App）。**不需要电脑、不需要 adb、不需要 IP Webcam。**

## 构成

```
android/
├─ app/src/main/
│  ├─ AndroidManifest.xml
│  ├─ assets/gesture_recognizer.task      # 与原型同一个 MediaPipe 模型（构建时自动拷贝）
│  ├─ res/xml/accessibility_service_config.xml
│  └─ java/com/airgesture/app/
│     ├─ MainActivity.kt                  权限引导 / 启停 / 状态 / 调试按钮
│     ├─ AppConfig.kt                     手势参数（与原型 v0.4 对齐）
│     ├─ AppState.kt                      轻量状态共享
│     ├─ core/Models.kt                   Hand / PerceptionResult / ActionIntent
│     ├─ camera/CameraController.kt       CameraX 前置 640x480 ImageAnalysis
│     ├─ camera/ImageConverter.kt         RGBA -> 正向/镜像 Bitmap + 亮度
│     ├─ perception/HandPerceiver.kt      MediaPipe GestureRecognizer（LIVE_STREAM）
│     ├─ perception/DynamicGestureExtractor.kt  捏合连续甩动状态机
│     ├─ mapping/GestureGate.kt           IDLE/ARMED/ESTOP、冷却、防误触
│     ├─ action/AirGestureAccessibilityService.kt  dispatchGesture / performGlobalAction
│     └─ service/AirGestureService.kt     前台服务（camera 类型）+ 通知
└─ keystore/debug.keystore                 项目内 debug 签名（保证可重复构建）
```

## 构建

本目录内自带构建脚本（需要 `..\scripts\setup_android.ps1` 安装过的便携工具链）：

```powershell
powershell -ExecutionPolicy Bypass -File ..\scripts\build_apk.ps1
```

产物：`..\dist\隔空手控-v0.1.0-debug.apk`

### 本机环境已踩过的坑（已写入脚本/配置）

| 问题 | 处理 |
| --- | --- |
| Gradle native services 加载 DLL 失败 | 构建脚本设置 `GRADLE_OPTS=-Dorg.gradle.native=false` |
| 项目目录含中文，AGP 拒绝构建 | `android.overridePathCheck=true` |
| 用户目录 `.android` 不可写导致签名失败 | 项目内 `keystore/debug.keystore`，`signingConfigs.debug` 指向它 |
| 中文 SDK 路径写 `local.properties` 会乱码 | 构建脚本改用 `ANDROID_HOME` 环境变量 |

## 安装与使用

```powershell
powershell -ExecutionPolicy Bypass -File ..\scripts\install_apk.ps1
```

手机上需要：

1. 授予相机/通知权限；
2. 在系统无障碍设置里开启 **AirGesture 手势控制**（Android 13+ 侧载 App 可能需要先在
   应用信息里点「允许受限设置」）；
3. 打开 App → 启动手势服务；
4. 前置摄像头拍到整个手掌后：拇指+食指捏合保持 → 向上/下/左/右甩动 = 对应滑动；
   捏住期间可连续甩；竖大拇指 = 双击（点赞）；张开手掌 0.8s = 激活/待机切换；
   握拳 2s 或用手遮镜头 = 急停。

## 测试

手势状态机（`DynamicGestureExtractor` + `GestureGate`）是纯 Kotlin 逻辑，有 14 项 JVM 单元测试：

```powershell
powershell -ExecutionPolicy Bypass -File ..\scripts\test_android.ps1
```

测试覆盖：捏合两帧确认、180ms 保护窗口、连续甩动回位/冷却、松开两帧、四向映射、
激活/待机、握拳急停、遮镜头急停、静态手势冷却与动态抑制、动态冷却。

> 说明：本机项目路径含中文，Gradle 的 test worker 会因 Java @argfile 编码问题起不来，
> 所以 `test_android.ps1` 先用 Gradle 编译测试类，再用 `java -cp ... JUnitCore` 直接跑。

## 诊断

服务运行后，App 状态区会显示：门控状态、识别手势、手是否入镜、最近动作、**识别帧数**、
**画面亮度**、相机 fps。可用于排查：

- `识别帧` 持续增长 → MediaPipe 推理回调正常；
- `亮度` 低于 20 持续 0.8s → 会触发遮镜头急停（属正常设计）；
- `手：有/手势：xxx` → 手部检测与分类正常。

## 内置自检

调试区有「**自检：识别内置手势图（验证 MediaPipe）**」按钮：用 IMAGE 模式对内置手部照片
跑同款 `gesture_recognizer.task`，状态区会显示类似
`自检：通过：手 1 只 / 关键点 21 / 手势 Thumb_Up 0.65`。

用于在没有真人手时也能验证「Android 端 MediaPipe 模型加载 + 手部关键点 + 手势分类」链路。

> 自检图：Wikimedia Commons `Hitchhiker's thumb.jpg`，作者 Alexis Lours，CC BY 4.0；
> 说明见 `app/src/main/assets/SELFTEST_IMAGE_LICENSE.txt`。



- `versionName = 0.1.0`，`versionCode = 2`
- 包名：`com.airgesture.app`
- minSdk 26（Android 8.0），targetSdk 34
- ABI：`arm64-v8a`（只打 64 位 ARM 以减小包体，目标机型 vivo V2509A）

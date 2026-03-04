# ScrollSnap Shizuku 开发参考（基于官方 README）

本文用于项目内快速查阅，内容来源于 Shizuku 官方仓库文档与 `shizuku-api` 文档。

## 官方文档入口

- Shizuku 主仓库 README: https://github.com/RikkaApps/Shizuku/blob/master/README.md
- Shizuku API（开发说明）README: https://github.com/RikkaApps/Shizuku-API/blob/master/README.md

## 关键结论（和本项目直接相关）

1. 不使用 Accessibility 的前提下，可通过 Shizuku 获取系统级能力（shell/binder）。
2. 从 Shizuku v13 开始，应用侧推荐通过 `ShizukuProvider` 与服务端建立 binder 通道。
3. `Shizuku.newProcess(...)` 在较新 API 中不再是公开可直接调用接口（项目中已做兼容处理）。
4. 典型权限流程为：
   - 检测 binder 是否可用（`Shizuku.pingBinder()`）
   - 检测授权（`Shizuku.checkSelfPermission()`）
   - 未授权时请求（`Shizuku.requestPermission(requestCode)`）

## 必要依赖

Gradle 依赖（应用模块）：

- `dev.rikka.shizuku:api:13.1.5`
- `dev.rikka.shizuku:provider:13.1.5`

## Manifest 必要项（本项目当前采用）

1. 权限声明：
   - `moe.shizuku.manager.permission.API_V23`
2. Provider 声明：
   - `android:name="rikka.shizuku.ShizukuProvider"`
   - `android:authorities="${applicationId}.shizuku"`
   - `android:exported="true"`
   - `android:multiprocess="false"`
   - `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`

## 应用侧初始化与状态监听

推荐最小流程：

1. 注册监听：
   - `Shizuku.addBinderReceivedListenerSticky(...)`
   - `Shizuku.addBinderDeadListener(...)`
   - `Shizuku.addRequestPermissionResultListener(...)`
2. 主动请求 binder（非 provider 进程场景）：
   - `ShizukuProvider.requestBinderForNonProviderProcess(context)`
3. 刷新状态：
   - `Shizuku.pingBinder()`
   - `Shizuku.getBinder() != null`
   - `Shizuku.checkSelfPermission()`

## ScrollSnap 当前接入点

- Shizuku 状态管理：`app/src/main/java/com/scrollsnap/core/shizuku/ShizukuManager.kt`
- Shell 执行器：`app/src/main/java/com/scrollsnap/core/shizuku/ShizukuShellExecutor.kt`
- UI 调试入口：`app/src/main/java/com/scrollsnap/MainActivity.kt`

## 常见问题排查清单

1. `Shizuku Binder: Disconnected`
   - 确认手机端 Shizuku 已启动且“正在运行”
   - 确认 APP 是最新安装包（Manifest 包含 `ShizukuProvider`）
   - 在应用内执行 `Refresh Status` 与 `Force Request Binder`
2. `Permission` 一直 `Not granted`
   - binder 先必须 `Connected`
   - 然后 `Request Shizuku Permission` 才能点击
3. 设备/ROM 差异
   - 某些 ROM 对跨进程 provider/broadcast 行为有额外限制
   - 需要通过状态文本中的 `requestBinderError` 进一步定位

## 与 ScrollSnap 功能关联

在 binder + permission 就绪后，本项目通过 Shizuku 执行：

- 自动滚动：`input swipe ...`
- 抓帧：`screencap -p`
- 拼接：OpenCV ORB + native fallback


# ScrollSnap

ScrollSnap 是一个基于 Android + Shizuku 的长截图工具：通过自动滑动与连续截图，拼接生成长图并保存到相册。

## 功能

- 悬浮球快速触发截图
- 自动滑动 + 连续捕获
- OpenCV + Native 双路径重叠估计拼接
- 支持手动停止（双击顶部区域）
- 设置页支持语言、拼接参数与调试模式

## 技术栈

- Kotlin + Jetpack Compose
- Android Foreground Service / TileService
- Shizuku（执行 `input swipe` / `screencap`）
- OpenCV + JNI（C++）

## 环境要求

- Android Studio（含 JDK 17）
- Android SDK（`compileSdk=35`）
- 可用的 Shizuku 服务与授权

## 本地构建

```powershell
.\gradlew.bat :app:assembleDebug
```

## Release 构建（小范围分发）

1. 复制 `keystore.properties.example` 为 `keystore.properties`
2. 填写签名参数（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）
3. 执行：

```powershell
.\gradlew.bat :app:assembleRelease
```

说明：
- `keystore.properties` 和 `*.jks` 已加入 `.gitignore`，不会入库
- 当前正式版本：`v1.0.0`（`versionCode=10000`）
- 后续发版需递增 `versionCode`

## 运行前准备

1. 安装并启动 Shizuku
2. 打开 App，完成引导授权：
   - 悬浮窗权限
   - 通知权限（Android 13+）
   - Shizuku 权限
3. 点击“打开悬浮按钮”开始使用

## 调试模式

- 设置页提供“调试模式”开关
- 开启后，每次截图会将诊断日志自动复制到剪贴板

## 项目结构

- `app/src/main/java/com/scrollsnap/core/capture`：捕获与流水线
- `app/src/main/java/com/scrollsnap/core/stitch`：拼接算法与参数
- `app/src/main/java/com/scrollsnap/core/shizuku`：Shizuku 执行层
- `app/src/main/java/com/scrollsnap/feature/control`：悬浮窗/控制服务
- `app/src/main/java/com/scrollsnap/ui/theme`：主题与配色

## 注意事项

- 长截图质量受页面动态内容、滚动速度、设备性能影响
- 不同厂商 ROM 的截图/输入行为存在差异
- Release 构建已开启混淆与资源压缩，请保留 `proguard-rules.pro` 规则

## 分发与更新

- 固定下载地址（GitHub Release）：https://github.com/li-junlei/scrollsnap/releases
- App 启动会自动检查 GitHub tag 版本更新
- 设置页支持手动检查更新，并提供：
  - 跳过此版本（该版本不再提醒）
  - 下载（打开对应 Release 页面）
  - 取消

## 文档

- 安装说明：`docs/INSTALL.md`
- 隐私说明：`docs/PRIVACY.md`

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

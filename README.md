# 词间 WordFlow 1.3 · Kokoro 语音缓存版

**[下载安卓 APK（约 149 MB）](https://github.com/480284856/EnglishLearningAPP/raw/refs/heads/downloads/kokoro-v1.3/WordFlow-1.3-Kokoro-cache-debug.apk)**

备用：[APK 文件页面](https://github.com/480284856/EnglishLearningAPP/blob/downloads/kokoro-v1.3/WordFlow-1.3-Kokoro-cache-debug.apk)，点击 **Download raw**。

支持 Android 8.0 及以上的 ARM64 / ARM32 手机。与交付的 1.0、1.1、1.2 使用相同签名，直接覆盖安装保留词表，不要先卸载旧版。已有模型也会复用。

## 新增语音缓存

- 首次正常听读并完整合成后自动保存语音，相同文本、声音、语速和模型版本再次播放时直接读取缓存，不再进行 Kokoro 推理。
- 点击 **预缓存词表**，提前生成当前词表的单词和例句；支持后台 / 息屏、进度通知及取消，已完成内容保留。
- 点击 **缓存管理** 查看段数和占用空间，或清理语音；不会删除词表及模型。
- 完整缓存上限 **256 MB**：普通播放淘汰较久未使用的内容；批量预缓存满额后停止，保留已完成内容。系统清理缓存后会重新生成。
- 不同语速独立缓存，重复文本自动复用。未完成、损坏的音频不会直接作为完整缓存播放。

内置 Kokoro 英语 INT8 模型，无需 API Key、联网、Google 服务和系统 TTS。首次准备及未命中的合成仍需等待；预缓存较大词表建议预留 1 GB 存储空间。Android 13 及以上首次预缓存会申请通知权限，允许后可以在通知栏查看和取消任务。

## 源码和验证

[下载应用源码](./WordFlow-Android-source.zip) · [SHA-256](./SHA256SUMS.txt)

编译、20 项 JUnit 测试、APK 签名及对齐校验通过。缓存测试涵盖持久化、语速隔离、取消、损坏校验、LRU 和容量限制。Linux 实际模型与应用缓存类联合验证通过：再次读缓存无需第二次合成，PCM 内容相同，取消的部分音频不会成为缓存。

尚未完成小米真机的音频输出、后台预缓存、通知授权和长期息屏验收。详细限制及构建步骤见源码包的 README 与 BUILD-VERIFICATION。

APK 由 Git LFS 存储；克隆本分支需要 Git LFS 和 `git lfs pull`。应用源码按 GPL-3.0-or-later，见 [LICENSE](./LICENSE)；第三方模型与运行库按各自原始许可，文本及对应源码地址在源码包 `app/src/main/assets/licenses/`。

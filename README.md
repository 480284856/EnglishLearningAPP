# 词间 WordFlow 1.2 · Kokoro 离线英语听读

## 下载并安装

**[下载安卓 APK（约 149 MB）](https://github.com/480284856/EnglishLearningAPP/raw/refs/heads/downloads/kokoro-v1.2/WordFlow-1.2-Kokoro-debug.apk)**

如果手机浏览器没有自动下载，请打开 [APK 文件页面](https://github.com/480284856/EnglishLearningAPP/blob/downloads/kokoro-v1.2/WordFlow-1.2-Kokoro-debug.apk)，点击 **Download raw / 下载原始文件**。

支持 Android 8.0 及以上的 ARM64 / ARM32 手机，包含 Kokoro-82M 英语 INT8 模型，无需 API Key、联网或系统英语 TTS 语音包。支持 CSV 词表导入、单词与例句顺序播放、息屏播放、通知栏控制、暂停续播、循环及语速调节。

与此前交付的 1.0 / 1.1 使用相同签名，可直接覆盖安装保留词表。不要先卸载旧版。首次打开等待模型准备完成，请预留至少 500 MB 空间；合成速度取决于设备。

## 源码与验证

- [下载应用源码](./WordFlow-Android-source.zip)：含构建说明、固定模型 / AAR 下载校验脚本及第三方许可。
- [SHA-256 校验值](./SHA256SUMS.txt)。APK 使用 Git LFS 存储；用 Git 克隆本分支时需安装 Git LFS 并执行 `git lfs pull`。
- 编译与 11 项 JUnit 测试通过；Android lint 无错误。同版 sherpa-onnx Linux 实际推理及应用 JNI 回调 / 取消验证通过。
- 尚未在安卓模拟器或小米真机完成音频及长期息屏验收。

本应用源码按 GPL-3.0-or-later 提供，见 [LICENSE](./LICENSE)。Kokoro 模型 / sherpa-onnx / Kotlin 按 Apache-2.0，ONNX Runtime 按 MIT，运行库内 eSpeak NG 按 GPL-3.0-or-later；原文和对应源码地址见源码包内 `app/src/main/assets/licenses/`。

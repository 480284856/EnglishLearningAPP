# WordFlow 1.5 · 英语离线听读

**[点击下载可安装 APK](https://github.com/480284856/EnglishLearningAPP/raw/refs/heads/downloads/kokoro-v1.5/WordFlow-1.5-study-debug.apk)**

Android 8.0 及以上，ARM64 / ARM32，约 149 MB，内置 Kokoro 离线模型。

直接覆盖安装，与此前交付版本签名相同，保留词表、例句、API 设置和缓存。不要先卸载。

## 新功能

- 模块化代码，独立词汇模型、导入合并、页面组件、动态生成与收听统计。
- 同一词表支持多选文件和继续追加；重复词忽略大小写合并，显示累计词频，保留不同例句。
- 没有例句的词、只有一列词汇的文件正常导入。
- 底部「整项播放」选择 1、2、3 次，单词加例句为一遍。
- 「个人」显示今日、累计和每日收听时长，从 1.5 开始统计；只计算实际播出音频，暂停和等待不计。
- 「个人」保存 DeepSeek / OpenRouter Key 后，可开启「播放时自动生成临时例句」并保存设置。每遍已有内容播完后，联网生成并朗读一条新例句；无例句的词在读完词后生成。临时文本和语音不保存。默认关闭，开启后会消耗 API 额度及流量，息屏播放也会调用。
- 无网络、缺 Key、生成失败或超时则跳过并继续，不自动重试或切换收费模型。手动生成仍可预览编辑后保存。

OpenRouter 默认 `openrouter/free`，仍需自己的 Key，额度以平台为准。

## 源码与验证

[可直接浏览的源码分支](https://github.com/480284856/EnglishLearningAPP/tree/source/modular-study-v1.5) · [架构说明](https://github.com/480284856/EnglishLearningAPP/blob/source/modular-study-v1.5/docs/ARCHITECTURE.md)

[下载源码 ZIP](https://github.com/480284856/EnglishLearningAPP/raw/refs/heads/downloads/kokoro-v1.5/WordFlow-Android-source.zip)。源码 GPL-3.0-or-later，不含模型和 AAR，内附固定版本下载 / 校验脚本；APK 已包含全部离线资源。

44 项单元测试、24 项独立 CSV 检查通过，构建与 lint 通过（0 错误 / 9 警告），签名和 16 KB 对齐检查通过。尚未完成小米真机与真实 API Key 联调。校验值见 SHA256SUMS.txt。

APK 使用 Git LFS 保存。如果下载到的是仅几行文字的文件，请用顶部下载链接，或文件页面的「Download raw file」，不要使用 GitHub 的源码打包下载来提取 APK。

# WordFlow 1.4 · 我的例句与 AI 生成

**[下载安卓安装包（约 149 MB）](https://github.com/480284856/EnglishLearningAPP/raw/refs/heads/downloads/kokoro-v1.4/WordFlow-1.4-examples-ai-debug.apk)**

[备用文件页面：点击 Download raw](https://github.com/480284856/EnglishLearningAPP/blob/downloads/kokoro-v1.4/WordFlow-1.4-examples-ai-debug.apk)

Android 8.0+，ARM64 / ARM32。与此前交付版本签名一致，直接覆盖安装保留词表和缓存，不要先卸载。

## 新增

- 首页显示词表，进入后查看单词列表，播放器固定在页面底部；保留息屏播放、语速、循环和语音缓存。
- 点开单词可查看导入例句，添加、编辑、删除自己的多条例句，原导入内容保留。
- CSV 支持空例句或单列单词；无例句时只读单词，直接进入下一词。
- 播放与预缓存包含「单词 → 导入例句（如有）→ 我的例句」。
- 「个人」中配置 DeepSeek / OpenRouter API Key，Key 本机加密保存，不回显，支持独立清除。
- 单词详情点「使用大模型生成例句」，生成后先预览并可编辑，确认保存才加入我的例句。

DeepSeek 使用官方当前推荐 `deepseek-flash`（关闭思考模式），按账户规则计费。OpenRouter 默认 `openrouter/free`，仍需自己申请 Key，免费额度 / 可用性以平台为准，不自动回退到收费模型。可手动设置 OpenRouter 模型 ID，自选模型可能收费。

[DeepSeek 官方文档](https://api-docs.deepseek.com/api/responses) · [OpenRouter 免费路由](https://openrouter.ai/openrouter/free/apps)

仅用户点击生成时发送当前单词给所选服务商，不发送整本词表，不后台自动生成。Kokoro 本地听读依然无需联网或 API Key。

## 源码与验证

[应用源码 ZIP](./WordFlow-Android-source.zip) · [SHA-256](./SHA256SUMS.txt) · [GPL-3.0-or-later](./LICENSE)

29 项单元测试及编译 / lint / 签名 / 对齐检查通过。覆盖缺失例句导入、旧数据兼容、多例句保存和播放、AI 请求 / 响应处理等。未使用真实账户 Key 调用服务；新 UI、KeyStore 设备端加解密和真实 API 认证 / 费用仍需手机验收。

APK 使用 Git LFS；克隆本分支后需执行 `git lfs pull`。源码包含构建步骤及固定模型下载校验脚本，第三方授权及源代码位置见源码包 `app/src/main/assets/licenses/`。

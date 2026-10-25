# WordFlow 1.4 验证

JDK 21 / Gradle 8.9 / AGP 8.7.3 / Android SDK 35。`testDebugUnitTest lintDebug assembleDebug` 成功。29 项 JUnit 测试通过，lint 0 errors / 9 warnings。

新增验证：单列词汇和空例句 CSV；自定义例句添加 / 编辑 / 删除不覆盖导入例句；旧 JSON 读取及多例句序列化恢复；服务快照内容比较；单词无例句时跳转下一词、多例句全部播放后再跳词；大模型固定 HTTPS 端点、选择模型和提示、禁止将 Key 放入 JSON 请求体、响应提取及截断 / 空响应拒绝。

首页词表、单词列表和底部播放器分离。个人主页支持 DeepSeek / OpenRouter Key 配置和独立清除，通过 Android KeyStore AES-GCM 加密保存，不回显 Key。仅用户点击才发送当前单词，结果先预览，确认保存为自定义例句。DeepSeek 使用官方当前推荐 deepseek-flash（关闭 thinking）；OpenRouter 默认 openrouter/free，不配置收费回退。

APK v2 签名与 1.3 一致，可覆盖安装，旧词表和语音缓存保留。16 KB zipalign 校验通过。包名 cn.wordflow.app，版本 1.4（5），minSdk 26 / targetSdk 35，ARM64 / ARM32。APK 大小 149291306 字节。SHA-256 `92600eaa24c936d8053974518baa1a7db51ac2ba79f86c763be04066639ded21`。

没有真实用户 API Key，未向服务商发送实际生成请求或产生计费。KeyStore 设备端加解密、新界面交互、真实 API 认证 / 限流 / 付费、息屏切换等尚未真机 / 模拟器验收。单元测试不能替代这些设备及真实账户检查。

官方接口依据： https://api-docs.deepseek.com/api/responses 和 https://openrouter.ai/openrouter/free/apps 。

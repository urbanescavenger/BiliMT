# hongguo-core

红果短剧 App 端数据与播放底层，移植自 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV)
的 `kotlin-tv/core` 模块（固定来源见 `NOTICE.md`）。

- 许可：**GPL-3.0-only**（见 `LICENSE`）。引用本模块的 BiliMT 发布物整体按 GPL-3.0-only 分发。
- 用途：匿名设备注册、v3/v5 签名、搜索/详情、`player/v1` 取流、CENC 解密。
- 与 BiliMT MIT 代码的边界：本模块是独立 Gradle module，应用层只通过
  `com.hongguotv.core.ContentRepository` 等公开 API 调用，不把 GPL 文件复制进 `app`。
- 上游接口与签名算法可能随红果版本变化；失败时应降级到网页端 3 集路径，不做无限重试。

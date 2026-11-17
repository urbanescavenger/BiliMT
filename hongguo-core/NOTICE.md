# 本次同步来源

2026-11-11：TV 功能与测试同步自 tsukiyo-zero/hongguoTV，提交 `49532eb2110dcbf66814ca250d43f222e7193895`（GPL-3.0）。保留原作者声明；本分支适配原包名、更新渠道及品牌。

# 原生版第三方声明

本地播放兼容修改参考 FanchangWang 的 GPL-3.0 项目贡献：
https://github.com/N3urda/hongguoTV/pull/22 。播放请求使用独立设备标识和无签名请求，复用本项目原有的响应大小限制、请求取消及媒体解析逻辑。

2026-10-21 账号测试功能参考 charlie901208/HGTV 的 `feat/phone-login` 分支（GPL-3.0-only）：
https://github.com/charlie901208/HGTV/tree/feat/phone-login 。`DeviceRegister.kt`、`PhoneLogin.kt` 由该分支移植并修改；账号读写的路径和字段参照其 `Account.kt`、`ContentRepository.kt`。本地实现补充有界响应读取、登录态加密存储、错误验证码停止重试与云端精确进度字段保留；账号界面和同步调度为本地实现。未合并该分支的广告、遥测、界面重构或其他功能。原作者著作权保留。

`core` 中内容请求签名、Spade URL / key 处理、MP4 CENC 处理是以下 GPL-3.0 源码的 Kotlin 移植：

- drpys, `spider/js/红果果[短].js`
- https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
- 本仓库对照源：`../server/vendor/hongguo.cjs`，取回于 2026-09-21。
- 本版改动：完整 Kotlin 实现；签名只选择已验证的 hash branch 0；加密媒体通过 Media3 DataSource 直接读取；增加样本边界检查、子样本处理和 AVC 原始编码恢复。

播放设备注册与 x-argus / x-ladon / Simon 签名、双模型选流是以下 GPL-3.0 源码的 Kotlin 移植（用于恢复被上游拒绝的固定设备播放路径）：

- fqapp, `rust/src/sign/{argus,simon,ladon,gorgon,protobuf}.rs`、`rust/src/device/{register,crypto}.rs`、`rust/src/endpoints/video.rs`
- https://github.com/ch6vip/fqapp
- 测试使用其仓库自带的独立参考实现黄金向量（`rust/testdata/vectors.json`、`device_vectors.json`）。

`kotlin-tv/` 原生应用源码和派生实现以 GPL-3.0-only 发布，许可证全文见 `LICENSE`。发布 APK 时同时提供本分支对应源码、Gradle 包装器和构建说明。APK 不包含 Node、React Native、JavaScript 引擎或该 JavaScript 源文件。`tools/signing-oracle.cjs` 仅为开发期生成对照测试数据的工具，不参与应用构建或运行。

其他依赖：

- ZXing core（本机生成手机输入与更新二维码）：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- Bouncy Castle SM3：https://github.com/bcgit/bc-java — MIT
- jsoup（仅解析官网 HTML，不执行网页脚本）：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0

应用图标和 TV banner 沿用本仓库素材。这里的应用不是内容平台官方客户端。


Mac/TV 共用评论签名的第三方许可：
# Mac 第三方声明

本集评论的协议字段参考 yangbin1322/hongguo-player 公开发行包中的接口线索（https://github.com/yangbin1322/hongguo-player/releases/tag/v1.2.11），以匿名请求核实数据来源和游标推进后独立实现；未纳入该客户端的代码、资源或二进制。

弹幕评论签名的通用 Argus / Simon 结构参考并改写自 tr4cex/TikTok-Encryption（MIT）：
https://github.com/tr4cex/TikTok-Encryption 。Gorgon、Ladon 基于本仓库已有 GPL core Signer 改写。红果请求字段为匿名读取验证的协议参数。未纳入许可证未确认的 guoapp 实现。

MIT License

Copyright (c) 2024 TikTok Encryption Project

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

EDUCATIONAL USE DISCLAIMER:
This software is intended for educational and research purposes only.
Users are responsible for ensuring their use complies with applicable laws
and terms of service. The authors disclaim any responsibility for misuse
of this software.

评论表情图使用 Microsoft Fluent Emoji（MIT），通过 GPL HGTV 参考项目取得；许可全文随 APK assets/comment_emoji/LICENSE.txt 分发。为通用替代表情，非红果官方贴图。

## 2026-11-11 单集播放协议修复

`PlayerSignature.kt` 基于本仓库 `CommentSignature.kt` 的 Argus/Simon/AES 和 Ladon 实现，按单集播放协议补齐设备、版本、请求体摘要和 Gorgon 参数。协议对照来源：qq294716498/HongguoTV-Phone-TV-NoAds，固定提交 `8eafa3908e9191c0a5ae186d5beff11e3a971f3c` 的 `native/core/provider_hongguo_player_sign.go`、`provider_hongguo_registered_media.go` 和 `provider_hongguo_app.go`：
https://github.com/qq294716498/HongguoTV-Phone-TV-NoAds/tree/8eafa3908e9191c0a5ae186d5beff11e3a971f3c/native/core 。保留原 MIT/GPL 声明与许可；未引入该项目的应用界面或账号数据。

`core/src/test/resources/player-signing.json` 使用该固定 Go 实现、固定时间和 nonce 独立生成，用于逐字节校验，不含真实设备身份、账号或媒体地址。正式播放优先使用 `/novel/player/video_model/v1/` 和完整 Gorgon/Argus/Ladon，旧批量签名仅保留为有界兼容回退；前述历史“无签名播放请求”说明不再描述当前主路径。

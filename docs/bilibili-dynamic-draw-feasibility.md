# B站图文动态(图片 + 长文)在移动端显示的可行性

研究日期:2026-09-25。结论基于**实测**(本机 wbi 签名复现接口)+ 本地 BV 源码(`E:\GITHUB\bv`)对照。

## 0. 结论

**可行,成本中等,接口侧零新增。** 现在看不到图文不是接口不给,而是**我们自己丢掉了**:
| 丢弃点 | 位置 | 现在 |
| --- | --- | --- |
| 服务端只取视频动态 | `UserFeedRepository.getDynamicFeed(type = "video")` | B站 直接不返回图文 |
| 客户端只收两种类型 | `VideoSummaryMappers.fromDynamicItem`(第 46-52 行) | 只收 `MAJOR_TYPE_ARCHIVE` / `live_rcmd`,图文/纯文字/转发/专栏一律 `null` drop |
| 数据模型是视频形态 | `core/model/VideoSummary.kt` | 只有 bvid/pic/duration,图文的正文与多图无处安放 |

主要成本集中在三处:① 图文卡片(文本 + 九宫格 + 展开全文);② 动态流的分页/去重/列表 key 全都绑在 `bvid` 上,图文没有 bvid,必须换成 `dynId`;③ `type=all` 后混进来的转发/纯文字/专栏要有明确处置策略(见 §4)。

## 0.1 已拍板(2026-09-26)

| 决策 | 结论 |
| --- | --- |
| 点击行为 | **对齐 BV**:点卡片 → 动态详情页;点图片 → 大图查看器。注意 BV 卡片上的点赞/评论/分享按钮**本身也是 `notYetImplemented()` 占位**,所以"与 BV 一致"**不包含**卡片点赞 |
| 看图器 | **自研最小集**(`HorizontalPager` + `transformable` 缩放,约 200 行)。不照搬 BV vendored 的 `com.origeek.imageViewer`(4,176 行 / 12 文件,MIT 可移植,但其重头是超大图 `BitmapRegionDecoder` 渐进解码、下拉关闭、卡片↔大图共享元素过渡,图文场景用不上) |
| 详情页评论 | **楼中楼(二级评论)先不做**,只做一级评论 |
| TV 端 | 本轮不动(其「动态·全部」tab 目前静默丢图文,属已知缺口) |

**切片进度**:切片1(数据层,纯诊断)= P11-180 **已真机复测** → 切片2(图文卡 + 九宫格 + 展开/收起 + 移动端切 `type=all`)= P11-181 **云编译绿,已真机复测数据侧**(`dev.r2094`:`type=all` 图文进流、`drawWithText=2` 证实 features 生效)→ 下一步:点图看大图(自研最小看图器)→ 再切片3(详情页 + 动态评论 `type=11`,不含楼中楼)。

**features 问号已结清(切片2 的取数依据)**:4 个 UP、两次独立采样对比 —

| 请求 | 图文条数 | 图文正文长度 | 视频动态字段完整度 |
| --- | --- | --- | --- |
| 不带 `features` | 3 | **[0, 0, 0]**(全无正文) | bvid/title/cover/play/duration_text/badge/module_stat/author 各 5/5 |
| 带 `features` | 15 | **[231, 132, 115, 17, 34, 14, 36, 18]** | 同上各 5/5,且 archive 项**没有**被挪进 opus(opus=0) |

⇒ **要出正文必须带 `features`,而带它不破坏视频动态的既有解析**。取值收敛到 BV 同款最小集合 `itemOpusStyle`。
另注:`feed/space` 频控很凶(连续探测即 HTTP 412),请求要克制 —— 与 P11-179 的 space 频控结论一致。

## 1. 接口事实(实测)

### 1.1 取数

| 项 | 事实 |
| --- | --- |
| 关注流 | `x/polymer/web-dynamic/v1/feed/all`,`type=video`(**现状**)/ `type=all`,**必须登录**(未登录 `-101`)。BV 默认 `all` + `timezone_offset=-480`,Cookie 只发 SESSDATA |
| 空间动态 | `x/polymer/web-dynamic/v1/feed/space?host_mid=`,**未登录可读**(需 wbi 签名 + buvid3/4 + Linux UA + `dm_img_*` 风控参数),可拿来离线核对字段 |
| 动态详情 | `x/polymer/web-dynamic/v1/detail?id={dynId}`(BV 还带 `features`) |

### 1.2 图文(DRAW)载荷有**两条分支** —— 这是最容易踩的坑

`features` 参数决定文案落在哪:

| 请求 | 文案 | 图片 |
| --- | --- | --- |
| 不带 `features` | `modules.module_dynamic.desc.text` | `module_dynamic.major.draw.items[]{src,width,height,size}`,**width/height/size 是字符串** |
| 带 `features=itemOpusStyle,opusBigCover,...` | `module_dynamic.major.opus.summary.text` | `major.opus.pics[]{url,width,height,size}`,**是数字** |

实测同一条动态:不带 features 时 `desc` 为 null(那批样本拿不到文案),带上 features 后返回

```
major.opus.summary.text = "【Q&A问题征集】我们频道已经来到1800w关注了！…"(完整长文)
major.opus.summary.rich_text_nodes[]  → type=RICH_TEXT_NODE_TYPE_TEXT(还有 EMOJI 等,带 icon_url)
major.opus.summary.has_more / paragraphs
major.opus.fold_action = ["展开", "收起"]      ← 官方折叠按钮文案
major.opus.jump_url = //www.bilibili.com/opus/{dynId}
major.opus.pics[] = {url,width:2000,height:1063,size:937.2}
```

BV 的 web 映射正是两分支兜底(**照抄即可**):

```kotlin
// bili-api/.../entity/user/Dynamic.kt:377  DynamicDrawModule.fromModuleDynamic
text   = moduleDynamic.desc?.text ?: moduleDynamic.major?.opus?.summary?.text ?: "empty text"
images = (moduleDynamic.major?.draw?.items ?: moduleDynamic.major?.opus?.pics)?.distinctBy { it.url } ?: emptyList()
```

### 1.3 其它可用字段

- **评论**:`basic.comment_type = 11` + `basic.comment_id_str = {dynId}` → 评论接口 `x/v2/reply/wbi/main?type=11&oid={dynId}`。
  注意:现有 `ui/feed/CommentScreen` 走的是 `oid=aid&type=1`(视频),**不能直接复用**,要参数化。
- **富文本**:正文是 `rich_text_nodes[]`,类型 `TEXT / EMOJI / AT / TOPIC / WEB`(实测 8 个 UP:TEXT 37 / EMOJI 12 / AT 2 / TOPIC 1 / WEB 1)。
  除表情外都自带可读 `text`;**表情的 `text` 只是 `[保佑]` 这类占位文案**,真图在 `emoji.icon_url`(hdslb emote URL,吃 CDN 尺寸后缀:原图 PNG 1989B → `@60w_60h_1c.webp` 1202B)。
  `emoji.size` 是**档位不是像素**(样本值 1)。P11-183 已按 Compose `InlineTextContent` 内联渲染。
- **计数**:`modules.module_stat.like/comment/forward.count`(现状已在 `fromArchiveDynamic` 里解析,可复用)。
- **图片 CDN 尺寸后缀实测可用**:原图 62,285 B(image/jpeg)→ `@480w_270h_1c.webp` = 5,944 B、`@320w_200h_1c.webp` = 4,302 B、`@60w_60h_1c.webp` = 1,248 B。
  项目已有 `String.biliCdnResizedImageUrl(w,h)`(`core/image/BiliImageRequest.kt:124`)负责拼这个后缀。
- **类型分布**:两套样本 —— ① 空间动态代理样本(8 个 UP、3 页):`DYNAMIC_TYPE_AV` 27 / `DRAW` 16 / `FORWARD` 5;② **关注流实测**(2026-09-26,真机 `dev.r2094`,`type=all` 一页 19 条):`AV` 14 / `FORWARD` 2 / `DRAW` 2 / `ARTICLE` 1 ⇒ 图文 **2/19 ≈ 10.5%**、转发 10.5%、专栏 5%,渲染后 `returned=16`(丢 2 转发 + 1 专栏)。**关注流的口径以 ② 为准**(样本仅一页,量级参考);同一行日志里 `drawWithText=2` 也实测证实了 features 生效 —— 关注流的图文正文确实能拿到。

## 2. 实现落点(文件级)

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| 新增图文/文字项模型 | `core/model/`(新文件) | 建议 `DynamicFeedItem`(sealed:`Video` / `Draw` / `Word`),或给 `VideoSummary` 加 `kind`,前者更干净 |
| 取数放开类型 | `core/network/UserFeedRepository.getDynamicFeed` | `type` 参数化(默认改 `all`)+ 加 `timezone_offset` |
| 新增映射 | `core/network/VideoSummaryMappers`(或新 `DynamicMappers.kt`) | `fromDrawDynamic` / `fromWordDynamic`,两分支兜底 + 字符串/数字容错解析(用 `BiliNumberParser`) |
| 新卡片 | `ui/mobile/feed/`(新文件) | 头像 + 昵称 + 时间 → 正文(默认折叠 N 行)→ 九宫格 → 点赞/评论/转发计数 |
| 九宫格布局 | 同上 | BV 规则可直接照抄:1 图 = 2:1 单卡;2 图 = 两个 1:1;≥3 取前 3 张 1:1 + 右下 `+N` 角标 |
| 列表接入 | `ui/mobile/feed/MobileDynamicScreen` | **key / `distinctBy` / `endReached` 全部从 `bvid` 换 `dynId`**;与 YouTube 项混排仍走 `pubdate` 排序 |
| 图片尺寸 | `core/image/BiliImageRequest.kt` + `BiliTokens` | 九宫格每格显式拼尺寸后缀;新视觉值先进 token |
| 性能档 | 遵循 `LocalBiliPerformancePolicy` 现有范式 | 流畅档关内存缓存 + `RGB_565`;精致档可放宽 |
| 文案 | `res/values*/strings.xml` × **6 个 locale** | 展开/收起、图片序号等 |

现有视频卡封面走的是**裸 URL**(`MobileVideoCard.kt:119` `model = coverOverride ?: video.pic`,由 Coil 按测量尺寸解码),所以九宫格必须**显式**拼尺寸后缀 —— 否则一条九图动态会拉 9 张原图(实测单张 62KB,九张 ≈ 560KB)。

## 2.1 卡片外观参照(2026-09-26,用户提供官方关注流截图)

官方动态流两张卡的样子(以此为准):

- **视频动态卡**:作者行(头像 + 名 + 时间)→ **动态正文(UP 自己的话)** → 缩略图(左下覆盖「时长 · N播放 · N弹幕」)→ 视频标题(2 行)→ 卡底互动计数行(转发 / 评论 / 点赞,带图标)。
- **图文动态卡**:作者行 → 动态正文 → 图片(2 图并排正方形;1 图整宽)→ 同款互动计数行。

对照我们原有实现,缺口**全在视频卡**:① 动态正文从未解析(只映射了 `archive.title`);
② 缩略图无时长/弹幕、播放数被挤在作者行;③ 卡底无互动计数行。已由 P11-182 补齐(视频卡补正文 + 覆盖行 + 计数行,图文卡复用同款计数行)。

## 3. 风险 / 约束

1. **`type=all` 会带回转发的、纯文字的、专栏的、番剧的**(代理样本里转发占 ~10%)。切片2 只渲染图文+视频+直播,其余**静默 drop**(对齐现状口径,风险最低);"看得见的条数变少"与 `endReached` 误判要一并处理(见下)。
2. **列表 key / 去重 / 翻页判据必须同步换 `dynId`**,否则图文项 bvid 为空 → Compose key 冲突(崩溃或错位);且 drop 过多时 `merged.size == current.videos.size` 会**误判到底** ⇒ 改看 API 的 `has_more`。
3. **点击行为**(已定,见 §0.1):点卡片进详情页、点图看大图,对齐 BV。
4. **无大图查看器**:项目里没有任何 image previewer / zoom 能力 ⇒ 切片2 自研最小集。
5. **展开全文**:长文默认折叠;折叠阈值用 `summary.has_more` + 行数,文案用资源字符串(`opus.fold_action` 的「展开/收起」可作参考但要走 strings.xml)。
6. **TV 端一致性**:TV 的动态流(`ui/feed/UserVideoFeedScreen`)共用同一仓库,且其「动态·全部」tab **本来就是 `type=all`**、目前静默丢图文;本轮不动 TV(要动的话九宫格还涉及 D-pad 焦点模型,是独立工作量)。
7. **两分支兜底是硬要求**:服务端 `features` 行为已证明会变(同一条动态文案位置不同),解析必须两处都试。

## 4. 分期(已拍板,见 §0.1)

- **切片 1(完成,P11-180)**:数据层 —— 模型 + 两分支映射 + 占比打点。
- **切片 2(P11-181,云编译绿)**:图文卡(文本折叠/展开 + 九宫格)+ 图片显式限尺寸 + 性能档 + 移动端动态 tab 切 `type=all` + key/去重换 `feedKey`。
- **切片 2b(下一步)**:**点图看大图**(自研最小看图器)。
- **切片 3**:动态详情页(opus 全文 + 计数)+ 动态评论 `type=11`(**不含楼中楼**)+ 点卡片进详情页 → 到此与 BV 点击行为对齐。
- **后置可选**:看图器补齐 BV 高级项(下拉关闭/共享元素过渡/渐进解码)、纯文字卡、转发卡嵌套(`orig`)、TV 端图文卡(D-pad 焦点模型)。

## 5. 参考

- 本地 BV 源码(同栈 Kotlin/Compose,可直接对照):
  `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt:377`(web 字段映射)、
  `app/mobile/src/main/kotlin/dev/aaa1115910/bv/mobile/component/home/dynamic/DynamicItem.kt:432-600`(图文卡 + 九宫格 + 折叠)、
  `app/mobile/src/main/kotlin/dev/aaa1115910/bv/mobile/screen/DynamicDetailScreen.kt`(详情页)。
- 实测样本(含 opus 全文 + pics 的完整 JSON):
  `C:\Users\Mort\AppData\Local\Temp\dynamic_opus_with_features.json`(临时文件,需要长期留档可移入 `docs/samples/`)。
- B站 space 接口频控(本轮另一件事):`docs/bilibili-space-risk-control.md`。

## 6. 待核实(真机/登录态)

- [x] 登录态 `feed/all?type=all` 的图文占比与文案分支 —— **已实测**(2026-09-26,`dev.r2094`):一页 19 条里图文 2 条(≈10.5%)、转发 2、专栏 1;`drawWithText=2` 说明走的是 `major.opus.summary` 分支(features 生效)。
- [ ] 转发动态(`DYNAMIC_TYPE_FORWARD`)的 `orig` 嵌套深度与图片归属(自己无图时是否要展示被转发的图)。
- [ ] 九图动态的真实样张(本次样本最多 2 图,`+N` 角标未实测)。
- [ ] `opus.summary.paragraphs` 富段落结构(加粗/图片混排)是否要还原。

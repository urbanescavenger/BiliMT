---
name: debug-build-no-monitor
description: Debug builds that compile successfully don't need CI monitoring before tag push.
metadata:
  type: feedback
---

当本地/云编译 debug 已经绿了，之后只打 alpha tag 并推送时，不需要再挂后台监控 CI。

**Why:**
- alpha tag 对应的代码已经是绿过的 debug commit，release build 只是多一步签名和打包。
- release build 出错概率低，不需要每次都盯着。
- 可以节省时间，避免不必要的等待。

**How to apply:**
- 如果用户在 debug 编译成功后说「打个 tag」，直接打 tag、推送、更新 release notes 即可。
- 只有**未验证的新代码**推送到 `mort_debug` 时才需要挂 Monitor 监控云编译。
- 稳定版 tag 推送前仍需要二次确认（见 [[stable-tag-push-needs-confirm]]）。

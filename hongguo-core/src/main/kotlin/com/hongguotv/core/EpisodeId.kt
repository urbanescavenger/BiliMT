// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 分集 vid 匹配。2026-11 之前为纯数字串（如「7710164888672291864」）；
 * 2026-11-27/28 起红果网页端/rank 页的 vid_list 统一改为 `v1_<base64url>` 形式
 * （如 `v1_dnYsWS_sCDzIfAOQre7MChnU8Zs`）。剧集 series_id 仍为纯数字。
 */
val EpisodeIdPattern: Regex = Regex("[0-9]{1,30}|v1_[A-Za-z0-9_-]{8,64}")

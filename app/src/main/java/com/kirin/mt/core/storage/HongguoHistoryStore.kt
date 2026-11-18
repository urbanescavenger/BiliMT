package com.kirin.mt.core.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kirin.mt.core.model.SourceHongguo
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.HongguoAppRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 一条红果短剧本地播放历史。红果是匿名只读端、没有服务端历史,由播放器在起播/保存进度时
 * 写入(P11-341),融入 TV/移动历史列表并续播。按 seriesId 前置去重——一部剧一张卡,
 * 记录最近观看的分集与进度。
 */
@Serializable
data class HongguoHistoryEntry(
  /** 剧集 series_id(唯一键)。 */
  val seriesId: String,
  val title: String,
  val coverUrl: String = "",
  /** 最近观看分集的 vid(续播定位:详情分集表里匹配 `hongguo-app:<vid>`)。 */
  val episodeVid: String = "",
  /** 最近观看分集序号(0 起):vid 在新详情里失配(剧集增删)时的兜底,并作卡片角标「第N集」。 */
  val episodeIndex: Int = 0,
  /** 上次播放位置(毫秒);播完/接近播完时为 0。 */
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  /** 最近播放时间(epoch 毫秒),排序用。 */
  val lastPlayedAtMs: Long = 0L,
)

/**
 * 红果播放历史本地存储。照抄 [YoutubeHistoryStore] 的 DataStore 列表 JSON 范式,
 * 复用 `context.biliDataStore`,免登录。
 */
class HongguoHistoryStore(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = ListSerializer(HongguoHistoryEntry.serializer())

  /** 最近观看的剧集列表,按 lastPlayedAtMs 倒序。 */
  val history: Flow<List<HongguoHistoryEntry>> = context.biliDataStore.data.map { prefs ->
    prefs[Keys.History]?.let(::decode).orEmpty().sortedByDescending { it.lastPlayedAtMs }
  }

  /** 记录一次起播:按 seriesId 去重前置,列表 cap 到 [MaxEntries]。重复观看/切集都走这里。 */
  suspend fun recordPlay(entry: HongguoHistoryEntry) {
    if (entry.seriesId.isBlank()) return
    context.biliDataStore.edit { prefs ->
      val current = decode(prefs[Keys.History]).orEmpty()
      val next = (listOf(entry) + current.filterNot { it.seriesId == entry.seriesId })
        .take(MaxEntries)
      prefs[Keys.History] = json.encodeToString(serializer, next)
    }
  }

  /** 更新某剧的播放位置/时长(起播后播放期间周期写入),并前置到列表头。 */
  suspend fun updatePosition(seriesId: String, positionMs: Long, durationMs: Long) {
    if (seriesId.isBlank()) return
    context.biliDataStore.edit { prefs ->
      val current = decode(prefs[Keys.History]).orEmpty()
      val existing = current.firstOrNull { it.seriesId == seriesId } ?: return@edit
      val updated = existing.copy(
        positionMs = positionMs.coerceAtLeast(0L),
        durationMs = durationMs.coerceAtLeast(0L),
        lastPlayedAtMs = System.currentTimeMillis(),
      )
      val next = (listOf(updated) + current.filterNot { it.seriesId == seriesId }).take(MaxEntries)
      prefs[Keys.History] = json.encodeToString(serializer, next)
    }
  }

  suspend fun remove(seriesId: String) {
    context.biliDataStore.edit { prefs ->
      val next = decode(prefs[Keys.History]).orEmpty().filterNot { it.seriesId == seriesId }
      if (next.isEmpty()) {
        prefs.remove(Keys.History)
      } else {
        prefs[Keys.History] = json.encodeToString(serializer, next)
      }
    }
  }

  suspend fun clear() {
    context.biliDataStore.edit { prefs -> prefs.remove(Keys.History) }
  }

  private fun decode(raw: String?): List<HongguoHistoryEntry> {
    if (raw == null) return emptyList()
    return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
  }

  private object Keys {
    val History = stringPreferencesKey("hongguo_history")
  }

  private companion object {
    const val MaxEntries = 100
  }
}

/**
 * 历史条目 → 卡片模型(TV/移动历史列表共用)。bvid = 红果卡片键 `hongguo:<seriesId>`
 * (详情补拉按此前缀取 seriesId);progress/viewAt 填秒数,与 B站历史同单位供混合排序与续播;
 * [VideoSummary.historyEpisodeVid] 带上最近分集,点击经
 * [com.kirin.mt.ui.player.toHongguoPlaybackRequest] 定位集与起点。
 */
fun HongguoHistoryEntry.toHongguoSummary(): VideoSummary {
  return VideoSummary(
    bvid = HongguoAppRepository.SeriesPrefix + seriesId,
    title = title,
    pic = coverUrl,
    ownerName = "红果短剧",
    ownerFace = "",
    ownerMid = 0L,
    view = 0,
    danmaku = 0,
    duration = (durationMs / 1000L).toInt(),
    pubdate = 0L,
    // 红果分集固定按「第N集」命名(HongguoAppRepository.toSummary),角标直接用序号还原。
    badge = "第${episodeIndex + 1}集",
    progress = (positionMs / 1000L).toInt(),
    viewAt = lastPlayedAtMs / 1000L,
    source = SourceHongguo,
    historyEpisodeVid = episodeVid,
    historyEpisodeIndex = episodeIndex,
  )
}

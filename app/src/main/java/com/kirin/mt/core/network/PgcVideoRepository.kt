package com.kirin.mt.core.network

import android.util.Log
import com.kirin.mt.core.model.PgcFeedPage
import com.kirin.mt.core.model.PgcIndexFilters
import com.kirin.mt.core.model.PgcIndexPage
import com.kirin.mt.core.model.PgcIndexResult
import com.kirin.mt.core.model.PgcSeason
import com.kirin.mt.core.model.PgcType
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray

internal class PgcVideoRepository(
  private val apiClient: BiliApiClient,
  private val sessionStore: SessionStore,
) {
  suspend fun getFeed(pgcType: PgcType, cursor: Int): PgcFeedPage {
    val params = mutableMapOf<String, String>(
      "name" to pgcType.feedName,
      "coursor" to cursor.toString(),
    )
    if (!pgcType.usesV3Feed) {
      params["new_cursor_status"] = "true"
    }

    val url = if (pgcType.usesV3Feed) BiliApiEndpoints.PgcFeedV3 else BiliApiEndpoints.PgcFeed
    val root = apiClient.getJson(url = url, params = params).rootObject()
    root.requireBiliCodeOk("pgc feed")
    val data = root.obj("data") ?: return PgcFeedPage(emptyList(), cursor, false)

    val nextCursor = data.int("coursor")
    val hasNext = data.boolean("has_next")
    val rawItems = data["items"] as? JsonArray ?: emptyList()
    val items = if (pgcType.usesV3Feed) {
      rawItems.flatMap { item ->
        val itemObj = item.asObjectOrNull() ?: return@flatMap emptyList()
        val subItems = itemObj["sub_items"] as? JsonArray ?: emptyList()
        subItems.mapNotNull { sub ->
          val subObj = sub.asObjectOrNull() ?: return@mapNotNull null
          if (subObj.string("card_style") != "v_card") return@mapNotNull null
          PgcMappers.fromFeedSubItem(subObj)
        }
      }
    } else {
      rawItems.mapNotNull { sub ->
        PgcMappers.fromFeedSubItem(sub.asObjectOrNull() ?: return@mapNotNull null)
      }
    }
    return PgcFeedPage(items, nextCursor, hasNext)
  }

  suspend fun getSeasonInfo(seasonId: Int, epId: Int = 0): PgcSeason? {
    val params = mutableMapOf<String, String>()
    if (seasonId > 0) {
      params["season_id"] = seasonId.toString()
    } else if (epId > 0) {
      params["ep_id"] = epId.toString()
    } else {
      return null
    }

    val sessData = sessionStore.sessData.first()
    val root = apiClient.getJson(
      url = BiliApiEndpoints.PgcSeasonView,
      params = params,
      sessData = sessData,
    ).rootObject()
    Log.i("BiliMT:Pgc", "pgc season raw: code=${root.int("code")} message=${root.string("message")} hasData=${root.obj("data") != null} hasResult=${root.obj("result") != null} keys=${root.keys.toList().take(8)}")
    root.requireBiliCodeOk("pgc season")
    val data = root.obj("data") ?: root.obj("result") ?: return null
    return PgcMappers.fromSeasonData(data)
  }

  suspend fun getPgcIndex(
    pgcType: PgcType,
    filters: PgcIndexFilters,
    page: PgcIndexPage,
  ): PgcIndexResult {
    val params = mutableMapOf<String, String>(
      "st" to pgcType.id.toString(),
      "season_type" to pgcType.id.toString(),
      "order" to filters.order.toString(),
      "sort" to filters.sort.toString(),
      "page" to page.page.toString(),
      "pagesize" to page.pageSize.toString(),
      "type" to "1",
    )
    when (pgcType) {
      PgcType.Anime -> {
        params["season_version"] = filters.seasonVersion.toString()
        params["spoken_language_type"] = filters.spokenLanguage.toString()
        params["area"] = filters.area.toString()
        params["is_finish"] = filters.isFinish.toString()
        params["copyright"] = filters.copyright.toString()
        params["season_status"] = filters.seasonStatus.toString()
        params["season_month"] = filters.seasonMonth.toString()
        params["year"] = filters.year
        params["style_id"] = filters.style.toString()
      }
      PgcType.GuoChuang -> {
        params["season_version"] = filters.seasonVersion.toString()
        params["is_finish"] = filters.isFinish.toString()
        params["copyright"] = filters.copyright.toString()
        params["season_status"] = filters.seasonStatus.toString()
        params["year"] = filters.year
        params["style_id"] = filters.style.toString()
      }
      PgcType.Movie -> {
        params["area"] = filters.area.toString()
        params["season_status"] = filters.seasonStatus.toString()
        params["release_date"] = filters.releaseDate
        params["style_id"] = filters.style.toString()
      }
      PgcType.Documentary -> {
        params["area"] = filters.area.toString()
        params["season_status"] = filters.seasonStatus.toString()
        params["producer_id"] = filters.producer.toString()
        params["release_date"] = filters.releaseDate
        params["style_id"] = filters.style.toString()
      }
      PgcType.Tv -> {
        params["area"] = filters.area.toString()
        params["season_status"] = filters.seasonStatus.toString()
        params["release_date"] = filters.releaseDate
        params["style_id"] = filters.style.toString()
      }
      PgcType.Variety -> {
        params["season_status"] = filters.seasonStatus.toString()
        params["style_id"] = filters.style.toString()
      }
    }

    val root = apiClient.getJson(url = BiliApiEndpoints.PgcSeasonIndex, params = params).rootObject()
    root.requireBiliCodeOk("pgc index")
    val data = root.obj("data")
      ?: return PgcIndexResult(emptyList(), page.copy(hasNext = false))
    val hasNext = data.int("has_next") == 1
    val list = data["list"] as? JsonArray ?: emptyList()
    val items = list.mapNotNull { PgcMappers.fromIndexItem(it.asObjectOrNull() ?: return@mapNotNull null) }
    val nextPage = PgcIndexPage(
      page = page.page + 1,
      pageSize = page.pageSize,
      hasNext = hasNext,
    )
    return PgcIndexResult(items, nextPage)
  }
}

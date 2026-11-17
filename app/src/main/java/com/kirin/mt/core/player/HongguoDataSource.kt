package com.kirin.mt.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.StreamInfo
import okhttp3.OkHttpClient
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * 红果 App 端流式播放描述（GPL-3.0 `:hongguo-core` 的取流结果）。
 *
 * [http] 是 `HongguoAppRepository` 的独立 client（红果 UA/Referer，不混入 B 站头）；
 * [key] 非空表示媒体是 CENC，由 [RemoteVideo] 在读取时按样本解密。
 */
data class HongguoStream(
  val url: String,
  val key: ByteArray?,
  val quality: Int,
  val http: OkHttpClient,
)

/**
 * 红果流式 Media3 数据源：按 Range 读远端，读到哪解到哪，不再等整集下载完。
 *
 * 移植自 N3urda/hongguoTV `VideoDataSource`（GPL-3.0-only）；底层 [RemoteVideo] 负责
 * `moov` 头解析、样本表与 CENC 解密，本类只做 Media3 的 DataSpec ↔ 绝对偏移映射。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class HongguoDataSource(private val video: RemoteVideo) : BaseDataSource(true) {
  private var position = 0L
  private var remaining = 0L
  private var reader: InputStream? = null
  private var rangeRemaining = 0
  private var opened = false
  private var uri: Uri? = null

  override fun open(spec: DataSpec): Long {
    reader?.close()
    reader = null
    rangeRemaining = 0
    transferInitializing(spec)
    // 首次 open 才拉 moov/样本表；之后同一 DataSource 的 seek 复用 prepared。
    video.prepare()
    uri = spec.uri
    position = spec.position
    if (position > video.total) throw IOException("读取位置超出视频长度")
    remaining =
      if (spec.length == C.LENGTH_UNSET.toLong()) {
        video.total - position
      } else {
        minOf(spec.length, video.total - position)
      }
    opened = true
    transferStarted(spec)
    return remaining
  }

  override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
    if (length == 0) return 0
    if (remaining == 0L) return C.RESULT_END_OF_INPUT
    val cached = if (reader == null) video.cachedBytes(position) else 0
    val count =
      if (cached > 0) {
        val bytes = video.read(
          position,
          minOf(length.toLong(), cached.toLong(), remaining, 32 * 1024L).toInt(),
        )
        bytes.copyInto(target, targetOffset)
        bytes.size
      } else {
        if (reader == null) {
          rangeRemaining = minOf(512 * 1024L, remaining).toInt()
          reader = video.openRead(position, rangeRemaining)
        }
        val n = reader!!.read(
          target,
          targetOffset,
          minOf(length.toLong(), remaining, rangeRemaining.toLong()).toInt(),
        )
        if (n <= 0) throw EOFException("视频分段意外结束")
        rangeRemaining -= n
        if (rangeRemaining == 0) {
          reader?.close()
          reader = null
        }
        n
      }
    position += count
    remaining -= count
    bytesTransferred(count)
    return count
  }

  override fun getUri(): Uri? = uri

  override fun close() {
    try {
      // 只关当前 Range 读取，不 close RemoteVideo：Media3 的同一个 DataSource 会在
      // seek/重试时再次 open()，底层 prepared/样本表要复用。RemoteVideo 不持有常驻连接，
      // 随 DataSource 一起被回收即可。
      reader?.close()
    } finally {
      reader = null
      uri = null
      if (opened) {
        opened = false
        transferEnded()
      }
    }
  }

  /** 每个 Media3 DataSource 一份 RemoteVideo；工厂不共享可变读取状态。 */
  class Factory(private val stream: HongguoStream) : DataSource.Factory {
    override fun createDataSource(): DataSource =
      HongguoDataSource(
        RemoteVideo(
          stream.http,
          StreamInfo(stream.url, stream.key, if (stream.quality > 0) "${stream.quality}P" else "自动"),
        ),
      )
  }
}

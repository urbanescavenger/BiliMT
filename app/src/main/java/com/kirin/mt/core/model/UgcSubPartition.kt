package com.kirin.mt.core.model

import androidx.annotation.StringRes

/**
 * UGC 主分区下的子分区（如"动画"下的"MAD·AMV""MMD·3D"）。
 *
 * [tid] 是 B 站传统分区 id，可直接作为 `rid` 传给 `/x/web-interface/dynamic/region`。
 * [code] 沿用 BV 源码 PartitionUtil 的子分区 code，仅作日志/标识用途。
 * [titleRes] 指向显示名字符串资源。
 */
data class UgcSubPartition(
  val tid: Int,
  val code: String,
  @StringRes val titleRes: Int,
)
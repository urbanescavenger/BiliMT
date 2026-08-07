package com.kirin.mt.core.cache

import java.util.Locale

/** 把字节数格式化为 "12.3 MB" 或 "456 KB"(对齐 TV AppShell 原实现)。 */
fun formatCacheSize(bytes: Long): String {
  val safeBytes = bytes.coerceAtLeast(0L)
  val mb = safeBytes / (1024.0 * 1024.0)
  return if (mb >= 1.0) {
    String.format(Locale.US, "%.1f MB", mb)
  } else {
    String.format(Locale.US, "%.0f KB", safeBytes / 1024.0)
  }
}

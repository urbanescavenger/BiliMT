package com.kirin.mt.core.player

enum class PlaybackCdnPreference(val key: String) {
  Auto("auto"),
  Official("official"),
  Aliyun("aliyun"),
  Akamai("akamai"),
  Hw("hw");

  companion object {
    fun fromKey(key: String?): PlaybackCdnPreference {
      return entries.firstOrNull { preference -> preference.key == key } ?: Auto
    }
  }
}

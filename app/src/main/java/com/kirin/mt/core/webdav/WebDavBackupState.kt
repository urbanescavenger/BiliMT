package com.kirin.mt.core.webdav

/**
 * UI state for the WebDAV backup/restore entry in settings.
 *
 * [WebDavBackupService] is a single suspend `Result`-returning call with no
 * byte-level progress, so the UI can only show an indeterminate "running"
 * state. [Running.isRestore] distinguishes which operation is in flight so the
 * row/button can label itself "备份中…" vs "还原中…".
 */
sealed interface WebDavBackupState {
  data object Idle : WebDavBackupState
  data class Running(val isRestore: Boolean) : WebDavBackupState
}
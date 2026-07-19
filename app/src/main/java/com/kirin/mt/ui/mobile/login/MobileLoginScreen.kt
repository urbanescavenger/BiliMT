package com.kirin.mt.ui.mobile.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kirin.mt.R
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.auth.TvLoginPollResult
import com.kirin.mt.ui.login.createQrCodeBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val QrCodeSizePx = 480
private const val LoginPollIntervalMs = 2_000L

private sealed interface QrLoginState {
  data object Loading : QrLoginState
  data class Waiting(val authCode: String, val qrBitmap: Bitmap, val scanned: Boolean = false) : QrLoginState
  data object Expired : QrLoginState
  data object Success : QrLoginState
  data class Error(val message: String) : QrLoginState
}

/** 移动端登录:复用 AuthRepository TV QR 流程。短信登录暂未支持(引擎无 SMS 路径)。 */
@Composable
fun MobileLoginScreen(
  authRepository: AuthRepository,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var state by remember { mutableStateOf<QrLoginState>(QrLoginState.Loading) }

  fun generateQrCode() {
    state = QrLoginState.Loading
    coroutineScope.launch {
      state = try {
        val qrCode = authRepository.generateTvQrCode()
        QrLoginState.Waiting(
          authCode = qrCode.authCode,
          qrBitmap = createQrCodeBitmap(qrCode.url, QrCodeSizePx),
        )
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        QrLoginState.Error(error.message.orEmpty().ifBlank { context.getString(R.string.login_qr_unavailable) })
      }
    }
  }

  LaunchedEffect(Unit) { generateQrCode() }

  LaunchedEffect(state) {
    val waitingState = state as? QrLoginState.Waiting ?: return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        delay(LoginPollIntervalMs)
        when (val result = authRepository.pollTvLogin(waitingState.authCode)) {
          TvLoginPollResult.Waiting -> Unit
          TvLoginPollResult.Scanned -> state = waitingState.copy(scanned = true)
          TvLoginPollResult.Expired -> { state = QrLoginState.Expired; break }
          TvLoginPollResult.Success -> { state = QrLoginState.Success; break }
          is TvLoginPollResult.Error -> { state = QrLoginState.Error(result.message); break }
        }
      }
    }
  }

  LaunchedEffect(state) {
    if (state is QrLoginState.Success) onClose()
  }

  Column(
    modifier = modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = stringResource(R.string.login_mobile_title),
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = stringResource(R.string.login_mobile_hint),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
    Box(
      modifier = Modifier
        .padding(top = 24.dp)
        .size(280.dp)
        .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(16.dp)),
      contentAlignment = Alignment.Center,
    ) {
      when (val s = state) {
        QrLoginState.Loading -> CircularProgressIndicator()
        QrLoginState.Expired, is QrLoginState.Error -> Text(
          text = stringResource(R.string.login_status_expired),
          color = MaterialTheme.colorScheme.error,
        )
        is QrLoginState.Waiting -> Image(
          bitmap = s.qrBitmap.asImageBitmap(),
          contentDescription = stringResource(R.string.login_qr_content_description),
          modifier = Modifier.size(256.dp),
        )
        QrLoginState.Success -> Text(text = stringResource(R.string.login_status_success))
      }
    }
    Text(
      text = statusText(state),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 16.dp),
    )
    if (state is QrLoginState.Error || state is QrLoginState.Expired) {
      Button(onClick = ::generateQrCode, modifier = Modifier.padding(top = 16.dp)) {
        Text(stringResource(R.string.login_qr_refresh))
      }
    }
    Text(
      text = stringResource(R.string.login_sms_placeholder),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
      modifier = Modifier.padding(top = 24.dp),
    )
  }
}

@Composable
private fun statusText(state: QrLoginState): String = when (state) {
  QrLoginState.Loading -> stringResource(R.string.login_status_loading)
  is QrLoginState.Waiting ->
    if (state.scanned) stringResource(R.string.login_status_scanned) else stringResource(R.string.login_status_waiting)
  QrLoginState.Expired -> stringResource(R.string.login_status_expired)
  QrLoginState.Success -> stringResource(R.string.login_status_success)
  is QrLoginState.Error -> stringResource(R.string.login_status_error)
}
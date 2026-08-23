package com.kirin.mt.ui.login

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kirin.mt.R
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.auth.TvLoginPollResult
import com.kirin.mt.core.storage.UserSession
import com.kirin.mt.ui.account.AccountProfileCard
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(
  userSession: UserSession,
  authRepository: AuthRepository,
  modifier: Modifier = Modifier,
) {
  if (userSession.isLoggedIn) {
    LaunchedEffect(userSession.face, userSession.uname, userSession.isLoggedIn) {
      if (userSession.isLoggedIn && (userSession.face.isNullOrBlank() || userSession.uname.isNullOrBlank())) {
        runCatching {
          authRepository.refreshUserProfile()
        }
      }
    }
    LoggedInAccount(
      userSession = userSession,
      modifier = modifier,
    )
  } else {
    TvQrLoginPanel(authRepository = authRepository, modifier = modifier)
  }
}

@Composable
private fun LoggedInAccount(
  userSession: UserSession,
  modifier: Modifier,
) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    AccountProfileCard(
      userSession = userSession,
      modifier = Modifier
        .size(width = BiliSizing.AccountProfilePanelWidth, height = BiliSizing.AccountProfilePanelHeight),
    )
  }
}

@Composable
private fun TvQrLoginPanel(authRepository: AuthRepository, modifier: Modifier) {
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
        QrLoginState.Error(error.message.orEmpty())
      }
    }
  }

  LaunchedEffect(Unit) {
    generateQrCode()
  }

  LaunchedEffect(state) {
    val waitingState = state as? QrLoginState.Waiting ?: return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        delay(LoginPollIntervalMs)
        when (val result = authRepository.pollTvLogin(waitingState.authCode)) {
          TvLoginPollResult.Waiting -> Unit
          TvLoginPollResult.Scanned -> state = waitingState.copy(scanned = true)
          TvLoginPollResult.Expired -> {
            state = QrLoginState.Expired
            break
          }
          TvLoginPollResult.Success -> {
            state = QrLoginState.Success
            break
          }
          is TvLoginPollResult.Error -> {
            state = QrLoginState.Error(result.message)
            break
          }
        }
      }
    }
  }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = stringResource(R.string.login_tv_title),
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.ScreenTitle,
      fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(BiliSpacing.Xl))
    QrContent(state = state)
    Spacer(modifier = Modifier.height(BiliSpacing.Lg))
    LoginStatusText(state = state)
    if (state is QrLoginState.Error || state is QrLoginState.Expired) {
      Spacer(modifier = Modifier.height(BiliSpacing.Xl))
      BiliFocusableSurface(
        scaleOnFocus = false,
        shape = RoundedCornerShape(BiliRadius.Pill),
        onClick = ::generateQrCode,
      ) {
        Text(
          text = stringResource(R.string.login_qr_refresh),
          color = BiliColors.TextPrimary,
          fontSize = BiliTypography.Body,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = BiliSpacing.Xl, vertical = BiliSpacing.Md),
        )
      }
    }
  }
}

@Composable
private fun QrContent(state: QrLoginState) {
  Box(
    modifier = Modifier
      .size(BiliSizing.LoginQrContainerSize)
      .background(BiliColors.TextPrimary, RoundedCornerShape(BiliRadius.Panel))
      .padding(BiliSpacing.Lg),
    contentAlignment = Alignment.Center,
  ) {
    val bitmap = (state as? QrLoginState.Waiting)?.qrBitmap
    if (bitmap != null) {
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.login_qr_content_description),
        modifier = Modifier.size(BiliSizing.LoginQrImageSize),
      )
    } else {
      Text(
        text = if (state is QrLoginState.Loading) {
          stringResource(R.string.login_loading_short)
        } else {
          stringResource(R.string.login_qr_unavailable)
        },
        color = BiliColors.Background,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun LoginStatusText(state: QrLoginState) {
  val text = when (state) {
    QrLoginState.Loading -> stringResource(R.string.login_status_loading)
    is QrLoginState.Waiting -> if (state.scanned) {
      stringResource(R.string.login_status_scanned)
    } else {
      stringResource(R.string.login_status_waiting)
    }
    QrLoginState.Expired -> stringResource(R.string.login_status_expired)
    is QrLoginState.Error -> stringResource(R.string.login_status_error)
    QrLoginState.Success -> stringResource(R.string.login_status_success)
  }
  val color = when (state) {
    QrLoginState.Success,
    is QrLoginState.Waiting -> if (state is QrLoginState.Waiting && state.scanned) {
      BiliColors.Aqua
    } else {
      BiliColors.TextSecondary
    }
    QrLoginState.Expired,
    is QrLoginState.Error -> BiliColors.BiliPink
    QrLoginState.Loading -> BiliColors.TextSecondary
  }

  Text(
    text = text,
    color = color,
    fontSize = BiliTypography.Body,
    fontWeight = FontWeight.Medium,
  )
}

private sealed interface QrLoginState {
  data object Loading : QrLoginState
  data class Waiting(
    val authCode: String,
    val qrBitmap: Bitmap,
    val scanned: Boolean = false,
  ) : QrLoginState
  data object Expired : QrLoginState
  data object Success : QrLoginState
  data class Error(val message: String) : QrLoginState
}

private const val LoginPollIntervalMs = 2_000L
private const val QrCodeSizePx = 360

/**
 * TV 二维码登录弹窗:复用 [TvQrLoginPanel](生成/轮询/过期刷新)包一层 scrim + BackHandler。
 * 「我的」页把账号信息并进设置列表首行后,未登录时由账号行点击唤起此弹窗,避免独立大块占屏。
 */
@Composable
fun TvQrLoginDialog(
  authRepository: AuthRepository,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler { onDismiss() }
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.55f)),
    contentAlignment = Alignment.Center,
  ) {
    TvQrLoginPanel(authRepository = authRepository, modifier = Modifier)
  }
}

/**
 * TV 账号操作弹窗:登录态点账号行弹出,提供退出登录确认(镜像移动端 MobileFollowSheet 的登出)。
 */
@Composable
fun TvAccountSettingsDialog(
  onLogout: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler { onDismiss() }
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.55f)),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .width(440.dp)
        .background(BiliColors.SurfaceElevated, RoundedCornerShape(BiliRadius.Panel))
        .padding(BiliSpacing.Xl),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.account_logout),
        color = BiliColors.TextPrimary,
        fontSize = BiliTypography.SectionTitle,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.account_logout_confirm),
        color = BiliColors.TextSecondary,
        fontSize = BiliTypography.Body,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
          BiliSpacing.Md,
          Alignment.CenterHorizontally,
        ),
      ) {
        BiliFocusableSurface(
          scaleOnFocus = false,
          shape = RoundedCornerShape(BiliRadius.Pill),
          onClick = onDismiss,
        ) {
          Text(
            text = stringResource(R.string.account_logout_cancel),
            color = BiliColors.TextSecondary,
            fontSize = BiliTypography.Body,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = BiliSpacing.Xl, vertical = BiliSpacing.Md),
          )
        }
        BiliFocusableSurface(
          scaleOnFocus = false,
          shape = RoundedCornerShape(BiliRadius.Pill),
          onClick = onLogout,
        ) {
          Text(
            text = stringResource(R.string.account_logout),
            color = BiliColors.BiliPink,
            fontSize = BiliTypography.Body,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = BiliSpacing.Xl, vertical = BiliSpacing.Md),
          )
        }
      }
    }
  }
}

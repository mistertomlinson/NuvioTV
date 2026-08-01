@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.simkl.SimklConnectionMode
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

@Composable
fun SimklScreen(
    viewModel: SimklSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val primaryFocusRequester = remember { FocusRequester() }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    BackHandler { onBackPress() }

    val nowMillis by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = uiState.expiresAtEpochMs
    ) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    LaunchedEffect(uiState.mode) {
        runCatching { primaryFocusRequester.requestFocus() }
    }

    val verificationUri = uiState.verificationUri
    val qrBitmap = remember(verificationUri) {
        verificationUri
            ?.takeIf(String::isNotBlank)
            ?.let { uri ->
                runCatching {
                    QrCodeGenerator.generate(uri, 420)
                }.getOrNull()
            }
    }

    val simklLogoPainter = rememberSimklRawSvgPainter(
        R.raw.simkl_tv_wordmark
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(horizontal = 48.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = simklLogoPainter,
                contentDescription = "Simkl logo",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.simkl_name),
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioColors.TextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.simkl_description),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.TextSecondary
            )

            if (uiState.mode == SimklConnectionMode.CONNECTED) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(
                        R.string.simkl_connected_as,
                        uiState.username
                            ?: stringResource(R.string.simkl_user_fallback)
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF7CFF9B)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
                .border(
                    width = 1.dp,
                    color = NuvioColors.Border.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(18.dp)
                )
                .background(
                    color = NuvioColors.BackgroundElevated.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(20.dp)
        ) {
            val remaining = uiState.expiresAtEpochMs
                ?.let { (it - nowMillis).coerceAtLeast(0L) }
                ?: 0L

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.simkl_account_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioColors.TextPrimary
                    )

                    if (
                        uiState.mode ==
                        SimklConnectionMode.AWAITING_APPROVAL
                    ) {
                        Button(
                            onClick = viewModel::onCancel,
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundCard,
                                contentColor = NuvioColors.TextPrimary
                            )
                        ) {
                            Text(stringResource(R.string.simkl_cancel))
                        }
                    }
                }

                when (uiState.mode) {
                    SimklConnectionMode.DISCONNECTED -> {
                        Text(
                            text = stringResource(
                                R.string.simkl_login_instruction
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioColors.TextSecondary
                        )

                        Button(
                            onClick = viewModel::onConnect,
                            enabled =
                                uiState.credentialsConfigured &&
                                    !uiState.isLoading,
                            modifier = Modifier.focusRequester(
                                primaryFocusRequester
                            ),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.Primary,
                                contentColor = Color.Black
                            )
                        ) {
                            Text(
                                if (uiState.isLoading) {
                                    stringResource(
                                        R.string.simkl_connecting
                                    )
                                } else {
                                    stringResource(R.string.simkl_connect)
                                }
                            )
                        }

                        if (!uiState.credentialsConfigured) {
                            Text(
                                text = stringResource(
                                    R.string.simkl_missing_credentials
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFFFB74D)
                            )
                        }
                    }

                    SimklConnectionMode.AWAITING_APPROVAL -> {
                        Text(
                            text = stringResource(
                                R.string.simkl_awaiting_instruction
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioColors.TextSecondary
                        )

                        Text(
                            text = uiState.userCode ?: "-",
                            color = NuvioColors.Primary,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        )

                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Simkl activation QR",
                                modifier = Modifier.size(180.dp),
                                contentScale = ContentScale.Fit
                            )
                        }

                        verificationUri
                            ?.takeIf(String::isNotBlank)
                            ?.let { uri ->
                                Text(
                                    text = uri,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioColors.TextSecondary
                                )

                                Button(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(uri)
                                                ).addFlags(
                                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                                )
                                            )
                                        }
                                    },
                                    colors = ButtonDefaults.colors(
                                        containerColor =
                                            NuvioColors.BackgroundCard,
                                        contentColor =
                                            NuvioColors.TextPrimary
                                    )
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.simkl_visit
                                        )
                                    )
                                }
                            }

                        Text(
                            text = stringResource(
                                R.string.simkl_code_expires,
                                formatSimklDuration(remaining)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextSecondary
                        )

                        Button(
                            onClick = viewModel::onRetryPolling,
                            enabled = !uiState.isLoading,
                            modifier = Modifier.focusRequester(
                                primaryFocusRequester
                            )
                        ) {
                            Text(stringResource(R.string.simkl_retry))
                        }
                    }

                    SimklConnectionMode.CONNECTED -> {
                        Button(
                            onClick = viewModel::onSyncNow,
                            enabled = !uiState.isLoading,
                            modifier = Modifier.focusRequester(
                                primaryFocusRequester
                            ),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.Primary,
                                contentColor = Color.Black
                            )
                        ) {
                            Text(
                                if (uiState.isLoading) {
                                    stringResource(R.string.simkl_status_syncing)
                                } else {
                                    stringResource(R.string.simkl_sync_now)
                                }
                            )
                        }

                        Button(
                            onClick = {
                                showDisconnectConfirm = true
                            },
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundCard,
                                contentColor = NuvioColors.TextPrimary
                            )
                        ) {
                            Text(stringResource(R.string.simkl_disconnect))
                        }
                    }
                }

                uiState.statusMessage
                    ?.takeIf(String::isNotBlank)
                    ?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextSecondary
                        )
                    }

                uiState.errorMessage
                    ?.takeIf(String::isNotBlank)
                    ?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFF6E6E)
                        )
                    }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onBackPress,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.simkl_back))
            }
        }
    }

    if (showDisconnectConfirm) {
        NuvioDialog(
            onDismiss = { showDisconnectConfirm = false },
            title = stringResource(R.string.simkl_disconnect_title),
            subtitle = stringResource(
                R.string.simkl_disconnect_subtitle
            ),
            width = 520.dp,
            suppressFirstKeyUp = false
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        showDisconnectConfirm = false
                        viewModel.onDisconnect()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.simkl_disconnect))
                }

                Button(
                    onClick = {
                        showDisconnectConfirm = false
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.simkl_cancel))
                }
            }
        }
    }
}

@Composable
private fun rememberSimklRawSvgPainter(
    @RawRes iconRes: Int
): Painter {
    val context = LocalContext.current
    val request = remember(iconRes, context) {
        ImageRequest.Builder(context)
            .data(iconRes)
            .decoderFactory(SvgDecoder.Factory())
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

private fun formatSimklDuration(valueMs: Long): String {
    val totalSeconds = (valueMs / 1_000L).coerceAtLeast(0L)
    val days = TimeUnit.SECONDS.toDays(totalSeconds)
    val hours = TimeUnit.SECONDS.toHours(totalSeconds) % 24
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
    val seconds = totalSeconds % 60

    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

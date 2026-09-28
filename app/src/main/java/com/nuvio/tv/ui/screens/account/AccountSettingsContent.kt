@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.screens.settings.SettingsRightSurfaceColor
import com.nuvio.tv.ui.screens.settings.SettingsRightSurfaceFocusedColor
import com.nuvio.tv.ui.screens.settings.SettingsRowGap
import com.nuvio.tv.ui.screens.settings.SettingsSecondaryCardRadius
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay

@Composable
fun AccountSettingsContent(
    uiState: AccountUiState,
    viewModel: AccountViewModel,
    onNavigateToAuthQrSignIn: () -> Unit = {}
) {
    var showSyncOverviewLoading by remember { mutableStateOf(false) }

    LaunchedEffect(
        uiState.authState,
        uiState.syncOverview,
        uiState.isSyncOverviewLoading
    ) {
        if (
            uiState.authState is AuthState.FullAccount &&
            uiState.syncOverview == null &&
            uiState.isSyncOverviewLoading
        ) {
            delay(120L)
            showSyncOverviewLoading = true
        } else {
            showSyncOverviewLoading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
    ) {
        when (val authState = uiState.authState) {
            is AuthState.Loading -> {
                AccountInfoSurface {
                    Text(
                        text = stringResource(R.string.account_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                }
            }

            is AuthState.SignedOut -> {
                AccountInfoSurface {
                    Text(
                        text = stringResource(R.string.account_sync_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AccountInlineNote(
                        text = stringResource(R.string.account_sync_restart_note)
                    )
                }

                SettingsActionButton(
                    icon = Icons.Default.VpnKey,
                    title = stringResource(R.string.account_signin_qr_title),
                    subtitle = stringResource(R.string.account_signin_qr_subtitle),
                    onClick = onNavigateToAuthQrSignIn
                )
            }

            is AuthState.FullAccount -> {
                AccountInfoSurface {
                    AccountStatusLine(
                        label = stringResource(R.string.account_signed_in_label),
                        value = authState.email
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    AccountInlineNote(
                        text = stringResource(R.string.account_sync_restart_note)
                    )

                    val overview = uiState.syncOverview
                    if (overview != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        SyncOverviewSection(overview)
                    } else if (showSyncOverviewLoading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        SyncOverviewLoadingRow()
                    }
                }

                SignOutSettingsButton(
                    onClick = { viewModel.signOut() }
                )
            }
        }
    }
}

@Composable
private fun AccountInfoSurface(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsSecondaryCardRadius))
            .background(SettingsRightSurfaceColor)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
private fun AccountInlineNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioColors.TextTertiary
    )
}

@Composable
private fun AccountStatusLine(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = NuvioColors.Secondary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label  ",
            style = MaterialTheme.typography.labelSmall,
            color = NuvioColors.TextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SyncOverviewSection(overview: SyncOverview) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.account_total_label),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.Secondary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(100.dp)
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProfileStatValue(overview.totalAddons, stringResource(R.string.account_stat_addons))
                ProfileStatValue(overview.totalPlugins, stringResource(R.string.account_stat_plugins))
                ProfileStatValue(overview.totalLibrary, stringResource(R.string.account_stat_library))
                ProfileStatValue(overview.totalWatchProgress, stringResource(R.string.account_stat_progress))
                ProfileStatValue(overview.totalWatchedItems, stringResource(R.string.account_stat_watched))
            }
        }

        overview.perProfile.forEach { profile ->
            ProfileSyncRow(profile)
        }
    }
}

@Composable
private fun ProfileSyncRow(profile: ProfileSyncStats) {
    val color = runCatching {
        Color(android.graphics.Color.parseColor(profile.avatarColorHex))
    }.getOrDefault(Color(0xFF1E88E5))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = profile.profileName.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = profile.profileName,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(70.dp)
        )

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ProfileStatValue(profile.addons, stringResource(R.string.account_stat_addons))
            ProfileStatValue(profile.plugins, stringResource(R.string.account_stat_plugins))
            ProfileStatValue(profile.library, stringResource(R.string.account_stat_library))
            ProfileStatValue(profile.watchProgress, stringResource(R.string.account_stat_progress))
            ProfileStatValue(profile.watchedItems, stringResource(R.string.account_stat_watched))
        }
    }
}

@Composable
private fun ProfileStatValue(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            fontSize = 12.sp,
            color = if (count > 0) NuvioColors.TextPrimary else NuvioColors.TextTertiary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = label,
            fontSize = 8.sp,
            color = NuvioColors.TextTertiary
        )
    }
}

@Composable
private fun SyncOverviewLoadingRow() {
    Text(
        text = stringResource(R.string.account_loading_sync),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioColors.TextSecondary
    )
}

@Composable
private fun SettingsActionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(SettingsSecondaryCardRadius)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = SettingsRightSurfaceColor,
            focusedContainerColor = SettingsRightSurfaceFocusedColor
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isFocused) NuvioColors.Primary else NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = NuvioColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SignOutSettingsButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(SettingsSecondaryCardRadius)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = SettingsRightSurfaceColor,
            focusedContainerColor = SettingsRightSurfaceFocusedColor
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color(0xFFF44336)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.account_sign_out),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFF44336),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

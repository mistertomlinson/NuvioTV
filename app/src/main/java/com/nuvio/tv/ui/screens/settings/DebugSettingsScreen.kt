@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun DebugSettingsContent(
    viewModel: DebugSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showErrorDialog by remember { mutableStateOf(false) }
    val debugListState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.debug_title),
            subtitle = stringResource(R.string.debug_subtitle)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                state = debugListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item(key = "debug_settings_group") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement =
                            Arrangement.spacedBy(SettingsRowGap)
                    ) {
                        SettingsActionRow(
                            title = stringResource(
                                R.string.debug_playback_error_title
                            ),
                            subtitle = stringResource(
                                R.string.debug_playback_error_subtitle
                            ),
                            onClick = { showErrorDialog = true },
                            modifier =
                                if (initialFocusRequester != null) {
                                    Modifier.focusRequester(
                                        initialFocusRequester
                                    )
                                } else {
                                    Modifier
                                },
                            showDivider = false,
                            groupPosition =
                                SettingsGroupPosition.TOP
                        )

                        SettingsToggleRow(
                            title = stringResource(
                                R.string.debug_account_tab_title
                            ),
                            subtitle = stringResource(
                                R.string.debug_account_tab_subtitle
                            ),
                            checked = uiState.accountTabEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    DebugSettingsEvent.ToggleAccountTab(
                                        !uiState.accountTabEnabled
                                    )
                                )
                            },
                            showDivider = false,
                            groupPosition =
                                SettingsGroupPosition.MIDDLE
                        )

                        SettingsToggleRow(
                            title = stringResource(
                                R.string.debug_sync_code_title
                            ),
                            subtitle = stringResource(
                                R.string.debug_sync_code_subtitle
                            ),
                            checked =
                                uiState.syncCodeFeaturesEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    DebugSettingsEvent
                                        .ToggleSyncCodeFeatures(
                                            !uiState
                                                .syncCodeFeaturesEnabled
                                        )
                                )
                            },
                            showDivider = false,
                            groupPosition =
                                SettingsGroupPosition.MIDDLE
                        )

                        DebugGenerateLibraryGroup(
                            isLoading =
                                uiState.generateLibraryLoading,
                            result =
                                uiState.generateLibraryResult,
                            onGenerate = { count ->
                                viewModel.onEvent(
                                    DebugSettingsEvent
                                        .GenerateLibraryItems(count)
                                )
                            }
                        )

                        DebugSignInGroup(
                            isLoading = uiState.signInLoading,
                            result = uiState.signInResult,
                            onSignIn = { email, password ->
                                viewModel.onEvent(
                                    DebugSettingsEvent.SignIn(
                                        email,
                                        password
                                    )
                                )
                            }
                        )
                    }
                }
            }

            SettingsVerticalScrollIndicators(
                state = debugListState
            )
        }
    }

    if (showErrorDialog) {
        NuvioDialog(
            glass = true,
            enhancedGlass = true,
            onDismiss = { showErrorDialog = false },
            title = stringResource(
                R.string.debug_error_dialog_title
            ),
            subtitle = stringResource(
                R.string.debug_error_dialog_subtitle
            )
        ) {
            DebugDialogButton(
                text = stringResource(R.string.debug_dismiss),
                onClick = { showErrorDialog = false }
            )
        }
    }
}

@Composable
private fun DebugGenerateLibraryGroup(
    isLoading: Boolean,
    result: String?,
    onGenerate: (count: Int) -> Unit
) {
    var countText by remember { mutableStateOf("") }
    val count = countText.toIntOrNull()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
    ) {
        DebugFormSurface(
            groupPosition = SettingsGroupPosition.MIDDLE,
            title = stringResource(
                R.string.debug_generate_library_title
            ),
            subtitle = stringResource(
                R.string.debug_generate_library_subtitle
            ),
            result = result
        ) {
            InputField(
                value = countText,
                onValueChange = {
                    countText =
                        it.filter { c -> c.isDigit() }
                },
                placeholder = stringResource(
                    R.string.debug_generate_library_placeholder
                ),
                keyboardType = KeyboardType.Number,
                containerColor = SettingsInsetControlColor,
                focusedContainerColor = SettingsInsetControlFocusedColor
            )
        }

        SettingsActionRow(
            title =
                if (isLoading) {
                    stringResource(
                        R.string.debug_generating_library
                    )
                } else {
                    stringResource(
                        R.string.debug_generate_library_button
                    )
                },
            subtitle = null,
            onClick = {
                if (count != null && count > 0) {
                    onGenerate(count)
                }
            },
            enabled =
                !isLoading &&
                    count != null &&
                    count > 0,
            showDivider = false,
            groupPosition = SettingsGroupPosition.MIDDLE
        )
    }
}

@Composable
private fun DebugSignInGroup(
    isLoading: Boolean,
    result: String?,
    onSignIn: (email: String, password: String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
    ) {
        DebugFormSurface(
            groupPosition = SettingsGroupPosition.MIDDLE,
            title = stringResource(
                R.string.debug_manual_signin_title
            ),
            subtitle = stringResource(
                R.string.debug_manual_signin_subtitle
            ),
            result = result
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InputField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = stringResource(
                        R.string.debug_email_placeholder
                    ),
                    keyboardType = KeyboardType.Email,
                    containerColor = SettingsInsetControlColor,
                    focusedContainerColor = SettingsInsetControlFocusedColor
                )

                InputField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(
                        R.string.debug_password_placeholder
                    ),
                    isPassword = true,
                    containerColor = SettingsInsetControlColor,
                    focusedContainerColor = SettingsInsetControlFocusedColor
                )
            }
        }

        SettingsActionRow(
            title =
                if (isLoading) {
                    stringResource(R.string.debug_signing_in)
                } else {
                    stringResource(R.string.debug_sign_in)
                },
            subtitle = null,
            onClick = {
                onSignIn(
                    email.trim(),
                    password
                )
            },
            enabled =
                !isLoading &&
                    email.isNotBlank() &&
                    password.isNotBlank(),
            showDivider = false,
            groupPosition = SettingsGroupPosition.BOTTOM
        )
    }
}

@Composable
private fun DebugFormSurface(
    groupPosition: SettingsGroupPosition,
    title: String,
    subtitle: String,
    result: String?,
    content: @Composable () -> Unit
) {
    val shape = settingsGroupShape(groupPosition)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SettingsRightSurfaceColor)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioColors.TextPrimary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary
        )

        content()

        if (result != null) {
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (result.startsWith("Failed")) {
                        NuvioColors.Error
                    } else {
                        NuvioColors.Secondary
                    }
            )
        }
    }
}

@Composable
private fun DebugDialogButton(
    text: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = SettingsRightSurfaceColor,
            focusedContainerColor =
                SettingsRightSurfaceFocusedColor
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = CardDefaults.shape(
            settingsGroupShape(
                SettingsGroupPosition.SINGLE
            )
        ),
        scale = CardDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    vertical = 12.dp,
                    horizontal = 16.dp
                ),
            textAlign =
                androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

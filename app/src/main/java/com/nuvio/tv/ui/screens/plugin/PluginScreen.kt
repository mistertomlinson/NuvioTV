@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.plugin

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.glassDialogFocusTransform
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.screens.settings.SettingsGlassCanvasBrush
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupPosition
import com.nuvio.tv.ui.screens.settings.SettingsRightSurfaceColor
import com.nuvio.tv.ui.screens.settings.SettingsRightSurfaceFocusedColor
import com.nuvio.tv.ui.screens.settings.SettingsRowGap
import com.nuvio.tv.ui.screens.settings.SettingsDialogGlassIdleColor
import com.nuvio.tv.ui.screens.settings.SettingsDialogGlassInsetColor
import com.nuvio.tv.ui.screens.settings.SettingsDialogPillShape
import com.nuvio.tv.ui.screens.settings.SettingsGlassRowFocusedColor
import com.nuvio.tv.ui.screens.settings.settingsGroupShape
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
fun PluginScreen(
    viewModel: PluginViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsGlassCanvasBrush)
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        PluginScreenContent(
            uiState = uiState,
            viewModel = viewModel
        )
    }
}

@Composable
fun PluginScreenContent(
    uiState: PluginUiState = PluginUiState(),
    viewModel: PluginViewModel = hiltViewModel(),
    showHeader: Boolean = true
) {
    var repoUrl by remember { mutableStateOf("") }

    if (!showHeader) {
        EmbeddedPluginSettingsContent(
            uiState = uiState,
            viewModel = viewModel,
            repoUrl = repoUrl,
            onRepoUrlChange = { repoUrl = it }
        )
        return
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopQrMode() }
    }

    // Clear messages after delay
    LaunchedEffect(uiState.successMessage) {
        if (uiState.successMessage != null) {
            delay(3000)
            viewModel.onEvent(PluginUiEvent.ClearSuccess)
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage != null) {
            delay(5000)
            viewModel.onEvent(PluginUiEvent.ClearError)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (showHeader) {
                item {
                    PluginHeader(
                        pluginsEnabled = uiState.pluginsEnabled,
                        isReadOnly = viewModel.isReadOnly,
                        onPluginsEnabledChange = { viewModel.onEvent(PluginUiEvent.SetPluginsEnabled(it)) }
                    )
                }
            }

            if (viewModel.isReadOnly) {
                item {
                    androidx.compose.material3.Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF1A3A5C)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        androidx.tv.material3.Text(
                            text = stringResource(R.string.plugin_readonly_notice),
                            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                            color = com.nuvio.tv.ui.theme.NuvioColors.TextSecondary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            if (!viewModel.isReadOnly) {
                item {
                    AddRepositoryInline(
                        url = repoUrl,
                        onUrlChange = { repoUrl = it },
                        onConfirm = {
                            if (repoUrl.isNotBlank()) {
                                viewModel.onEvent(PluginUiEvent.AddRepository(repoUrl))
                                repoUrl = ""
                            }
                        },
                        isLoading = uiState.isAddingRepo
                    )
                }

                // Manage from phone card
                item {
                    ManageFromPhoneCard(onClick = { viewModel.onEvent(PluginUiEvent.StartQrMode) })
                }
            }

            // Repositories section
            item {
                Text(
                    text = stringResource(R.string.plugin_repositories_section, uiState.repositories.size),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )
            }

            if (uiState.repositories.isEmpty()) {
                item {
                    EmptyState(
                        message = stringResource(R.string.plugin_no_repos),
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }

            items(uiState.repositories, key = { it.id }) { repo ->
                RepositoryCard(
                    repository = repo,
                    onRefresh = { viewModel.onEvent(PluginUiEvent.RefreshRepository(repo.id)) },
                    onRemove = { viewModel.onEvent(PluginUiEvent.RemoveRepository(repo.id)) },
                    isLoading = uiState.isLoading,
                    isReadOnly = viewModel.isReadOnly
                )
            }

            // Scrapers section
            if (uiState.scrapers.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.plugin_providers_section, uiState.scrapers.size),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioColors.TextPrimary
                    )
                }

                items(uiState.scrapers, key = { it.id }) { scraper ->
                    ScraperCard(
                        scraper = scraper,
                        onToggle = { enabled ->
                            viewModel.onEvent(PluginUiEvent.ToggleScraper(scraper.id, enabled))
                        },
                        onTest = { viewModel.onEvent(PluginUiEvent.TestScraper(scraper.id)) },
                        isTesting = uiState.isTesting && uiState.testScraperId == scraper.id,
                        testResults = if (uiState.testScraperId == scraper.id) uiState.testResults else null,
                        isReadOnly = viewModel.isReadOnly
                    )
                }
            }
        }

    // Success/Error Messages
    MessageOverlay(
        successMessage = uiState.successMessage,
        errorMessage = uiState.errorMessage
    )

    // Keep exactly one modal visible at a time. The QR server stays alive
    // while a pending change is being reviewed and returns after Reject.
    if (
        uiState.isQrModeActive &&
        uiState.pendingRepoChange == null
    ) {
        QrCodeOverlay(
            qrBitmap = uiState.qrCodeBitmap,
            serverUrl = uiState.serverUrl,
            onClose = {
                viewModel.onEvent(PluginUiEvent.StopQrMode)
            }
        )
    }

    uiState.pendingRepoChange?.let { pending ->
        ConfirmRepoChangesDialog(
            pendingChange = pending,
            onConfirm = {
                viewModel.onEvent(
                    PluginUiEvent.ConfirmPendingRepoChange
                )
            },
            onReject = {
                viewModel.onEvent(
                    PluginUiEvent.RejectPendingRepoChange
                )
            }
        )
    }
    }
}


@Composable
private fun EmbeddedPluginSettingsContent(
    uiState: PluginUiState,
    viewModel: PluginViewModel,
    repoUrl: String,
    onRepoUrlChange: (String) -> Unit
) {
    DisposableEffect(Unit) {
        onDispose { viewModel.stopQrMode() }
    }

    LaunchedEffect(uiState.successMessage) {
        if (uiState.successMessage != null) {
            delay(3000)
            viewModel.onEvent(PluginUiEvent.ClearSuccess)
        }
    }
    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage != null) {
            delay(5000)
            viewModel.onEvent(PluginUiEvent.ClearError)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
        ) {
            if (viewModel.isReadOnly) {
                item(key = "plugin_readonly") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                SettingsRightSurfaceColor,
                                settingsGroupShape(
                                    SettingsGroupPosition.SINGLE
                                )
                            )
                            .padding(
                                horizontal = 14.dp,
                                vertical = 12.dp
                            )
                    ) {
                        Text(
                            text = stringResource(
                                R.string.plugin_readonly_notice
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextSecondary
                        )
                    }
                }
            } else {
                item(key = "plugin_add_repository") {
                    EmbeddedAddRepositoryRow(
                        url = repoUrl,
                        onUrlChange = onRepoUrlChange,
                        onConfirm = {
                            if (repoUrl.isNotBlank()) {
                                viewModel.onEvent(
                                    PluginUiEvent.AddRepository(repoUrl)
                                )
                                onRepoUrlChange("")
                            }
                        },
                        isLoading = uiState.isAddingRepo
                    )
                }

                item(key = "plugin_manage_phone") {
                    SettingsActionRow(
                        title = stringResource(
                            R.string.plugin_manage_from_phone_title
                        ),
                        subtitle = stringResource(
                            R.string.plugin_manage_from_phone_subtitle
                        ),
                        onClick = {
                            viewModel.onEvent(
                                PluginUiEvent.StartQrMode
                            )
                        },
                        trailingIcon = Icons.Default.PhoneAndroid,
                        modifier = Modifier.height(58.dp),
                        showDivider = false,
                        groupPosition =
                            SettingsGroupPosition.BOTTOM
                    )
                }
            }

            if (uiState.repositories.isNotEmpty()) {
                item(key = "plugin_repo_label") {
                    Text(
                        text = stringResource(
                            R.string.plugin_repositories_section,
                            uiState.repositories.size
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextSecondary,
                        modifier = Modifier.padding(
                            start = 4.dp,
                            top = 10.dp,
                            bottom = 2.dp
                        )
                    )
                }

                itemsIndexed(
                    items = uiState.repositories,
                    key = { _, repo -> repo.id }
                ) { index, repo ->
                    EmbeddedRepositoryRow(
                        repository = repo,
                        onRefresh = {
                            viewModel.onEvent(
                                PluginUiEvent.RefreshRepository(
                                    repo.id
                                )
                            )
                        },
                        onRemove = {
                            viewModel.onEvent(
                                PluginUiEvent.RemoveRepository(
                                    repo.id
                                )
                            )
                        },
                        isLoading = uiState.isLoading,
                        isReadOnly = viewModel.isReadOnly,
                        groupPosition = when {
                            uiState.repositories.size == 1 ->
                                SettingsGroupPosition.SINGLE
                            index == 0 ->
                                SettingsGroupPosition.TOP
                            index == uiState.repositories.lastIndex ->
                                SettingsGroupPosition.BOTTOM
                            else ->
                                SettingsGroupPosition.MIDDLE
                        }
                    )
                }
            }

            if (uiState.scrapers.isNotEmpty()) {
                item(key = "plugin_provider_label") {
                    Text(
                        text = stringResource(
                            R.string.plugin_providers_section,
                            uiState.scrapers.size
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextSecondary,
                        modifier = Modifier.padding(
                            start = 4.dp,
                            top = 10.dp,
                            bottom = 2.dp
                        )
                    )
                }

                itemsIndexed(
                    items = uiState.scrapers,
                    key = { _, scraper -> scraper.id }
                ) { index, scraper ->
                    EmbeddedScraperRow(
                        scraper = scraper,
                        onToggle = { enabled ->
                            viewModel.onEvent(
                                PluginUiEvent.ToggleScraper(
                                    scraper.id,
                                    enabled
                                )
                            )
                        },
                        onTest = {
                            viewModel.onEvent(
                                PluginUiEvent.TestScraper(
                                    scraper.id
                                )
                            )
                        },
                        isTesting =
                            uiState.isTesting &&
                                uiState.testScraperId ==
                                    scraper.id,
                        testResults =
                            if (
                                uiState.testScraperId ==
                                scraper.id
                            ) {
                                uiState.testResults
                            } else {
                                null
                            },
                        isReadOnly = viewModel.isReadOnly,
                        groupPosition = when {
                            uiState.scrapers.size == 1 ->
                                SettingsGroupPosition.SINGLE
                            index == 0 ->
                                SettingsGroupPosition.TOP
                            index == uiState.scrapers.lastIndex ->
                                SettingsGroupPosition.BOTTOM
                            else ->
                                SettingsGroupPosition.MIDDLE
                        }
                    )
                }
            }
        }

        MessageOverlay(
            successMessage = uiState.successMessage,
            errorMessage = uiState.errorMessage
        )

        if (
            uiState.isQrModeActive &&
            uiState.pendingRepoChange == null
        ) {
            QrCodeOverlay(
                qrBitmap = uiState.qrCodeBitmap,
                serverUrl = uiState.serverUrl,
                onClose = {
                    viewModel.onEvent(
                        PluginUiEvent.StopQrMode
                    )
                }
            )
        }

        uiState.pendingRepoChange?.let { pending ->
            ConfirmRepoChangesDialog(
                pendingChange = pending,
                onConfirm = {
                    viewModel.onEvent(
                        PluginUiEvent
                            .ConfirmPendingRepoChange
                    )
                },
                onReject = {
                    viewModel.onEvent(
                        PluginUiEvent
                            .RejectPendingRepoChange
                    )
                }
            )
        }
    }
}

@Composable
private fun EmbeddedAddRepositoryRow(
    url: String,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    isLoading: Boolean
) {
    val keyboardController =
        LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    val shape = settingsGroupShape(SettingsGroupPosition.TOP)

    LaunchedEffect(isEditing) {
        if (isEditing) {
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    androidx.tv.material3.Card(
        onClick = { isEditing = true },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                isFocused = state.isFocused || state.hasFocus
            },
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = SettingsRightSurfaceColor,
            focusedContainerColor =
                SettingsRightSurfaceFocusedColor
        ),
        border = androidx.tv.material3.CardDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = androidx.tv.material3.CardDefaults.shape(shape),
        scale = androidx.tv.material3.CardDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint =
                    if (isFocused) {
                        NuvioColors.Primary
                    } else {
                        NuvioColors.TextSecondary
                    }
            )
            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.plugin_add_repository
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                BasicTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onFocusChanged {
                            if (!it.isFocused && isEditing) {
                                isEditing = false
                                keyboardController?.hide()
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            onConfirm()
                            isEditing = false
                            keyboardController?.hide()
                        }
                    ),
                    textStyle =
                        MaterialTheme.typography.bodySmall.copy(
                            color = NuvioColors.TextSecondary
                        ),
                    cursorBrush = SolidColor(
                        if (isEditing) {
                            NuvioColors.Primary
                        } else {
                            Color.Transparent
                        }
                    ),
                    decorationBox = { innerTextField ->
                        if (url.isEmpty()) {
                            Text(
                                text =
                                    "https://example.com/manifest.json",
                                style =
                                    MaterialTheme.typography.bodySmall,
                                color =
                                    NuvioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }

            if (isLoading) {
                LoadingIndicator(
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun EmbeddedRepositoryRow(
    repository: PluginRepository,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    isLoading: Boolean,
    isReadOnly: Boolean,
    groupPosition: SettingsGroupPosition
) {
    val shape = settingsGroupShape(groupPosition)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SettingsRightSurfaceColor, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = repository.name,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.plugin_providers_count,
                    repository.scraperCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary
            )
        }

        if (!isReadOnly) {
            androidx.tv.material3.Card(
                onClick = {
                    if (!isLoading) onRefresh()
                },
                colors = androidx.tv.material3.CardDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor =
                        SettingsRightSurfaceFocusedColor
                ),
                border = androidx.tv.material3.CardDefaults.border(
                    border = Border.None,
                    focusedBorder = Border.None
                ),
                shape = androidx.tv.material3.CardDefaults.shape(
                    RoundedCornerShape(10.dp)
                ),
                scale = androidx.tv.material3.CardDefaults.scale(
                    focusedScale = 1f,
                    pressedScale = 1f
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = NuvioColors.TextSecondary,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(18.dp)
                )
            }

            androidx.tv.material3.Card(
                onClick = {
                    if (!isLoading) onRemove()
                },
                colors = androidx.tv.material3.CardDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor =
                        SettingsRightSurfaceFocusedColor
                ),
                border = androidx.tv.material3.CardDefaults.border(
                    border = Border.None,
                    focusedBorder = Border.None
                ),
                shape = androidx.tv.material3.CardDefaults.shape(
                    RoundedCornerShape(10.dp)
                ),
                scale = androidx.tv.material3.CardDefaults.scale(
                    focusedScale = 1f,
                    pressedScale = 1f
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = NuvioColors.Error,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(18.dp)
                )
            }
        }
    }
}


@Composable
private fun EmbeddedScraperRow(
    scraper: ScraperInfo,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    isTesting: Boolean,
    testResults: List<LocalScraperResult>?,
    isReadOnly: Boolean,
    groupPosition: SettingsGroupPosition
) {
    val shape = settingsGroupShape(groupPosition)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SettingsRightSurfaceColor, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.tv.material3.Card(
            onClick = {
                if (!isReadOnly) {
                    onToggle(!scraper.enabled)
                }
            },
            modifier = Modifier.weight(1f),
            colors = androidx.tv.material3.CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor =
                    SettingsRightSurfaceFocusedColor
            ),
            border = androidx.tv.material3.CardDefaults.border(
                border = Border.None,
                focusedBorder = Border.None
            ),
            shape = androidx.tv.material3.CardDefaults.shape(
                RoundedCornerShape(10.dp)
            ),
            scale = androidx.tv.material3.CardDefaults.scale(
                focusedScale = 1f,
                pressedScale = 1f
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 49.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = scraper.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("Version ")
                            append(scraper.version)
                            if (testResults != null) {
                                append("  •  ")
                                append(testResults.size)
                                append(" test result")
                                if (testResults.size != 1) {
                                    append("s")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text =
                        if (scraper.enabled) {
                            stringResource(R.string.plugin_enabled)
                        } else {
                            stringResource(R.string.plugin_disabled)
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (scraper.enabled) {
                            NuvioColors.Secondary
                        } else {
                            NuvioColors.TextTertiary
                        }
                )
            }
        }

        if (!isReadOnly) {
            Spacer(modifier = Modifier.width(4.dp))
            androidx.tv.material3.Card(
                onClick = {
                    if (!isTesting) onTest()
                },
                colors = androidx.tv.material3.CardDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor =
                        SettingsRightSurfaceFocusedColor
                ),
                border = androidx.tv.material3.CardDefaults.border(
                    border = Border.None,
                    focusedBorder = Border.None
                ),
                shape = androidx.tv.material3.CardDefaults.shape(
                    RoundedCornerShape(10.dp)
                ),
                scale = androidx.tv.material3.CardDefaults.scale(
                    focusedScale = 1f,
                    pressedScale = 1f
                )
            ) {
                if (isTesting) {
                    LoadingIndicator(
                        modifier = Modifier
                            .padding(10.dp)
                            .size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Test",
                        tint = NuvioColors.TextSecondary,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginHeader(
    pluginsEnabled: Boolean,
    isReadOnly: Boolean,
    onPluginsEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.plugin_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.Secondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.plugin_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary
            )
        }

        Surface(
            onClick = {
                if (!isReadOnly) {
                    onPluginsEnabledChange(!pluginsEnabled)
                }
            },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.FocusBackground
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(12.dp)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (pluginsEnabled) stringResource(R.string.plugin_enabled) else stringResource(R.string.plugin_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pluginsEnabled) NuvioColors.Secondary else NuvioColors.TextSecondary
                )
                Switch(
                    checked = pluginsEnabled,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NuvioColors.Secondary,
                        checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f)
                    )
                )
            }
        }
    }
}

@Composable
private fun AddRepositoryInline(
    url: String,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    isLoading: Boolean
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val textFieldFocusRequester = remember { FocusRequester() }
    var isEditing by remember { mutableStateOf(false) }

    // When isEditing changes to true, focus the text field and show keyboard
    LaunchedEffect(isEditing) {
        if (isEditing) {
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NuvioColors.BackgroundCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.plugin_add_repository),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Surface always stays in the tree for stable D-pad focus
                Surface(
                    onClick = { isEditing = true },
                    modifier = Modifier.weight(1f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = NuvioColors.BackgroundElevated,
                        focusedContainerColor = NuvioColors.BackgroundElevated
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(
                            border = BorderStroke(1.dp, NuvioColors.Border),
                            shape = RoundedCornerShape(12.dp)
                        ),
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        BasicTextField(
                            value = url,
                            onValueChange = onUrlChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(textFieldFocusRequester)
                                .onFocusChanged {
                                    if (!it.isFocused && isEditing) {
                                        isEditing = false
                                        keyboardController?.hide()
                                    }
                                },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    onConfirm()
                                    isEditing = false
                                    keyboardController?.hide()
                                    focusManager.clearFocus(force = true)
                                }
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = NuvioColors.TextPrimary
                            ),
                            cursorBrush = SolidColor(if (isEditing) NuvioColors.Primary else Color.Transparent),
                            decorationBox = { innerTextField ->
                                if (url.isEmpty()) {
                                    Text(
                                        text = "https://example.com/manifest.json",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NuvioColors.TextTertiary
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }

                Button(
                    onClick = {
                        onConfirm()
                        isEditing = false
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                    },
                    enabled = !isLoading && url.isNotBlank(),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.Secondary,
                        focusedContainerColor = NuvioColors.SecondaryVariant,
                        contentColor = NuvioColors.OnSecondary,
                        focusedContentColor = NuvioColors.OnSecondaryVariant
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(50)
                        )
                    )
                ) {
                    if (isLoading) {
                        LoadingIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.plugin_add_btn))
                }
            }
        }
    }
}

@Composable
private fun ManageFromPhoneCard(onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(18.dp)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) NuvioColors.Secondary else NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.plugin_manage_from_phone_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioColors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.plugin_manage_from_phone_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioColors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QrCodeOverlay(
    qrBitmap: Bitmap?,
    serverUrl: String?,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NuvioDialog(
        glass = true,
        enhancedGlass = true,
        compact = true,
        onDismiss = onClose,
        title = stringResource(
            R.string.plugin_manage_from_phone_title
        ),
        subtitle = stringResource(
            R.string.plugin_qr_instruction
        ),
        width = 400.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(220.dp),
                    contentScale = ContentScale.Fit
                )
            }

            if (serverUrl != null) {
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .glassDialogFocusTransform(),
                colors = ButtonDefaults.colors(
                    containerColor =
                        SettingsDialogGlassIdleColor,
                    focusedContainerColor =
                        SettingsGlassRowFocusedColor,
                    contentColor =
                        NuvioColors.TextSecondary,
                    focusedContentColor =
                        NuvioColors.TextPrimary
                ),
                scale = ButtonDefaults.scale(
                    focusedScale = 1f,
                    pressedScale = 1f
                ),
                border = ButtonDefaults.border(
                    border = Border.None,
                    focusedBorder = Border.None
                ),
                shape = ButtonDefaults.shape(
                    SettingsDialogPillShape
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.plugin_qr_close)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmRepoChangesDialog(
    pendingChange: PendingRepoChangeInfo,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NuvioDialog(
        glass = true,
        enhancedGlass = true,
        onDismiss = onReject,
        title = stringResource(
            R.string.plugin_confirm_title
        ),
        subtitle = stringResource(
            R.string.plugin_confirm_subtitle
        ),
        width = 520.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .background(
                    color = SettingsDialogGlassInsetColor,
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .verticalScroll(scrollState)
            ) {
                if (pendingChange.addedUrls.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.plugin_confirm_added
                        ),
                        style =
                            MaterialTheme.typography.titleSmall,
                        color = NuvioColors.Success,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )

                    pendingChange.addedUrls.forEach { url ->
                        Text(
                            text = "+ $url",
                            style =
                                MaterialTheme.typography.bodySmall,
                            color = NuvioColors.Success,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = 8.dp,
                                    bottom = 2.dp
                                )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (pendingChange.removedUrls.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.plugin_confirm_removed
                        ),
                        style =
                            MaterialTheme.typography.titleSmall,
                        color = NuvioColors.Error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )

                    pendingChange.removedUrls.forEach { url ->
                        Text(
                            text = "- $url",
                            style =
                                MaterialTheme.typography.bodySmall,
                            color = NuvioColors.Error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = 8.dp,
                                    bottom = 2.dp
                                )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (
                    pendingChange.addedUrls.isEmpty() &&
                    pendingChange.removedUrls.isEmpty()
                ) {
                    Text(
                        text = stringResource(
                            R.string.plugin_confirm_no_changes
                        ),
                        style =
                            MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
        }

        Text(
            text = stringResource(
                R.string.plugin_confirm_total,
                pendingChange.proposedUrls.size
            ),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextTertiary,
            modifier = Modifier.fillMaxWidth()
        )

        if (pendingChange.isApplying) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator(
                    modifier = Modifier.size(36.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onReject,
                    modifier = Modifier
                        .weight(1f)
                        .glassDialogFocusTransform(),
                    colors = ButtonDefaults.colors(
                        containerColor =
                            SettingsDialogGlassIdleColor,
                        focusedContainerColor =
                            SettingsGlassRowFocusedColor,
                        contentColor =
                            NuvioColors.TextSecondary,
                        focusedContentColor =
                            NuvioColors.TextPrimary
                    ),
                    scale = ButtonDefaults.scale(
                        focusedScale = 1f,
                        pressedScale = 1f
                    ),
                    border = ButtonDefaults.border(
                        border = Border.None,
                        focusedBorder = Border.None
                    ),
                    shape = ButtonDefaults.shape(
                        SettingsDialogPillShape
                    )
                ) {
                    Text(
                        stringResource(
                            R.string.plugin_confirm_reject
                        )
                    )
                }

                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .glassDialogFocusTransform(),
                    colors = ButtonDefaults.colors(
                        containerColor =
                            NuvioColors.Secondary,
                        focusedContainerColor =
                            SettingsGlassRowFocusedColor,
                        contentColor =
                            NuvioColors.OnSecondary,
                        focusedContentColor =
                            NuvioColors.TextPrimary
                    ),
                    scale = ButtonDefaults.scale(
                        focusedScale = 1f,
                        pressedScale = 1f
                    ),
                    border = ButtonDefaults.border(
                        border = Border.None,
                        focusedBorder = Border.None
                    ),
                    shape = ButtonDefaults.shape(
                        SettingsDialogPillShape
                    )
                ) {
                    Text(
                        stringResource(
                            R.string.plugin_confirm_confirm
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun RepositoryCard(
    repository: PluginRepository,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    isLoading: Boolean,
    isReadOnly: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioColors.BackgroundCard,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repository.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.plugin_providers_count, repository.scraperCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
                Text(
                    text = stringResource(R.string.plugin_updated_format, formatDate(repository.lastUpdated)),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }

            if (!isReadOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.Surface,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh"
                    )
                }

                Button(
                    onClick = onRemove,
                    enabled = !isLoading,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.Surface,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Error
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove"
                    )
                }
            }
        }
    }
}

@Composable
private fun ScraperCard(
    scraper: ScraperInfo,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    isTesting: Boolean,
    testResults: List<LocalScraperResult>?,
    isReadOnly: Boolean = false
) {
    var showResults by remember { mutableStateOf(false) }

    LaunchedEffect(testResults) {
        showResults = testResults != null
    }

    // Use Box instead of focusable Surface to allow child focus
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioColors.BackgroundCard,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = scraper.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioColors.TextPrimary
                        )

                        // Type badges
                        scraper.supportedTypes.forEach { type ->
                            TypeBadge(type = type)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Version ${scraper.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Test button
                    Button(
                        onClick = onTest,
                        enabled = !isTesting && scraper.enabled,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.Surface,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        if (isTesting) {
                            LoadingIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Test",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.plugin_test_btn))
                    }

                    // Enable toggle
                    if (!isReadOnly) {
                        Switch(
                            checked = scraper.enabled,
                            onCheckedChange = onToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NuvioColors.Secondary,
                                checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }

            // Test results
            AnimatedVisibility(visible = showResults && testResults != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.plugin_test_results, testResults?.size ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    testResults?.take(3)?.forEach { result ->
                        TestResultItem(result = result)
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if ((testResults?.size ?: 0) > 3) {
                        Text(
                            text = stringResource(R.string.plugin_and_more, testResults!!.size - 3),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    val color = when (type.lowercase()) {
        "movie" -> Color(0xFF4CAF50)
        "series", "show", "tv" -> Color(0xFF2196F3)
        else -> NuvioColors.TextSecondary
    }

    Box(
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = type.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun TestResultItem(result: LocalScraperResult) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioColors.Surface,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                result.quality?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioColors.Primary
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}


@Composable
private fun EmptyState(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MessageOverlay(
    successMessage: String?,
    errorMessage: String?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = successMessage != null || errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val isSuccess = successMessage != null
            val message = successMessage ?: errorMessage ?: ""

            Surface(
                onClick = { },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSuccess)
                        Color(0xFF2E7D32).copy(alpha = 0.9f)
                    else
                        Color(0xFFC62828).copy(alpha = 0.9f)
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
}

package com.nuvio.tv.ui.screens.addon

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Refresh
import kotlinx.coroutines.delay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.settings.SettingsGlassBorderColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassControlIdleColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassGroupColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassRowColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassRowFocusedColor
import com.nuvio.tv.ui.screens.settings.SettingsCompactContent
import com.nuvio.tv.ui.screens.settings.SettingsGroupPosition
import com.nuvio.tv.ui.screens.settings.SettingsRightSurfaceColor
import com.nuvio.tv.ui.screens.settings.SettingsRightSurfaceFocusedColor
import com.nuvio.tv.ui.screens.settings.SettingsInsetControlColor
import com.nuvio.tv.ui.screens.settings.SettingsRowGap
import com.nuvio.tv.ui.screens.settings.settingsGroupShape
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddonManagerScreen(
    viewModel: AddonManagerViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToCatalogOrder: () -> Unit = {},
    onRefreshCatalogs: () -> Unit = {}
) {
    SettingsCompactContent {
        AddonManagerScreenContent(
            viewModel = viewModel,
            showBuiltInHeader = showBuiltInHeader,
            onNavigateToCatalogOrder = onNavigateToCatalogOrder,
            onRefreshCatalogs = onRefreshCatalogs
        )
    }
}

@Composable
private fun AddonManagerScreenContent(
    viewModel: AddonManagerViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToCatalogOrder: () -> Unit = {},
    onRefreshCatalogs: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val surfaceFocusRequester = remember { FocusRequester() }
    val manageFromPhoneFocusRequester = remember { FocusRequester() }
    val catalogOrderFocusRequester = remember { FocusRequester() }

    /*
     * The Addons destination is removed from composition while one of its child
     * routes is on top. Save only the identity of the control that launched that
     * child; Navigation's saveable-state holder restores it when we come back.
     *
     * QR mode stays inside this destination, but uses the same restoration path.
     */
    var rememberedFocusTarget by rememberSaveable {
        mutableStateOf("input")
    }

    val defaultRefreshCatalogsSubtitle = "Re-fetch content from all installed addons"
    var refreshCatalogsSubtitle by remember { mutableStateOf(defaultRefreshCatalogsSubtitle) }
    LaunchedEffect(refreshCatalogsSubtitle) {
        if (refreshCatalogsSubtitle != defaultRefreshCatalogsSubtitle) {
            delay(5_000)
            refreshCatalogsSubtitle = defaultRefreshCatalogsSubtitle
        }
    }
    val installButtonFocusRequester = remember { FocusRequester() }
    val textFieldFocusRequester = remember { FocusRequester() }
    var isEditing by remember { mutableStateOf(false) }
    val hasHomeVisibleCatalogs = remember(uiState.installedAddons) {
        uiState.installedAddons.any { addon ->
            addon.catalogs.any { catalog -> !catalog.isSearchOnlyCatalog() }
        }
    }

    // When isEditing changes to true, focus the text field and show keyboard
    LaunchedEffect(isEditing) {
        if (isEditing) {
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val requestRememberedFocus = {
        coroutineScope.launch {
            val targetRequester = when (rememberedFocusTarget) {
                "manage_from_phone" -> manageFromPhoneFocusRequester
                "catalog_order" -> catalogOrderFocusRequester
                else -> surfaceFocusRequester
            }

            var restored = false

            for (attempt in 0 until 6) {
                withFrameNanos { }

                if (
                    runCatching {
                        targetRequester.requestFocus()
                    }.getOrDefault(false)
                ) {
                    restored = true
                    break
                }
            }

            // Preserve the existing safety behavior: if the remembered card is
            // unexpectedly unavailable, focus the always-present input surface.
            if (!restored) {
                runCatching { surfaceFocusRequester.requestFocus() }
            }
        }
    }

    LaunchedEffect(uiState.isQrModeActive, uiState.pendingChange, isEditing) {
        if (!uiState.isQrModeActive && uiState.pendingChange == null && !isEditing) {
            requestRememberedFocus()
        }
    }

    LaunchedEffect(uiState.transientMessage) {
        if (uiState.transientMessage != null) {
            delay(3200)
            viewModel.clearTransientMessage()
        }
    }

    DisposableEffect(lifecycleOwner, uiState.isQrModeActive, uiState.pendingChange, isEditing) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                !uiState.isQrModeActive &&
                uiState.pendingChange == null &&
                !isEditing
            ) {
                requestRememberedFocus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopQrMode() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
        ) {
            item {
                Text(
                    text = stringResource(R.string.addon_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (showBuiltInHeader) NuvioColors.TextPrimary else Color.Transparent,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (viewModel.isReadOnly) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = NuvioColors.Secondary.copy(alpha = 0.16f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.addon_readonly_notice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextSecondary,
                            modifier = androidx.compose.ui.Modifier.padding(16.dp)
                        )
                    }
                }
            }

            if (!viewModel.isReadOnly) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        colors = CardDefaults.cardColors(
                            containerColor = SettingsRightSurfaceColor
                        ),
                        shape = settingsGroupShape(SettingsGroupPosition.TOP)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.addon_install_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = NuvioColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Surface always stays in the tree for stable D-pad focus
                                Surface(
                                    onClick = { isEditing = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(surfaceFocusRequester),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = SettingsGlassGroupColor,
                                        focusedContainerColor = SettingsGlassGroupColor
                                    ),
                                    border = ClickableSurfaceDefaults.border(
                                        border = Border(
                                            border = BorderStroke(1.dp, SettingsGlassBorderColor),
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
                                            value = uiState.installUrl,
                                            onValueChange = viewModel::onInstallUrlChange,
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
                                                    viewModel.installAddon()
                                                    isEditing = false
                                                    keyboardController?.hide()
                                                    installButtonFocusRequester.requestFocus()
                                                }
                                            ),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = NuvioColors.TextPrimary
                                            ),
                                            cursorBrush = SolidColor(if (isEditing) NuvioColors.Primary else Color.Transparent),
                                            decorationBox = { innerTextField ->
                                                if (uiState.installUrl.isEmpty()) {
                                                    Text(
                                                        text = stringResource(R.string.addon_install_placeholder),
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
                                        viewModel.installAddon()
                                        isEditing = false
                                        keyboardController?.hide()
                                        installButtonFocusRequester.requestFocus()
                                    },
                                    enabled = !uiState.isInstalling,
                                    modifier = Modifier.focusRequester(installButtonFocusRequester),
                                    colors = ButtonDefaults.colors(
                                        containerColor = SettingsRightSurfaceColor,
                                        contentColor = NuvioColors.TextPrimary,
                                        focusedContainerColor = SettingsRightSurfaceFocusedColor,
                                        focusedContentColor = NuvioColors.Primary
                                    ),
                                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                                ) {
                                    Text(text = if (uiState.isInstalling) stringResource(R.string.addon_installing) else stringResource(R.string.addon_install_btn))
                                }
                            }

                            AnimatedVisibility(visible = uiState.error != null) {
                                Text(
                                    text = uiState.error.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioColors.Error,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                            }
                        }
                    }
                }

                // Manage from phone card
                item {
                    ManageFromPhoneCard(
                        onClick = {
                            rememberedFocusTarget = "manage_from_phone"
                            viewModel.startQrMode()
                        },
                        groupPosition =
                            if (hasHomeVisibleCatalogs) {
                                SettingsGroupPosition.MIDDLE
                            } else {
                                SettingsGroupPosition.BOTTOM
                            },
                        modifier = Modifier.focusRequester(
                            manageFromPhoneFocusRequester
                        )
                    )
                }

                if (hasHomeVisibleCatalogs) {
                    item {
                        CatalogOrderEntryCard(
                            onClick = {
                                rememberedFocusTarget = "catalog_order"
                                onNavigateToCatalogOrder()
                            },
                            groupPosition =
                                SettingsGroupPosition.MIDDLE,
                            modifier = Modifier.focusRequester(
                                catalogOrderFocusRequester
                            )
                        )
                    }
                    item {
                        RefreshCatalogsEntryCard(
                            subtitle = refreshCatalogsSubtitle,
                            groupPosition =
                                SettingsGroupPosition.BOTTOM,
                            onClick = {
                                onRefreshCatalogs()
                                refreshCatalogsSubtitle = "Catalogs refreshing…"
                            }
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.addon_installed_section),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioColors.TextPrimary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    if (uiState.isLoading && uiState.installedAddons.isEmpty()) {
                        LoadingIndicator(modifier = Modifier.height(24.dp))
                    }
                }
            }

            if (uiState.installedAddons.isEmpty() && !uiState.isLoading) {
                item {
                    Text(
                        text = stringResource(R.string.addon_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
            } else {
                itemsIndexed(
                    items = uiState.installedAddons,
                    key = { index, addon -> "${addon.id}:${addon.baseUrl}:$index" }
                ) { index, addon ->
                    AddonCard(
                        addon = addon,
                        groupPosition =
                            when {
                                uiState.installedAddons.size == 1 ->
                                    SettingsGroupPosition.SINGLE
                                index == 0 ->
                                    SettingsGroupPosition.TOP
                                index == uiState.installedAddons.lastIndex ->
                                    SettingsGroupPosition.BOTTOM
                                else ->
                                    SettingsGroupPosition.MIDDLE
                            },
                        canMoveUp = index > 0,
                        canMoveDown = index < uiState.installedAddons.lastIndex,
                        onMoveUp = { viewModel.moveAddonUp(addon.baseUrl) },
                        onMoveDown = { viewModel.moveAddonDown(addon.baseUrl) },
                        onRemove = { viewModel.removeAddon(addon.baseUrl) },
                        isReadOnly = viewModel.isReadOnly
                    )
                }
            }
        }

        if (uiState.isQrModeActive) {
            QrCodeOverlay(
                qrBitmap = uiState.qrCodeBitmap,
                serverUrl = uiState.serverUrl,
                onClose = viewModel::stopQrMode,
                hasPendingChange = uiState.pendingChange != null
            )
        }

        uiState.pendingChange?.let { pending ->
            ConfirmAddonChangesDialog(
                pendingChange = pending,
                onConfirm = viewModel::confirmPendingChange,
                onReject = viewModel::rejectPendingChange
            )
        }

        AddonMessageOverlay(
            message = uiState.transientMessage,
            isError = uiState.transientMessageIsError
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddonMessageOverlay(
    message: String?,
    isError: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val visibleMessage = message ?: return@AnimatedVisibility
            Surface(
                onClick = { },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isError) {
                        Color(0xFFC62828).copy(alpha = 0.92f)
                    } else {
                        Color(0xFF2E7D32).copy(alpha = 0.92f)
                    }
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Close else Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Text(
                        text = visibleMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageFromPhoneCard(
    onClick: () -> Unit,
    groupPosition: SettingsGroupPosition,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SettingsRightSurfaceColor,
            focusedContainerColor = SettingsRightSurfaceFocusedColor
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = ClickableSurfaceDefaults.shape(
            settingsGroupShape(groupPosition)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 14.dp,
                    vertical = 10.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (isFocused) NuvioColors.Secondary else NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.addon_manage_from_phone_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioColors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.addon_manage_from_phone_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = NuvioColors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatalogOrderEntryCard(
    onClick: () -> Unit,
    groupPosition: SettingsGroupPosition,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SettingsRightSurfaceColor,
            focusedContainerColor = SettingsRightSurfaceFocusedColor
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = ClickableSurfaceDefaults.shape(
            settingsGroupShape(groupPosition)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 14.dp,
                    vertical = 10.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Reorder,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (isFocused) NuvioColors.Secondary else NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.addon_reorder_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioColors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.addon_reorder_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = NuvioColors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RefreshCatalogsEntryCard(
    subtitle: String,
    groupPosition: SettingsGroupPosition,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SettingsRightSurfaceColor,
            focusedContainerColor = SettingsRightSurfaceFocusedColor
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = ClickableSurfaceDefaults.shape(
            settingsGroupShape(groupPosition)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 14.dp,
                    vertical = 10.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (isFocused) NuvioColors.Secondary else NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Refresh Catalogs",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioColors.TextPrimary
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
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
    onClose: () -> Unit,
    hasPendingChange: Boolean = false
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(hasPendingChange) {
        if (!hasPendingChange) {
            focusRequester.requestFocus()
        }
    }

    NuvioDialog(
        glass = true,
        enhancedGlass = true,
        compact = true,
        onDismiss = onClose,
        title = stringResource(R.string.addon_manage_from_phone_title),
        subtitle = stringResource(R.string.addon_qr_scan_instruction),
        width = 400.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    .focusRequester(focusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = SettingsRightSurfaceColor,
                    focusedContainerColor =
                        SettingsRightSurfaceFocusedColor,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContentColor = NuvioColors.TextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.addon_qr_close))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmAddonChangesDialog(
    pendingChange: PendingChangeInfo,
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
        title = stringResource(R.string.addon_confirm_title),
        subtitle = stringResource(R.string.addon_confirm_subtitle),
        width = 520.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .background(
                    color = SettingsInsetControlColor,
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
                        text = stringResource(R.string.addon_confirm_added),
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioColors.Success,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                    pendingChange.addedUrls.forEach { url ->
                        val displayName =
                            pendingChange.addedNames[url] ?: url
                        Text(
                            text = "+ $displayName",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.Success,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, bottom = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                }

                if (pendingChange.removedUrls.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.addon_confirm_removed),
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioColors.Error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                    pendingChange.removedUrls.forEach { url ->
                        val displayName =
                            pendingChange.removedNames[url] ?: url
                        Text(
                            text = "- $displayName",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.Error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, bottom = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                }

                if (pendingChange.catalogsReordered) {
                    Text(
                        text = stringResource(
                            R.string.addon_confirm_catalog_reordered
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    )
                }

                if (pendingChange.disabledCatalogNames.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.addon_confirm_catalogs_disabled
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioColors.Error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                    pendingChange.disabledCatalogNames.forEach { name ->
                        Text(
                            text = "- $name",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.Error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, bottom = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                }

                if (pendingChange.enabledCatalogNames.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.addon_confirm_catalogs_enabled
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioColors.Success,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                    pendingChange.enabledCatalogNames.forEach { name ->
                        Text(
                            text = "+ $name",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.Success,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, bottom = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                }

                if (
                    pendingChange.addedUrls.isEmpty() &&
                    pendingChange.removedUrls.isEmpty() &&
                    !pendingChange.catalogsReordered &&
                    pendingChange.disabledCatalogNames.isEmpty() &&
                    pendingChange.enabledCatalogNames.isEmpty()
                ) {
                    Text(
                        text = stringResource(
                            R.string.addon_confirm_no_changes
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
        }

        Text(
            text = stringResource(
                R.string.addon_confirm_total_addons,
                pendingChange.proposedUrls.size
            ),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextTertiary,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(
                R.string.addon_confirm_total_catalogs,
                pendingChange.proposedCatalogOrderKeys.size
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
                LoadingIndicator(modifier = Modifier.size(36.dp))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.colors(
                        containerColor = SettingsRightSurfaceColor,
                        focusedContainerColor =
                            SettingsRightSurfaceFocusedColor,
                        contentColor = NuvioColors.TextPrimary,
                        focusedContentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.addon_confirm_reject))
                }

                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.Secondary,
                        focusedContainerColor =
                            NuvioColors.SecondaryVariant,
                        contentColor = NuvioColors.OnSecondary,
                        focusedContentColor = NuvioColors.OnSecondary
                    )
                ) {
                    Text(stringResource(R.string.addon_confirm_confirm))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddonCard(
    addon: Addon,
    groupPosition: SettingsGroupPosition,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    isReadOnly: Boolean = false
) {
    if (isReadOnly) {
        Surface(
            onClick = { },
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = SettingsRightSurfaceColor,
                focusedContainerColor = SettingsRightSurfaceColor
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border.None,
                focusedBorder = Border.None
            ),
            shape = ClickableSurfaceDefaults.shape(
                settingsGroupShape(groupPosition)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            AddonCardContent(addon = addon, isReadOnly = true)
        }
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            colors = CardDefaults.cardColors(
                containerColor = SettingsRightSurfaceColor
            ),
            shape = settingsGroupShape(groupPosition)
        ) {
            AddonCardContent(
                addon = addon,
                isReadOnly = false,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddonCardContent(
    addon: Addon,
    isReadOnly: Boolean,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onRemove: () -> Unit = {}
) {
    Column(modifier = Modifier.padding(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = "v${addon.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
            if (!isReadOnly) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        colors = ButtonDefaults.colors(
                            containerColor = SettingsGlassControlIdleColor,
                            disabledContainerColor = SettingsGlassControlIdleColor,
                            contentColor = NuvioColors.TextSecondary,
                            focusedContainerColor = SettingsGlassRowFocusedColor,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                        contentPadding = PaddingValues(
                            horizontal = 10.dp,
                            vertical = 5.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Move up",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Button(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        colors = ButtonDefaults.colors(
                            containerColor = SettingsGlassControlIdleColor,
                            disabledContainerColor = SettingsGlassControlIdleColor,
                            contentColor = NuvioColors.TextSecondary,
                            focusedContainerColor = SettingsGlassRowFocusedColor,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                        contentPadding = PaddingValues(
                            horizontal = 10.dp,
                            vertical = 5.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Move down",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Button(
                        onClick = onRemove,
                        colors = ButtonDefaults.colors(
                            containerColor = SettingsGlassControlIdleColor,
                            contentColor = NuvioColors.TextSecondary,
                            focusedContainerColor = SettingsGlassRowFocusedColor,
                            focusedContentColor = NuvioColors.Error
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                        contentPadding = PaddingValues(
                            horizontal = 10.dp,
                            vertical = 5.dp
                        )
                    ) {
                        Text(text = stringResource(R.string.addon_remove))
                    }
                }
            }
        }

        if (!addon.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = addon.description ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = addon.baseUrl,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextTertiary
        )

        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = stringResource(R.string.addon_catalogs_types, addon.catalogs.size, addon.rawTypes.joinToString()),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextTertiary
        )
    }
}

private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
    return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
}

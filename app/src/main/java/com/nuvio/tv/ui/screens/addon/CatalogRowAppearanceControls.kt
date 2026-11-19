package com.nuvio.tv.ui.screens.addon

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogPosterMode
import com.nuvio.tv.ui.screens.home.NumberStyle
import com.nuvio.tv.ui.screens.settings.SettingsGlassControlIdleColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassControlSelectedColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassRowFocusedColor
import com.nuvio.tv.ui.theme.NuvioColors

internal fun catalogAppearanceTint(selected: Boolean): Color =
    if (selected) NuvioColors.TextPrimary.copy(alpha = 0.85f) else NuvioColors.TextSecondary.copy(alpha = 0.4f)

internal fun CatalogPosterMode.labelResource() = when (this) {
    CatalogPosterMode.PORTRAIT -> R.string.catalog_poster_portrait
    CatalogPosterMode.LANDSCAPE -> R.string.catalog_poster_landscape
    CatalogPosterMode.XL_WITH_SUMMARY -> R.string.catalog_poster_xl_summary
    CatalogPosterMode.XL_WITHOUT_SUMMARY -> R.string.catalog_poster_xl_no_summary
}

@Composable
internal fun CatalogPosterModeControl(mode: CatalogPosterMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val selected = mode != CatalogPosterMode.PORTRAIT
    val label = stringResource(mode.labelResource())
    Button(
        onClick = onClick,
        modifier = modifier.size(40.dp).testTag("catalog-poster-mode").semantics { stateDescription = label },
        colors = ButtonDefaults.colors(
            containerColor = if (selected) SettingsGlassControlSelectedColor else SettingsGlassControlIdleColor,
            contentColor = catalogAppearanceTint(selected),
            focusedContainerColor = SettingsGlassRowFocusedColor,
            focusedContentColor = catalogAppearanceTint(selected)
        ),
        shape = ButtonDefaults.shape(CircleShape), contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            painterResource(when (mode) {
                CatalogPosterMode.PORTRAIT -> R.drawable.ic_portrait_poster
                CatalogPosterMode.LANDSCAPE -> R.drawable.ic_landscape_poster
                CatalogPosterMode.XL_WITH_SUMMARY -> R.drawable.ic_xl_catalog
                CatalogPosterMode.XL_WITHOUT_SUMMARY -> R.drawable.ic_xl_catalog_no_summary
            }),
            contentDescription = stringResource(R.string.catalog_poster_cycle, label, stringResource(mode.next().labelResource())),
            modifier = Modifier.size(when (mode) {
                CatalogPosterMode.PORTRAIT -> 22.dp
                CatalogPosterMode.LANDSCAPE -> 18.dp
                else -> 30.dp
            })
        )
    }
}

@Composable
internal fun CatalogNumberStyleControl(style: NumberStyle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val selected = style != NumberStyle.OFF
    Button(
        onClick = onClick,
        modifier = modifier.size(40.dp).testTag("catalog-number-style"),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) SettingsGlassControlSelectedColor else SettingsGlassControlIdleColor,
            contentColor = catalogAppearanceTint(selected),
            focusedContainerColor = SettingsGlassRowFocusedColor,
            focusedContentColor = catalogAppearanceTint(selected)
        ),
        shape = ButtonDefaults.shape(CircleShape), contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            painterResource(if (style == NumberStyle.OUTLINE) R.drawable.ic_catalog_number_outline else R.drawable.ic_catalog_number_solid),
            contentDescription = stringResource(when (style) {
                NumberStyle.OFF -> R.string.catalog_numbers_off
                NumberStyle.SOLID -> R.string.catalog_numbers_solid
                NumberStyle.OUTLINE -> R.string.catalog_numbers_outline
            }),
            modifier = Modifier.size(18.dp)
        )
    }
}

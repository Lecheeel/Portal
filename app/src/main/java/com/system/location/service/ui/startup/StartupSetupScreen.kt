package com.system.location.service.ui.startup

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.system.location.service.R
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.maps.MapProvider

internal enum class SetupPermission { GRANTED, REQUEST, RETRY, SETTINGS, SKIPPED }

@Composable
internal fun StartupSetupScreen(
    location: SetupPermission,
    notifications: SetupPermission?,
    selectedMap: MapProvider?,
    mapError: Boolean,
    onLocation: () -> Unit,
    onNotifications: () -> Unit,
    onSkipNotifications: () -> Unit,
    onSelectMap: (MapProvider) -> Unit,
    onReadPolicy: (MapProvider) -> Unit,
    onContinue: (MapProvider) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing), horizontalAlignment = Alignment.CenterHorizontally) {
            LazyColumn(
                modifier = Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().testTag("startup-setup"),
                contentPadding = PaddingValues(horizontal = PortalSpacing.xl, vertical = PortalSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PortalSpacing.lg),
            ) {
                item(key = "hero") { SetupHero() }
                item(key = "device") {
                    Column(verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
                        SetupHeading(stringResource(R.string.setup_permissions))
                        PermissionCard(
                            title = stringResource(R.string.setup_location),
                            description = stringResource(
                                if (location == SetupPermission.RETRY || location == SetupPermission.SETTINGS)
                                    R.string.setup_location_denied else R.string.setup_location_description,
                            ),
                            icon = R.drawable.setup_location,
                            permission = location,
                            requestLabel = R.string.setup_grant_location,
                            onRequest = onLocation,
                            actionTag = "setup-location-action",
                        )
                        if (notifications != null) {
                            PermissionCard(
                                title = stringResource(R.string.setup_notification),
                                description = stringResource(R.string.setup_notification_description),
                                icon = R.drawable.setup_notification,
                                permission = notifications,
                                requestLabel = R.string.setup_enable_notifications,
                                onRequest = onNotifications,
                                actionTag = "setup-notification-action",
                                onSkip = onSkipNotifications,
                            )
                        }
                    }
                }
                item(key = "maps") {
                    Column(verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
                        SetupHeading(stringResource(R.string.setup_maps))
                        Text(stringResource(R.string.setup_maps_description), style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant)
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
                            MapProvider.entries.forEach { provider ->
                                MapServiceCard(provider, provider == selectedMap,
                                    onSelect = { onSelectMap(provider) }, onReadPolicy = { onReadPolicy(provider) })
                            }
                        }
                    }
                }
            }
            SetupFooter(location == SetupPermission.GRANTED, selectedMap, mapError, onContinue)
        }
    }
}

@Composable
private fun SetupHero() {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraLarge
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 142.dp).clip(shape).background(
            Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)),
        ).padding(start = PortalSpacing.xl, end = PortalSpacing.sm, top = PortalSpacing.lg, bottom = PortalSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
            Text(stringResource(R.string.setup_title), color = colors.onPrimaryContainer,
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.setup_subtitle), color = colors.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium)
        }
        Image(painterResource(R.drawable.setup_route), contentDescription = null,
            modifier = Modifier.width(114.dp), colorFilter = ColorFilter.tint(colors.onPrimaryContainer))
    }
}

@Composable
private fun SetupHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    @DrawableRes icon: Int,
    permission: SetupPermission,
    @StringRes requestLabel: Int,
    onRequest: () -> Unit,
    actionTag: String,
    onSkip: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val granted = permission == SetupPermission.GRANTED
    val skipped = permission == SetupPermission.SKIPPED
    Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(horizontal = PortalSpacing.lg, vertical = PortalSpacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PortalSpacing.md)) {
                SetupIcon(icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PortalSpacing.xs)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (granted) {
                    PermissionBadge(R.string.setup_permission_granted, checked = true)
                } else if (onSkip != null) {
                    PermissionBadge(if (skipped) R.string.setup_notification_skipped else R.string.setup_optional)
                }
            }
            if (!granted) {
                Row(Modifier.fillMaxWidth().padding(top = PortalSpacing.xs),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (onSkip != null && !skipped) TextButton(onClick = onSkip) {
                        Text(stringResource(R.string.setup_notification_skip), textAlign = TextAlign.Center)
                    }
                    OutlinedButton(onClick = onRequest, modifier = Modifier.testTag(actionTag)) {
                        Text(stringResource(if (permission == SetupPermission.SETTINGS) R.string.setup_open_settings else requestLabel),
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionBadge(@StringRes label: Int, checked: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.extraSmall,
        color = if (checked) colors.primaryContainer else colors.surfaceVariant,
        contentColor = if (checked) colors.onPrimaryContainer else colors.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = PortalSpacing.sm, vertical = PortalSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(PortalSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (checked) Icon(painterResource(R.drawable.setup_check), contentDescription = null, Modifier.size(14.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MapServiceCard(provider: MapProvider, selected: Boolean, onSelect: () -> Unit, onReadPolicy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val title = stringResource(mapTitle(provider))
    val amap = provider == MapProvider.AMAP
    Surface(shape = MaterialTheme.shapes.large,
        color = if (selected) colors.primaryContainer.copy(alpha = 0.32f).compositeOver(colors.surface) else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("setup-map-${provider.name}")
                    .selectable(selected, role = Role.RadioButton, onClick = onSelect)
                    .padding(start = PortalSpacing.lg, end = PortalSpacing.lg, top = PortalSpacing.md, bottom = PortalSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(PortalSpacing.md),
            ) {
                SetupIcon(if (amap) R.drawable.setup_amap else R.drawable.setup_tencent, secondary = !amap)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PortalSpacing.xs)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (amap) R.string.setup_map_amap_privacy else R.string.setup_map_tencent_privacy),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = PortalSpacing.sm))
            }
            TextButton(onClick = onReadPolicy, modifier = Modifier.align(Alignment.End).padding(end = PortalSpacing.sm)) {
                Text(stringResource(R.string.setup_read_map_privacy, title))
            }
        }
    }
}

@Composable
private fun SetupIcon(@DrawableRes icon: Int, secondary: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(42.dp).clip(MaterialTheme.shapes.small)
        .background(if (secondary) colors.tertiaryContainer else colors.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp),
            tint = if (secondary) colors.onTertiaryContainer else colors.onPrimaryContainer)
    }
}

@Composable
private fun SetupFooter(preciseLocation: Boolean, selectedMap: MapProvider?, mapError: Boolean,
    onContinue: (MapProvider) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = PortalSpacing.xl, vertical = PortalSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PortalSpacing.xs)) {
                if (mapError) {
                    Text(stringResource(R.string.setup_map_error), color = colors.error, style = MaterialTheme.typography.bodyMedium)
                } else if (selectedMap != null && preciseLocation) {
                    Text(stringResource(R.string.setup_privacy_acknowledgement, stringResource(mapTitle(selectedMap))),
                        textAlign = TextAlign.Center, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { selectedMap?.let(onContinue) }, enabled = preciseLocation && selectedMap != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-continue")) {
                    Text(stringResource(when {
                        !preciseLocation -> R.string.setup_grant_to_continue
                        selectedMap == null -> R.string.setup_choose_map
                        else -> R.string.setup_enter
                    }), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@StringRes
private fun mapTitle(provider: MapProvider): Int = when (provider) {
    MapProvider.AMAP -> R.string.setup_map_amap
    MapProvider.TENCENT -> R.string.setup_map_tencent
}

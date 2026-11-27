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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

internal enum class SetupPermission { GRANTED, REQUEST, RETRY, SETTINGS }

internal enum class SetupStep(val section: Int) {
    PERMISSIONS(1), PERMISSION_SETTINGS(1), SYSTEM_BATTERY(2), XIAOMI_BATTERY(2), XIAOMI_AUTOSTART(2), MAP(3),
}

internal data class SetupProgress(
    val location: SetupPermission,
    val notifications: SetupPermission,
    val batteryExempt: Boolean,
    val xiaomiBackgroundRestricted: Boolean?,
    val step: SetupStep,
)

@Composable
internal fun StartupSetupScreen(
    progress: SetupProgress,
    selectedMap: MapProvider,
    mapError: Boolean,
    onSkipBackground: () -> Unit,
    onSelectMap: (MapProvider) -> Unit,
    onReadPolicy: (MapProvider) -> Unit,
    onContinue: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    var previousSection by remember { mutableIntStateOf(progress.step.section) }
    LaunchedEffect(progress.step.section) {
        if (previousSection != progress.step.section) {
            previousSection = progress.step.section
            listState.animateScrollToItem(previousSection)
        }
    }
    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            horizontalAlignment = Alignment.CenterHorizontally) {
            LazyColumn(
                modifier = Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().testTag("startup-setup"),
                state = listState,
                contentPadding = PaddingValues(horizontal = PortalSpacing.xl, vertical = PortalSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PortalSpacing.lg),
            ) {
                item(key = "hero") { SetupHero() }
                item(key = "device") {
                    Column(verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
                        SetupHeading(stringResource(R.string.setup_permissions))
                        SetupStatusCard(
                            title = stringResource(R.string.setup_location),
                            description = stringResource(R.string.setup_location_description),
                            icon = R.drawable.setup_location,
                            badge = permissionBadge(progress.location),
                            checked = progress.location == SetupPermission.GRANTED,
                        )
                        SetupStatusCard(
                            title = stringResource(R.string.setup_notification),
                            description = stringResource(R.string.setup_notification_description),
                            icon = R.drawable.setup_notification,
                            badge = permissionBadge(progress.notifications),
                            checked = progress.notifications == SetupPermission.GRANTED,
                        )
                    }
                }
                item(key = "background") {
                    Column(verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
                        SetupHeading(stringResource(R.string.setup_background))
                        SetupStatusCard(
                            title = stringResource(R.string.setup_battery),
                            description = stringResource(R.string.setup_battery_description),
                            icon = R.drawable.setup_battery,
                            badge = if (progress.batteryExempt) R.string.setup_battery_exempt else R.string.setup_battery_not_exempt,
                            checked = progress.batteryExempt,
                        )
                        progress.xiaomiBackgroundRestricted?.let { restricted ->
                            SetupStatusCard(
                                title = stringResource(R.string.setup_xiaomi_battery),
                                description = stringResource(R.string.setup_xiaomi_battery_description),
                                icon = R.drawable.setup_battery,
                                badge = R.string.setup_xiaomi_background_restricted.takeIf { restricted },
                            )
                            SetupStatusCard(
                                title = stringResource(R.string.setup_xiaomi_autostart),
                                description = stringResource(R.string.setup_xiaomi_autostart_description),
                                icon = R.drawable.setup_autostart,
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
                            listOf(MapProvider.TENCENT, MapProvider.AMAP).forEach { provider ->
                                MapServiceCard(provider, provider == selectedMap,
                                    onSelect = { onSelectMap(provider) }, onReadPolicy = { onReadPolicy(provider) })
                            }
                        }
                    }
                }
            }
            SetupFooter(progress, selectedMap, mapError, onSkipBackground, onContinue)
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
private fun SetupStatusCard(
    title: String,
    description: String,
    @DrawableRes icon: Int,
    @StringRes badge: Int? = null,
    checked: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = PortalSpacing.lg, vertical = PortalSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(PortalSpacing.md)) {
            SetupIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PortalSpacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(PortalSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (badge != null) PermissionBadge(badge, checked)
                }
                Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@StringRes
private fun permissionBadge(permission: SetupPermission): Int = when (permission) {
    SetupPermission.GRANTED -> R.string.setup_permission_granted
    SetupPermission.REQUEST -> R.string.setup_permission_pending
    else -> R.string.setup_permission_missing
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
private fun SetupFooter(progress: SetupProgress, selectedMap: MapProvider, mapError: Boolean,
    onSkipBackground: () -> Unit, onContinue: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val step = progress.step
    val backgroundStep = step.section == 2
    val denied = progress.location in setOf(SetupPermission.RETRY, SetupPermission.SETTINGS) ||
        progress.notifications in setOf(SetupPermission.RETRY, SetupPermission.SETTINGS)
    val action = when (step) {
        SetupStep.PERMISSIONS -> if (denied) R.string.setup_retry_permissions else R.string.setup_start_permissions
        SetupStep.PERMISSION_SETTINGS -> R.string.setup_open_settings
        SetupStep.SYSTEM_BATTERY -> R.string.setup_open_battery
        SetupStep.XIAOMI_BATTERY -> R.string.setup_open_xiaomi_battery
        SetupStep.XIAOMI_AUTOSTART -> R.string.setup_open_xiaomi_autostart
        SetupStep.MAP -> R.string.setup_enter
    }
    Surface(color = colors.surface, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = PortalSpacing.xl, vertical = PortalSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PortalSpacing.xs)) {
                val hint = when (step) {
                    SetupStep.PERMISSIONS -> stringResource(if (denied) R.string.setup_permissions_denied else R.string.setup_permissions_hint)
                    SetupStep.PERMISSION_SETTINGS -> stringResource(R.string.setup_permissions_denied)
                    SetupStep.MAP -> if (mapError) stringResource(R.string.setup_map_error)
                        else stringResource(R.string.setup_privacy_acknowledgement, stringResource(mapTitle(selectedMap)))
                    else -> null
                }
                if (hint != null) Text(hint, textAlign = TextAlign.Center,
                    color = if (step == SetupStep.MAP && mapError) colors.error else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-continue")) {
                    Text(stringResource(action), textAlign = TextAlign.Center)
                }
                if (backgroundStep) TextButton(onClick = onSkipBackground, modifier = Modifier.testTag("setup-skip-background")) {
                    Text(stringResource(R.string.setup_skip_background))
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

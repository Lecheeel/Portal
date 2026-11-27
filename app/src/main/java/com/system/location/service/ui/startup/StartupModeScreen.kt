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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.system.location.service.R
import com.system.location.service.core.backend.BackendType
import com.system.location.service.designsystem.theme.PortalSpacing

private enum class ModeOption(val backend: BackendType?, @StringRes val label: Int, @DrawableRes val icon: Int,
    @StringRes val description: Int? = null) {
    STANDARD(BackendType.MOCK_PROVIDER, R.string.setup_mode_standard, R.drawable.setup_mode_standard, R.string.setup_mode_standard_detail),
    XPOSED(BackendType.XPOSED, R.string.setup_mode_xposed, R.drawable.setup_mode_xposed, R.string.setup_mode_xposed_detail),
    NATIVE(BackendType.NATIVE, R.string.setup_mode_native, R.drawable.setup_mode_native, R.string.setup_mode_native_detail),
    KERNEL(null, R.string.setup_mode_kernel, R.drawable.setup_mode_kernel),
    SHIZUKU(null, R.string.setup_mode_shizuku, R.drawable.setup_mode_shizuku),
}

@Composable
internal fun StartupModeScreen(selected: BackendType, requirement: ModeRequirement, working: Boolean,
    onBack: () -> Unit, onSelect: (BackendType) -> Unit, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.background, modifier = Modifier.fillMaxSize().testTag("startup-mode-setup")) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            horizontalAlignment = Alignment.CenterHorizontally) {
            LazyColumn(
                modifier = Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().testTag("startup-mode-list"),
                contentPadding = PaddingValues(horizontal = PortalSpacing.xl, vertical = PortalSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(PortalSpacing.md),
            ) {
                item(key = "intro") {
                    Column {
                        TextButton(onClick = onBack, enabled = !working) { Text(stringResource(R.string.setup_modes_back)) }
                        ModeHero()
                    }
                }
                item(key = "choices") {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
                        ModeOption.entries.forEach { mode ->
                            ModeCard(mode, mode.backend == selected, enabled = mode.backend != null && !working,
                                onClick = { mode.backend?.let(onSelect) })
                        }
                    }
                }
            }
            ModeFooter(selected, requirement, working, onAction)
        }
    }
}

@Composable
private fun ModeHero() {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(
        Brush.linearGradient(listOf(colors.tertiaryContainer, colors.primaryContainer)),
    ).padding(start = PortalSpacing.xl, top = PortalSpacing.lg, end = PortalSpacing.md, bottom = PortalSpacing.lg),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PortalSpacing.xs)) {
            Text(stringResource(R.string.setup_modes_step), color = colors.onTertiaryContainer, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.setup_modes_title), color = colors.onTertiaryContainer,
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.setup_modes_subtitle), color = colors.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium)
        }
        Image(painterResource(R.drawable.setup_mode_route), contentDescription = null, modifier = Modifier.width(94.dp),
            colorFilter = ColorFilter.tint(colors.onTertiaryContainer))
    }
}

@Composable
private fun ModeCard(mode: ModeOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val available = mode.backend != null
    val shape = MaterialTheme.shapes.large
    Surface(shape = shape, border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
        color = if (selected) colors.primaryContainer.copy(alpha = 0.35f).compositeOver(colors.surface) else colors.surface) {
        Row(Modifier.fillMaxWidth().testTag("setup-mode-${mode.name}").selectable(selected, enabled = enabled,
            role = Role.RadioButton, onClick = onClick).padding(horizontal = PortalSpacing.lg, vertical = PortalSpacing.md),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PortalSpacing.md)) {
            Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
                .background(if (available) colors.primaryContainer else colors.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(painterResource(mode.icon), contentDescription = null, modifier = Modifier.size(22.dp),
                    tint = if (available) colors.onPrimaryContainer else colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PortalSpacing.xs)) {
                Text(stringResource(mode.label), style = MaterialTheme.typography.titleMedium,
                    color = if (available) colors.onSurface else colors.onSurfaceVariant)
                mode.description?.let { Text(stringResource(it), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            }
            if (available) RadioButton(selected = selected, onClick = null, enabled = enabled)
            else Surface(shape = MaterialTheme.shapes.extraSmall, color = colors.surfaceVariant) {
                Text(stringResource(R.string.setup_modes_developing), Modifier.padding(horizontal = PortalSpacing.sm, vertical = PortalSpacing.xs),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModeFooter(selected: BackendType, requirement: ModeRequirement, working: Boolean, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val status = when {
        working -> R.string.setup_modes_checking
        requirement == ModeRequirement.READY && selected == BackendType.NATIVE -> R.string.setup_modes_native_risk
        else -> when (requirement) {
            ModeRequirement.CHECKING -> R.string.setup_modes_checking
            ModeRequirement.READY -> R.string.setup_modes_ready
            ModeRequirement.DEVELOPER_OPTIONS -> R.string.setup_modes_need_developer
            ModeRequirement.MOCK -> R.string.setup_modes_need_mock
            ModeRequirement.MODULE -> R.string.setup_modes_need_module
            ModeRequirement.ANDROID -> R.string.setup_modes_need_android
            ModeRequirement.ABI -> R.string.setup_modes_need_arm64
            ModeRequirement.NATIVE_LIBRARY -> R.string.setup_modes_need_library
            ModeRequirement.ROOT -> R.string.setup_modes_need_root
            ModeRequirement.ROOT_DENIED -> R.string.setup_modes_root_failed
            ModeRequirement.CHECK_FAILED -> R.string.setup_modes_check_failed
            ModeRequirement.SAVE_FAILED -> R.string.setup_modes_save_failed
        }
    }
    val action = when (requirement) {
        ModeRequirement.DEVELOPER_OPTIONS -> R.string.setup_modes_open_about
        ModeRequirement.MOCK -> R.string.setup_modes_open_developer
        ModeRequirement.ROOT, ModeRequirement.ROOT_DENIED -> R.string.setup_modes_verify_root
        ModeRequirement.MODULE, ModeRequirement.CHECK_FAILED -> R.string.setup_modes_retry
        else -> R.string.setup_modes_continue
    }
    Surface(color = colors.surface, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = PortalSpacing.xl, vertical = PortalSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
                Text(stringResource(status), color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Button(onClick = onAction, enabled = !working && requirement != ModeRequirement.CHECKING &&
                    requirement != ModeRequirement.ANDROID && requirement != ModeRequirement.ABI &&
                    requirement != ModeRequirement.NATIVE_LIBRARY,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-mode-action")) {
                    Text(stringResource(action), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

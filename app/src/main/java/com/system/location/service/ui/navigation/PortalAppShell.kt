package com.system.location.service.ui.navigation

import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.system.location.service.R
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.displayLabel
import com.system.location.service.ui.runtime.DrawerModeControl
import com.system.location.service.ui.runtime.MockWirelessReminderDialog
import kotlinx.coroutines.launch

internal data class PortalDestination(val id: Int, val label: String, val icon: Int, val section: String)

internal val portalDestinations = listOf(
    PortalDestination(R.id.nav_home, "首页地图", R.drawable.ic_portal_map, "定位"),
    PortalDestination(R.id.nav_mock, "环境/卫星", R.drawable.baseline_satellite_alt_24, "环境/卫星"),
    PortalDestination(R.id.nav_route_gallery, "资料库", R.drawable.ic_portal_route, "环境/卫星"),
    PortalDestination(R.id.nav_runtime, "运行中心", R.drawable.ic_portal_run, "运行"),
    PortalDestination(R.id.nav_settings, "设置", R.drawable.ic_portal_settings, "其他"),
)

/** Compose owns the entire application chrome; only the SDK-compatible navigation host is a View. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortalAppShell(drawer: DrawerState, selectedSection: Int,
    onNavigate: (Int) -> Unit, createHost: () -> View) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val scope = rememberCoroutineScope()
    val runtime by ScenarioRuntime.state.collectAsStateWithLifecycle()
    MockWirelessReminderDialog(runtime)
    val drawerWidth = minOf(288.dp, maxWidth * .78f)
    ModalNavigationDrawer(drawerState = drawer,
        // Open only from a menu button on every page. Once open, retain drag-to-close
        // and scrim dismissal without intercepting horizontal content gestures.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
        ModalDrawerSheet(drawerState = drawer, modifier = Modifier
            .width(drawerWidth).fillMaxHeight().testTag("portal_drawer"),
            drawerContainerColor = MaterialTheme.colorScheme.surface,
            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                Spacer(Modifier.height(16.dp))
                Text("Portal", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text(runtime.displayLabel(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                DrawerModeControl(drawer.isOpen || drawer.isAnimationRunning)
                portalDestinations.groupBy { it.section }.forEach { (section, destinations) ->
                    Spacer(Modifier.height(8.dp))
                    Text(section, Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    destinations.forEach { target ->
                        NavigationDrawerItem(label = { Text(target.label) },
                            icon = { Icon(painterResource(target.icon), null) },
                            selected = selectedSection == target.id,
                            onClick = {
                                // Close before navigating: the old map should not animate under
                                // a drawer scrim while the next page is still being constructed.
                                scope.launch { drawer.close(); onNavigate(target.id) }
                            }, modifier = Modifier.testTag("portal_destination_${target.id}"))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }) {
        // Keep the outgoing SDK surface at its original size while the destination changes.
        // Insets belong to each Fragment view, so showing the toolbar never resizes the old map.
        Surface(Modifier.fillMaxSize().testTag("portal_shell"), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { createHost() }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

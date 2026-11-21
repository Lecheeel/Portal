package com.system.location.service.ui.navigation

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import com.system.location.service.MainActivity
import com.system.location.service.R
import com.system.location.service.designsystem.theme.PortalTheme

/** The toolbar belongs to the same Fragment view as its page, including seekable Back. */
@SuppressLint("RestrictedApi") // FragmentNavigator tags each Fragment with its back-stack entry ID.
@OptIn(ExperimentalMaterial3Api::class)
internal fun ComposeView.setPortalPageContent(fragment: Fragment, content: @Composable () -> Unit) {
    setContent {
        // Only the app shell owns these toolbars. Standalone editor fixtures also
        // use a NavHost, but restore its graph separately and have no drawer.
        if (fragment.activity !is MainActivity || fragment.parentFragment !is NavHostFragment) {
            content()
            return@setContent
        }
        val controller = fragment.findNavController()
        val entry = remember(fragment) {
            requireNotNull((controller.currentBackStack.value + controller.visibleEntries.value)
                .lastOrNull { it.id == fragment.tag }) { "Missing navigation entry for ${fragment.tag}" }
        }
        val title = remember(entry) {
            Regex("\\{(.+?)\\}").replace(entry.destination.label?.toString().orEmpty()) {
                entry.arguments?.get(it.groupValues[1])?.toString().orEmpty()
            }
        }
        val topLevel = portalDestinations.any { it.id == entry.destination.id } ||
            entry.destination.id == R.id.nav_gnss_mock
        PortalTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(title = { Text(title) }, navigationIcon = {
                        IconButton(onClick = {
                            val activity = fragment.requireActivity() as MainActivity
                            if (topLevel) activity.openDrawer() else activity.onSupportNavigateUp()
                        }) {
                            Icon(painterResource(if (topLevel) R.drawable.ic_home_menu else R.drawable.baseline_arrow_back_24),
                                if (topLevel) "打开导航菜单" else "返回")
                        }
                    }, colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ), windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        modifier = Modifier.testTag("portal_toolbar"))
                    Box(Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    )) { content() }
                }
            }
        }
    }
}

package com.system.location.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navOptions
import com.system.location.service.maps.*
import com.system.location.service.privacy.TencentPrivacy
import com.system.location.service.privacy.SdkPrivacy
import kotlinx.coroutines.launch
import com.system.location.service.android.window.OverlayUtils
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.system.location.service.ui.navigation.PortalAppShell
import com.system.location.service.ui.notification.NotificationUtils
import com.system.location.service.ui.viewmodel.AMapViewModel
import com.system.location.service.ui.viewmodel.MockServiceViewModel
import com.system.location.service.update.StartupUpdateViewModel
import com.system.location.service.update.UpdateDialogFragment
import com.system.location.service.ui.navigation.PortalNavigationTransitions
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.core.backend.BackendType
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.startup.StartupModeSetup
import com.system.location.service.ui.startup.StartupSetup

class MainActivity : AppCompatActivity() {
    internal var drawerState = androidx.compose.material3.DrawerState(androidx.compose.material3.DrawerValue.Closed)
        private set
    internal var currentDestinationId by androidx.compose.runtime.mutableIntStateOf(R.id.nav_home)
        private set
    internal var selectedDrawerId by androidx.compose.runtime.mutableIntStateOf(R.id.nav_home)
        private set
    private var shellReady by mutableStateOf(false)
    private var choosingStartupMode by mutableStateOf(false)
    private var navHost: androidx.navigation.fragment.NavHostFragment? = null
    private var drawerUiScope: kotlinx.coroutines.CoroutineScope? = null
    internal val shellView: View get() = findViewById(android.R.id.content)
    internal val navigationController get() = requireNotNull(navHost).navController

    fun openDrawer() { drawerUiScope?.launch { drawerState.open() } }
    fun closeDrawer() { drawerUiScope?.launch { drawerState.close() } }
    fun navigateFromDrawer(destination: Int) {
        val controller = navigationController
        if (controller.currentDestination?.id == destination) return
        val start = controller.graph.findStartDestination().id
        if (destination == start && controller.popBackStack(start, false)) return
        controller.navigate(destination, null, navOptions { launchSingleTop = true; popUpTo(start) })
    }

    @SuppressLint("RestrictedApi") // Same container reattachment used by AndroidFragment in fragment-compose.
    private fun createNavigationHost(): View {
        // Fragment navigation remains the interoperability layer for the existing Compose screens.
        // Construct its container synchronously before restoring the graph's Fragment views.
        return androidx.fragment.app.FragmentContainerView(this).apply {
            id = R.id.nav_host_fragment_content_main
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.page_background))
            val host = supportFragmentManager.findFragmentById(id) as? androidx.navigation.fragment.NavHostFragment
                ?: androidx.navigation.fragment.NavHostFragment.create(R.navigation.mobile_navigation).also {
                    supportFragmentManager.beginTransaction().add(this, it, "portal_navigation_host")
                        .setPrimaryNavigationFragment(it).commitNow()
                }
            navHost = host
            // super.onCreate restores the Fragment before Compose creates this replacement View.
            // Rebind the restored host's view, just as AndroidFragment does for its own container.
            supportFragmentManager.onContainerAvailable(this)
            androidx.navigation.Navigation.setViewNavController(this, host.navController)
            host.navController.addOnDestinationChangedListener { _, destination, _ ->
                currentDestinationId = destination.id
                selectedDrawerId = when {
                    destination.arguments.containsKey("category") -> R.id.nav_settings
                    destination.id == R.id.nav_gnss_mock -> R.id.nav_mock
                    destination.id == R.id.nav_route_edit -> R.id.nav_route_gallery
                    destination.id in setOf(R.id.nav_app_selection, R.id.nav_sensor_packages,
                        R.id.nav_point_selection, R.id.nav_map_keys, R.id.nav_carrier_identity) -> R.id.nav_settings
                    destination.id in setOf(R.id.nav_environment_library, R.id.nav_environment_editor) -> R.id.nav_route_gallery
                    destination.id == R.id.nav_environment -> R.id.nav_mock
                    else -> destination.id
                }
            }
        }
    }

    /* ViewModels */
    private val aMapViewModel by viewModels<AMapViewModel>()
    private val mockServiceViewModel by viewModels<MockServiceViewModel>()
    private val startupUpdateViewModel by viewModels<StartupUpdateViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.registerFragmentLifecycleCallbacks(PortalNavigationTransitions(), true)
        // A saved onboarding screen contains no NavHost. Never restore an old MapView before consent.
        val mapsReady = runCatching { selectedMapsReady() }.getOrDefault(false)
        val showHome = mapsReady && !ScenarioRuntime.startupModePending()
        val restoreDrawerOpen = showHome && savedInstanceState?.getBoolean("portal.drawer.open") == true
        val safeToRestore = showHome || savedInstanceState?.getBoolean(SETUP_VISIBLE) == true
        super.onCreate(savedInstanceState.takeIf { safeToRestore })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (showHome) prepareAppShell(restoreDrawerOpen)
        choosingStartupMode = mapsReady && !showHome && savedInstanceState?.getBoolean(SETUP_MODE_VISIBLE, true) != false

        setContent {
            PortalTheme {
                if (shellReady) {
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    androidx.compose.runtime.DisposableEffect(scope) {
                        drawerUiScope = scope
                        onDispose { if (drawerUiScope === scope) drawerUiScope = null }
                    }
                    PortalAppShell(drawerState, selectedDrawerId,
                        onNavigate = ::navigateFromDrawer,
                        createHost = ::createNavigationHost)
                } else {
                    val setupState = rememberSaveableStateHolder()
                    if (choosingStartupMode) {
                        setupState.SaveableStateProvider(SETUP_MODE_VISIBLE) {
                            StartupModeSetup(this@MainActivity, onBack = { choosingStartupMode = false }, ::completeStartupMode)
                        }
                    } else {
                        setupState.SaveableStateProvider(SETUP_VISIBLE) {
                            val initialMap = if (ScenarioRuntime.startupModePending()) mapProvider else MapProvider.TENCENT
                            StartupSetup(this@MainActivity, initialMap, ::completeStartupSetup)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                startupUpdateViewModel.pendingUpdate.collect { info ->
                    if (info != null && UpdateDialogFragment.show(supportFragmentManager, info)) {
                        startupUpdateViewModel.onPromptShown()
                    }
                }
            }
        }

    }

    private fun completeStartupSetup(provider: MapProvider): Boolean {
        if (shellReady) return true
        val ready = runCatching {
            check(ScenarioRuntime.beginStartupModeSetup())
            mapProvider = provider
            when (provider) {
                MapProvider.AMAP -> SdkPrivacy.acceptMaps()
                MapProvider.TENCENT -> TencentPrivacy.accept(this)
            }
            selectedMapsReady()
        }.getOrDefault(false)
        if (ready) choosingStartupMode = true
        return ready
    }

    private suspend fun completeStartupMode(backend: BackendType): Boolean {
        if (!ScenarioRuntime.completeStartupModeSetup(backend)) return false
        prepareAppShell(restoreDrawerOpen = false)
        startupUpdateViewModel.check()
        return true
    }

    private fun prepareAppShell(restoreDrawerOpen: Boolean) {
        mockServiceViewModel.locationManager = getSystemService(LocationManager::class.java)
        initNotification()
        drawerState = androidx.compose.material3.DrawerState(if (restoreDrawerOpen)
            androidx.compose.material3.DrawerValue.Open else androidx.compose.material3.DrawerValue.Closed)
        shellReady = true
    }

    private fun selectedMapsReady(): Boolean = if (mapProvider == MapProvider.TENCENT)
        TencentPrivacy.ready(this) else SdkPrivacy.mapsAccepted && SdkPrivacy.mapsReady

    fun onUpdateChannelChanged() {
        startupUpdateViewModel.channelChanged()
    }

    override fun onStart() {
        super.onStart()
        if (shellReady) startupUpdateViewModel.check()
    }

    override fun onResume() {
        super.onResume()
        applySystemBarTheme()
        com.system.location.service.ui.mock.RockerOverlay.refresh()
    }

    private fun applySystemBarTheme() {
        // Android 15 edge-to-edge also derives navigation icon contrast from the
        // window background. Resolve it after AppCompat applies local night mode.
        window.setBackgroundDrawableResource(R.color.page_background)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        val light = resources.getBoolean(R.bool.light_system_bars)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) startupUpdateViewModel.onBackgrounded()
        super.onStop()
    }

    private fun initNotification() {
        with(aMapViewModel) {
            mNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationUtils = NotificationUtils(this@MainActivity)
                val builder = notificationUtils.getAndroidChannelNotification(
                    "LocationService后台定位服务",
                    "正在后台定位"
                )
                builder.build()
            } else {
                val builder = Notification.Builder(this@MainActivity)
                val nfIntent = Intent(
                    this@MainActivity,
                    MainActivity::class.java
                )
                builder.setContentIntent(PendingIntent.getActivity(
                    this@MainActivity, 0, nfIntent, PendingIntent.FLAG_IMMUTABLE
                ))
                    .setContentTitle("LocationService后台定位服务")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentText("正在后台定位")
                    .setWhen(System.currentTimeMillis())

                builder.build()
            }.also {
                it.defaults = Notification.DEFAULT_SOUND
            }
        }
    }

    private fun requireFloatWindows(): Boolean {
        fun requestSettingCanDrawOverlays() {
            kotlin.runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:")
                startActivity(intent)
            }.onFailure {
                Log.e("MainActivity", "requestSettingCanDrawOverlays: ", it)
                Toast.makeText(this, "跳转失败，请手动去设置授权", Toast.LENGTH_LONG).show()
            }
            finish()
        }

        if (!OverlayUtils.hasOverlayPermissions(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            requestSettingCanDrawOverlays()
            return false
        }

        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val controller = navHost?.navController ?: return false
        if (com.system.location.service.ui.navigation.portalDestinations.any { it.id == currentDestinationId } ||
            currentDestinationId == R.id.nav_gnss_mock) {
            openDrawer()
            return true
        }
        return controller.navigateUp()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("portal.drawer.open", drawerState.isOpen)
        outState.putBoolean(SETUP_VISIBLE, !shellReady)
        outState.putBoolean(SETUP_MODE_VISIBLE, choosingStartupMode)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val SETUP_VISIBLE = "portal.startup.setup_visible"
        private const val SETUP_MODE_VISIBLE = "portal.startup.mode_visible"

        internal var mCityString: String? = null
            set(value) {
                if (field != value)  {
                    field = value
                    Log.d("MainActivity", "cityString: ")
                }
            }
    }
}

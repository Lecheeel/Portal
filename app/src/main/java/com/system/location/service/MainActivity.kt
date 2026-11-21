package com.system.location.service

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS
import android.Manifest.permission.ACCESS_NETWORK_STATE
import android.Manifest.permission.ACCESS_WIFI_STATE
import android.Manifest.permission.CHANGE_WIFI_STATE
import android.Manifest.permission.FOREGROUND_SERVICE
import android.Manifest.permission.INTERNET
import android.Manifest.permission.READ_PHONE_STATE
import android.Manifest.permission.VIBRATE
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import com.system.location.service.android.permission.RequestPermissions
import com.system.location.service.android.root.ShellUtils
import com.system.location.service.android.window.OverlayUtils
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.system.location.service.ui.navigation.PortalAppShell
import com.system.location.service.ext.Loc4j
import com.system.location.service.ui.notification.NotificationUtils
import com.system.location.service.ui.viewmodel.AMapViewModel
import com.system.location.service.ui.viewmodel.MockServiceViewModel
import com.system.location.service.update.StartupUpdateViewModel
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.update.UpdateDialogFragment
import com.system.location.service.ui.navigation.PortalNavigationTransitions
import com.system.location.service.designsystem.theme.PortalTheme

class MainActivity : AppCompatActivity() {
    internal var drawerState = androidx.compose.material3.DrawerState(androidx.compose.material3.DrawerValue.Closed)
        private set
    internal var currentDestinationId by androidx.compose.runtime.mutableIntStateOf(R.id.nav_home)
        private set
    internal var selectedDrawerId by androidx.compose.runtime.mutableIntStateOf(R.id.nav_home)
        private set
    private var pageTitle by androidx.compose.runtime.mutableStateOf("Portal")
    private var shellReady = false
    private var navHost: androidx.navigation.fragment.NavHostFragment? = null
    internal val shellView: View get() = findViewById(android.R.id.content)
    internal val navigationController get() = requireNotNull(navHost).navController

    fun openDrawer() { lifecycleScope.launch { drawerState.open() } }
    fun closeDrawer() { lifecycleScope.launch { drawerState.close() } }
    fun navigateFromDrawer(destination: Int) {
        val controller = navigationController
        if (controller.currentDestination?.id == destination) return
        val start = controller.graph.findStartDestination().id
        if (destination == start && controller.popBackStack(start, false)) return
        controller.navigate(destination, null, navOptions { launchSingleTop = true; popUpTo(start) })
    }

    private fun createNavigationHost(): View {
        // Fragment navigation remains the interoperability layer for the existing Compose screens.
        // Construct its container synchronously before restoring the graph's Fragment views.
        return androidx.fragment.app.FragmentContainerView(this).apply {
            id = R.id.nav_host_fragment_content_main
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.page_background))
            val host = supportFragmentManager.findFragmentById(id) as? androidx.navigation.fragment.NavHostFragment
                ?: androidx.navigation.fragment.NavHostFragment.create(R.navigation.mobile_navigation).also {
                    supportFragmentManager.beginTransaction().replace(id, it).setPrimaryNavigationFragment(it).commitNow()
                }
            navHost = host
            androidx.navigation.Navigation.setViewNavController(this, host.navController)
            host.navController.addOnDestinationChangedListener { _, destination, args ->
                currentDestinationId = destination.id
                pageTitle = destination.label?.toString()?.let { label ->
                    Regex("\\{(.+?)\\}").replace(label) { match -> args?.get(match.groupValues[1])?.toString().orEmpty() }
                }.orEmpty()
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

    /* Permission */
    private val requestMultiplePermissions = RequestPermissions(this)

    /* ViewModels */
    private val aMapViewModel by viewModels<AMapViewModel>()
    private val mockServiceViewModel by viewModels<MockServiceViewModel>()
    private val startupUpdateViewModel by viewModels<StartupUpdateViewModel>()

    private fun getRequiredPermissions(): MutableSet<String> {
        val permissions = mutableSetOf(
            ACCESS_FINE_LOCATION,
            ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions
    }

    private fun handleDeniedPermissions(denied: Set<String>) {
        denied.forEach { permission ->
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                showPermissionDeniedToast(permission)
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_PERMISSIONS_CODE)
            }
        }

    }

    private fun showPermissionDeniedToast(permission: String) {
        val message = when (permission) {
            ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION -> "LocationService需要完整位置权限"
            ACCESS_LOCATION_EXTRA_COMMANDS -> "LocationService需要额外位置命令权限和系统交互"
            CHANGE_WIFI_STATE, ACCESS_WIFI_STATE -> "LocationService需要访问Wi-Fi状态"
            READ_PHONE_STATE -> "LocationService需要读取设备信息"
            ACCESS_NETWORK_STATE, INTERNET -> "LocationService需要访问网络"
            VIBRATE -> "LocationService需要访问传感器"
            else -> "需要  才能运行"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private suspend fun checkPermission(): Boolean {
        val permissions = getRequiredPermissions()
        val (_, denied) = requestMultiplePermissions.request(permissions)
        handleDeniedPermissions(denied)
        return denied.isEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.registerFragmentLifecycleCallbacks(PortalNavigationTransitions(), true)
        // super restores NavHost and its MapViews synchronously.
        // Discard old UI state unless consent is confirmed and the map SDK is ready.
        val mapsReady = selectedMapsReady()
        val restoreDrawerOpen = mapsReady && savedInstanceState?.getBoolean("portal.drawer.open") == true
        super.onCreate(if (mapsReady) savedInstanceState else null)

        // Edge-to-Edge full immersion layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        lifecycleScope.launch {
            if (!awaitMapConsent()) { finish(); return@launch }
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                checkPermission()
                mockServiceViewModel.locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager

                initNotification()

                drawerState = androidx.compose.material3.DrawerState(if (restoreDrawerOpen)
                    androidx.compose.material3.DrawerValue.Open else androidx.compose.material3.DrawerValue.Closed)
                setContent {
                    PortalTheme {
                        PortalAppShell(drawerState, currentDestinationId, selectedDrawerId, pageTitle,
                            onNavigate = ::navigateFromDrawer, onBack = { onSupportNavigateUp() },
                            createHost = ::createNavigationHost)
                    }
                }
                shellReady = true
                applySystemBarTheme()
                startupUpdateViewModel.check()
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

    private suspend fun awaitMapConsent(): Boolean {
        if (selectedMapsReady()) return true
        return suspendCancellableCoroutine { continuation ->
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.map_privacy_title)
                .setMessage(if (mapProvider == MapProvider.TENCENT) R.string.tencent_privacy_message else R.string.map_privacy_message)
                .setCancelable(false)
                .setNegativeButton(R.string.map_privacy_decline) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .setNeutralButton(R.string.map_privacy_policy, null)
                .setPositiveButton(R.string.map_privacy_accept, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (mapProvider == MapProvider.TENCENT) "https://lbs.qq.com/userAgreements/agreements/privacy" else "https://lbs.amap.com/pages/privacy/"))) }
                        .onFailure { Toast.makeText(this, R.string.map_privacy_no_browser, Toast.LENGTH_LONG).show() }
                }
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    runCatching { if (mapProvider == MapProvider.TENCENT) TencentPrivacy.accept(this) else SdkPrivacy.acceptMaps() }.onSuccess {
                        if (continuation.isActive) continuation.resume(true)
                        dialog.dismiss()
                    }.onFailure { Toast.makeText(this, it.message ?: getString(R.string.privacy_save_failed), Toast.LENGTH_LONG).show() }
                }
            }
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val denied = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }

            if (denied.isEmpty()) {
                mockServiceViewModel.locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
                return
            }

            for (permission in denied) {
                Toast.makeText(this, when(permission) {
                    ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION -> "LocationService需要完整位置权限"
                    ACCESS_LOCATION_EXTRA_COMMANDS -> "LocationService需要额外位置命令权限和系统交互"
                    CHANGE_WIFI_STATE, ACCESS_WIFI_STATE -> "LocationService需要访问Wi-Fi状态"
                    READ_PHONE_STATE -> "LocationService需要读取设备信息"
                    ACCESS_NETWORK_STATE, INTERNET -> "LocationService需要访问网络"
                    VIBRATE -> "LocationService需要访问传感器"
                    else -> "需要  才能运行"
                } + "，请手动授权！", Toast.LENGTH_SHORT).show()
            }

        }
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
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val REQUEST_PERMISSIONS_CODE = 111

        internal var mCityString: String? = null
            set(value) {
                if (field != value)  {
                    field = value
                    Log.d("MainActivity", "cityString: ")
                }
            }
    }
}

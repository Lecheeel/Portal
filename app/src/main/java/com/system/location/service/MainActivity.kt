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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.amap.api.maps.model.LatLng
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.GeocodeSearch.OnGeocodeSearchListener
import com.amap.api.services.geocoder.RegeocodeAddress
import com.amap.api.services.geocoder.RegeocodeResult
import com.google.android.material.navigation.NavigationView
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.launch
import com.system.location.service.android.permission.RequestPermissions
import com.system.location.service.android.root.ShellUtils
import com.system.location.service.android.window.OverlayUtils
import com.system.location.service.databinding.ActivityMainBinding
import com.system.location.service.ext.Loc4j
import com.system.location.service.ui.notification.NotificationUtils
import com.system.location.service.ui.viewmodel.AMapViewModel
import com.system.location.service.ui.viewmodel.MockServiceViewModel

class MainActivity : AppCompatActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    lateinit var binding: ActivityMainBinding

    /* Permission */
    private val requestMultiplePermissions = RequestPermissions(this)

    /* ViewModels */
    private val aMapViewModel by viewModels<AMapViewModel>()
    private val mockServiceViewModel by viewModels<MockServiceViewModel>()

    private fun getRequiredPermissions(): MutableSet<String> {
        val permissions = mutableSetOf(
            ACCESS_FINE_LOCATION,
            ACCESS_COARSE_LOCATION,
            ACCESS_LOCATION_EXTRA_COMMANDS,
            ACCESS_WIFI_STATE,
            CHANGE_WIFI_STATE,
            READ_PHONE_STATE,
            INTERNET,
            ACCESS_NETWORK_STATE,
            VIBRATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(FOREGROUND_SERVICE)
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

        if (denied.isEmpty()) {
            requireFloatWindows()
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
        super.onCreate(savedInstanceState)

        // Edge-to-Edge full immersion layout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        CrashReport.setUserSceneTag(this, 261771)

        if (!ShellUtils.hasRoot()) {
            Toast.makeText(this, "无Root可能导致传感器Hook失效", Toast.LENGTH_LONG).show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                if (checkPermission()) {
                    mockServiceViewModel.locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
                }

                initNotification()

                binding = ActivityMainBinding.inflate(layoutInflater)
                setContentView(binding.root)

                setSupportActionBar(binding.appBarMain.toolbar)

                val drawerLayout: DrawerLayout = binding.drawerLayout
                val navView: NavigationView = binding.navView
                val navController = findNavController(R.id.nav_host_fragment_content_main)

                appBarConfiguration = AppBarConfiguration(
                    setOf(
                        R.id.nav_home, R.id.nav_mock, R.id.nav_gnss_mock, R.id.nav_route_gallery, R.id.nav_settings
                    ), drawerLayout
                )

                setupActionBarWithNavController(navController, appBarConfiguration)
                navView.setupWithNavController(navController)

                navController.addOnDestinationChangedListener { _, destination, _ ->
                    // Show standard toolbar for secondary screens, hide for home full-screen map
                    if (destination.id == R.id.nav_home) {
                        binding.appBarMain.toolbar.visibility = View.GONE
                    } else {
                        binding.appBarMain.toolbar.visibility = View.VISIBLE
                    }
                }
            }
        }

        aMapViewModel.mGeoCoder = GeocodeSearch(this)
        aMapViewModel.mGeoCoder?.setOnGeocodeSearchListener(object : OnGeocodeSearchListener {
            override fun onGeocodeSearched(result: GeocodeResult?, code: Int) {}

            override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) {
                if (code != 1000 || result == null) {
                    Log.e("MainActivity", "Reverse GeoCode error: ")
                    return
                }
                with(aMapViewModel) {
                    val addr: RegeocodeAddress = result.regeocodeAddress
                    markName = addr.formatAddress
                }
            }
        })

        mockServiceViewModel.initRocker(this)
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

            setContentView(R.layout.activity_no_permission)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
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

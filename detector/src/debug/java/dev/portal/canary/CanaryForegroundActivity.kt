package dev.portal.canary

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Keeps while-in-use AppOps active without requesting or changing any permission. */
class CanaryForegroundActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "诊断测试正在运行" })
    }
}

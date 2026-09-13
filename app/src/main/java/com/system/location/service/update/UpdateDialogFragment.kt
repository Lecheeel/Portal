package com.system.location.service.update

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class UpdateDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("发现新版本")
            .setMessage(
                "最新版本：${args.getString("version")}\n" +
                    "当前版本：${UpdateChecker.parseLocalVersion().versionName}\n\n" +
                    args.getString("body").orEmpty().take(600).ifBlank { "查看 Release 页获取更新说明" }
            )
            .setPositiveButton("下载更新") { _, _ ->
                UpdateChecker.openDownload(requireContext(), args.getString("url")!!)
            }
            .setNegativeButton("下次再说", null)
            .create()
    }

    companion object {
        private const val TAG = "release-update"

        fun show(manager: FragmentManager, info: UpdateChecker.UpdateInfo): Boolean {
            if (manager.isStateSaved) return false
            if (manager.findFragmentByTag(TAG) == null) {
                UpdateDialogFragment().apply {
                    arguments = bundleOf(
                        "version" to info.versionName,
                        "body" to info.body,
                        "url" to (info.apkUrl ?: info.htmlUrl),
                    )
                }.show(manager, TAG)
            }
            return true
        }
    }
}

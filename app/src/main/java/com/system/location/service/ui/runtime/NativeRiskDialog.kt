package com.system.location.service.ui.runtime

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.location.service.R

/** Confirmation stays attached to the fragment manager across configuration changes. */
class NativeRiskDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.native_risk_title)
            .setMessage(R.string.native_risk_message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> report(false) }
            .setPositiveButton(R.string.native_risk_accept) { _, _ -> report(true) }
            .create()

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        report(false)
    }

    private fun report(accepted: Boolean) {
        parentFragmentManager.setFragmentResult(RESULT, Bundle().apply { putBoolean(ACCEPTED, accepted) })
    }

    companion object {
        const val RESULT = "native_risk_confirmation"
        const val ACCEPTED = "accepted"
        const val TAG = "native_risk_dialog"
    }
}

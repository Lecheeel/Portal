package com.system.location.service.ui.runtime

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.system.location.service.ui.PortalDialog
import com.system.location.service.ui.PortalDialogContent
import com.system.location.service.R

/** Confirmation stays attached to the fragment manager across configuration changes. */
class NativeRiskDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        PortalDialog(requireContext()).content {
            PortalDialogContent(getString(R.string.native_risk_title), getString(R.string.native_risk_message),
                buttons = {
                    TextButton(onClick = { report(false); dismiss() }) { Text(stringResource(android.R.string.cancel)) }
                    TextButton(onClick = { report(true); dismiss() }) { Text(stringResource(R.string.native_risk_accept)) }
                })
        }

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

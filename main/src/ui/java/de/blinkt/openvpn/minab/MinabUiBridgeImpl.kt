/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 */

package de.blinkt.openvpn.minab

import android.app.Activity
import android.content.Intent
import androidx.annotation.Keep
import de.blinkt.openvpn.R
import de.blinkt.openvpn.activities.MinabDiagnosticsActivity

@Keep
object MinabUiBridgeImpl {

    @JvmStatic
    @Keep
    fun install() {
        MinabUiBridge.setHandler(object : MinabUiBridge.Handler {
            override fun showConfigError(
                activity: Activity,
                title: String,
                message: String,
                onDismiss: Runnable
            ): Boolean {
                MinabDialogs.showConfirmJava(
                    activity,
                    title,
                    message,
                    activity.getString(android.R.string.ok),
                    "",
                    true,
                    true,
                    onDismiss,
                    onDismiss
                )
                return true
            }

            override fun showPasswordPrompt(
                activity: Activity,
                title: String,
                scopePrompt: String,
                initUser: String,
                initPass: String,
                onConfirm: MinabUiBridge.CredentialsCallback,
                onCancel: Runnable
            ): Boolean {
                MinabDialogs.showCredentialsJava(
                    activity,
                    title,
                    scopePrompt,
                    false,
                    true,
                    false,
                    initUser,
                    initPass,
                    activity.getString(android.R.string.ok),
                    activity.getString(android.R.string.cancel),
                    { username, password, saveAsDefault ->
                        if (saveAsDefault) {
                            SharedCredentials.save(activity, username, password)
                        }
                        onConfirm.onResult(username, password, saveAsDefault)
                    },
                    onCancel
                )
                return true
            }

            override fun showSecretPrompt(
                activity: Activity,
                title: String,
                message: String,
                hint: String?,
                onConfirm: MinabUiBridge.TextCallback,
                onCancel: Runnable
            ): Boolean {
                MinabDialogs.showSecretInputJava(
                    activity,
                    title,
                    message,
                    hint,
                    activity.getString(android.R.string.ok),
                    { text -> onConfirm.onText(text) },
                    onCancel
                )
                return true
            }

            override fun openDiagnostics(activity: Activity): Boolean {
                activity.startActivity(Intent(activity, MinabDiagnosticsActivity::class.java))
                return true
            }

            override fun showPendingCrashDialog(
                activity: Activity,
                title: String,
                message: String,
                isDanger: Boolean,
                onClear: Runnable
            ): Boolean {
                MinabDialogs.showConfirmJava(
                    activity,
                    title,
                    message,
                    activity.getString(R.string.minab_diag_open_center),
                    activity.getString(R.string.minab_test_dismiss_btn),
                    isDanger,
                    true,
                    {
                        try {
                            activity.startActivity(Intent(activity, MinabDiagnosticsActivity::class.java))
                        } catch (_: Exception) {
                        }
                    },
                    onClear
                )
                return true
            }

            override fun setRealTestingUuid(uuid: String?) {
                MinabConnectState.setRealTestingUuid(uuid)
            }

            override fun setDisconnectingUuid(uuid: String?, minPulseMs: Long) {
                MinabConnectState.markDisconnecting(uuid, minPulseMs, 4000L)
            }

            override fun onPingServerStart(uuid: String?) {
                MinabConnectState.onPingServerStart(uuid)
            }

            override fun onPingServerEnd(uuid: String?) {
                MinabConnectState.onPingServerEnd(uuid)
            }
        })
    }
}

/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabRedetectIpDialog: lets the user re-detect IP-based countries for:
 *   - selected configs (when any checked)
 *   - the active group tab (including wrong-country groups)
 *   - uncategorized configs only
 *   - all configs
 */

package de.blinkt.openvpn.minab

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import java.util.concurrent.Executors

object MinabRedetectIpDialog {
    private val EXECUTOR = Executors.newSingleThreadExecutor()

    fun show(
        context: Context,
        allProfiles: List<VpnProfile>,
        selectedProfiles: List<VpnProfile>,
        currentGroup: String?
    ) {
        if (allProfiles.isEmpty()) {
            Toast.makeText(context, R.string.minab_no_profiles_yet, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = mutableListOf<String>()
        val targets = mutableListOf<List<VpnProfile>>()

        if (selectedProfiles.isNotEmpty()) {
            labels.add(context.getString(R.string.minab_redetect_scope_selected, selectedProfiles.size))
            targets.add(selectedProfiles)
        }
        if (currentGroup != null && !CategoryStore.isPseudoTab(currentGroup)) {
            val groupProfiles = allProfiles.filter {
                CategoryStore.normalise(it.getCategory()) == currentGroup
            }
            if (groupProfiles.isNotEmpty()) {
                labels.add(
                    context.getString(
                        R.string.minab_redetect_scope_group,
                        currentGroup,
                        groupProfiles.size
                    )
                )
                targets.add(groupProfiles)
            }
        }
        val uncategorized = allProfiles.filter {
            CategoryStore.normalise(it.getCategory()) == CategoryStore.DEFAULT_CATEGORY
        }
        if (uncategorized.isNotEmpty()) {
            labels.add(context.getString(R.string.minab_redetect_scope_uncat, uncategorized.size))
            targets.add(uncategorized)
        }
        labels.add(context.getString(R.string.minab_redetect_scope_all, allProfiles.size))
        targets.add(allProfiles)

        AlertDialog.Builder(context)
            .setTitle(R.string.minab_redetect_title)
            .setItems(labels.toTypedArray()) { _, which ->
                runRedetect(context, targets[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runRedetect(context: Context, targetList: List<VpnProfile>) {
        if (targetList.isEmpty()) return
        val appCtx = context.applicationContext
        val progressDialog = AlertDialog.Builder(context)
            .setTitle(R.string.minab_redetect_title)
            .setMessage(context.getString(R.string.minab_import_categorising, 0, targetList.size))
            .setCancelable(false)
            .create()
        try {
            progressDialog.show()
        } catch (_: Exception) {
        }

        EXECUTOR.execute {
            var updated = 0
            val total = targetList.size
            try {
                AutoCategory.detectCategoriesForProfiles(
                    appCtx,
                    targetList,
                    true
                ) { profile, code, countryName, done, tot ->
                    if (!code.isNullOrBlank()) {
                        MinabServerCountry.put(appCtx, profile.getUUIDString(), code)
                    }
                    if (!countryName.isNullOrBlank()) {
                        profile.setCategory(countryName)
                        CategoryStore.addCategory(appCtx, countryName)
                        try {
                            ProfileManager.saveProfile(appCtx, profile)
                        } catch (_: Exception) {
                        }
                        updated++
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            if (progressDialog.isShowing) {
                                progressDialog.setMessage(
                                    appCtx.getString(R.string.minab_import_categorising, done, tot)
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
            val finalUpdated = updated
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                } catch (_: Exception) {
                }
                MinabProfileBus.notifyChanged()
                Toast.makeText(
                    appCtx,
                    appCtx.getString(R.string.minab_redetect_done, finalUpdated, total),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

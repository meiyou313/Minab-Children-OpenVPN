/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabDialogs: shared modern rounded card dialogs replacing legacy
 * stock AlertDialog lists, inputs, confirmations, and Manage categories.
 */

package de.blinkt.openvpn.minab

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile

object MinabDialogs {

    data class ActionItem(
        val title: String,
        val iconRes: Int = 0,
        val badgeText: String? = null,
        val isDanger: Boolean = false,
        val onClick: () -> Unit
    )

    fun showActionList(
        context: Context,
        title: String,
        iconRes: Int = 0,
        items: List<ActionItem>
    ) {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.minab_action_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val headerIcon = root.findViewById<ImageView>(R.id.minab_action_dialog_icon)
        val titleView = root.findViewById<TextView>(R.id.minab_action_dialog_title)
        val closeX = root.findViewById<ImageButton>(R.id.minab_action_dialog_close_x)
        val scroll = root.findViewById<ScrollView>(R.id.minab_action_dialog_scroll)
        val container = root.findViewById<LinearLayout>(R.id.minab_action_dialog_container)
        val cancelBtn = root.findViewById<Button>(R.id.minab_action_dialog_cancel)

        titleView.text = title
        val tealColor = ContextCompat.getColor(context, R.color.minab_teal)
        val dangerColor = ContextCompat.getColor(context, R.color.minab_danger)
        val dangerTintColor = ContextCompat.getColor(context, R.color.minab_danger_tint)
        val primaryTextColor = ContextCompat.getColor(context, R.color.minab_text_primary)

        if (iconRes != 0) {
            headerIcon.setImageResource(iconRes)
            headerIcon.imageTintList = ColorStateList.valueOf(tealColor)
            headerIcon.visibility = View.VISIBLE
        } else {
            headerIcon.visibility = View.GONE
        }

        closeX.setOnClickListener { dialog.dismiss() }
        cancelBtn.setOnClickListener { dialog.dismiss() }

        for (item in items) {
            val row = inflater.inflate(R.layout.minab_action_dialog_row, container, false)
            val rowIcon = row.findViewById<ImageView>(R.id.minab_action_row_icon)
            val rowTitle = row.findViewById<TextView>(R.id.minab_action_row_title)
            val rowBadge = row.findViewById<TextView>(R.id.minab_action_row_badge)
            val rowChevron = row.findViewById<ImageView>(R.id.minab_action_row_chevron)

            if (item.iconRes != 0) {
                rowIcon.setImageResource(item.iconRes)
                rowIcon.imageTintList = ColorStateList.valueOf(
                    if (item.isDanger) dangerColor else tealColor
                )
                rowIcon.visibility = View.VISIBLE
            } else {
                rowIcon.visibility = View.GONE
            }

            rowTitle.text = item.title
            rowTitle.setTextColor(if (item.isDanger) dangerColor else primaryTextColor)
            rowChevron.imageTintList = ColorStateList.valueOf(
                if (item.isDanger) dangerColor
                else ContextCompat.getColor(context, R.color.minab_text_secondary)
            )

            val badge = item.badgeText?.trim()
            if (!badge.isNullOrEmpty()) {
                rowBadge.text = badge
                rowBadge.visibility = View.VISIBLE
                if (item.isDanger) {
                    rowBadge.setTextColor(dangerColor)
                    rowBadge.backgroundTintList = ColorStateList.valueOf(dangerTintColor)
                }
            } else {
                rowBadge.visibility = View.GONE
            }

            row.setOnClickListener {
                dialog.dismiss()
                item.onClick()
            }
            container.addView(row)
        }

        if (items.size > 7) {
            scroll.layoutParams.height = (context.resources.displayMetrics.heightPixels * 0.52f).toInt()
        }
        dialog.show()
    }

    fun showManageCategories(
        context: Context,
        profilesProvider: () -> List<VpnProfile>,
        onRedetect: () -> Unit,
        onCategoryCreated: (String) -> Unit,
        onCategoryRenamed: (oldName: String, newName: String) -> Unit,
        onCategoryDeleted: (String) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.minab_manage_categories_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val closeBtn = root.findViewById<ImageButton>(R.id.minab_cat_dialog_close)
        val emptyView = root.findViewById<TextView>(R.id.minab_cat_dialog_empty)
        val scroll = root.findViewById<ScrollView>(R.id.minab_cat_dialog_scroll)
        val container = root.findViewById<LinearLayout>(R.id.minab_cat_dialog_container)
        val redetectBtn = root.findViewById<Button>(R.id.minab_cat_btn_redetect)
        val newBtn = root.findViewById<Button>(R.id.minab_cat_btn_new)

        val density = context.resources.displayMetrics.density
        val flagW = (22f * density).toInt()
        val flagH = (16f * density).toInt()
        val folderSize = (18f * density).toInt()
        val tealColor = ContextCompat.getColor(context, R.color.minab_teal)

        fun renderRows() {
            container.removeAllViews()
            val categories = CategoryStore.getOrderedCategories(context)
                .filter { it != CategoryStore.DEFAULT_CATEGORY }

            val counts = HashMap<String, Int>()
            for (profile in profilesProvider()) {
                val cat = CategoryStore.normalise(profile.getCategory())
                counts[cat] = (counts[cat] ?: 0) + 1
            }

            if (categories.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                scroll.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                scroll.visibility = View.VISIBLE
            }

            scroll.layoutParams.height = if (categories.size > 6) {
                (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }

            for (cat in categories) {
                val row = inflater.inflate(R.layout.minab_manage_category_row, container, false)
                val icon = row.findViewById<ImageView>(R.id.minab_cat_row_icon)
                val nameView = row.findViewById<TextView>(R.id.minab_cat_row_name)
                val countView = row.findViewById<TextView>(R.id.minab_cat_row_count)
                val renameBtn = row.findViewById<ImageButton>(R.id.minab_cat_row_rename)
                val deleteBtn = row.findViewById<ImageButton>(R.id.minab_cat_row_delete)

                val flagRes = FlagHelper.flagResForCategory(cat)
                val lp = icon.layoutParams as LinearLayout.LayoutParams
                if (flagRes != 0 && flagRes != R.drawable.minab_flag_globe) {
                    icon.setImageResource(flagRes)
                    icon.imageTintList = null
                    lp.width = flagW
                    lp.height = flagH
                } else {
                    icon.setImageResource(R.drawable.ic_baseline_folder_24)
                    icon.imageTintList = ColorStateList.valueOf(tealColor)
                    lp.width = folderSize
                    lp.height = folderSize
                }
                icon.layoutParams = lp

                nameView.text = cat
                countView.text = (counts[cat] ?: 0).toString()

                val openRename = View.OnClickListener {
                    showTextInput(
                        context = context,
                        title = context.getString(R.string.minab_rename_category),
                        initialText = cat,
                        hint = context.getString(R.string.minab_group_name_hint)
                    ) { newName ->
                        CategoryStore.renameCategory(context, cat, newName)
                        onCategoryRenamed(cat, CategoryStore.normalise(newName))
                        renderRows()
                    }
                }
                row.setOnClickListener(openRename)
                renameBtn.setOnClickListener(openRename)

                deleteBtn.setOnClickListener {
                    showConfirm(
                        context = context,
                        title = context.getString(R.string.minab_delete_category),
                        message = cat,
                        positiveText = context.getString(R.string.minab_delete_category),
                        isDanger = true
                    ) {
                        CategoryStore.deleteCategory(context, cat)
                        onCategoryDeleted(cat)
                        renderRows()
                    }
                }
                container.addView(row)
            }
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        redetectBtn.setOnClickListener {
            dialog.dismiss()
            onRedetect()
        }
        newBtn.setOnClickListener {
            showTextInput(
                context = context,
                title = context.getString(R.string.minab_new_category),
                hint = context.getString(R.string.minab_group_name_hint)
            ) { raw ->
                val name = CategoryStore.normalise(raw)
                CategoryStore.addManualCategory(context, name)
                onCategoryCreated(name)
                renderRows()
            }
        }

        renderRows()
        dialog.show()
    }

    fun showTextInput(
        context: Context,
        title: String,
        initialText: String = "",
        hint: String? = null,
        positiveText: String = context.getString(android.R.string.ok),
        onConfirm: (String) -> Unit
    ) {
        val root = LayoutInflater.from(context).inflate(R.layout.minab_input_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }

        val titleView = root.findViewById<TextView>(R.id.minab_input_dialog_title)
        val field = root.findViewById<EditText>(R.id.minab_input_dialog_field)
        val cancelBtn = root.findViewById<Button>(R.id.minab_input_dialog_cancel)
        val okBtn = root.findViewById<Button>(R.id.minab_input_dialog_ok)

        titleView.text = title
        okBtn.text = positiveText
        field.visibility = View.VISIBLE
        if (!hint.isNullOrEmpty()) field.hint = hint
        if (initialText.isNotEmpty()) {
            field.setText(initialText)
            field.setSelection(field.text.length)
        }
        field.requestFocus()

        cancelBtn.setOnClickListener { dialog.dismiss() }
        okBtn.setOnClickListener {
            val value = field.text.toString()
            dialog.dismiss()
            onConfirm(value)
        }
        dialog.show()
    }

    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        positiveText: String = context.getString(android.R.string.ok),
        isDanger: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val root = LayoutInflater.from(context).inflate(R.layout.minab_input_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val titleView = root.findViewById<TextView>(R.id.minab_input_dialog_title)
        val messageView = root.findViewById<TextView>(R.id.minab_input_dialog_message)
        val cancelBtn = root.findViewById<Button>(R.id.minab_input_dialog_cancel)
        val okBtn = root.findViewById<Button>(R.id.minab_input_dialog_ok)

        titleView.text = title
        messageView.text = message
        messageView.visibility = View.VISIBLE
        okBtn.text = positiveText
        if (isDanger) {
            okBtn.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.minab_danger)
            )
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }
        okBtn.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.show()
    }
}

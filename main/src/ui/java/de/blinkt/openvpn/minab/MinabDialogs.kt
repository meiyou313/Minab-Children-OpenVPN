/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabDialogs: shared modern rounded card dialogs replacing legacy
 * stock AlertDialog lists, inputs, confirmations, multi-choice, progress,
 * scoped credentials, Manage categories, empty-group cleaner & failed cleaner.
 */

package de.blinkt.openvpn.minab

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus

object MinabDialogs {

    fun interface TextCallback {
        fun onText(text: String)
    }

    fun interface CredentialsCallback {
        fun onResult(username: String, password: String, saveAsDefault: Boolean)
    }

    data class ActionItem(
        val title: String,
        val iconRes: Int = 0,
        val badgeText: String? = null,
        val isDanger: Boolean = false,
        val hasAttentionDot: Boolean = false,
        val subtitle: String? = null,
        val onClick: () -> Unit
    )

    data class MultiChoiceOption(
        val label: String,
        val sublabel: String? = null,
        val checked: Boolean = false,
        val iconRes: Int = 0
    )

    class ProgressController internal constructor(
        private val dialog: AlertDialog,
        private val messageView: TextView,
        private val bar: ProgressBar,
        private val determinate: Boolean
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())

        fun update(message: String, progress: Int = -1, max: Int = 100) {
            val apply = Runnable {
                try {
                    if (!dialog.isShowing) return@Runnable
                    messageView.text = message
                    if (determinate && progress >= 0) {
                        bar.visibility = View.VISIBLE
                        bar.isIndeterminate = false
                        bar.max = max.coerceAtLeast(1)
                        bar.progress = progress.coerceIn(0, bar.max)
                    }
                } catch (_: Exception) {
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) apply.run()
            else mainHandler.post(apply)
        }

        fun dismiss() {
            val close = Runnable {
                try {
                    if (dialog.isShowing) dialog.dismiss()
                } catch (_: Exception) {
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) close.run()
            else mainHandler.post(close)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun showActionList(
        context: Context,
        title: String,
        iconRes: Int = 0,
        items: List<ActionItem>,
        subtitle: String? = null
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

        if (iconRes != 0) {
            headerIcon.setImageResource(iconRes)
            headerIcon.visibility = View.VISIBLE
        } else {
            headerIcon.visibility = View.GONE
        }
        titleView.text = title
        closeX.setOnClickListener { dialog.dismiss() }
        cancelBtn.setOnClickListener { dialog.dismiss() }

        if (!subtitle.isNullOrBlank()) {
            val parentLayout = scroll.parent as? LinearLayout
            val scrollIdx = parentLayout?.indexOfChild(scroll) ?: -1
            if (parentLayout != null && scrollIdx >= 0) {
                val sub = TextView(context).apply {
                    text = subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(ContextCompat.getColor(context, R.color.minab_text_secondary))
                    typeface = ResourcesCompat.getFont(context, R.font.vazirmatn)
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    val dp4 = (4f * context.resources.displayMetrics.density).toInt()
                    lp.bottomMargin = dp4
                    layoutParams = lp
                }
                parentLayout.addView(sub, scrollIdx)
            }
        }

        val tealColor = ContextCompat.getColor(context, R.color.minab_teal)
        val dangerColor = ContextCompat.getColor(context, R.color.minab_danger)
        val dangerTintColor = ContextCompat.getColor(context, R.color.minab_danger_tint)
        val primaryTextColor = ContextCompat.getColor(context, R.color.minab_text_primary)
        val secondaryTextColor = ContextCompat.getColor(context, R.color.minab_text_secondary)

        for (item in items) {
            val row = inflater.inflate(R.layout.minab_action_dialog_row, container, false)
            val rowIcon = row.findViewById<ImageView>(R.id.minab_action_row_icon)
            val rowTitle = row.findViewById<TextView>(R.id.minab_action_row_title)
            val rowSub = row.findViewById<TextView>(R.id.minab_action_row_subtitle)
            val rowDot = row.findViewById<View>(R.id.minab_action_row_dot)
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

            if (!item.subtitle.isNullOrBlank()) {
                rowSub.text = item.subtitle
                rowSub.visibility = View.VISIBLE
            } else {
                rowSub.visibility = View.GONE
            }

            rowDot.visibility = if (item.hasAttentionDot) View.VISIBLE else View.GONE

            rowChevron.imageTintList = ColorStateList.valueOf(
                if (item.isDanger) dangerColor else secondaryTextColor
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

    @JvmOverloads
    fun showManageCategories(
        context: Context,
        profilesProvider: () -> List<VpnProfile>,
        onRedetect: () -> Unit,
        onCategoryCreated: (String) -> Unit,
        onCategoryRenamed: (oldName: String, newName: String) -> Unit,
        onCategoryDeleted: (String) -> Unit,
        onCleanEmpty: (() -> Unit)? = null
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

        // Optional compact Clean Empty shortcut pill inserted right above the scroll area.
        val rootLinear = root as? LinearLayout
        val scrollIdx = rootLinear?.indexOfChild(scroll) ?: -1
        val cleanPill = TextView(context).apply {
            setBackgroundResource(R.drawable.minab_chip_bg_selected)
            setTextColor(ContextCompat.getColor(context, R.color.minab_teal_dark))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = ResourcesCompat.getFont(context, R.font.vazirmatn)
            setTypeface(typeface, Typeface.BOLD)
            val dp10 = (10f * density).toInt()
            val dp5 = (5f * density).toInt()
            setPadding(dp10, dp5, dp10, dp5)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (6f * density).toInt()
            layoutParams = lp
        }
        if (rootLinear != null && scrollIdx >= 0) {
            rootLinear.addView(cleanPill, scrollIdx)
        }

        fun renderRows() {
            container.removeAllViews()
            val allProfiles = profilesProvider()
            val categories = CategoryStore.getOrderedCategories(context)
                .filter { it != CategoryStore.DEFAULT_CATEGORY }

            val counts = HashMap<String, Int>()
            for (profile in allProfiles) {
                for (grp in profile.getAllGroups()) {
                    val norm = CategoryStore.normalise(grp)
                    if (norm != CategoryStore.DEFAULT_CATEGORY) {
                        counts[norm] = (counts[norm] ?: 0) + 1
                    }
                }
            }

            val autoEmptyCount = CategoryStore.getEmptyAutoCategories(context, allProfiles).size
            val manualEmptyCount = CategoryStore.getEmptyManualCategories(context, allProfiles).size
            val totalEmpty = autoEmptyCount + manualEmptyCount
            if (totalEmpty > 0) {
                cleanPill.text = context.getString(R.string.minab_clean_empty_pill_fmt, totalEmpty)
                cleanPill.visibility = View.VISIBLE
                cleanPill.setOnClickListener {
                    if (onCleanEmpty != null) {
                        dialog.dismiss()
                        onCleanEmpty()
                    } else {
                        showCleanEmptyGroupsDialog(context, profilesProvider) {
                            renderRows()
                        }
                    }
                }
            } else {
                cleanPill.visibility = View.GONE
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

                val isManual = CategoryStore.isManualCategory(context, cat)
                val flagRes = FlagHelper.flagResForCategory(cat)
                val lp = icon.layoutParams as LinearLayout.LayoutParams
                if (!isManual && flagRes != 0 && flagRes != R.drawable.minab_flag_globe) {
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

                nameView.text = if (isManual) {
                    "$cat · ${context.getString(R.string.minab_group_manual_tag)}"
                } else {
                    cat
                }
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

    @JvmOverloads
    fun showTextInput(
        context: Context,
        title: String,
        initialText: String = "",
        hint: String? = null,
        positiveText: String = context.getString(android.R.string.ok),
        onCancel: (() -> Unit)? = null,
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

        cancelBtn.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }
        dialog.setOnCancelListener {
            onCancel?.invoke()
        }
        okBtn.setOnClickListener {
            val value = field.text.toString()
            dialog.dismiss()
            onConfirm(value)
        }
        dialog.show()
    }

    @JvmStatic
    @JvmOverloads
    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        positiveText: String = context.getString(android.R.string.ok),
        isDanger: Boolean = false,
        negativeText: String = context.getString(android.R.string.cancel),
        cancelable: Boolean = true,
        onCancel: (() -> Unit)? = null,
        onConfirm: () -> Unit
    ) {
        val root = LayoutInflater.from(context).inflate(R.layout.minab_confirm_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(cancelable)

        val iconView = root.findViewById<ImageView>(R.id.minab_confirm_icon)
        val titleView = root.findViewById<TextView>(R.id.minab_confirm_title)
        val messageView = root.findViewById<TextView>(R.id.minab_confirm_message)
        val cancelBtn = root.findViewById<Button>(R.id.minab_confirm_negative)
        val okBtn = root.findViewById<Button>(R.id.minab_confirm_positive)

        titleView.text = title
        messageView.text = message
        okBtn.text = positiveText

        if (isDanger) {
            val danger = ContextCompat.getColor(context, R.color.minab_danger)
            okBtn.backgroundTintList = ColorStateList.valueOf(danger)
            iconView.setImageResource(R.drawable.ic_baseline_warning_24)
            iconView.imageTintList = ColorStateList.valueOf(danger)
            iconView.visibility = View.VISIBLE
        }

        if (negativeText.isEmpty()) {
            cancelBtn.visibility = View.GONE
        } else {
            cancelBtn.text = negativeText
            cancelBtn.setOnClickListener {
                dialog.dismiss()
                onCancel?.invoke()
            }
        }

        dialog.setOnCancelListener {
            onCancel?.invoke()
        }
        okBtn.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.show()
    }

    @JvmOverloads
    fun showConfirmWithCheckbox(
        context: Context,
        title: String,
        message: String,
        checkboxLabel: String,
        initialChecked: Boolean = false,
        positiveText: String = context.getString(android.R.string.ok),
        isDanger: Boolean = false,
        onCancel: (() -> Unit)? = null,
        onConfirm: (checked: Boolean) -> Unit
    ) {
        val root = LayoutInflater.from(context).inflate(R.layout.minab_confirm_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val iconView = root.findViewById<ImageView>(R.id.minab_confirm_icon)
        val titleView = root.findViewById<TextView>(R.id.minab_confirm_title)
        val messageView = root.findViewById<TextView>(R.id.minab_confirm_message)
        val checkBox = root.findViewById<CheckBox>(R.id.minab_confirm_checkbox)
        val cancelBtn = root.findViewById<Button>(R.id.minab_confirm_negative)
        val okBtn = root.findViewById<Button>(R.id.minab_confirm_positive)

        titleView.text = title
        messageView.text = message
        okBtn.text = positiveText
        checkBox.visibility = View.VISIBLE
        checkBox.text = checkboxLabel
        checkBox.isChecked = initialChecked

        if (isDanger) {
            val danger = ContextCompat.getColor(context, R.color.minab_danger)
            okBtn.backgroundTintList = ColorStateList.valueOf(danger)
            iconView.setImageResource(R.drawable.ic_baseline_warning_24)
            iconView.imageTintList = ColorStateList.valueOf(danger)
            iconView.visibility = View.VISIBLE
        }

        cancelBtn.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }
        dialog.setOnCancelListener {
            onCancel?.invoke()
        }
        okBtn.setOnClickListener {
            val checked = checkBox.isChecked
            dialog.dismiss()
            onConfirm(checked)
        }
        dialog.show()
    }

    @JvmOverloads
    fun showThreeWayConfirm(
        context: Context,
        title: String,
        message: String,
        positiveText: String,
        neutralText: String,
        negativeText: String = context.getString(android.R.string.cancel),
        isPositiveDanger: Boolean = false,
        isNeutralDanger: Boolean = false,
        onPositive: () -> Unit,
        onNeutral: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val root = LayoutInflater.from(context).inflate(R.layout.minab_confirm_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val iconView = root.findViewById<ImageView>(R.id.minab_confirm_icon)
        val titleView = root.findViewById<TextView>(R.id.minab_confirm_title)
        val messageView = root.findViewById<TextView>(R.id.minab_confirm_message)
        val neutralBtn = root.findViewById<Button>(R.id.minab_confirm_neutral)
        val cancelBtn = root.findViewById<Button>(R.id.minab_confirm_negative)
        val okBtn = root.findViewById<Button>(R.id.minab_confirm_positive)

        titleView.text = title
        messageView.text = message

        val danger = ContextCompat.getColor(context, R.color.minab_danger)
        if (isPositiveDanger || isNeutralDanger) {
            iconView.setImageResource(R.drawable.ic_baseline_warning_24)
            iconView.imageTintList = ColorStateList.valueOf(danger)
            iconView.visibility = View.VISIBLE
        }

        neutralBtn.visibility = View.VISIBLE
        neutralBtn.text = neutralText
        if (isNeutralDanger) {
            neutralBtn.setTextColor(danger)
        }
        neutralBtn.setOnClickListener {
            dialog.dismiss()
            onNeutral()
        }

        cancelBtn.text = negativeText
        cancelBtn.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }
        dialog.setOnCancelListener {
            onCancel?.invoke()
        }

        okBtn.text = positiveText
        if (isPositiveDanger) {
            okBtn.backgroundTintList = ColorStateList.valueOf(danger)
        }
        okBtn.setOnClickListener {
            dialog.dismiss()
            onPositive()
        }
        dialog.show()
    }

    @JvmOverloads
    fun showSingleChoice(
        context: Context,
        title: String,
        options: List<String>,
        selectedIndex: Int = -1,
        iconRes: Int = 0,
        onPicked: (Int) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.minab_multi_choice_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val titleView = root.findViewById<TextView>(R.id.minab_multi_title)
        val toggleAll = root.findViewById<TextView>(R.id.minab_multi_toggle_all)
        val container = root.findViewById<LinearLayout>(R.id.minab_multi_container)
        val cancelBtn = root.findViewById<Button>(R.id.minab_multi_cancel)
        val okBtn = root.findViewById<Button>(R.id.minab_multi_positive)

        titleView.text = title
        toggleAll.visibility = View.GONE
        okBtn.visibility = View.GONE

        options.forEachIndexed { idx, label ->
            val row = inflater.inflate(R.layout.minab_picker_option_row, container, false)
            val icon = row.findViewById<ImageView>(R.id.minab_picker_icon)
            val text = row.findViewById<TextView>(R.id.minab_picker_label)
            val check = row.findViewById<ImageView>(R.id.minab_picker_check)
            val selected = idx == selectedIndex

            if (iconRes != 0) {
                icon.setImageResource(iconRes)
                icon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.minab_teal)
                )
                icon.visibility = View.VISIBLE
            }
            text.text = label
            row.setBackgroundResource(
                if (selected) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
            )
            check.visibility = if (selected) View.VISIBLE else View.GONE
            row.setOnClickListener {
                dialog.dismiss()
                onPicked(idx)
            }
            container.addView(row)
        }

        val scroll = container.parent as? ScrollView
        if (scroll != null && options.size > 7) {
            scroll.layoutParams.height = (context.resources.displayMetrics.heightPixels * 0.50f).toInt()
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    @JvmOverloads
    fun showMultiChoice(
        context: Context,
        title: String,
        options: List<MultiChoiceOption>,
        positiveText: String = context.getString(android.R.string.ok),
        showSelectAll: Boolean = true,
        extraCheckLabel: String? = null,
        onConfirm: (selectedIndices: List<Int>, extraChecked: Boolean) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.minab_multi_choice_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val titleView = root.findViewById<TextView>(R.id.minab_multi_title)
        val toggleAll = root.findViewById<TextView>(R.id.minab_multi_toggle_all)
        val container = root.findViewById<LinearLayout>(R.id.minab_multi_container)
        val extraCheck = root.findViewById<CheckBox>(R.id.minab_multi_extra_check)
        val cancelBtn = root.findViewById<Button>(R.id.minab_multi_cancel)
        val okBtn = root.findViewById<Button>(R.id.minab_multi_positive)

        titleView.text = title
        okBtn.text = positiveText

        if (!extraCheckLabel.isNullOrBlank()) {
            extraCheck.text = extraCheckLabel
            extraCheck.visibility = View.VISIBLE
        } else {
            extraCheck.visibility = View.GONE
        }

        val checked = BooleanArray(options.size) { i -> options[i].checked }
        val rowViews = ArrayList<Pair<View, ImageView>>(options.size)

        fun updateToggleLabel() {
            if (!showSelectAll || options.isEmpty()) {
                toggleAll.visibility = View.GONE
                return
            }
            toggleAll.visibility = View.VISIBLE
            val allChecked = checked.all { it }
            toggleAll.text = context.getString(
                if (allChecked) R.string.minab_deselect_all else R.string.minab_select_all
            )
        }

        fun refreshRow(index: Int) {
            val (row, checkIcon) = rowViews[index]
            val active = checked[index]
            row.setBackgroundResource(
                if (active) R.drawable.minab_chip_bg_selected else R.drawable.minab_chip_bg
            )
            checkIcon.visibility = if (active) View.VISIBLE else View.GONE
        }

        options.forEachIndexed { idx, opt ->
            val row = inflater.inflate(R.layout.minab_picker_option_row, container, false)
            val icon = row.findViewById<ImageView>(R.id.minab_picker_icon)
            val label = row.findViewById<TextView>(R.id.minab_picker_label)
            val sub = row.findViewById<TextView>(R.id.minab_picker_sublabel)
            val check = row.findViewById<ImageView>(R.id.minab_picker_check)

            if (opt.iconRes != 0) {
                icon.setImageResource(opt.iconRes)
                icon.visibility = View.VISIBLE
            }
            label.text = opt.label
            if (!opt.sublabel.isNullOrBlank()) {
                sub.text = opt.sublabel
                sub.visibility = View.VISIBLE
            }
            rowViews.add(row to check)
            refreshRow(idx)

            row.setOnClickListener {
                checked[idx] = !checked[idx]
                refreshRow(idx)
                updateToggleLabel()
            }
            container.addView(row)
        }

        updateToggleLabel()
        toggleAll.setOnClickListener {
            val target = !checked.all { it }
            for (i in checked.indices) {
                checked[i] = target
                refreshRow(i)
            }
            updateToggleLabel()
        }

        val scroll = container.parent as? ScrollView
        if (scroll != null && options.size > 7) {
            scroll.layoutParams.height = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }
        okBtn.setOnClickListener {
            val picked = ArrayList<Int>()
            for (i in checked.indices) {
                if (checked[i]) picked.add(i)
            }
            val extra = extraCheck.visibility == View.VISIBLE && extraCheck.isChecked
            dialog.dismiss()
            onConfirm(picked, extra)
        }
        dialog.show()
    }

    @JvmOverloads
    fun showProgressDialog(
        context: Context,
        title: String,
        initialMessage: String = "",
        determinate: Boolean = false,
        cancelable: Boolean = false,
        onCancel: (() -> Unit)? = null
    ): ProgressController {
        val root = LayoutInflater.from(context).inflate(R.layout.minab_progress_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(cancelable)

        val titleView = root.findViewById<TextView>(R.id.minab_progress_title)
        val msgView = root.findViewById<TextView>(R.id.minab_progress_message)
        val bar = root.findViewById<ProgressBar>(R.id.minab_progress_bar)
        val cancelBtn = root.findViewById<Button>(R.id.minab_progress_cancel)

        titleView.text = title
        msgView.text = initialMessage
        if (determinate) {
            bar.visibility = View.VISIBLE
            bar.isIndeterminate = true
        }
        if (cancelable || onCancel != null) {
            cancelBtn.visibility = View.VISIBLE
            cancelBtn.setOnClickListener {
                onCancel?.invoke()
                dialog.dismiss()
            }
            dialog.setOnCancelListener {
                onCancel?.invoke()
            }
        }
        try {
            dialog.show()
        } catch (_: Exception) {
        }
        return ProgressController(dialog, msgView, bar, determinate)
    }

    @JvmOverloads
    fun showCredentialsDialog(
        context: Context,
        title: String,
        scopeSummary: String,
        isAllScopeWarning: Boolean,
        showSaveDefaultOption: Boolean,
        initialSaveDefault: Boolean = false,
        initialUsername: String = "",
        initialPassword: String = "",
        positiveText: String = context.getString(R.string.minab_apply_credentials),
        negativeText: String = context.getString(android.R.string.cancel),
        onConfirm: (username: String, password: String, saveAsDefault: Boolean) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val root = LayoutInflater.from(context).inflate(R.layout.minab_credentials_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }

        val titleView = root.findViewById<TextView>(R.id.minab_creds_title)
        val scopeBox = root.findViewById<LinearLayout>(R.id.minab_creds_scope_box)
        val scopeIcon = root.findViewById<ImageView>(R.id.minab_creds_scope_icon)
        val scopeText = root.findViewById<TextView>(R.id.minab_creds_scope_text)
        val fillRow = root.findViewById<LinearLayout>(R.id.minab_creds_fill_row)
        val fillSaved = root.findViewById<TextView>(R.id.minab_creds_fill_saved)
        val userField = root.findViewById<EditText>(R.id.minab_creds_user)
        val passField = root.findViewById<EditText>(R.id.minab_creds_pass)
        val passToggle = root.findViewById<ImageButton>(R.id.minab_creds_pass_toggle)
        val saveDefault = root.findViewById<CheckBox>(R.id.minab_creds_save_default)
        val cancelBtn = root.findViewById<Button>(R.id.minab_creds_cancel)
        val saveBtn = root.findViewById<Button>(R.id.minab_creds_save)

        titleView.text = title
        scopeText.text = scopeSummary
        saveBtn.text = positiveText
        cancelBtn.text = negativeText

        if (isAllScopeWarning) {
            scopeBox.setBackgroundResource(R.drawable.minab_scope_banner_all)
            scopeIcon.setImageResource(R.drawable.ic_baseline_warning_24)
            scopeIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.minab_test_ping_border)
            )
        } else {
            scopeBox.setBackgroundResource(R.drawable.minab_scope_banner_safe)
            scopeIcon.setImageResource(R.drawable.ic_minab_check_circle)
            scopeIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.minab_connected)
            )
        }

        if (initialUsername.isNotEmpty()) {
            userField.setText(initialUsername)
        }
        if (initialPassword.isNotEmpty()) {
            passField.setText(initialPassword)
        }

        if (SharedCredentials.hasCredentials(context)) {
            fillRow.visibility = View.VISIBLE
            fillSaved.setOnClickListener {
                userField.setText(SharedCredentials.getUsername(context))
                passField.setText(SharedCredentials.getPassword(context))
            }
        } else {
            fillRow.visibility = View.GONE
        }

        var passwordVisible = false
        val vazir = ResourcesCompat.getFont(context, R.font.vazirmatn)
        passToggle.setOnClickListener {
            passwordVisible = !passwordVisible
            val sel = passField.selectionEnd.coerceAtLeast(0)
            passField.inputType = if (passwordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            passField.typeface = vazir
            passField.setSelection(sel.coerceAtMost(passField.text?.length ?: 0))
            passToggle.setImageResource(
                if (passwordVisible) R.drawable.ic_minab_eye_off else R.drawable.ic_minab_eye
            )
        }

        if (showSaveDefaultOption) {
            saveDefault.visibility = View.VISIBLE
            saveDefault.isChecked = initialSaveDefault
        } else {
            saveDefault.visibility = View.GONE
        }

        cancelBtn.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }
        dialog.setOnCancelListener {
            onCancel?.invoke()
        }
        saveBtn.setOnClickListener {
            val u = userField.text.toString()
            val p = passField.text.toString()
            val def = saveDefault.visibility == View.VISIBLE && saveDefault.isChecked
            dialog.dismiss()
            onConfirm(u, p, def)
        }
        dialog.show()
    }

    fun showCleanEmptyGroupsDialog(
        context: Context,
        profilesProvider: () -> List<VpnProfile>,
        onChanged: () -> Unit
    ) {
        val profiles = profilesProvider()
        val autoEmpty = CategoryStore.getEmptyAutoCategories(context, profiles)
        val manualEmpty = CategoryStore.getEmptyManualCategories(context, profiles)
        if (autoEmpty.isEmpty() && manualEmpty.isEmpty()) {
            Toast.makeText(context, R.string.minab_no_empty_groups, Toast.LENGTH_SHORT).show()
            return
        }

        fun applyClean(includeManual: Boolean) {
            val removed = CategoryStore.cleanEmptyCategories(context, profilesProvider(), includeManual)
            MinabProfileBus.notifyChanged()
            onChanged()
            Toast.makeText(
                context,
                context.getString(R.string.minab_empty_groups_cleaned, removed),
                Toast.LENGTH_SHORT
            ).show()
        }

        when {
            autoEmpty.isNotEmpty() && manualEmpty.isEmpty() -> {
                showConfirm(
                    context = context,
                    title = context.getString(R.string.minab_clean_empty_title),
                    message = context.getString(R.string.minab_clean_empty_msg_auto, autoEmpty.size),
                    positiveText = context.getString(R.string.minab_clean_empty_action)
                ) {
                    applyClean(false)
                }
            }
            autoEmpty.isEmpty() && manualEmpty.isNotEmpty() -> {
                showConfirmWithCheckbox(
                    context = context,
                    title = context.getString(R.string.minab_clean_empty_title),
                    message = context.getString(R.string.minab_clean_empty_msg_manual, manualEmpty.size),
                    checkboxLabel = context.getString(R.string.minab_clean_empty_include_manual, manualEmpty.size),
                    initialChecked = false,
                    positiveText = context.getString(R.string.minab_clean_empty_action),
                    isDanger = true
                ) { checked ->
                    if (checked) {
                        applyClean(true)
                    } else {
                        Toast.makeText(context, R.string.minab_clean_empty_none_selected, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            else -> {
                showConfirmWithCheckbox(
                    context = context,
                    title = context.getString(R.string.minab_clean_empty_title),
                    message = context.getString(
                        R.string.minab_clean_empty_msg_both,
                        autoEmpty.size,
                        manualEmpty.size
                    ),
                    checkboxLabel = context.getString(R.string.minab_clean_empty_include_manual, manualEmpty.size),
                    initialChecked = false,
                    positiveText = context.getString(R.string.minab_clean_empty_action)
                ) { includeManual ->
                    applyClean(includeManual)
                }
            }
        }
    }

    fun showDeleteFailedDialog(
        context: Context,
        profiles: Collection<VpnProfile>,
        onDeleted: (Int) -> Unit
    ) {
        val connectedUuid = try {
            if (VpnStatus.isVPNActive()) VpnStatus.getLastConnectedVPNProfile() else null
        } catch (_: Exception) {
            null
        }
        val failed = MinabProfileBus.findFailedProfiles(profiles)
            .filter { it.getUUIDString() != connectedUuid }
        val untested = MinabProfileBus.findUntestedProfiles(profiles)
            .filter { it.getUUIDString() != connectedUuid }

        if (failed.isEmpty() && untested.isEmpty()) {
            Toast.makeText(context, R.string.minab_no_inactive, Toast.LENGTH_SHORT).show()
            return
        }

        fun performDelete(targets: List<VpnProfile>) {
            if (targets.isEmpty()) return
            val pm = ProfileManager.getInstance(context)
            val removedUuids = ArrayList<String>(targets.size)
            for (p in targets) {
                removedUuids.add(p.getUUIDString())
                pm.removeProfile(context, p)
            }
            MinabServerStats.removeStatsForUuids(context, removedUuids)
            MinabProfileBus.notifyChanged()
            Toast.makeText(
                context,
                context.getString(R.string.minab_inactive_removed, targets.size),
                Toast.LENGTH_SHORT
            ).show()
            onDeleted(targets.size)
        }

        if (untested.isEmpty()) {
            showConfirm(
                context = context,
                title = context.getString(R.string.minab_remove_inactive),
                message = context.getString(R.string.minab_delete_failed_msg_only, failed.size),
                positiveText = context.getString(R.string.minab_remove_inactive),
                isDanger = true
            ) {
                performDelete(failed)
            }
        } else if (failed.isEmpty()) {
            showConfirmWithCheckbox(
                context = context,
                title = context.getString(R.string.minab_remove_inactive),
                message = context.getString(R.string.minab_delete_failed_msg_none_tested, untested.size),
                checkboxLabel = context.getString(R.string.minab_delete_failed_include_untested, untested.size),
                initialChecked = false,
                positiveText = context.getString(R.string.minab_remove_inactive),
                isDanger = true
            ) { includeUntested ->
                if (includeUntested) {
                    performDelete(untested)
                } else {
                    Toast.makeText(context, R.string.minab_no_inactive, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            showConfirmWithCheckbox(
                context = context,
                title = context.getString(R.string.minab_remove_inactive),
                message = context.getString(R.string.minab_delete_failed_msg_with_untested, failed.size, untested.size),
                checkboxLabel = context.getString(R.string.minab_delete_failed_include_untested, untested.size),
                initialChecked = false,
                positiveText = context.getString(R.string.minab_remove_inactive),
                isDanger = true
            ) { includeUntested ->
                val targets = if (includeUntested) failed + untested else failed
                performDelete(targets)
            }
        }
    }

    @JvmStatic
    fun showConfirmJava(
        context: Context,
        title: String,
        message: String,
        positiveText: String,
        negativeText: String,
        isDanger: Boolean,
        cancelable: Boolean,
        onConfirm: Runnable,
        onCancel: Runnable?
    ) {
        showConfirm(
            context = context,
            title = title,
            message = message,
            positiveText = positiveText,
            isDanger = isDanger,
            negativeText = negativeText,
            cancelable = cancelable,
            onCancel = { onCancel?.run() },
            onConfirm = { onConfirm.run() }
        )
    }

    @JvmStatic
    fun showCredentialsJava(
        context: Context,
        title: String,
        scopeSummary: String,
        isAllScopeWarning: Boolean,
        showSaveDefaultOption: Boolean,
        initialSaveDefault: Boolean,
        initialUsername: String,
        initialPassword: String,
        positiveText: String,
        negativeText: String,
        onConfirm: CredentialsCallback,
        onCancel: Runnable?
    ) {
        showCredentialsDialog(
            context = context,
            title = title,
            scopeSummary = scopeSummary,
            isAllScopeWarning = isAllScopeWarning,
            showSaveDefaultOption = showSaveDefaultOption,
            initialSaveDefault = initialSaveDefault,
            initialUsername = initialUsername,
            initialPassword = initialPassword,
            positiveText = positiveText,
            negativeText = negativeText,
            onConfirm = { u, p, saveDef -> onConfirm.onResult(u, p, saveDef) },
            onCancel = { onCancel?.run() }
        )
    }

    @JvmStatic
    fun showSecretInputJava(
        context: Context,
        title: String,
        message: String,
        hint: String?,
        positiveText: String,
        onConfirm: TextCallback,
        onCancel: Runnable?
    ) {
        val root = LayoutInflater.from(context).inflate(R.layout.minab_input_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }

        val titleView = root.findViewById<TextView>(R.id.minab_input_dialog_title)
        val messageView = root.findViewById<TextView>(R.id.minab_input_dialog_message)
        val field = root.findViewById<EditText>(R.id.minab_input_dialog_field)
        val cancelBtn = root.findViewById<Button>(R.id.minab_input_dialog_cancel)
        val okBtn = root.findViewById<Button>(R.id.minab_input_dialog_ok)

        titleView.text = title
        if (!TextUtils.isEmpty(message)) {
            messageView.text = message
            messageView.visibility = View.VISIBLE
        }
        field.visibility = View.VISIBLE
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (!hint.isNullOrEmpty()) field.hint = hint
        okBtn.text = positiveText
        field.requestFocus()

        cancelBtn.setOnClickListener {
            dialog.dismiss()
            onCancel?.run()
        }
        dialog.setOnCancelListener {
            onCancel?.run()
        }
        okBtn.setOnClickListener {
            val valText = field.text.toString()
            dialog.dismiss()
            onConfirm.onText(valText)
        }
        dialog.show()
    }
}

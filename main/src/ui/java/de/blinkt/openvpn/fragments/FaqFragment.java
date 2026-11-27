/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.fragments;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.blinkt.openvpn.R;

public class FaqFragment extends Fragment {

    public static final int CAT_ALL = 0;
    public static final int CAT_START = 1;
    public static final int CAT_TESTING = 2;
    public static final int CAT_GROUPS = 3;
    public static final int CAT_TROUBLESHOOT = 4;

    public static final class FAQEntry {
        public final int categoryId;
        public final int titleRes;
        public final int descriptionRes;
        public final String[] tags;

        public FAQEntry(int categoryId, int titleRes, int descriptionRes, String... tags) {
            this.categoryId = categoryId;
            this.titleRes = titleRes;
            this.descriptionRes = descriptionRes;
            this.tags = tags;
        }
    }

    public static int categoryTitleRes(int categoryId) {
        switch (categoryId) {
            case CAT_START:
                return R.string.minab_faq_cat_start;
            case CAT_TESTING:
                return R.string.minab_faq_cat_testing;
            case CAT_GROUPS:
                return R.string.minab_faq_cat_groups;
            case CAT_TROUBLESHOOT:
                return R.string.minab_faq_cat_troubleshoot;
            default:
                return R.string.minab_all_tab;
        }
    }

    private static final List<FAQEntry> FAQ_ITEMS = new ArrayList<>();
    static {
        FAQ_ITEMS.add(new FAQEntry(
                CAT_START,
                R.string.minab_faq_v98_import_title,
                R.string.minab_faq_v98_import_body,
                "#import", "#zip", "#geoip"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_START,
                R.string.minab_faq_v98_creds_title,
                R.string.minab_faq_v98_creds_body,
                "#credentials", "#userpass", "#security"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_GROUPS,
                R.string.minab_faq_v98_multigroup_title,
                R.string.minab_faq_v98_multigroup_body,
                "#groups", "#favorites", "#unique"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_GROUPS,
                R.string.minab_faq_v98_dedup_empty_title,
                R.string.minab_faq_v98_dedup_empty_body,
                "#duplicates", "#cleanup", "#groups"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_TESTING,
                R.string.minab_faq_v98_tests_title,
                R.string.minab_faq_v98_tests_body,
                "#ping", "#realtest", "#test"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_TESTING,
                R.string.minab_faq_v98_dead_servers_title,
                R.string.minab_faq_v98_dead_servers_body,
                "#deadservers", "#cleanup", "#failed"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_TESTING,
                R.string.minab_faq_v98_history_title,
                R.string.minab_faq_v98_history_body,
                "#history", "#network"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_START,
                R.string.minab_faq_v98_smarttools_title,
                R.string.minab_faq_v98_smarttools_body,
                "#smarttools", "#cleanup"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_GROUPS,
                R.string.minab_faq_v98_backup_share_title,
                R.string.minab_faq_v98_backup_share_body,
                "#backup", "#share", "#windows", "#security"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_TESTING,
                R.string.minab_faq_v98_best_failover_title,
                R.string.minab_faq_v98_best_failover_body,
                "#best", "#failover"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_START,
                R.string.minab_faq_v98_ip_widget_perapp_title,
                R.string.minab_faq_v98_ip_widget_perapp_body,
                "#ipcheck", "#perapp", "#widget"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_TROUBLESHOOT,
                R.string.minab_faq_v98_disconnect_notif_title,
                R.string.minab_faq_v98_disconnect_notif_body,
                "#disconnect", "#notification", "#tile"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_TROUBLESHOOT,
                R.string.minab_faq_v98_battery_title,
                R.string.minab_faq_v98_battery_body,
                "#battery", "#background"
        ));
        FAQ_ITEMS.add(new FAQEntry(
                CAT_TROUBLESHOOT,
                R.string.minab_faq_v98_diagnostics_title,
                R.string.minab_faq_v98_diagnostics_body,
                "#logs", "#diagnostics", "#privacy"
        ));
    }

    private List<FaqViewAdapter.BoundFaqItem> allBound = new ArrayList<>();
    private FaqViewAdapter adapter;
    private LinearLayout categoryChips;
    private View activeTagBar;
    private TextView activeTagChip;
    private TextView resultCountView;
    private TextView emptyView;
    private EditText searchEdit;
    private ImageButton searchClear;

    private int selectedCategory = CAT_ALL;
    private String selectedTag = null;
    private String searchQuery = "";

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.faq, container, false);
        final Context ctx = requireContext();

        categoryChips = root.findViewById(R.id.faq_category_chips);
        activeTagBar = root.findViewById(R.id.faq_active_tag_bar);
        activeTagChip = root.findViewById(R.id.faq_active_tag_chip);
        resultCountView = root.findViewById(R.id.faq_result_count);
        emptyView = root.findViewById(R.id.faq_empty);
        searchEdit = root.findViewById(R.id.faq_search);
        searchClear = root.findViewById(R.id.faq_search_clear);
        RecyclerView recyclerView = root.findViewById(R.id.faq_recycler_view);

        recyclerView.setLayoutManager(new LinearLayoutManager(ctx));
        Utils.applyInsetListener(recyclerView);

        allBound = FaqViewAdapter.bindAll(ctx, FAQ_ITEMS);
        adapter = new FaqViewAdapter(ctx, null, tag -> {
            selectedTag = tag.equals(selectedTag) ? null : tag;
            applyFilter();
        });
        recyclerView.setAdapter(adapter);

        activeTagChip.setOnClickListener(v -> {
            selectedTag = null;
            applyFilter();
        });

        searchClear.setOnClickListener(v -> searchEdit.setText(""));
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                searchQuery = s == null ? "" : s.toString().trim();
                searchClear.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                applyFilter();
            }
        });

        renderCategoryChips(ctx);
        applyFilter();
        return root;
    }

    private void renderCategoryChips(Context ctx) {
        if (categoryChips == null) return;
        categoryChips.removeAllViews();
        int[] cats = {CAT_ALL, CAT_START, CAT_TESTING, CAT_GROUPS, CAT_TROUBLESHOOT};
        float density = ctx.getResources().getDisplayMetrics().density;
        int padH = (int) (11f * density);
        int padV = (int) (5f * density);
        int marginEnd = (int) (6f * density);
        Typeface vazir = ResourcesCompat.getFont(ctx, R.font.vazirmatn);
        int tealDark = ContextCompat.getColor(ctx, R.color.minab_teal_dark);
        int chipText = ContextCompat.getColor(ctx, R.color.minab_chip_text);

        for (final int catId : cats) {
            boolean active = (selectedCategory == catId);
            TextView pill = new TextView(ctx);
            pill.setText(getString(categoryTitleRes(catId)));
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            pill.setBackgroundResource(active ? R.drawable.minab_chip_bg_selected : R.drawable.minab_chip_bg);
            pill.setTextColor(active ? tealDark : chipText);
            if (vazir != null) pill.setTypeface(vazir, active ? Typeface.BOLD : Typeface.NORMAL);
            pill.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(marginEnd);
            pill.setLayoutParams(lp);
            pill.setOnClickListener(v -> {
                selectedCategory = catId;
                renderCategoryChips(ctx);
                applyFilter();
            });
            categoryChips.addView(pill);
        }
    }

    private void applyFilter() {
        if (adapter == null) return;
        if (selectedTag != null) {
            activeTagBar.setVisibility(View.VISIBLE);
            activeTagChip.setText(selectedTag + "  ×");
        } else {
            activeTagBar.setVisibility(View.GONE);
        }

        String q = searchQuery.toLowerCase(Locale.getDefault());
        List<FaqViewAdapter.BoundFaqItem> filtered = new ArrayList<>();
        for (FaqViewAdapter.BoundFaqItem item : allBound) {
            if (selectedCategory != CAT_ALL && item.categoryId != selectedCategory) {
                continue;
            }
            if (selectedTag != null) {
                boolean hasTag = false;
                for (String t : item.tags) {
                    if (selectedTag.equalsIgnoreCase(t)) {
                        hasTag = true;
                        break;
                    }
                }
                if (!hasTag) continue;
            }
            if (!q.isEmpty()) {
                boolean match = item.titleLower.contains(q)
                        || item.bodyPlainLower.contains(q)
                        || item.categoryLower.contains(q);
                if (!match) {
                    for (String t : item.tags) {
                        if (t.toLowerCase(Locale.getDefault()).contains(q)) {
                            match = true;
                            break;
                        }
                    }
                }
                if (!match) continue;
            }
            filtered.add(item);
        }

        adapter.setItems(filtered);
        if (filtered.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
        resultCountView.setText(getString(R.string.minab_faq_count_fmt, filtered.size(), allBound.size()));
    }
}

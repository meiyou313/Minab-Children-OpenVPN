/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.fragments;

import android.content.Context;
import android.graphics.Typeface;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.text.HtmlCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.blinkt.openvpn.R;

public class FaqViewAdapter extends RecyclerView.Adapter<FaqViewAdapter.FaqViewHolder> {

    public interface OnTagClickListener {
        void onTagClick(String tag);
    }

    public static final class BoundFaqItem {
        public final int categoryId;
        public final String categoryLabel;
        public final int titleRes;
        public final CharSequence title;
        public final CharSequence bodyHtml;
        public final String bodyPlainLower;
        public final String titleLower;
        public final String categoryLower;
        public final String[] tags;

        public BoundFaqItem(
                int categoryId,
                String categoryLabel,
                int titleRes,
                CharSequence title,
                CharSequence bodyHtml,
                String bodyPlainLower,
                String[] tags) {
            this.categoryId = categoryId;
            this.categoryLabel = categoryLabel;
            this.titleRes = titleRes;
            this.title = title;
            this.bodyHtml = bodyHtml;
            this.bodyPlainLower = bodyPlainLower;
            this.titleLower = title.toString().toLowerCase();
            this.categoryLower = categoryLabel.toLowerCase();
            this.tags = tags;
        }
    }

    private final Context mContext;
    private final OnTagClickListener mTagClickListener;
    private final List<BoundFaqItem> mDisplayed = new ArrayList<>();
    private final Set<Integer> mExpandedIds = new HashSet<>();
    private final Typeface mVazir;

    public FaqViewAdapter(
            Context context,
            List<FaqFragment.FAQEntry> entries,
            OnTagClickListener tagClickListener) {
        mContext = context;
        mTagClickListener = tagClickListener;
        mVazir = ResourcesCompat.getFont(context, R.font.vazirmatn);
        if (entries != null) {
            setItems(bindAll(context, entries));
        }
    }

    public static List<BoundFaqItem> bindAll(Context ctx, List<FaqFragment.FAQEntry> entries) {
        List<BoundFaqItem> list = new ArrayList<>(entries.size());
        for (FaqFragment.FAQEntry e : entries) {
            String titleStr = ctx.getString(e.titleRes);
            String rawHtml = ctx.getString(e.descriptionRes);
            CharSequence spanned = HtmlCompat.fromHtml(rawHtml, HtmlCompat.FROM_HTML_MODE_LEGACY);
            String catLabel = ctx.getString(FaqFragment.categoryTitleRes(e.categoryId));
            list.add(new BoundFaqItem(
                    e.categoryId,
                    catLabel,
                    e.titleRes,
                    titleStr,
                    spanned,
                    spanned.toString().toLowerCase(),
                    e.tags));
        }
        return list;
    }

    public void setItems(List<BoundFaqItem> items) {
        mDisplayed.clear();
        if (items != null) {
            mDisplayed.addAll(items);
        }
        notifyDataSetChanged();
    }

    public static class FaqViewHolder extends RecyclerView.ViewHolder {
        final View headerRow;
        final TextView categoryPill;
        final TextView head;
        final ImageView chevron;
        final View answerGroup;
        final TextView body;
        final LinearLayout tagsRow;

        public FaqViewHolder(@NonNull View itemView) {
            super(itemView);
            headerRow = itemView.findViewById(R.id.faq_header_row);
            categoryPill = itemView.findViewById(R.id.faq_category_pill);
            head = itemView.findViewById(R.id.faq_head);
            chevron = itemView.findViewById(R.id.faq_chevron);
            answerGroup = itemView.findViewById(R.id.faq_answer_group);
            body = itemView.findViewById(R.id.faq_body);
            tagsRow = itemView.findViewById(R.id.faq_tags_row);
            body.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    @NonNull
    @Override
    public FaqViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.faqcard, parent, false);
        return new FaqViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull FaqViewHolder holder, int position) {
        final BoundFaqItem item = mDisplayed.get(position);
        holder.categoryPill.setText(item.categoryLabel);
        holder.head.setText(item.title);
        holder.body.setText(item.bodyHtml);

        boolean expanded = mExpandedIds.contains(item.titleRes);
        holder.answerGroup.setVisibility(expanded ? View.VISIBLE : View.GONE);
        holder.chevron.setRotation(expanded ? 180f : 0f);

        holder.headerRow.setOnClickListener(v -> {
            if (mExpandedIds.contains(item.titleRes)) {
                mExpandedIds.remove(item.titleRes);
            } else {
                mExpandedIds.add(item.titleRes);
            }
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos);
            }
        });

        holder.tagsRow.removeAllViews();
        float density = mContext.getResources().getDisplayMetrics().density;
        int padH = (int) (9f * density);
        int padV = (int) (3f * density);
        int marginEnd = (int) (6f * density);
        int chipTextColor = ContextCompat.getColor(mContext, R.color.minab_teal_dark);

        for (final String tag : item.tags) {
            TextView chip = new TextView(mContext);
            chip.setText(tag);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            chip.setTextColor(chipTextColor);
            if (mVazir != null) chip.setTypeface(mVazir);
            chip.setBackgroundResource(R.drawable.minab_chip_bg);
            chip.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(marginEnd);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (mTagClickListener != null) {
                    mTagClickListener.onTagClick(tag);
                }
            });
            holder.tagsRow.addView(chip);
        }
    }

    @Override
    public int getItemCount() {
        return mDisplayed.size();
    }
}

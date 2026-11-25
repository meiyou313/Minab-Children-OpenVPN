/*
 * Copyright (c) 2012-2017 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.fragments;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.core.OpenVPNManagement;
import de.blinkt.openvpn.core.TrafficHistory;
import de.blinkt.openvpn.core.VpnStatus;
import de.blinkt.openvpn.minab.DataUsageStore;

import static android.content.Context.MODE_PRIVATE;
import static de.blinkt.openvpn.core.OpenVPNService.humanReadableByteCount;
import static java.lang.Math.max;

/**
 * Created by arne on 19.05.17.
 */

public class GraphFragment extends Fragment implements VpnStatus.ByteCountListener {
    private static final String PREF_USE_LOG = "useLogGraph";
    private ListView mListView;

    private ChartDataAdapter mChartAdapter;
    private int mColourIn;
    private int mColourOut;
    private int mColourPoint;
    private int mTextColour;

    private long firstTs;
    private TextView mSpeedStatus;
    private boolean mLogScale;

    // --- Minab v0.7: "Data used" bar charts (daily / weekly /
    // monthly) above the live charts. The bars are plain Views
    // built in renderDataUsed(); the numbers come from
    // DataUsageStore, which records the real byte-count deltas. ---
    private static final String PREF_DATA_MODE = "minabDataMode";
    private static final int DATA_DAILY = 0;
    private static final int DATA_WEEKLY = 1;
    private static final int DATA_MONTHLY = 2;
    private int mDataMode = DATA_DAILY;
    private LinearLayout mDataBars;
    private HorizontalScrollView mDataBarsScroll;
    /**
     * Minab Beta v0.9.1 — the Daily strip holds 50 bars, wider than
     * the screen; when the mode changes (or the tab first opens) it
     * scrolls to the recent end (today + the 3 empty future bars).
     * Live byte-count updates rebuild the bars but never move the
     * scroll position, so looking back through history is not
     * interrupted every 2 seconds.
     */
    private boolean mScrollDataToEnd = true;
    private TextView mDataTotal;
    private TextView mDataEmpty;
    // Minab Beta v0.9.6.1 (fixes #5/#7) — the "jump to the
    // current bar" icon, and the line showing the tapped bar's
    // date / range.
    private ImageButton mDataJump;
    private TextView mDataSelected;
    // Minab Beta v0.9.6.5 (fix #5) — the one-line footer row
    // (Total + tapped period + jump icon); hidden with the Total
    // when there is no data yet.
    private View mDataFooter;
    private Button mDataDaily;
    private Button mDataWeekly;
    private Button mDataMonthly;
    private String mLastDataSignature = null;
    private static final ExecutorService DATA_EXECUTOR = Executors.newSingleThreadExecutor();
    private volatile int mDataLoadGen = 0;

    private static final int TIME_PERIOD_SECDONS = 0;
    private static final int TIME_PERIOD_MINUTES = 1;
    private static final int TIME_PERIOD_HOURS = 2;
    // Minab Beta v0.9.5 — AUDIT: this was assigned inside
    // onCreateView's try block, after lines that can throw — if
    // the try failed first, onResume / onPause / updateByteCount
    // crashed with an NPE on every switch to Graph. A Handler
    // needs no view, so it is created with the field instead.
    private Handler mHandler = new Handler();


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.graph, container, false);
        // Minab Beta v0.9.4 — the Graph tab must never kill the
        // launch: a failure while wiring the charts / data card is
        // caught and logged, and the tab shows an empty view.
        try {
        mListView = v.findViewById(R.id.graph_listview);
        mSpeedStatus = v.findViewById(R.id.speedStatus);
        CheckBox logScaleView = v.findViewById(R.id.useLogScale);
        mLogScale = getActivity().getPreferences(MODE_PRIVATE).getBoolean(PREF_USE_LOG, false);
        logScaleView.setChecked(mLogScale);

        List<Integer> charts = new LinkedList<>();
        charts.add(TIME_PERIOD_SECDONS);
        charts.add(TIME_PERIOD_MINUTES);
        charts.add(TIME_PERIOD_HOURS);

        // Minab v0.7: the "Data used" card is a ListView header
        // (added before the adapter, as ListView requires) — the
        // live line charts below it are unchanged.
        View dataHeader = inflater.inflate(R.layout.graph_data_used, mListView, false);
        mDataBars = dataHeader.findViewById(R.id.minab_data_bars);
        mDataBarsScroll = dataHeader.findViewById(R.id.minab_data_bars_scroll);
        mDataTotal = dataHeader.findViewById(R.id.minab_data_total);
        mDataFooter = dataHeader.findViewById(R.id.minab_data_footer);
        mDataEmpty = dataHeader.findViewById(R.id.minab_data_empty);
        // Minab Beta v0.9.6.1 (fix #5) — jump to the bar for the
        // period we are in; (fix #7) the tapped bar's date / range.
        mDataJump = dataHeader.findViewById(R.id.minab_data_jump_current);
        mDataSelected = dataHeader.findViewById(R.id.minab_data_selected);
        if (mDataJump != null) {
            mDataJump.setOnClickListener(v1 -> jumpToCurrentBar());
        }
        mDataDaily = dataHeader.findViewById(R.id.minab_data_daily);
        mDataWeekly = dataHeader.findViewById(R.id.minab_data_weekly);
        mDataMonthly = dataHeader.findViewById(R.id.minab_data_monthly);
        // Minab Beta v0.9.1 — Daily is always the default when the
        // tab opens (fix #3). The last-chosen mode used to persist,
        // so a past Weekly choice stuck and the user never saw
        // today's bar first.
        mDataMode = DATA_DAILY;
        mScrollDataToEnd = true;
        mDataDaily.setOnClickListener(v1 -> setDataMode(DATA_DAILY));
        mDataWeekly.setOnClickListener(v1 -> setDataMode(DATA_WEEKLY));
        mDataMonthly.setOnClickListener(v1 -> setDataMode(DATA_MONTHLY));
        mListView.addHeaderView(dataHeader);

        mChartAdapter = new ChartDataAdapter(getActivity(), charts);
        mListView.setAdapter(mChartAdapter);

        mColourIn = getActivity().getResources().getColor(R.color.dataIn);
        mColourOut = getActivity().getResources().getColor(R.color.dataOut);
        mColourPoint = getActivity().getResources().getColor(android.R.color.black);

        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        switch (currentNightMode) {
            case Configuration.UI_MODE_NIGHT_NO:
                mTextColour = getActivity().getResources().getColor(android.R.color.primary_text_light);
                break;
            case Configuration.UI_MODE_NIGHT_YES:
                mTextColour = getActivity().getResources().getColor(android.R.color.primary_text_dark);
                break;
        }

        logScaleView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mLogScale = isChecked;
            mChartAdapter.notifyDataSetChanged();
            getActivity().getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_USE_LOG, isChecked).apply();
        });

        // Minab Beta v0.9.5 — mHandler is created with its field
        // now (see above): re-assigning it here would orphan the
        // callbacks already posted to the old one.
        renderDataUsed();
        } catch (Exception e) {
            android.util.Log.e("GraphFragment", "Minab: onCreateView failed", e);
        }

        return v;


    }

    /** Minab v0.7: switch the "Data used" view and remember it. */
    private void setDataMode(int mode) {
        mDataMode = mode;
        getActivity().getPreferences(MODE_PRIVATE).edit().putInt(PREF_DATA_MODE, mode).apply();
        mScrollDataToEnd = true;
        renderDataUsed();
    }

    /** Minab v0.7: (re)build the "Data used" bar chart from the
        real measured usage. Loads MODE_MULTI_PROCESS SharedPreferences off
        the UI thread and caches a signature so switching to the Graph tab
        never stalls on disk I/O or rebuilds 200 identical views. */
    private void renderDataUsed() {
        if (!isAdded() || mDataBars == null) return;
        final Context ctx = getActivity();
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        final int mode = mDataMode;
        final int gen = ++mDataLoadGen;
        updateDataChips();
        DATA_EXECUTOR.execute(() -> {
            final List<DataUsageStore.Bucket> buckets;
            try {
                switch (mode) {
                    case DATA_WEEKLY:
                        buckets = DataUsageStore.getWeekly(appCtx);
                        break;
                    case DATA_MONTHLY:
                        buckets = DataUsageStore.getMonthly(appCtx);
                        break;
                    default:
                        buckets = DataUsageStore.getDaily(appCtx);
                        break;
                }
            } catch (Exception e) {
                return;
            }
            mHandler.post(() -> {
                if (!isAdded() || mDataBars == null || gen != mDataLoadGen) return;
                applyDataUsedBuckets(mode, buckets);
            });
        });
    }

    private void applyDataUsedBuckets(int mode, List<DataUsageStore.Bucket> buckets) {
        if (!isAdded() || mDataBars == null) return;
        Context ctx = getActivity();
        if (ctx == null) return;
        boolean daily = (mode == DATA_DAILY);
        float density = getResources().getDisplayMetrics().density;
        int colWidthPx;
        if (daily) {
            colWidthPx = (int) (40 * density);
        } else {
            int viewportW = mDataBarsScroll != null ? mDataBarsScroll.getWidth() : 0;
            if (viewportW <= 0) {
                if (mDataBarsScroll != null) {
                    mDataBarsScroll.post(() -> applyDataUsedBuckets(mode, buckets));
                }
                return;
            }
            colWidthPx = viewportW / 6;
        }
        long max = 0;
        long total = 0;
        StringBuilder sig = new StringBuilder();
        sig.append(mode).append('|').append(colWidthPx).append('|').append(buckets.size());
        for (DataUsageStore.Bucket b : buckets) {
            max = Math.max(max, b.bytes);
            total += b.bytes;
            sig.append('|').append(b.label).append(':').append(b.bytes).append(':').append(b.current ? 1 : 0);
        }
        String signature = sig.toString();
        if (signature.equals(mLastDataSignature) && mDataBars.getChildCount() == buckets.size()) {
            if (mScrollDataToEnd && mDataBarsScroll != null) {
                mScrollDataToEnd = false;
                mDataBarsScroll.post(() -> mDataBarsScroll.fullScroll(View.FOCUS_RIGHT));
            }
            return;
        }
        mLastDataSignature = signature;
        mDataBars.removeAllViews();
        int maxBarPx = (int) (88 * density);
        int minBarPx = (int) (2 * density);
        int barWidthPx = (int) (20 * density);
        // Minab Beta v0.9.6 — these params MUST be
        // FrameLayout.LayoutParams (a MarginLayoutParams): mDataBars
        // is the direct child of the HorizontalScrollView in
        // graph_data_used.xml.
        mDataBars.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                (int) (130 * density)));
        int dividerColor = getResources().getColor(R.color.minab_divider);
        int currentLabelColor = getResources().getColor(R.color.minab_connected);
        int secondaryLabelColor = getResources().getColor(R.color.minab_text_secondary);
        for (DataUsageStore.Bucket b : buckets) {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    colWidthPx, LinearLayout.LayoutParams.MATCH_PARENT));
            final DataUsageStore.Bucket bucket = b;
            col.setClickable(true);
            col.setOnClickListener(v2 -> showSelectedBar(bucket));

            TextView value = new TextView(ctx);
            value.setText(b.bytes > 0 ? DataUsageStore.formatBytes(b.bytes) : "");
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            value.setTypeface(null, Typeface.BOLD);
            value.setGravity(Gravity.CENTER);
            value.setMaxLines(1);
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setTextColor(mTextColour);
            col.addView(value, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            View bar = new View(ctx);
            int height = b.bytes <= 0 ? minBarPx
                    : (int) (minBarPx + (b.bytes / (double) max) * maxBarPx);
            if (b.current) {
                bar.setBackgroundResource(R.drawable.minab_data_bar_current);
            } else if (b.bytes > 0) {
                bar.setBackgroundResource(R.drawable.minab_data_bar);
            } else {
                bar.setBackgroundColor(dividerColor);
            }
            LinearLayout.LayoutParams barParams =
                    new LinearLayout.LayoutParams(barWidthPx, height);
            barParams.gravity = Gravity.CENTER_HORIZONTAL;
            barParams.topMargin = minBarPx;
            col.addView(bar, barParams);

            TextView label = new TextView(ctx);
            label.setText(b.label);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setTextColor(b.current ? currentLabelColor : secondaryLabelColor);
            col.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            mDataBars.addView(col);
        }
        mDataEmpty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        mDataTotal.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
        if (mDataFooter != null) {
            mDataFooter.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
        }
        if (total > 0) {
            mDataTotal.setText(getString(
                    R.string.minab_data_total, DataUsageStore.formatBytes(total)));
        }
        updateDataChips();
        if (mScrollDataToEnd && mDataBarsScroll != null) {
            mScrollDataToEnd = false;
            mDataBarsScroll.post(() ->
                    mDataBarsScroll.fullScroll(View.FOCUS_RIGHT));
        }
    }

    /**
     * Minab Beta v0.9.6.1 (fix #5) — the "jump to the current bar"
     * icon: scroll the strip to the recent end, where the bar for
     * the period we are in lives (today is 4th from the end of the
     * Daily strip; Weekly/Monthly end on the current week/month).
     */
    private void jumpToCurrentBar() {
        if (mDataBarsScroll == null || mDataBars == null) return;
        mDataBarsScroll.post(() ->
                mDataBarsScroll.smoothScrollTo(mDataBars.getWidth(), 0));
    }

    /**
     * Minab Beta v0.9.6.1 (fix #7) — tapping a bar shows its date
     * or range under the card ("Sat 14 Nov 2026 · 1.54 GB",
     * "9 Nov – 15 Nov 2026 · 840 B", "November 2026 · 0 B").
     */
    private void showSelectedBar(DataUsageStore.Bucket b) {
        if (mDataSelected == null) return;
        mDataSelected.setText(b.detail + " · " + DataUsageStore.formatBytes(b.bytes));
        mDataSelected.setVisibility(View.VISIBLE);
    }

    /** Minab v0.7: highlight the active Daily / Weekly / Monthly chip. */
    private void updateDataChips() {
        Button[] chips = {mDataDaily, mDataWeekly, mDataMonthly};
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            boolean active = (i == mDataMode);
            chips[i].setBackgroundResource(active
                    ? R.drawable.minab_btn_primary
                    : R.drawable.minab_btn_outline);
            chips[i].setTextColor(getResources().getColor(active
                    ? android.R.color.white
                    : R.color.minab_teal));
        }
    }

    private Runnable triggerRefresh = new Runnable() {
        @Override
        public void run() {
            if (mChartAdapter != null) mChartAdapter.notifyDataSetChanged();
            mHandler.postDelayed(triggerRefresh, OpenVPNManagement.mBytecountInterval * 1500);
        }
    };

    @Override
    public void onResume() {
        super.onResume();

        VpnStatus.addByteCountListener(this);
        mHandler.postDelayed(triggerRefresh, OpenVPNManagement.mBytecountInterval * 1500);
        renderDataUsed();
    }

    @Override
    public void onPause() {
        super.onPause();

        mHandler.removeCallbacks(triggerRefresh);
        VpnStatus.removeByteCountListener(this);
    }

    @Override
    public void onDestroyView() {
        mLastDataSignature = null;
        mHandler.removeCallbacks(triggerRefresh);
        super.onDestroyView();
    }

    @Override
    public void updateByteCount(long in, long out, long diffIn, long diffOut) {
        if (firstTs == 0)
            firstTs = System.currentTimeMillis() / 100;

        long now = (System.currentTimeMillis() / 100) - firstTs;
        int interval = OpenVPNManagement.mBytecountInterval * 10;
        // Minab Beta v0.9.3 — a byte-count tick can arrive after
        // the fragment detached (the listener is removed in
        // onPause, but a tick already in flight keeps going):
        // getActivity() is null then and the NPE killed the app
        // on a tab switch.
        if (getActivity() == null) return;
        Resources res = getActivity().getResources();

        final String netstat = String.format(getString(R.string.statusline_bytecount),
                humanReadableByteCount(in, false, res),
                humanReadableByteCount(diffIn / OpenVPNManagement.mBytecountInterval, true, res),
                humanReadableByteCount(out, false, res),
                humanReadableByteCount(diffOut / OpenVPNManagement.mBytecountInterval, true, res));

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Minab Beta v0.9.5 — AUDIT: this runnable can land
                // after onCreateView failed early (or after the
                // view was destroyed): the views and adapter it
                // touches may be null — a stale tick must not kill
                // the app on a tab switch.
                if (mSpeedStatus != null) mSpeedStatus.setText(netstat);
                if (mChartAdapter != null) mChartAdapter.notifyDataSetChanged();
                // Minab v0.7: the "Data used" bars grow live while
                // connected (cheap rebuild of plain Views).
                renderDataUsed();
                mHandler.removeCallbacks(triggerRefresh);
                mHandler.postDelayed(triggerRefresh, OpenVPNManagement.mBytecountInterval * 1500);
            }
        });

    }

    private class ChartDataAdapter extends ArrayAdapter<Integer> {

        private Context mContext;

        public ChartDataAdapter(Context context, List<Integer> trafficData) {
            super(context, 0, trafficData);
            mContext = context;
        }

        @NonNull
        @Override
        public View getView(final int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            ViewHolder holder = null;

            if (convertView == null) {

                holder = new ViewHolder();

                convertView = LayoutInflater.from(mContext).inflate(
                        R.layout.graph_item, parent, false);
                holder.chart = (LineChart) convertView.findViewById(R.id.chart);
                holder.title = (TextView) convertView.findViewById(R.id.tvName);
                convertView.setTag(holder);

            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            // apply styling
            // holder.chart.setValueTypeface(mTf);
            holder.chart.getDescription().setEnabled(false);
            holder.chart.setDrawGridBackground(false);
            holder.chart.getLegend().setTextColor(mTextColour);

            XAxis xAxis = holder.chart.getXAxis();
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setDrawGridLines(false);
            xAxis.setDrawAxisLine(true);
            xAxis.setTextColor(mTextColour);

            switch (position) {
                case TIME_PERIOD_HOURS:
                    holder.title.setText(R.string.avghour);
                    break;
                case TIME_PERIOD_MINUTES:
                    holder.title.setText(R.string.avgmin);
                    break;
                default:
                    holder.title.setText(R.string.last5minutes);
                    break;
            }

            xAxis.setValueFormatter(new ValueFormatter() {


                @Override
                public String getFormattedValue(float value) {
                    switch (position) {
                        case TIME_PERIOD_HOURS:
                            return String.format(Locale.getDefault(), "%.0f\u2009h ago", (xAxis.getAxisMaximum() - value) / 10 / 3600);
                        case TIME_PERIOD_MINUTES:
                            return String.format(Locale.getDefault(), "%.0f\u2009m ago", (xAxis.getAxisMaximum() - value) / 10 / 60);
                        default:
                            return String.format(Locale.getDefault(), "%.0f\u2009s ago", (xAxis.getAxisMaximum() - value) / 10);
                    }

                }
            });
            xAxis.setLabelCount(5);

            YAxis yAxis = holder.chart.getAxisLeft();
            yAxis.setLabelCount(5, false);

            final Resources res = mContext.getResources();
            yAxis.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    if (mLogScale && value < 2.1f)
                        return "< 100\u2009bit/s";
                    if (mLogScale)
                        value = (float) Math.pow(10, value) / 8;

                    return humanReadableByteCount((long) value, true, res);
                }
            });
            yAxis.setTextColor(mTextColour);

            holder.chart.getAxisRight().setEnabled(false);

            LineData data = getDataSet(position);
            float ymax = data.getYMax();

            if (mLogScale) {
                yAxis.setAxisMinimum(2f);
                yAxis.setAxisMaximum((float) Math.ceil(ymax));
                yAxis.setLabelCount((int) (Math.ceil(ymax - 2f)));
            } else {
                yAxis.setAxisMinimum(0f);
                yAxis.resetAxisMaximum();
                yAxis.setLabelCount(6);
            }

            if (data.getDataSetByIndex(0).getEntryCount() < 3)
                holder.chart.setData(null);
            else
                holder.chart.setData(data);

            holder.chart.setNoDataText(getString(R.string.notenoughdata));

            holder.chart.invalidate();
            //holder.chart.animateX(750);

            return convertView;
        }

        private LineData getDataSet(int timeperiod) {

            LinkedList<Entry> dataIn = new LinkedList<>();
            LinkedList<Entry> dataOut = new LinkedList<>();

            long interval;
            long totalInterval;

            LinkedList<TrafficHistory.TrafficDatapoint> list;
            switch (timeperiod) {
                case TIME_PERIOD_HOURS:
                    list = VpnStatus.trafficHistory.getHours();
                    interval = TrafficHistory.TIME_PERIOD_HOUR;
                    totalInterval = 0;
                    break;
                case TIME_PERIOD_MINUTES:
                    list = VpnStatus.trafficHistory.getMinutes();
                    interval = TrafficHistory.TIME_PERIOD_MINUTE;
                    totalInterval = TrafficHistory.TIME_PERIOD_HOUR * TrafficHistory.PERIODS_TO_KEEP;
                    break;
                default:
                    list = VpnStatus.trafficHistory.getSeconds();
                    interval = OpenVPNManagement.mBytecountInterval * 1000;
                    totalInterval = TrafficHistory.TIME_PERIOD_MINUTE * TrafficHistory.PERIODS_TO_KEEP;
                    break;
            }
            if (list.isEmpty()) {
                list = TrafficHistory.getDummyList();
            }


            long lastts = 0;
            float zeroValue;
            if (mLogScale)
                zeroValue = 2;
            else
                zeroValue = 0;

            long now = System.currentTimeMillis();


            long firstTimestamp = 0;
            long lastBytecountOut = 0;
            long lastBytecountIn = 0;

            for (TrafficHistory.TrafficDatapoint tdp : list) {
                if (totalInterval != 0 && (now - tdp.timestamp) > totalInterval)
                    continue;

                if (firstTimestamp == 0) {
                    firstTimestamp = list.peek().timestamp;
                    lastBytecountIn = list.peek().in;
                    lastBytecountOut = list.peek().out;
                }

                float t = (tdp.timestamp - firstTimestamp) / 100f;

                float in = (tdp.in - lastBytecountIn) / (float) (interval / 1000);
                float out = (tdp.out - lastBytecountOut) / (float) (interval / 1000);

                lastBytecountIn = tdp.in;
                lastBytecountOut = tdp.out;

                if (mLogScale) {
                    in = max(2f, (float) Math.log10(in * 8));
                    out = max(2f, (float) Math.log10(out * 8));
                }

                if (lastts > 0 && (tdp.timestamp - lastts > 2 * interval)) {
                    dataIn.add(new Entry((lastts - firstTimestamp + interval) / 100f, zeroValue));
                    dataOut.add(new Entry((lastts - firstTimestamp + interval) / 100f, zeroValue));

                    dataIn.add(new Entry(t - interval / 100f, zeroValue));
                    dataOut.add(new Entry(t - interval / 100f, zeroValue));
                }

                lastts = tdp.timestamp;

                dataIn.add(new Entry(t, in));
                dataOut.add(new Entry(t, out));

            }
            if (lastts < now - interval) {

                if (now - lastts > 2 * interval * 1000) {
                    dataIn.add(new Entry((lastts - firstTimestamp + interval * 1000) / 100f, zeroValue));
                    dataOut.add(new Entry((lastts - firstTimestamp + interval * 1000) / 100f, zeroValue));
                }

                dataIn.add(new Entry((now - firstTimestamp) / 100, zeroValue));
                dataOut.add(new Entry((now - firstTimestamp) / 100, zeroValue));
            }

            ArrayList<ILineDataSet> dataSets = new ArrayList<>();


            LineDataSet indata = new LineDataSet(dataIn, getString(R.string.data_in));
            LineDataSet outdata = new LineDataSet(dataOut, getString(R.string.data_out));

            setLineDataAttributes(indata, mColourIn);
            setLineDataAttributes(outdata, mColourOut);

            dataSets.add(indata);
            dataSets.add(outdata);

            return new LineData(dataSets);
        }

        private void setLineDataAttributes(LineDataSet dataSet, int colour) {
            dataSet.setLineWidth(2);
            dataSet.setCircleRadius(1);
            dataSet.setDrawCircles(true);
            dataSet.setCircleColor(mColourPoint);
            dataSet.setDrawFilled(true);
            dataSet.setFillAlpha(42);
            dataSet.setFillColor(colour);
            dataSet.setColor(colour);
            dataSet.setMode(LineDataSet.Mode.LINEAR);

            dataSet.setDrawValues(false);
            dataSet.setValueTextColor(mTextColour);

        }
    }

    private static class ViewHolder {
        LineChart chart;
        TextView title;
    }
}

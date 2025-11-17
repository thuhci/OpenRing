package com.tsinghua.openring.activity;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tsinghua.openring.PlotView;
import com.tsinghua.openring.R;
import com.tsinghua.openring.utils.VitalSignsHistoryManager;
import com.tsinghua.openring.utils.VitalSignsRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HistoryViewActivity extends AppCompatActivity {

    private RecyclerView historyRecyclerView;
    private HistoryAdapter adapter;
    private VitalSignsHistoryManager historyManager;

    // Week overview charts
    private PlotView weekHrChart;
    private PlotView weekBpChart;
    private PlotView weekSpo2Chart;
    private PlotView weekRrChart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_view);

        historyManager = new VitalSignsHistoryManager(this);

        // Initialize week overview charts
        weekHrChart = findViewById(R.id.weekHrChart);
        weekBpChart = findViewById(R.id.weekBpChart);
        weekSpo2Chart = findViewById(R.id.weekSpo2Chart);
        weekRrChart = findViewById(R.id.weekRrChart);

        // Set chart colors
        weekHrChart.setPlotColor(Color.parseColor("#FF4444"));
        weekBpChart.setPlotColor(Color.parseColor("#E53935"));
        weekBpChart.setPlotColor2(Color.parseColor("#FB8C00"));
        weekSpo2Chart.setPlotColor(Color.parseColor("#1E88E5"));
        weekRrChart.setPlotColor(Color.parseColor("#9C27B0"));

        // Enable data labels for week overview charts
        weekHrChart.setShowDataLabels(true);
        weekBpChart.setShowDataLabels(true);
        weekSpo2Chart.setShowDataLabels(true);
        weekRrChart.setShowDataLabels(true);

        historyRecyclerView = findViewById(R.id.historyRecyclerView);
        historyRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        Button backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());

        loadHistory();
    }

    private void loadHistory() {
        // Get past 7 days data
        Map<String, List<VitalSignsRecord>> weekData = historyManager.getRecordsForPastWeek();

        // Prepare list of day items (sorted by date descending)
        List<DayItem> dayItems = new ArrayList<>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar calendar = Calendar.getInstance();

        // Lists for week overview (reversed to show oldest to newest)
        List<Integer> weekHrData = new ArrayList<>();
        List<Integer> weekBpSysData = new ArrayList<>();
        List<Integer> weekBpDiaData = new ArrayList<>();
        List<Integer> weekSpo2Data = new ArrayList<>();
        List<Integer> weekRrData = new ArrayList<>();

        for (int i = 0; i < 7; i++) {
            String date = dateFormat.format(calendar.getTime());
            List<VitalSignsRecord> records = weekData.get(date);

            DayItem item = new DayItem();
            item.date = date;
            item.records = records != null ? records : new ArrayList<>();

            dayItems.add(item);

            // Calculate average for week overview (insert at beginning for correct order)
            // Only add data points for days that have actual records
            if (records != null && !records.isEmpty()) {
                VitalSignsRecord avg = VitalSignsHistoryManager.calculateAverage(records);
                if (avg != null) {
                    // 过滤掉0值，避免在图表中显示无效数据
                    if (avg.getHr() > 0) weekHrData.add(0, avg.getHr());
                    if (avg.getBp_sys() > 0) weekBpSysData.add(0, avg.getBp_sys());
                    if (avg.getBp_dia() > 0) weekBpDiaData.add(0, avg.getBp_dia());
                    if (avg.getSpo2() > 0) weekSpo2Data.add(0, avg.getSpo2());
                    if (avg.getRr() > 0) weekRrData.add(0, avg.getRr());
                }
            }
            // Skip days with no data - don't add placeholder points

            calendar.add(Calendar.DAY_OF_YEAR, -1);
        }

        // Plot week overview charts (will be empty if no data)
        weekHrChart.setData(weekHrData);
        weekBpChart.setDualData(weekBpSysData, weekBpDiaData);
        weekSpo2Chart.setData(weekSpo2Data);
        weekRrChart.setData(weekRrData);

        adapter = new HistoryAdapter(dayItems);
        historyRecyclerView.setAdapter(adapter);
    }

    private static class DayItem {
        String date;
        List<VitalSignsRecord> records;
        boolean expanded = false;
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {
        private final List<DayItem> dayItems;

        public HistoryAdapter(List<DayItem> dayItems) {
            this.dayItems = dayItems;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history_day, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            DayItem item = dayItems.get(position);
            holder.bind(item);
        }

        @Override
        public int getItemCount() {
            return dayItems.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView dateText;
            TextView statusText;
            TextView averageText;
            TextView expandIndicator;
            View detailsContainer;
            View dayCard;

            // Day detail charts
            PlotView dayHrChart;
            PlotView dayBpChart;
            PlotView daySpo2Chart;
            PlotView dayRrChart;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                dateText = itemView.findViewById(R.id.dateText);
                statusText = itemView.findViewById(R.id.statusText);
                averageText = itemView.findViewById(R.id.averageText);
                expandIndicator = itemView.findViewById(R.id.expandIndicator);
                detailsContainer = itemView.findViewById(R.id.detailsContainer);
                dayCard = itemView.findViewById(R.id.dayCard);

                // Initialize day charts
                dayHrChart = itemView.findViewById(R.id.dayHrChart);
                dayBpChart = itemView.findViewById(R.id.dayBpChart);
                daySpo2Chart = itemView.findViewById(R.id.daySpo2Chart);
                dayRrChart = itemView.findViewById(R.id.dayRrChart);

                // Set chart colors
                if (dayHrChart != null) dayHrChart.setPlotColor(Color.parseColor("#FF4444"));
                if (dayBpChart != null) {
                    dayBpChart.setPlotColor(Color.parseColor("#E53935"));
                    dayBpChart.setPlotColor2(Color.parseColor("#FB8C00"));
                }
                if (daySpo2Chart != null) daySpo2Chart.setPlotColor(Color.parseColor("#1E88E5"));
                if (dayRrChart != null) dayRrChart.setPlotColor(Color.parseColor("#9C27B0"));

                // Enable data labels for day detail charts
                if (dayHrChart != null) dayHrChart.setShowDataLabels(true);
                if (dayBpChart != null) dayBpChart.setShowDataLabels(true);
                if (daySpo2Chart != null) daySpo2Chart.setShowDataLabels(true);
                if (dayRrChart != null) dayRrChart.setShowDataLabels(true);
            }

            public void bind(DayItem item) {
                // Set date (mark today)
                String displayDate = item.date;
                if (item.date.equals(VitalSignsHistoryManager.getTodayDate())) {
                    displayDate += " (Today)";
                }
                dateText.setText(displayDate);

                // Set status and average
                if (item.records.isEmpty()) {
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("No records");
                    averageText.setVisibility(View.GONE);
                } else {
                    statusText.setVisibility(View.GONE);
                    averageText.setVisibility(View.VISIBLE);

                    // Calculate and display average with measurement count
                    VitalSignsRecord avgRecord = VitalSignsHistoryManager.calculateAverage(item.records);
                    if (avgRecord != null) {
                        String avgText = String.format(Locale.US,
                                "%d measurements - Average: HR=%d BP=%d/%d SpO2=%d%% RR=%d",
                                item.records.size(),
                                avgRecord.getHr(), avgRecord.getBp_sys(), avgRecord.getBp_dia(),
                                avgRecord.getSpo2(), avgRecord.getRr());
                        averageText.setText(avgText);
                    }
                }

                // Update expand indicator
                expandIndicator.setText(item.expanded ? "▲" : "▼");

                // Show/hide details
                if (item.expanded && !item.records.isEmpty()) {
                    detailsContainer.setVisibility(View.VISIBLE);
                    populateDetails(item.records);
                } else {
                    detailsContainer.setVisibility(View.GONE);
                }

                // Click to expand/collapse
                dayCard.setOnClickListener(v -> {
                    if (!item.records.isEmpty()) {
                        item.expanded = !item.expanded;
                        notifyItemChanged(getAdapterPosition());
                    }
                });
            }

            private void populateDetails(List<VitalSignsRecord> records) {
                if (records == null || records.isEmpty()) {
                    return;
                }

                // Extract data for charts
                List<Integer> hrData = new ArrayList<>();
                List<Integer> bpSysData = new ArrayList<>();
                List<Integer> bpDiaData = new ArrayList<>();
                List<Integer> spo2Data = new ArrayList<>();
                List<Integer> rrData = new ArrayList<>();

                // 过滤掉0值，避免在图表中显示无效数据
                for (VitalSignsRecord record : records) {
                    if (record.getHr() > 0) hrData.add(record.getHr());
                    if (record.getBp_sys() > 0) bpSysData.add(record.getBp_sys());
                    if (record.getBp_dia() > 0) bpDiaData.add(record.getBp_dia());
                    if (record.getSpo2() > 0) spo2Data.add(record.getSpo2());
                    if (record.getRr() > 0) rrData.add(record.getRr());
                }

                // Plot the data
                if (dayHrChart != null) dayHrChart.setData(hrData);
                if (dayBpChart != null) dayBpChart.setDualData(bpSysData, bpDiaData);
                if (daySpo2Chart != null) daySpo2Chart.setData(spo2Data);
                if (dayRrChart != null) dayRrChart.setData(rrData);
            }
        }
    }
}

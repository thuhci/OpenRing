package com.tsinghua.openring.utils;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manager class for vital signs history records
 * Stores and retrieves history data in JSON format
 */
public class VitalSignsHistoryManager {
    private static final String TAG = "VitalSignsHistory";
    private static final String HISTORY_FILE = "vital_signs_history.json";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

    private final Context context;
    private final ObjectMapper mapper;
    private final File historyFile;

    public VitalSignsHistoryManager(Context context) {
        this.context = context.getApplicationContext();
        this.mapper = new ObjectMapper();
        this.historyFile = new File(context.getFilesDir(), HISTORY_FILE);
    }

    /**
     * Save a new measurement record
     */
    public synchronized void saveRecord(VitalSignsRecord record) {
        try {
            // Load existing data
            Map<String, List<VitalSignsRecord>> historyData = loadAllRecords();

            // Get date from timestamp
            String date = record.getTimestamp().substring(0, 10); // Extract yyyy-MM-dd

            // Add record to the date's list
            List<VitalSignsRecord> dayRecords = historyData.computeIfAbsent(date, k -> new ArrayList<>());
            dayRecords.add(record);

            // Save back to file
            saveAllRecords(historyData);

            Log.d(TAG, "Record saved for date: " + date);
        } catch (Exception e) {
            Log.e(TAG, "Error saving record", e);
        }
    }

    /**
     * Load all records from file
     */
    private synchronized Map<String, List<VitalSignsRecord>> loadAllRecords() {
        Map<String, List<VitalSignsRecord>> result = new HashMap<>();

        if (!historyFile.exists()) {
            return result;
        }

        try (FileInputStream fis = new FileInputStream(historyFile)) {
            JsonNode rootNode = mapper.readTree(fis);

            if (rootNode.isObject()) {
                rootNode.fields().forEachRemaining(entry -> {
                    String date = entry.getKey();
                    JsonNode recordsNode = entry.getValue();

                    List<VitalSignsRecord> dayRecords = new ArrayList<>();
                    if (recordsNode.isArray()) {
                        for (JsonNode recordNode : recordsNode) {
                            VitalSignsRecord record = new VitalSignsRecord();
                            record.setTimestamp(recordNode.get("timestamp").asText());
                            record.setHr(recordNode.get("hr").asInt());
                            record.setBp_sys(recordNode.get("bp_sys").asInt());
                            record.setBp_dia(recordNode.get("bp_dia").asInt());
                            record.setSpo2(recordNode.get("spo2").asInt());
                            record.setRr(recordNode.get("rr").asInt());
                            dayRecords.add(record);
                        }
                    }
                    result.put(date, dayRecords);
                });
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading records", e);
        }

        return result;
    }

    /**
     * Save all records to file
     */
    private synchronized void saveAllRecords(Map<String, List<VitalSignsRecord>> data) {
        try {
            ObjectNode rootNode = mapper.createObjectNode();

            for (Map.Entry<String, List<VitalSignsRecord>> entry : data.entrySet()) {
                String date = entry.getKey();
                List<VitalSignsRecord> records = entry.getValue();

                ArrayNode recordsArray = mapper.createArrayNode();
                for (VitalSignsRecord record : records) {
                    ObjectNode recordNode = mapper.createObjectNode();
                    recordNode.put("timestamp", record.getTimestamp());
                    recordNode.put("hr", record.getHr());
                    recordNode.put("bp_sys", record.getBp_sys());
                    recordNode.put("bp_dia", record.getBp_dia());
                    recordNode.put("spo2", record.getSpo2());
                    recordNode.put("rr", record.getRr());
                    recordsArray.add(recordNode);
                }

                rootNode.set(date, recordsArray);
            }

            try (FileOutputStream fos = new FileOutputStream(historyFile)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(fos, rootNode);
            }

            Log.d(TAG, "All records saved to file");
        } catch (IOException e) {
            Log.e(TAG, "Error saving all records", e);
        }
    }

    /**
     * Get records for a specific date
     */
    public synchronized List<VitalSignsRecord> getRecordsForDate(String date) {
        Map<String, List<VitalSignsRecord>> allRecords = loadAllRecords();
        return allRecords.getOrDefault(date, new ArrayList<>());
    }

    /**
     * Get records for the past week (including today)
     * Returns a map: date -> list of records
     */
    public synchronized Map<String, List<VitalSignsRecord>> getRecordsForPastWeek() {
        Map<String, List<VitalSignsRecord>> allRecords = loadAllRecords();
        Map<String, List<VitalSignsRecord>> weekRecords = new HashMap<>();

        Calendar calendar = Calendar.getInstance();
        for (int i = 0; i < 7; i++) {
            String date = DATE_FORMAT.format(calendar.getTime());
            weekRecords.put(date, allRecords.getOrDefault(date, new ArrayList<>()));
            calendar.add(Calendar.DAY_OF_YEAR, -1);
        }

        return weekRecords;
    }

    /**
     * Calculate average values for a list of records
     * Returns null if no records
     */
    public static VitalSignsRecord calculateAverage(List<VitalSignsRecord> records) {
        if (records == null || records.isEmpty()) {
            return null;
        }

        int sumHr = 0, sumBpSys = 0, sumBpDia = 0, sumSpo2 = 0, sumRr = 0;
        int count = records.size();

        for (VitalSignsRecord record : records) {
            sumHr += record.getHr();
            sumBpSys += record.getBp_sys();
            sumBpDia += record.getBp_dia();
            sumSpo2 += record.getSpo2();
            sumRr += record.getRr();
        }

        VitalSignsRecord avgRecord = new VitalSignsRecord();
        avgRecord.setTimestamp(records.get(0).getTimestamp().substring(0, 10)); // Just the date
        avgRecord.setHr(sumHr / count);
        avgRecord.setBp_sys(sumBpSys / count);
        avgRecord.setBp_dia(sumBpDia / count);
        avgRecord.setSpo2(sumSpo2 / count);
        avgRecord.setRr(sumRr / count);

        return avgRecord;
    }

    /**
     * Get today's date string
     */
    public static String getTodayDate() {
        return DATE_FORMAT.format(new Date());
    }

    /**
     * Format current timestamp
     */
    public static String getCurrentTimestamp() {
        return TIMESTAMP_FORMAT.format(new Date());
    }
}

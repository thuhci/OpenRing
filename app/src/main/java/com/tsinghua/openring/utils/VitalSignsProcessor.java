package com.tsinghua.openring.utils;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Real-time Heart Rate and Respiratory Rate Processing
 * Processes PPG and accelerometer data to calculate vital signs
 */
public class VitalSignsProcessor {
    private static final String TAG = "VitalSignsProcessor";
    
    // Configuration constants
    private static final int SAMPLE_RATE = 25; // Hz - based on your ring's sampling rate
    private static final int HR_WINDOW_SIZE = SAMPLE_RATE * 5; // 5 seconds sliding window for HR calculation
    private static final int HR_UPDATE_INTERVAL = SAMPLE_RATE * 1; // Update every 1 second
    private static final int MIN_PEAKS_FOR_HR = 2; // Minimum peaks needed for HR calculation (reduced for 1s updates)
    
    // Heart rate range constraints (BPM)
    private static final int MIN_HR_BPM = 40;
    private static final int MAX_HR_BPM = 200;
    
    // SpO2 range constraints (%)
    private static final int MIN_SPO2 = 70;
    private static final int MAX_SPO2 = 100;
    
    // Data buffers for processing
    private final List<Long> ppgGreenBuffer = new ArrayList<>();
    private final List<Long> ppgIrBuffer = new ArrayList<>();
    private final List<Short> accXBuffer = new ArrayList<>();
    private final List<Short> accYBuffer = new ArrayList<>();
    private final List<Short> accZBuffer = new ArrayList<>();
    private final List<Long> timestampBuffer = new ArrayList<>();
    
    // Current vital signs
    private volatile int currentHeartRate = -1;
    private volatile SignalQuality currentSignalQuality = SignalQuality.POOR;
    private volatile long lastUpdateTime = 0;
    
    // HR/SpO2 smoothing and validation
    private final List<Integer> hrHistory = new ArrayList<>(); // Recent HR values for smoothing (5 seconds)
    private final List<Integer> spo2History = new ArrayList<>(); // Recent SpO2 values for smoothing
    private static final int HR_HISTORY_SIZE = 5; // Keep last 5 HR readings (5 seconds, 1 per second)
    private static final int SPO2_HISTORY_SIZE = 5; // Keep last 5 SpO2 readings
    private static final int MAX_HR_CHANGE_BPM = 10; // Maximum HR change per 1s update
    private static final int OUTLIER_THRESHOLD_BPM = 30; // If change > 30 BPM, treat as outlier
    private static final int MIN_CONFIRMATIONS = 3; // Need 3 similar readings to accept large change
    
    // Sample counter for update interval
    private int samplesSinceLastHRUpdate = 0;
        // Callback interface for vital signs updates
    public interface VitalSignsCallback {
        void onHeartRateUpdate(int heartRate);
        void onSignalQualityUpdate(SignalQuality quality);
    }
    
    public enum SignalQuality {
        EXCELLENT("Excellent", "#4CAF50"),
        GOOD("Good", "#8BC34A"),
        FAIR("Fair", "#FFC107"),
        POOR("Poor", "#FF5722"),
        NO_SIGNAL("No Signal", "#9E9E9E");
        
        private final String displayName;
        private final String color;
        
        SignalQuality(String displayName, String color) {
            this.displayName = displayName;
            this.color = color;
        }
        
        public String getDisplayName() { return displayName; }
        public String getColor() { return color; }
    }
    
    private VitalSignsCallback callback;
    
    public VitalSignsProcessor(VitalSignsCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Add new sensor data point for processing
     */
    public synchronized void addDataPoint(long green, long ir, short accX, short accY, short accZ, long timestamp) {
        // Add data to buffers
        ppgGreenBuffer.add(green);
        ppgIrBuffer.add(ir);
        accXBuffer.add(accX);
        accYBuffer.add(accY);
        accZBuffer.add(accZ);
        timestampBuffer.add(timestamp);
        
        // Maintain buffer size for HR calculation (5 second sliding window)
        if (ppgGreenBuffer.size() > HR_WINDOW_SIZE) {
            ppgGreenBuffer.remove(0);
            ppgIrBuffer.remove(0);
            timestampBuffer.remove(0);
        }
        
        // Maintain buffer size for accelerometer (keep same window)
        if (accXBuffer.size() > HR_WINDOW_SIZE) {
            accXBuffer.remove(0);
            accYBuffer.remove(0);
            accZBuffer.remove(0);
        }
        
        // Increment sample counter
        samplesSinceLastHRUpdate++;
        
        // Update HR every 1 second (every 25 samples at 25Hz)
        if (ppgGreenBuffer.size() >= HR_WINDOW_SIZE && samplesSinceLastHRUpdate >= HR_UPDATE_INTERVAL) {
            processHeartRate();
            samplesSinceLastHRUpdate = 0; // Reset counter
        }
        
        // Update signal quality
        updateSignalQuality();
        
        lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Process heart rate from PPG data using peak detection
     * Updates every 1 second with 5-second sliding window
     */
    private void processHeartRate() {
        try {
            // Use Green PPG for heart rate calculation (typically best signal)
            List<Long> ppgData = new ArrayList<>(ppgGreenBuffer);
            
            // Apply bandpass filter (0.5-4Hz = 30-240 BPM)
            List<Double> filteredPPG = applyBandpassFilter(ppgData, 0.5, 4.0);
            
            // Detect peaks with adaptive threshold
            List<Integer> peaks = detectPeaks(filteredPPG, 0.5); // Lower threshold for better detection
            
            if (peaks.size() >= MIN_PEAKS_FOR_HR) {
                // Calculate intervals between peaks
                List<Double> intervals = new ArrayList<>();
                for (int i = 1; i < peaks.size(); i++) {
                    double interval = (peaks.get(i) - peaks.get(i-1)) / (double) SAMPLE_RATE;
                    // Filter out unreasonable intervals (< 0.3s or > 2.0s)
                    if (interval >= 0.3 && interval <= 2.0) {
                        intervals.add(interval);
                    }
                }
                
                if (intervals.isEmpty()) {
                    Log.v(TAG, "HR: No valid intervals after filtering");
                    return;
                }
                
                // Calculate median interval for robustness
                Collections.sort(intervals);
                double medianInterval = intervals.get(intervals.size() / 2);
                
                // Convert to BPM
                int heartRate = (int) Math.round(60.0 / medianInterval);
                
                // Validate and smooth heart rate with 5-second history
                if (heartRate >= MIN_HR_BPM && heartRate <= MAX_HR_BPM) {
                    int smoothedHR = smoothHeartRateWith5SecHistory(heartRate);
                    
                    if (smoothedHR > 0) {
                        currentHeartRate = smoothedHR;
                        if (callback != null) {
                            callback.onHeartRateUpdate(smoothedHR);
                        }
                        Log.d(TAG, String.format("HR: %d BPM (raw: %d, peaks: %d)", 
                            smoothedHR, heartRate, peaks.size()));
                    }
                } else {
                    Log.v(TAG, String.format("HR: %d BPM out of range [%d-%d]", 
                        heartRate, MIN_HR_BPM, MAX_HR_BPM));
                }
            } else {
                Log.v(TAG, String.format("HR: Only %d peaks (need %d)", peaks.size(), MIN_PEAKS_FOR_HR));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing heart rate", e);
        }
    }
    
    /**
     * Process SpO2 (blood oxygen saturation) from Red and IR PPG signals
     * Uses R-value method: R = (AC_red/DC_red) / (AC_ir/DC_ir)
     */
    // SpO2 processing removed as per request
    
    /**
     * Calculate AC component (peak-to-peak amplitude) of PPG signal
     */
    private double calculateAC(List<Long> ppgData) {
        if (ppgData.isEmpty()) return 0;
        
        long max = Collections.max(ppgData);
        long min = Collections.min(ppgData);
        return (max - min) / 2.0; // Half of peak-to-peak
    }
    
    /**
     * Calculate DC component (average) of PPG signal
     */
    private double calculateDC(List<Long> ppgData) {
        if (ppgData.isEmpty()) return 0;
        
        long sum = 0;
        for (long value : ppgData) {
            sum += value;
        }
        return sum / (double) ppgData.size();
    }
    
    /**
     * Smooth heart rate with 5-second history (simplified algorithm)
     * - Rejects outliers (>30 BPM change)
     * - Limits max change per update (10 BPM/second)
     * - Applies weighted averaging with 5-second history
     */
    private int smoothHeartRateWith5SecHistory(int rawHR) {
        // Add to history (keeps last 5 readings = 5 seconds at 1 Hz)
        hrHistory.add(rawHR);
        if (hrHistory.size() > HR_HISTORY_SIZE) {
            hrHistory.remove(0);
        }
        
        // First reading - accept it
        if (currentHeartRate == -1) {
            Log.d(TAG, "HR: First reading " + rawHR + " BPM");
            return rawHR;
        }
        
        int hrChange = Math.abs(rawHR - currentHeartRate);
        
        // Level 1: Reject extreme outliers (>30 BPM change in 1 second)
        if (hrChange > OUTLIER_THRESHOLD_BPM) {
            Log.w(TAG, String.format("HR: Outlier rejected %d->%d (%d BPM)", 
                currentHeartRate, rawHR, hrChange));
            // Remove outlier from history
            if (!hrHistory.isEmpty()) {
                hrHistory.remove(hrHistory.size() - 1);
            }
            return currentHeartRate; // Keep current value
        }
        
        // Level 2: Limit large changes (>10 BPM)
        if (hrChange > MAX_HR_CHANGE_BPM) {
            int limitedHR = rawHR > currentHeartRate 
                ? currentHeartRate + MAX_HR_CHANGE_BPM 
                : currentHeartRate - MAX_HR_CHANGE_BPM;
            Log.d(TAG, String.format("HR: Limited %d->%d to %d BPM", 
                currentHeartRate, rawHR, limitedHR));
            return limitedHR;
        }
        
        // Level 3: Smooth with 5-second history
        if (hrHistory.size() >= 3) {
            // Calculate weighted average: more weight on recent values
            int smoothedHR = 0;
            int totalWeight = 0;
            
            for (int i = 0; i < hrHistory.size(); i++) {
                int weight = i + 1; // Linear weights: 1, 2, 3, 4, 5 (newer values have more weight)
                smoothedHR += hrHistory.get(i) * weight;
                totalWeight += weight;
            }
            smoothedHR = Math.round((float) smoothedHR / totalWeight);
            
            Log.v(TAG, String.format("HR: Smoothed %d->%d BPM (history size: %d)", 
                rawHR, smoothedHR, hrHistory.size()));
            return smoothedHR;
        }
        
        // Not enough history - simple average
        if (hrHistory.size() >= 2) {
            int sum = 0;
            for (int hr : hrHistory) {
                sum += hr;
            }
            return sum / hrHistory.size();
        }
        
        return rawHR;
    }
    
    
    /**
     * Simple bandpass filter implementation
     */
    private List<Double> applyBandpassFilter(List<? extends Number> data, double lowFreq, double highFreq) {
        List<Double> filtered = new ArrayList<>();
        
        // Simple moving average for demonstration (replace with proper filter if needed)
        int windowSize = Math.max(1, SAMPLE_RATE / 5); // 0.2 second window
        
        for (int i = 0; i < data.size(); i++) {
            double sum = 0;
            int count = 0;
            
            int start = Math.max(0, i - windowSize/2);
            int end = Math.min(data.size(), i + windowSize/2 + 1);
            
            for (int j = start; j < end; j++) {
                sum += data.get(j).doubleValue();
                count++;
            }
            
            filtered.add(sum / count);
        }
        
        return filtered;
    }
    
    /**
     * Peak detection using threshold-based approach
     */
    private List<Integer> detectPeaks(List<Double> data, double threshold) {
        List<Integer> peaks = new ArrayList<>();
        
        if (data.size() < 3) return peaks;
        
        // Calculate dynamic threshold based on data range
        double min = Collections.min(data);
        double max = Collections.max(data);
        double dynamicThreshold = min + (max - min) * threshold;
        
        // Find peaks
        for (int i = 1; i < data.size() - 1; i++) {
            double current = data.get(i);
            double prev = data.get(i - 1);
            double next = data.get(i + 1);
            
            // Peak criteria: local maximum above threshold
            if (current > prev && current > next && current > dynamicThreshold) {
                // Avoid peaks too close together (minimum distance)
                if (peaks.isEmpty() || i - peaks.get(peaks.size() - 1) > SAMPLE_RATE / 4) {
                    peaks.add(i);
                }
            }
        }
        
        return peaks;
    }
    
    /**
     * Update signal quality based on data characteristics
     */
    private void updateSignalQuality() {
        SignalQuality quality = SignalQuality.NO_SIGNAL;
        
        if (!ppgGreenBuffer.isEmpty()) {
            // Calculate signal-to-noise ratio
            long min = Collections.min(ppgGreenBuffer);
            long max = Collections.max(ppgGreenBuffer);
            double range = max - min;
            double mean = ppgGreenBuffer.stream().mapToLong(Long::longValue).average().orElse(0);
            
            // Simple quality assessment based on signal range and mean
            if (mean > 1000 && range > 500) {
                if (range > 2000) {
                    quality = SignalQuality.EXCELLENT;
                } else if (range > 1500) {
                    quality = SignalQuality.GOOD;
                } else if (range > 1000) {
                    quality = SignalQuality.FAIR;
                } else {
                    quality = SignalQuality.POOR;
                }
            } else {
                quality = SignalQuality.POOR;
            }
        }
        
        if (currentSignalQuality != quality) {
            currentSignalQuality = quality;
            if (callback != null) {
                callback.onSignalQualityUpdate(quality);
            }
        }
    }
    
    
    /**
     * Clear all buffers and reset state
     */
    public synchronized void reset() {
        ppgGreenBuffer.clear();
        ppgIrBuffer.clear();
        accXBuffer.clear();
        accYBuffer.clear();
        accZBuffer.clear();
        timestampBuffer.clear();
        
        hrHistory.clear();
        spo2History.clear();
        
        currentHeartRate = -1;
        currentSignalQuality = SignalQuality.NO_SIGNAL;
        lastUpdateTime = 0;
        samplesSinceLastHRUpdate = 0;
        
        Log.d(TAG, "VitalSignsProcessor reset");
    }
    
    // Getters for current values
    public int getCurrentHeartRate() { return currentHeartRate; }
    public SignalQuality getCurrentSignalQuality() { return currentSignalQuality; }
    public long getLastUpdateTime() { return lastUpdateTime; }
    
    /**
     * Get buffer sizes for debugging
     */
    public String getBufferStatus() {
        return String.format("PPG: %d/%d, ACC: %d/%d", 
                ppgGreenBuffer.size(), HR_WINDOW_SIZE,
                accXBuffer.size(), HR_WINDOW_SIZE);
    }
}

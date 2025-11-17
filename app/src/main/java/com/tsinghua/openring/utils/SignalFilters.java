package com.tsinghua.openring.utils;

import java.util.Arrays;

/**
 * Signal processing filters for physiological signals
 */
public class SignalFilters {

    /**
     * Butterworth filter coefficients for respiratory rate
     * Designed for fs=100Hz, order=3, [0.067, 0.5] Hz passband
     * Equivalent to [4, 30] breaths per minute
     */
    public static class RespiratoryRateFilter {
        // These coefficients should be calculated using signal processing tools
        // For now, using a simple moving average as placeholder
        // TODO: Implement proper Butterworth filter coefficients

        private static final int WINDOW_SIZE = 300; // 3 seconds at 100Hz

        /**
         * Apply respiratory rate bandpass filter
         * @param signal Input signal
         * @return Filtered signal
         */
        public static float[] filter(float[] signal) {
            if (signal == null || signal.length < WINDOW_SIZE) {
                return signal;
            }

            // Simple moving average as placeholder
            // This removes high-frequency noise but doesn't implement proper bandpass
            float[] filtered = new float[signal.length];

            for (int i = 0; i < signal.length; i++) {
                int startIdx = Math.max(0, i - WINDOW_SIZE/2);
                int endIdx = Math.min(signal.length, i + WINDOW_SIZE/2);

                float sum = 0;
                for (int j = startIdx; j < endIdx; j++) {
                    sum += signal[j];
                }
                filtered[i] = sum / (endIdx - startIdx);
            }

            // Apply high-pass to remove DC component
            float mean = 0;
            for (float v : filtered) {
                mean += v;
            }
            mean /= filtered.length;

            for (int i = 0; i < filtered.length; i++) {
                filtered[i] -= mean;
            }

            return filtered;
        }
    }

    /**
     * Butterworth filter coefficients for heart rate / BP / SpO2
     * Designed for fs=100Hz, order=3, [0.5, 3] Hz passband
     * Equivalent to [30, 180] beats per minute
     */
    public static class PhysiologicalSignalFilter {
        private static final int WINDOW_SIZE = 50; // 0.5 seconds at 100Hz

        /**
         * Apply bandpass filter for HR/BP/SpO2 signals
         * @param signal Input signal
         * @return Filtered signal
         */
        public static float[] filter(float[] signal) {
            if (signal == null || signal.length < WINDOW_SIZE) {
                return signal;
            }

            // Simple moving average to remove high frequency noise
            float[] filtered = new float[signal.length];

            // First pass - moving average (low-pass)
            for (int i = 0; i < signal.length; i++) {
                int startIdx = Math.max(0, i - WINDOW_SIZE/2);
                int endIdx = Math.min(signal.length, i + WINDOW_SIZE/2);

                float sum = 0;
                for (int j = startIdx; j < endIdx; j++) {
                    sum += signal[j];
                }
                filtered[i] = sum / (endIdx - startIdx);
            }

            // Second pass - remove DC component (high-pass at 0.5 Hz)
            // Calculate trend using larger window
            int trendWindow = 200; // 2 seconds at 100Hz
            float[] trend = new float[signal.length];

            for (int i = 0; i < signal.length; i++) {
                int startIdx = Math.max(0, i - trendWindow/2);
                int endIdx = Math.min(signal.length, i + trendWindow/2);

                float sum = 0;
                for (int j = startIdx; j < endIdx; j++) {
                    sum += filtered[j];
                }
                trend[i] = sum / (endIdx - startIdx);
            }

            // Subtract trend to get bandpass filtered signal
            for (int i = 0; i < signal.length; i++) {
                filtered[i] -= trend[i];
            }

            return filtered;
        }
    }
}

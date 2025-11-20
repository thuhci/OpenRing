package com.tsinghua.openring.inference;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Classic Algorithm Processor for Vital Signs
 * Implements FFT and Peak detection algorithms for HR and RR estimation
 */
public class ClassicAlgorithmProcessor {
    private static final String TAG = "ClassicAlgorithmProcessor";

    // Heart Rate constraints
    private static final float MIN_HR_BPM = 40f;
    private static final float MAX_HR_BPM = 200f;
    private static final float MIN_HR_HZ = MIN_HR_BPM / 60f;  // 0.67 Hz
    private static final float MAX_HR_HZ = MAX_HR_BPM / 60f;  // 3.33 Hz

    // Respiratory Rate constraints
    private static final float MIN_RR_BPM = 8f;
    private static final float MAX_RR_BPM = 30f;
    private static final float MIN_RR_HZ = MIN_RR_BPM / 60f;  // 0.133 Hz
    private static final float MAX_RR_HZ = MAX_RR_BPM / 60f;  // 0.5 Hz

    // Welch's method parameters
    private static final int WELCH_SEGMENT_SIZE = 512;  // FFT segment size (must be power of 2)
    private static final float WELCH_OVERLAP_RATIO = 0.5f;  // 50% overlap

    /**
     * Complex number class for FFT computation
     */
    public static class Complex {
        public double real;
        public double imag;

        public Complex(double real, double imag) {
            this.real = real;
            this.imag = imag;
        }

        public Complex add(Complex other) {
            return new Complex(this.real + other.real, this.imag + other.imag);
        }

        public Complex subtract(Complex other) {
            return new Complex(this.real - other.real, this.imag - other.imag);
        }

        public Complex multiply(Complex other) {
            double r = this.real * other.real - this.imag * other.imag;
            double i = this.real * other.imag + this.imag * other.real;
            return new Complex(r, i);
        }

        public double magnitude() {
            return Math.sqrt(real * real + imag * imag);
        }
    }

    /**
     * Welch Power Spectral Density result
     */
    public static class WelchResult {
        public double[] frequencies;  // Frequency bins (Hz)
        public double[] psd;          // Power spectral density

        public WelchResult(double[] frequencies, double[] psd) {
            this.frequencies = frequencies;
            this.psd = psd;
        }
    }

    /**
     * Estimate Heart Rate using Peak Detection (HR_PEAK)
     *
     * @param irSignal IR channel signal (already filtered and normalized)
     * @param sampleRate Sampling rate in Hz
     * @return Estimated heart rate in BPM, or NaN if detection fails
     */
    public static float estimateHrByPeak(float[] irSignal, int sampleRate) {
        try {
            // Detect peaks with adaptive threshold (height_factor ~= 0.1 from training config)
            // and a minimum distance ~0.3-0.4s between peaks to avoid double-counting
            // Use thresholdRatio=0.0 so threshold≈mean,避免因振幅过小导致整段都低于阈值
            List<Integer> peaks = detectPeaks(irSignal, 0.0, sampleRate, 0.4);

            if (peaks.size() < 2) {
                Log.d(TAG, "HR_PEAK: Insufficient peaks detected (" + peaks.size() + ")");
                return Float.NaN;
            }

            // Calculate inter-peak intervals
            List<Double> intervals = new ArrayList<>();
            for (int i = 1; i < peaks.size(); i++) {
                double interval = (peaks.get(i) - peaks.get(i-1)) / (double) sampleRate;
                // Filter physiologically plausible intervals
                if (interval >= 0.3 && interval <= 1.5) {  // 40-200 BPM range
                    intervals.add(interval);
                }
            }

            if (intervals.isEmpty()) {
                Log.d(TAG, "HR_PEAK: No valid intervals found");
                return Float.NaN;
            }

            // Use median interval for robustness
            Collections.sort(intervals);
            double medianInterval = intervals.get(intervals.size() / 2);
            float heartRate = (float) (60.0 / medianInterval);

            // Validate result
            if (heartRate >= MIN_HR_BPM && heartRate <= MAX_HR_BPM) {
                Log.d(TAG, String.format("HR_PEAK: %.1f BPM (peaks: %d, intervals: %d)",
                    heartRate, peaks.size(), intervals.size()));
                return heartRate;
            } else {
                Log.d(TAG, String.format("HR_PEAK: %.1f BPM out of range", heartRate));
                return Float.NaN;
            }

        } catch (Exception e) {
            Log.e(TAG, "HR_PEAK: Error", e);
            return Float.NaN;
        }
    }

    /**
     * Estimate Heart Rate using FFT (HR_FFT)
     *
     * @param irSignal IR channel signal (already filtered and normalized)
     * @param sampleRate Sampling rate in Hz
     * @return Estimated heart rate in BPM, or NaN if detection fails
     */
    public static float estimateHrByFFT(float[] irSignal, int sampleRate) {
        try {
            float dominantFreq = estimateDominantFreqFFT(irSignal, sampleRate, 0.8f, MAX_HR_HZ);

            if (Float.isNaN(dominantFreq)) {
                Log.d(TAG, "HR_FFT: No dominant frequency found");
                return Float.NaN;
            }

            // Convert to BPM
            float heartRate = dominantFreq * 60f;

            Log.d(TAG, String.format("HR_FFT: %.1f BPM (freq: %.3f Hz)", heartRate, dominantFreq));
            return heartRate;

        } catch (Exception e) {
            Log.e(TAG, "HR_FFT: Error", e);
            return Float.NaN;
        }
    }

    /**
     * Estimate Respiratory Rate using Peak Detection (RR_PEAK)
     *
     * @param irSignal IR channel signal (already filtered with RR filter and normalized)
     * @param sampleRate Sampling rate in Hz
     * @return Estimated respiratory rate in breaths/min, or NaN if detection fails
     */
    public static float estimateRrByPeak(float[] irSignal, int sampleRate) {
        try {
            // RR signal has lower amplitude, use a low threshold
            // and no strict minimum distance (interval filtering is applied later)
            // 同样使用 thresholdRatio=0.0，让阈值≈均值，只依赖后续间隔筛选
            List<Integer> peaks = detectPeaks(irSignal, 0.0, sampleRate, 0.0);

            if (peaks.size() < 2) {
                Log.d(TAG, "RR_PEAK: Insufficient peaks detected (" + peaks.size() + ")");
                return Float.NaN;
            }

            // Calculate inter-peak intervals
            List<Double> intervals = new ArrayList<>();
            for (int i = 1; i < peaks.size(); i++) {
                double interval = (peaks.get(i) - peaks.get(i-1)) / (double) sampleRate;
                // Filter physiologically plausible intervals (2-7.5 seconds for 8-30 brpm)
                if (interval >= 2.0 && interval <= 7.5) {
                    intervals.add(interval);
                }
            }

            if (intervals.isEmpty()) {
                Log.d(TAG, "RR_PEAK: No valid intervals found");
                return Float.NaN;
            }

            // Use median interval
            Collections.sort(intervals);
            double medianInterval = intervals.get(intervals.size() / 2);
            float respiratoryRate = (float) (60.0 / medianInterval);

            // Validate result
            if (respiratoryRate >= MIN_RR_BPM && respiratoryRate <= MAX_RR_BPM) {
                Log.d(TAG, String.format("RR_PEAK: %.1f brpm (peaks: %d, intervals: %d)",
                    respiratoryRate, peaks.size(), intervals.size()));
                return respiratoryRate;
            } else {
                Log.d(TAG, String.format("RR_PEAK: %.1f brpm out of range", respiratoryRate));
                return Float.NaN;
            }

        } catch (Exception e) {
            Log.e(TAG, "RR_PEAK: Error", e);
            return Float.NaN;
        }
    }

    /**
     * Estimate Respiratory Rate using FFT (RR_FFT)
     *
     * @param irSignal IR channel signal (already filtered with RR filter and normalized)
     * @param sampleRate Sampling rate in Hz
     * @return Estimated respiratory rate in breaths/min, or NaN if detection fails
     */
    public static float estimateRrByFFT(float[] irSignal, int sampleRate) {
        try {
            // Use Welch PSD for RR as before
            WelchResult welch = computeWelchPSD(irSignal, sampleRate, WELCH_SEGMENT_SIZE);
            float dominantFreq = findDominantFrequency(welch, MIN_RR_HZ, MAX_RR_HZ);

            if (Float.isNaN(dominantFreq)) {
                Log.d(TAG, "RR_FFT: No dominant frequency found");
                return Float.NaN;
            }

            // Convert to breaths per minute
            float respiratoryRate = dominantFreq * 60f;

            Log.d(TAG, String.format("RR_FFT: %.1f brpm (freq: %.3f Hz)", respiratoryRate, dominantFreq));
            return respiratoryRate;

        } catch (Exception e) {
            Log.e(TAG, "RR_FFT: Error", e);
            return Float.NaN;
        }
    }

    /**
     * Estimate dominant frequency using a direct FFT on the full window,
     * with zero-padding to the next power of two for finer resolution.
     *
     * @param signal     Input signal (already filtered)
     * @param sampleRate Sampling rate in Hz
     * @param minFreq    Minimum frequency of interest (Hz)
     * @param maxFreq    Maximum frequency of interest (Hz)
     * @return Dominant frequency in Hz, or NaN if not found
     */
    private static float estimateDominantFreqFFT(float[] signal, int sampleRate,
                                                 float minFreq, float maxFreq) {
        if (signal == null || signal.length < 4 || sampleRate <= 0) {
            return Float.NaN;
        }

        int n = signal.length;
        // Remove DC component
        double mean = 0.0;
        for (float v : signal) {
            mean += v;
        }
        mean /= n;

        // Zero-pad to next power of two for higher spectral resolution
        int fftSize = 1;
        while (fftSize < n) {
            fftSize <<= 1;
        }

        Complex[] input = new Complex[fftSize];
        for (int i = 0; i < n; i++) {
            input[i] = new Complex(signal[i] - (float) mean, 0.0);
        }
        for (int i = n; i < fftSize; i++) {
            input[i] = new Complex(0.0, 0.0);
        }

        Complex[] fft = computeFFT(input);

        int maxIdx = -1;
        double maxPower = Double.NEGATIVE_INFINITY;
        int nyquistBin = fftSize / 2;

        for (int k = 0; k <= nyquistBin; k++) {
            double freq = (double) k * sampleRate / fftSize;
            if (freq >= minFreq && freq <= maxFreq) {
                double mag = fft[k].magnitude();
                double power = mag * mag;
                if (power > maxPower) {
                    maxPower = power;
                    maxIdx = k;
                }
            }
        }

        if (maxIdx == -1) {
            return Float.NaN;
        }

        return (float) (maxIdx * (double) sampleRate / fftSize);
    }

    /**
     * Detect peaks in signal using adaptive threshold and optional minimum distance.
     *
     * @param signal Input signal
     * @param thresholdRatio Height factor relative to (max-mean), e.g., 0.1
     * @param sampleRate Sampling rate (Hz)
     * @param minIntervalSec Minimum interval between peaks (seconds), 0 for no constraint
     * @return List of peak indices
     */
    private static List<Integer> detectPeaks(float[] signal, double thresholdRatio,
                                             int sampleRate, double minIntervalSec) {
        List<Integer> peaks = new ArrayList<>();

        if (signal.length < 3) {
            return peaks;
        }

        // Find signal range and mean
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        double mean = 0.0;
        for (float val : signal) {
            if (val < min) min = val;
            if (val > max) max = val;
            mean += val;
        }
        mean /= signal.length;

        // Adaptive threshold: mean + height_factor * (max - mean)
        float dynamicThreshold = (float) (mean + (max - mean) * thresholdRatio);

        int minDistanceSamples = 0;
        if (minIntervalSec > 0 && sampleRate > 0) {
            minDistanceSamples = Math.max(1, (int) Math.round(minIntervalSec * sampleRate));
        }

        // Detect contiguous regions above threshold, and take the local maximum in each region
        int lastPeakIndex = -minDistanceSamples;
        int i = 0;
        while (i < signal.length) {
            if (signal[i] >= dynamicThreshold) {
                int regionStart = i;
                int regionMaxIdx = i;
                float regionMaxVal = signal[i];
                int j = i + 1;
                while (j < signal.length && signal[j] >= dynamicThreshold) {
                    if (signal[j] > regionMaxVal) {
                        regionMaxVal = signal[j];
                        regionMaxIdx = j;
                    }
                    j++;
                }
                // region is [regionStart, j-1], choose regionMaxIdx as peak
                if (minDistanceSamples <= 0 ||
                    peaks.isEmpty() ||
                    regionMaxIdx - lastPeakIndex >= minDistanceSamples) {
                    peaks.add(regionMaxIdx);
                    lastPeakIndex = regionMaxIdx;
                }
                i = j;
            } else {
                i++;
            }
        }

        return peaks;
    }

    /**
     * Compute Welch's Power Spectral Density estimate
     * Uses overlapping segments with Hanning window
     *
     * @param signal Input signal
     * @param sampleRate Sampling rate in Hz
     * @param segmentSize FFT size (must be power of 2)
     * @return WelchResult containing frequencies and PSD
     */
    private static WelchResult computeWelchPSD(float[] signal, int sampleRate, int segmentSize) {
        // Validate segment size is power of 2
        if ((segmentSize & (segmentSize - 1)) != 0) {
            throw new IllegalArgumentException("Segment size must be power of 2");
        }

        int overlap = (int) (segmentSize * WELCH_OVERLAP_RATIO);
        int step = segmentSize - overlap;

        // Calculate number of segments
        int numSegments = (signal.length - overlap) / step;
        if (numSegments < 1) {
            numSegments = 1;
            segmentSize = signal.length;
            overlap = 0;
            step = segmentSize;
        }

        // Generate Hanning window
        double[] window = createHanningWindow(segmentSize);

        // Initialize accumulated PSD
        double[] psd = new double[segmentSize / 2 + 1];

        // Process each segment
        for (int seg = 0; seg < numSegments; seg++) {
            int start = seg * step;
            int end = Math.min(start + segmentSize, signal.length);
            int actualSize = end - start;

            // Extract and window the segment
            Complex[] segment = new Complex[segmentSize];
            for (int i = 0; i < actualSize; i++) {
                double windowed = signal[start + i] * window[i];
                segment[i] = new Complex(windowed, 0);
            }
            // Zero-pad if necessary
            for (int i = actualSize; i < segmentSize; i++) {
                segment[i] = new Complex(0, 0);
            }

            // Compute FFT
            Complex[] fft = computeFFT(segment);

            // Accumulate power (magnitude squared)
            for (int i = 0; i < psd.length; i++) {
                double mag = fft[i].magnitude();
                psd[i] += mag * mag;
            }
        }

        // Average across segments
        for (int i = 0; i < psd.length; i++) {
            psd[i] /= numSegments;
        }

        // Generate frequency bins
        double[] frequencies = new double[psd.length];
        for (int i = 0; i < frequencies.length; i++) {
            frequencies[i] = (double) i * sampleRate / segmentSize;
        }

        return new WelchResult(frequencies, psd);
    }

    /**
     * Find dominant frequency in specified range from Welch PSD
     *
     * @param welch Welch PSD result
     * @param minFreq Minimum frequency (Hz)
     * @param maxFreq Maximum frequency (Hz)
     * @return Dominant frequency in Hz, or NaN if not found
     */
    private static float findDominantFrequency(WelchResult welch, float minFreq, float maxFreq) {
        int maxIdx = -1;
        double maxPower = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < welch.frequencies.length; i++) {
            double freq = welch.frequencies[i];
            if (freq >= minFreq && freq <= maxFreq) {
                if (welch.psd[i] > maxPower) {
                    maxPower = welch.psd[i];
                    maxIdx = i;
                }
            }
        }

        if (maxIdx == -1) {
            return Float.NaN;
        }

        return (float) welch.frequencies[maxIdx];
    }

    /**
     * Create Hanning (Hann) window
     * w(n) = 0.5 * (1 - cos(2*pi*n / (N-1)))
     */
    private static double[] createHanningWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        return window;
    }

    /**
     * Compute Fast Fourier Transform using Cooley-Tukey algorithm
     * Requires input size to be power of 2
     *
     * @param input Complex array (size must be power of 2)
     * @return FFT result
     */
    private static Complex[] computeFFT(Complex[] input) {
        int n = input.length;

        // Base case
        if (n == 1) {
            return new Complex[] { input[0] };
        }

        // Ensure n is power of 2
        if ((n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be power of 2");
        }

        // Divide: split into even and odd indices
        Complex[] even = new Complex[n / 2];
        Complex[] odd = new Complex[n / 2];
        for (int i = 0; i < n / 2; i++) {
            even[i] = input[2 * i];
            odd[i] = input[2 * i + 1];
        }

        // Conquer: recursively compute FFT of even and odd parts
        Complex[] fftEven = computeFFT(even);
        Complex[] fftOdd = computeFFT(odd);

        // Combine: apply butterfly operations
        Complex[] result = new Complex[n];
        for (int k = 0; k < n / 2; k++) {
            double angle = -2.0 * Math.PI * k / n;
            Complex twiddle = new Complex(Math.cos(angle), Math.sin(angle));
            Complex twiddledOdd = twiddle.multiply(fftOdd[k]);

            result[k] = fftEven[k].add(twiddledOdd);
            result[k + n / 2] = fftEven[k].subtract(twiddledOdd);
        }

        return result;
    }
}

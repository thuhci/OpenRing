package com.tsinghua.openring.inference;

/**
 * Model Architecture Enumeration
 */
public enum ModelArchitecture {
    RESNET("ResNet", "resnet", ClassicAlgorithmType.NONE),
    TRANSFORMER("Transformer", "transformer", ClassicAlgorithmType.NONE),
    INCEPTION("Inception", "inception", ClassicAlgorithmType.NONE),
    CLASSIC_HR_PEAK("Classic Peak (HR)", null, ClassicAlgorithmType.HR_PEAK),
    CLASSIC_HR_FFT("Classic FFT (HR)", null, ClassicAlgorithmType.HR_FFT),
    CLASSIC_RR_FFT("Classic FFT (RR)", null, ClassicAlgorithmType.RR_FFT),
    CLASSIC_RR_PEAK("Classic Peak (RR)", null, ClassicAlgorithmType.RR_PEAK);

    private final String displayName;
    private final String pathPrefix;
    private final ClassicAlgorithmType classicType;

    ModelArchitecture(String displayName, String pathPrefix, ClassicAlgorithmType classicType) {
        this.displayName = displayName;
        this.pathPrefix = pathPrefix;
        this.classicType = classicType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public boolean isClassicAlgorithm() {
        return classicType != ClassicAlgorithmType.NONE;
    }

    public ClassicAlgorithmType getClassicType() {
        return classicType;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public enum ClassicAlgorithmType {
        NONE,
        HR_PEAK,
        HR_FFT,
        RR_FFT,
        RR_PEAK
    }
}

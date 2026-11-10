package me.pintobox.pintoAntiCheat.rotation;

final class RotationStepSettings {
    static final double MIN_STEP = 0.0096;
    static final double MAX_STEP = 0.6144;

    static final int LEARNING_SAMPLE_COUNT = 16;
    static final double MAX_LEARNING_DELTA = 20.0;
    static final double EPSILON = 0.0005;
    static final double YAW_ROUNDING_MULTIPLIER = 4.0;

    static final int MISSES_BEFORE_RELEARN = 3;
    static final int UNSTABLE_REPORT_INTERVAL = 20;

    static final int RELEARN_CONFIRM_PACKETS = 16;
    static final int OLD_STEP_RECOVERY_PACKETS = 4;

    private RotationStepSettings() { }
}
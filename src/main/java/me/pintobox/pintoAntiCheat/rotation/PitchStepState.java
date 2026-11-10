package me.pintobox.pintoAntiCheat.rotation;

import static me.pintobox.pintoAntiCheat.rotation.RotationStepSettings.*;

final class PitchStepState {
    private final double[] samples = new double[LEARNING_SAMPLE_COUNT];

    private float previousYaw = Float.NaN;
    private float previousPitch = Float.NaN;
    private double step;

    private int misses;
    private Result firstMiss;

    private int sampleCount;
    private int sampleIndex;
    private boolean relearning;
    private int oldStepMatches;
    private double candidateStep;
    private int candidateMatches;
    private int failedLearningPackets;

    double step() {
        return step;
    }

    void resetAngle() {
        previousYaw = Float.NaN;
        previousPitch = Float.NaN;
        misses = 0;
        firstMiss = null;
        clearLearning();
    }

    Result sample(float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)
            || Math.abs(pitch) > 90.0f) {
            resetAngle();
            return null;
        }

        float fromYaw = previousYaw;
        float fromPitch = previousPitch;
        previousYaw = yaw;
        previousPitch = pitch;
        if (Float.isNaN(fromYaw)) return null;

        double yawDelta = Math.abs((double) yaw - fromYaw);
        double pitchDelta = Math.abs((double) pitch - fromPitch);

        // Skip possible yaw wraparound and turns cut short by the pitch clamp.
        if (yawDelta > 180.0) yawDelta = 0.0;
        if (Math.abs(pitch) == 90.0f) pitchDelta = 0.0;

        double yawRounding = YAW_ROUNDING_MULTIPLIER
            * Math.max(Math.ulp(fromYaw), Math.ulp(yaw));

        if (step == 0.0) {
            learnPacket(yawDelta, pitchDelta, yawRounding);
            return null;
        }

        double yawTolerance = EPSILON + yawRounding;
        boolean checkYaw = yawDelta > 0.0 && yawTolerance < step / 2.0;
        boolean checkPitch = pitchDelta > 0.0;
        if (!checkYaw && !checkPitch) return null;

        double yawError = checkYaw ? error(yawDelta, step) : 0.0;
        double pitchError = checkPitch ? error(pitchDelta, step) : 0.0;

        boolean fitsOldStep = yawError <= yawTolerance && pitchError <= EPSILON;

        if (fitsOldStep) {
            if (firstMiss != null && ++oldStepMatches >= OLD_STEP_RECOVERY_PACKETS) {
                Result report = firstMiss;
                clearLearning();
                return report;
            }
        } else {
            oldStepMatches = 0;

            if (firstMiss == null) {
                firstMiss = pitchError > EPSILON
                    ? new Result("pitch", pitchDelta, step, pitchError)
                    : new Result("yaw", yawDelta, step, yawError);
            }

            if (!relearning && ++misses >= MISSES_BEFORE_RELEARN) {
                relearning = true;
                return null;
            }
        }

        if (relearning) {
            learnPacket(yawDelta, pitchDelta, yawRounding);
        }

        return null;
    }

    private void learnPacket(double yawDelta, double pitchDelta, double yawRounding) {
        boolean useYaw = yawRounding <= EPSILON && isLearningDelta(yawDelta);
        boolean usePitch = isLearningDelta(pitchDelta);
        if (!useYaw && !usePitch) return;

        if (usePitch) addSample(pitchDelta);
        if (useYaw) addSample(yawDelta);

        if (candidateStep != 0.0) {
            if ((!useYaw || error(yawDelta, candidateStep) <= EPSILON)
                && (!usePitch || error(pitchDelta, candidateStep) <= EPSILON)) {
                if (++candidateMatches >= RELEARN_CONFIRM_PACKETS) {
                    step = candidateStep;
                    clearLearning();
                }
                return;
            }

            candidateStep = 0.0;
            candidateMatches = 0;
        }

        if (sampleCount < samples.length) return;

        double fit = fitStep();
        if (fit == 0.0) {
            failedLearningPackets++;
        } else if (step == 0.0) {
            step = fit;
            clearLearning();
        } else {
            candidateStep = fit;
            failedLearningPackets = 0;
        }
    }

    private static boolean isLearningDelta(double delta) {
        return delta >= MIN_STEP - EPSILON && delta <= MAX_LEARNING_DELTA;
    }

    private void addSample(double delta) {
        samples[sampleIndex] = delta;
        sampleIndex = (sampleIndex + 1) % samples.length;
        if (sampleCount < samples.length) sampleCount++;
    }

    private double fitStep() {
        double smallest = Double.POSITIVE_INFINITY;
        for (double sample : samples) {
            smallest = Math.min(smallest, sample);
        }

        int maxCounts = (int) ((smallest + EPSILON) / MIN_STEP);
        for (int counts = 1; counts <= maxCounts; counts++) {
            double candidate = smallest / counts;
            if (candidate > MAX_STEP + EPSILON) continue;

            double weightedDelta = 0.0;
            double squaredCounts = 0.0;
            for (double sample : samples) {
                long n = Math.round(sample / candidate);
                weightedDelta += n * sample;
                squaredCounts += (double) n * n;
            }

            candidate = Math.max(MIN_STEP, Math.min(weightedDelta / squaredCounts, MAX_STEP));

            boolean fits = true;
            for (double sample : samples) {
                if (error(sample, candidate) > EPSILON) {
                    fits = false;
                    break;
                }
            }

            if (fits) return candidate;
        }

        return 0.0;
    }

    private void clearLearning() {
        misses = 0;
        firstMiss = null;
        relearning = false;
        oldStepMatches = 0;
        candidateStep = 0.0;
        candidateMatches = 0;
        sampleCount = 0;
        sampleIndex = 0;
        failedLearningPackets = 0;
    }

    boolean takeUnstableReport() {
        if (failedLearningPackets < UNSTABLE_REPORT_INTERVAL) return false;

        failedLearningPackets = 0;
        return true;
    }

    private static double error(double delta, double step) {
        return Math.abs(delta - Math.round(delta / step) * step);
    }

    record Result(String axis, double delta, double step, double error) {}
}
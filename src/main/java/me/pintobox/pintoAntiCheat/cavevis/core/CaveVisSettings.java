package me.pintobox.pintoAntiCheat.cavevis.core;

public final class CaveVisSettings {
    private CaveVisSettings() {}

    public static final int ALWAYS_VISIBLE_FROM_Y = 48;
    public static final int OCCLUDERS_UP_TO_Y = 64;

    public static final boolean ENTITY_CULLING_ENABLED = true;
    public static final boolean PARTICLE_CULLING_ENABLED = true;
    public static final int ENTITY_VISIBILITY_INTERVAL_TICKS = 5;

    public static final int DEFAULT_RAYS = 2048;
    public static final int RAY_WORKERS = 2;

    public static final int SCAN_STARTS_PER_TICK = 6;

    public static final int SEARCH_RADIUS_CHUNKS = 10;
    public static final int SEARCH_INTERVAL_TICKS = 20;
    public static final int SEARCH_RETRY_DELAY_TICKS = 20;

    public static final int DEFAULT_BUILD_RADIUS_CHUNKS = 16;
    public static final int BUILD_IN_FLIGHT = 64;
    public static final double BUILD_BUDGET_MS = 16.0;
    public static final double EDIT_BUDGET_MS = 1.0;

    public static final int COLUMNS_PER_PLAYER_PER_TICK = 8;
    public static final int COLUMNS_TOTAL_PER_TICK = 64;
    public static final double COLUMN_SEND_BUDGET_MS = 3.0;

    public static final int NEAR_REVEAL_RADIUS_BLOCKS = 8;
    public static final boolean NEAR_SEND_IMMEDIATELY = false;
    public static final boolean VISIBILITY_MARGIN_ENABLED = true;

    public static final long BUILD_BUDGET_NANOS = nanos(BUILD_BUDGET_MS);
    public static final long EDIT_BUDGET_NANOS = nanos(EDIT_BUDGET_MS);

    private static long nanos(double milliseconds) {
        return Math.max(1L, (long) (milliseconds * 1_000_000.0));
    }

    public static void validate() {
        require((ALWAYS_VISIBLE_FROM_Y & 15) == 0
                && (OCCLUDERS_UP_TO_Y & 15) == 0,
            "Visibility heights must be multiples of 16");

        require(OCCLUDERS_UP_TO_Y >= ALWAYS_VISIBLE_FROM_Y,
            "Occluders must reach the always-visible height");

        require(DEFAULT_RAYS >= 256 && DEFAULT_RAYS <= 65536,
            "DEFAULT_RAYS must be within 256..65536");

        require(RAY_WORKERS > 0,
            "RAY_WORKERS must be positive");

        require(SEARCH_RADIUS_CHUNKS >= 1
                && SEARCH_RADIUS_CHUNKS <= 64,
            "SEARCH_RADIUS_CHUNKS must be within 1..64");

        require(SEARCH_INTERVAL_TICKS > 0
                && SEARCH_RETRY_DELAY_TICKS > 0,
            "Search intervals must be positive");

        require(DEFAULT_BUILD_RADIUS_CHUNKS >= 1
                && DEFAULT_BUILD_RADIUS_CHUNKS <= 64,
            "DEFAULT_BUILD_RADIUS_CHUNKS must be within 1..64");

        require(BUILD_IN_FLIGHT > 0,
            "BUILD_IN_FLIGHT must be positive");

        require(validBudget(BUILD_BUDGET_MS)
                && validBudget(EDIT_BUDGET_MS)
                && validBudget(COLUMN_SEND_BUDGET_MS),
            "Time budgets must be finite and positive");

        require(COLUMNS_PER_PLAYER_PER_TICK > 0
                && COLUMNS_TOTAL_PER_TICK > 0,
            "Column limits must be positive");

        require(ENTITY_VISIBILITY_INTERVAL_TICKS > 0
                && NEAR_REVEAL_RADIUS_BLOCKS >= 0,
            "Invalid entity interval or near-reveal radius");
    }

    private static boolean validBudget(double milliseconds) {
        return Double.isFinite(milliseconds) && milliseconds > 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
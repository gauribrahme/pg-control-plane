package com.gauribrahme.pgcp.instance;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Plan catalog: maps a plan name to compute and storage limits. */
public enum Plan {
    SMALL(1, 2, 20, 500),
    MEDIUM(2, 8, 100, 2000),
    LARGE(4, 16, 500, 8000);

    private final int cpu;
    private final int memoryGb;
    private final int defaultStorageGb;
    private final int maxStorageGb;

    Plan(int cpu, int memoryGb, int defaultStorageGb, int maxStorageGb) {
        this.cpu = cpu;
        this.memoryGb = memoryGb;
        this.defaultStorageGb = defaultStorageGb;
        this.maxStorageGb = maxStorageGb;
    }

    public int cpu() { return cpu; }
    public int memoryGb() { return memoryGb; }
    public int defaultStorageGb() { return defaultStorageGb; }
    public int maxStorageGb() { return maxStorageGb; }

    @JsonValue
    public String json() { return name().toLowerCase(Locale.ROOT); }

    @JsonCreator
    public static Plan fromJson(String value) {
        return Plan.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

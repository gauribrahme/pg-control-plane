package com.gauribrahme.pgcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Settable clock so backoff and lease expiry can be tested without sleeping. */
public class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

    public void set(Instant instant) { this.now = instant; }
    public void advance(Duration d) { this.now = now.plus(d); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}

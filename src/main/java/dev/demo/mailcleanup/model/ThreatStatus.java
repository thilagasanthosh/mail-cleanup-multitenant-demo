package dev.demo.mailcleanup.model;

/** Verdict on one threat from the detection stack. Alerts are per threat, not per message. */
public enum ThreatStatus {
    ACTIVE, CLEARED, FALSE_POSITIVE;

    public boolean isBad() { return this == ACTIVE; }
}

package ua.medicine.service;

import java.util.OptionalInt;

public record Adherence(long taken, long missed) {

    /** taken / (taken + missed) in whole percent; empty when nothing is resolved yet. */
    public OptionalInt percent() {
        long total = taken + missed;
        return total == 0 ? OptionalInt.empty() : OptionalInt.of((int) Math.round(100.0 * taken / total));
    }
}

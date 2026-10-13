package ua.medicine.service;

import java.time.LocalDate;

/** Notices that the calendar day changed since the last check, however long the gap (e.g. sleep). */
public final class DayChangeDetector {

    private LocalDate lastDay;

    public DayChangeDetector(LocalDate today) {
        this.lastDay = today;
    }

    public synchronized boolean changed(LocalDate today) {
        if (today.equals(lastDay)) {
            return false;
        }
        lastDay = today;
        return true;
    }
}

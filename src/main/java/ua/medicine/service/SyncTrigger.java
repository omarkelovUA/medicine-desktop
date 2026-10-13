package ua.medicine.service;

/** Asks the calendar sync to run soon. */
@FunctionalInterface
public interface SyncTrigger {

    SyncTrigger NOOP = () -> {
    };

    void requestSync();
}

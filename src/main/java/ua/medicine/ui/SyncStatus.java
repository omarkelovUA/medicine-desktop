package ua.medicine.ui;

import ua.medicine.i18n.Formats;
import ua.medicine.i18n.Messages;
import ua.medicine.i18n.UkrainianPlural;
import ua.medicine.sync.SyncResult;

import java.time.LocalTime;

/** Status-bar text for a sync run. Raw (English) error details go to the log only. */
final class SyncStatus {

    private SyncStatus() {
    }

    static String text(SyncResult result, LocalTime now, boolean anyoneLinked) {
        if (!anyoneLinked) {
            return "";
        }
        if (!result.authFailedPersonIds().isEmpty()) {
            return Messages.get("sync.authNeeded");
        }
        if (result.failed() > 0) {
            int n = result.failed();
            return Messages.get("sync.failed", n + " " + UkrainianPlural.choose(n, "запис", "записи", "записів"));
        }
        return Messages.get("sync.ok", Formats.time(now));
    }
}

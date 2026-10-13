package ua.medicine.sync;

import java.util.Set;

public record SyncResult(int pushed, int deleted, int failed, Set<Long> authFailedPersonIds, String lastError) {
}

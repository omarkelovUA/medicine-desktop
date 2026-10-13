package ua.medicine.ui;

import org.junit.jupiter.api.Test;
import ua.medicine.sync.SyncResult;

import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SyncStatusTest {

    private final LocalTime now = LocalTime.of(14, 5);

    @Test
    void nothingWhenNobodyIsLinked() {
        assertThat(SyncStatus.text(new SyncResult(0, 0, 0, Set.of(), null), now, false)).isEmpty();
    }

    @Test
    void okInUkrainian() {
        assertThat(SyncStatus.text(new SyncResult(3, 0, 0, Set.of(), null), now, true))
                .isEqualTo("Синхронізовано о 14:05");
    }

    @Test
    void failuresAreUkrainianWithoutRawErrorText() {
        var text = SyncStatus.text(new SyncResult(0, 0, 2, Set.of(), "Read timed out (java.net.SocketTimeoutException)"),
                now, true);
        assertThat(text).isEqualTo("Не вдалося синхронізувати 2 записи, спробуємо пізніше");
    }

    @Test
    void authProblemPointsToReconnect() {
        assertThat(SyncStatus.text(new SyncResult(0, 0, 0, Set.of(4L), "invalid_grant"), now, true))
                .isEqualTo("Потрібно перепідключити Google (позначено G ⚠ на вкладці особи)");
    }
}

package ua.medicine.sync;

import java.time.LocalDateTime;

public record CalendarEvent(String summary, String description, LocalDateTime start, int durationMinutes,
                            boolean taken) {
}

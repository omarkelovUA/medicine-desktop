package ua.medicine.service;

import ua.medicine.domain.DoseStatus;

import java.time.LocalDateTime;

/** {@code period}: the range of the medicine's period containing the dose, empty when outside every period. */
public record HistoryRow(LocalDateTime scheduledAt, String personName, String medicineName, DoseStatus status,
                         LocalDateTime takenAt, String period) {
}

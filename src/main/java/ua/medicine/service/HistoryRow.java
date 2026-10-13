package ua.medicine.service;

import ua.medicine.domain.DoseStatus;

import java.time.LocalDateTime;

public record HistoryRow(LocalDateTime scheduledAt, String personName, String medicineName, DoseStatus status,
                         LocalDateTime takenAt) {
}

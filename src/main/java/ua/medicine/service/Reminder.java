package ua.medicine.service;

import java.time.LocalTime;

public record Reminder(long doseId, String personName, String medicineName, String dosage, LocalTime time) {
}

package ua.medicine.schedule;

import java.time.LocalDate;

/** Inclusive date range. */
public record DateRange(LocalDate from, LocalDate to) {
}

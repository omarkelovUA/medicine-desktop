package ua.medicine.service;

/** Null fields mean "all". */
public record HistoryFilter(Long personId, Long medicineId, Integer lastDays) {
}

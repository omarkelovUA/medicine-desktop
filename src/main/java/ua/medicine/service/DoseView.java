package ua.medicine.service;

import ua.medicine.domain.DoseOccurrence;
import ua.medicine.domain.DoseStatus;

public record DoseView(DoseOccurrence dose, DoseStatus status) {
}

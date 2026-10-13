package ua.medicine.domain;

import java.time.temporal.ChronoUnit;

public enum PeriodUnit {
    DAYS(ChronoUnit.DAYS),
    WEEKS(ChronoUnit.WEEKS),
    MONTHS(ChronoUnit.MONTHS),
    YEARS(ChronoUnit.YEARS);

    private final ChronoUnit chronoUnit;

    PeriodUnit(ChronoUnit chronoUnit) {
        this.chronoUnit = chronoUnit;
    }

    public ChronoUnit chronoUnit() {
        return chronoUnit;
    }
}

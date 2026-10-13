package ua.medicine.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** The visible date range of the timeline for one zoom level, split into columns. Immutable and pure. */
public final class TimeScale {

    public enum Zoom {
        DAYS(ChronoUnit.DAYS, 1, 0),
        WEEKS(ChronoUnit.WEEKS, 13, 2),
        MONTHS(ChronoUnit.MONTHS, 12, 2),
        QUARTERS(ChronoUnit.MONTHS, 8, 1),
        YEARS(ChronoUnit.YEARS, 5, 1);

        private final ChronoUnit unit;
        private final int columns;
        private final int leadIn;

        Zoom(ChronoUnit unit, int columns, int leadIn) {
            this.unit = unit;
            this.columns = columns;
            this.leadIn = leadIn;
        }

        /** Length of one column in {@link #unit}s. */
        private int step() {
            return this == QUARTERS ? 3 : 1;
        }
    }

    public record Column(LocalDate start, LocalDate endExclusive) {
    }

    private final Zoom zoom;
    private final LocalDate start;

    private TimeScale(Zoom zoom, LocalDate start) {
        this.zoom = zoom;
        this.start = start;
    }

    /** The range for {@code zoom} that shows {@code anchor} with a little of the past before it. */
    public static TimeScale around(Zoom zoom, LocalDate anchor) {
        LocalDate first = switch (zoom) {
            case DAYS, MONTHS -> anchor.withDayOfMonth(1);
            case WEEKS -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case QUARTERS -> anchor.withDayOfMonth(1).withMonth((anchor.getMonthValue() - 1) / 3 * 3 + 1);
            case YEARS -> anchor.withDayOfYear(1);
        };
        return new TimeScale(zoom, first.minus((long) zoom.leadIn * zoom.step(), zoom.unit));
    }

    public TimeScale previous() {
        return new TimeScale(zoom, zoom == Zoom.DAYS ? start.minusMonths(1) : start.minus(span(), zoom.unit));
    }

    public TimeScale next() {
        return new TimeScale(zoom, endExclusive());
    }

    public Zoom zoom() {
        return zoom;
    }

    public LocalDate start() {
        return start;
    }

    public LocalDate endExclusive() {
        return start.plus(span(), zoom.unit);
    }

    public List<Column> columns() {
        List<Column> result = new ArrayList<>();
        LocalDate end = endExclusive();
        for (LocalDate d = start; d.isBefore(end); ) {
            LocalDate next = zoom == Zoom.DAYS ? d.plusDays(1) : d.plus(zoom.step(), zoom.unit);
            result.add(new Column(d, next));
            d = next;
        }
        return result;
    }

    /** Where the start of {@code date} lies across the range, from 0 to 1, proportional to days. */
    public double fraction(LocalDate date) {
        double total = ChronoUnit.DAYS.between(start, endExclusive());
        double f = ChronoUnit.DAYS.between(start, date) / total;
        return Math.max(0, Math.min(1, f));
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && date.isBefore(endExclusive());
    }

    /** The whole range in {@code zoom.unit}s; DAYS spans one calendar month. */
    private long span() {
        return zoom == Zoom.DAYS ? ChronoUnit.DAYS.between(start, start.plusMonths(1))
                : (long) zoom.columns * zoom.step();
    }
}

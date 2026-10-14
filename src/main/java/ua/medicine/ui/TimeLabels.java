package ua.medicine.ui;

import ua.medicine.domain.Medicine;
import ua.medicine.domain.Period;
import ua.medicine.i18n.Formats;
import ua.medicine.i18n.Messages;
import ua.medicine.schedule.TimeScale;
import ua.medicine.schedule.TimeScale.Column;
import ua.medicine.schedule.TimeScale.Zoom;

import java.time.LocalDate;

/** Ukrainian header, range and bar texts for the timeline. */
final class TimeLabels {

    private static final String[] MONTHS = Messages.get("month.short").split(",");
    private static final String[] MONTHS_FULL = Messages.get("month.full").split(",");
    private static final String[] WEEKDAYS = Messages.get("weekday.short").split(",");
    private static final String[] ROMAN = {"I", "II", "III", "IV"};
    private static final java.time.format.DateTimeFormatter COMPACT_WEEK =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM");

    private TimeLabels() {
    }

    static String column(Zoom zoom, Column c) {
        LocalDate last = c.endExclusive().minusDays(1);
        return switch (zoom) {
            case DAYS -> String.valueOf(c.start().getDayOfMonth());
            case WEEKS -> c.start().getMonth() == last.getMonth()
                    ? c.start().getDayOfMonth() + "–" + last.getDayOfMonth() + " " + month(last)
                    : dayMonth(c.start()) + " – " + dayMonth(last);
            case MONTHS -> month(c.start());
            case QUARTERS -> quarter(c.start());
            case YEARS -> String.valueOf(c.start().getYear());
        };
    }

    /** A shorter header for narrow columns: the week's Monday as {@code 28.09}; other zooms are already short. */
    static String columnCompact(Zoom zoom, Column c) {
        return zoom == Zoom.WEEKS ? COMPACT_WEEK.format(c.start()) : column(zoom, c);
    }

    static String columnSub(Zoom zoom, Column c, boolean first) {
        return switch (zoom) {
            case DAYS -> WEEKDAYS[c.start().getDayOfWeek().getValue() - 1];
            case MONTHS -> first || c.start().getMonthValue() == 1 ? String.valueOf(c.start().getYear()) : "";
            default -> "";
        };
    }

    static String range(TimeScale s) {
        LocalDate from = s.start();
        LocalDate last = s.endExclusive().minusDays(1);
        return switch (s.zoom()) {
            case DAYS -> MONTHS_FULL[from.getMonthValue() - 1] + " " + from.getYear();
            case WEEKS -> from.getYear() == last.getYear()
                    ? dayMonth(from) + " – " + dayMonth(last) + " " + last.getYear()
                    : dayMonth(from) + " " + from.getYear() + " – " + dayMonth(last) + " " + last.getYear();
            case MONTHS -> month(from) + " " + from.getYear() + " – " + month(last) + " " + last.getYear();
            case QUARTERS -> quarter(from) + " – " + quarter(last);
            case YEARS -> from.getYear() + " – " + last.getYear();
        };
    }

    static String barLabel(Medicine m) {
        return m.dosage() == null || m.dosage().isBlank() ? m.name() : m.name() + " · " + m.dosage().trim();
    }

    static String tooltip(Medicine m, Period period) {
        String title = m.dosage() == null || m.dosage().isBlank() ? m.name() : m.name() + " — " + m.dosage().trim();
        String start = Formats.date(period.start());
        return period.endDate()
                .map(end -> Messages.get("timeline.tooltip.fixed", title, start, Formats.date(end)))
                .orElseGet(() -> Messages.get("timeline.tooltip.ongoing", title, start));
    }

    private static String month(LocalDate d) {
        return MONTHS[d.getMonthValue() - 1];
    }

    private static String dayMonth(LocalDate d) {
        return d.getDayOfMonth() + " " + month(d);
    }

    private static String quarter(LocalDate d) {
        return Messages.get("timeline.quarter", ROMAN[(d.getMonthValue() - 1) / 3], String.valueOf(d.getYear()));
    }
}

package ua.medicine.testutil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Test clock in the system zone that can be moved. */
public final class MutableClock extends Clock {

    private final ZoneId zone = ZoneId.systemDefault();
    private Instant instant;

    private MutableClock(Instant instant) {
        this.instant = instant;
    }

    public static MutableClock at(String isoLocalDateTime) {
        var clock = new MutableClock(Instant.EPOCH);
        clock.set(LocalDateTime.parse(isoLocalDateTime));
        return clock;
    }

    public void set(LocalDateTime time) {
        instant = time.atZone(zone).toInstant();
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        return instant;
    }
}

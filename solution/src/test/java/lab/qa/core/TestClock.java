package lab.qa.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand: "25 hours later" without waiting 25 hours. */
public class TestClock extends Clock {

    private Instant now;

    public TestClock(Instant start) {
        this.now = start;
    }

    public static TestClock startingAt(String isoInstant) {
        return new TestClock(Instant.parse(isoInstant));
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }

    public synchronized void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}

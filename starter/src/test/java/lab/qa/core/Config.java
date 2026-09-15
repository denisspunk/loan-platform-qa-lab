package lab.qa.core;

import java.time.Duration;

/** Everything a run can tune from the command line, in one place. */
public final class Config {

    /** Every test run starts at this moment, so dates in assertions never drift. */
    public static final String START_TIME = System.getProperty("lab.startTime", "2026-09-15T09:00:00Z");

    /** How long to wait for asynchronous processing before a test fails. */
    public static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(Long.getLong("lab.asyncTimeoutSeconds", 5));

    private Config() {
    }
}

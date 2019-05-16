package com.aws.cfn.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class CronHelper {

    private final Clock clock;

    public CronHelper() {
        this.clock = Clock.systemUTC();
    }

    /**
     * This .ctor provided for testing
     */
    public CronHelper(final Clock clock) {
        this.clock = clock;
    }

    /**
     * Schedule a re-invocation of the executing handler no less than 1 minute from now
     *

    /**
     * Creates a cron(..) expression for a single instance at Now+minutesFromNow
     * NOTE: CloudWatchEvents only support a 1minute granularity for re-invoke
     * Anything less should be handled inside the original handler request
     *
     * @param minutesFromNow The number of minutes from now for building the cron expression
     * @return A cron expression for use with CloudWatchEvents putRule(..) API
     * @apiNote Expression is of form cron(minutes, hours, day-of-month, month, day-of-year, year) where
     * day-of-year is not necessary when the day-of-month and month-of-year fields are supplied
     */
    public String generateOneTimeCronExpression(final int minutesFromNow) {
        //Add additional 1 minute, since rule can be created after scheduled time, like "13:40:59" -> cron(41 13 30)
        final Instant instant = Instant.now(this.clock).plusSeconds(60 * (minutesFromNow + 1));
        final OffsetDateTime odt = instant.atOffset(ZoneOffset.UTC);

        return DateTimeFormatter.ofPattern("'cron('m H d M ? u')'").format(odt);
    }
}

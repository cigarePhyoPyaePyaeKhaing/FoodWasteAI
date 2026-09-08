package com.foodwasteai.util;

import java.time.*;

/** Business calendars use Yangon. Event LocalDateTime values represent UTC;
 * scheduled pickup LocalDateTime values represent Yangon wall time. */
public final class AppTime {
    public static final ZoneId APP_ZONE = ZoneId.of("Asia/Yangon");
    public static final Clock CLOCK = Clock.system(APP_ZONE);
    // MySQL named time-zone tables are not required. Myanmar uses UTC+06:30.
    public static final String SQL_TODAY = "DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+06:30'))";
    private AppTime() {}
    public static LocalDate today() { return today(CLOCK); }
    public static LocalDate today(Clock clock) { return LocalDate.now(clock.withZone(APP_ZONE)); }
    public static LocalDateTime now() { return LocalDateTime.now(CLOCK); }
    public static LocalDateTime utcNow() { return utcNow(CLOCK); }
    public static LocalDateTime utcNow(Clock clock) { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    public static LocalDateTime startOfDayUtc(LocalDate date) {
        return LocalDateTime.ofInstant(date.atStartOfDay(APP_ZONE).toInstant(), ZoneOffset.UTC);
    }
    public static LocalDate businessDate(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(APP_ZONE).toLocalDate();
    }
}

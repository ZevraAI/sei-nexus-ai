package com.sei.nexus.report;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/**
 * Computes the next scheduled run time for a report based on its schedule configuration.
 * All computation is done in the report's configured timezone.
 */
public final class ReportScheduleHelper {

    private static final Map<String, DayOfWeek> DAY_MAP = Map.of(
            "MON", DayOfWeek.MONDAY,
            "TUE", DayOfWeek.TUESDAY,
            "WED", DayOfWeek.WEDNESDAY,
            "THU", DayOfWeek.THURSDAY,
            "FRI", DayOfWeek.FRIDAY,
            "SAT", DayOfWeek.SATURDAY,
            "SUN", DayOfWeek.SUNDAY
    );

    private ReportScheduleHelper() {}

    /**
     * Computes the next Instant at which the report should run, always in the future.
     *
     * @param report    the report configuration
     * @param fromNow   the reference point (normally Instant.now())
     * @return an Instant strictly after fromNow
     */
    public static Instant computeNextRunAt(ScheduledReport report, Instant fromNow) {
        ZoneId zone = safeZone(report.timezone());
        ZonedDateTime now = ZonedDateTime.ofInstant(fromNow, zone);
        int[] hm = parseTime(report.scheduleTime());

        ZonedDateTime candidate = switch (normalise(report.scheduleType())) {
            case "DAILY" -> {
                ZonedDateTime d = now.withHour(hm[0]).withMinute(hm[1]).withSecond(0).withNano(0);
                yield d.isAfter(now) ? d : d.plusDays(1);
            }
            case "WEEKLY" -> {
                DayOfWeek dow = parseDow(report.scheduleDayOfWeek());
                ZonedDateTime d = now
                        .with(TemporalAdjusters.nextOrSame(dow))
                        .withHour(hm[0]).withMinute(hm[1]).withSecond(0).withNano(0);
                yield d.isAfter(now) ? d : d.plusWeeks(1);
            }
            case "MONTHLY" -> {
                int dom = report.scheduleDayOfMonth() != null ? report.scheduleDayOfMonth() : 1;
                int clamped = Math.min(dom, now.toLocalDate().lengthOfMonth());
                ZonedDateTime d = now.withDayOfMonth(clamped)
                        .withHour(hm[0]).withMinute(hm[1]).withSecond(0).withNano(0);
                if (d.isAfter(now)) yield d;
                // advance one month and re-clamp
                ZonedDateTime next = now.plusMonths(1);
                yield next.withDayOfMonth(Math.min(dom, next.toLocalDate().lengthOfMonth()))
                        .withHour(hm[0]).withMinute(hm[1]).withSecond(0).withNano(0);
            }
            default -> now.plusDays(1).withHour(hm[0]).withMinute(hm[1]).withSecond(0).withNano(0);
        };

        return candidate.toInstant();
    }

    /** Human-readable schedule description for display in the UI and reports. */
    public static String describe(ScheduledReport r) {
        String time = r.scheduleTime() != null ? r.scheduleTime() : "08:00";
        String tz   = r.timezone()     != null ? r.timezone()     : "UTC";
        return switch (normalise(r.scheduleType())) {
            case "DAILY"   -> "Every day at " + time + " " + tz;
            case "WEEKLY"  -> "Every " + titleCase(r.scheduleDayOfWeek()) + " at " + time + " " + tz;
            case "MONTHLY" -> "Monthly on day " + (r.scheduleDayOfMonth() != null ? r.scheduleDayOfMonth() : 1)
                              + " at " + time + " " + tz;
            default -> r.scheduleType();
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ZoneId safeZone(String tz) {
        try { return tz != null ? ZoneId.of(tz) : ZoneId.of("UTC"); }
        catch (Exception e) { return ZoneId.of("UTC"); }
    }

    private static int[] parseTime(String time) {
        if (time == null || !time.contains(":")) return new int[]{8, 0};
        String[] p = time.split(":");
        try { return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])}; }
        catch (NumberFormatException e) { return new int[]{8, 0}; }
    }

    private static DayOfWeek parseDow(String dow) {
        if (dow == null) return DayOfWeek.MONDAY;
        DayOfWeek d = DAY_MAP.get(dow.toUpperCase().substring(0, Math.min(3, dow.length())));
        return d != null ? d : DayOfWeek.MONDAY;
    }

    private static String normalise(String s) {
        return s != null ? s.toUpperCase() : "WEEKLY";
    }

    private static String titleCase(String s) {
        if (s == null || s.isBlank()) return "Monday";
        String full = Map.of(
                "MON","Monday","TUE","Tuesday","WED","Wednesday",
                "THU","Thursday","FRI","Friday","SAT","Saturday","SUN","Sunday"
        ).get(s.toUpperCase().substring(0, Math.min(3, s.length())));
        return full != null ? full : s;
    }
}

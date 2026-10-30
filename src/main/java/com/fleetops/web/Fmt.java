package com.fleetops.web;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fleetops.common.geo.GridReference;
import com.fleetops.common.time.OperationalClock;

/** Display formatting for templates, in the operation's time zone: {@code ${@fmt.time(trip.plannedStart)}}. */
@Component("fmt")
public class Fmt {

    private final ZoneId zone;
    private final OperationalClock clock;
    private final DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private final DateTimeFormatter dayTime = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);
    private final DateTimeFormatter day = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private final DateTimeFormatter shortDay = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private final DateTimeFormatter inputDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ENGLISH);

    public Fmt(@Value("${fleet.display-zone}") String zone, OperationalClock clock) {
        this.zone = ZoneId.of(zone);
        this.clock = clock;
    }

    public ZoneId zone() {
        return zone;
    }

    public String time(Instant i) {
        return i == null ? "—" : time.format(i.atZone(zone));
    }

    /** Time only when the instant is today, otherwise date and time. */
    public String when(Instant i) {
        if (i == null) {
            return "—";
        }
        LocalDate d = i.atZone(zone).toLocalDate();
        return d.equals(today()) ? time(i) : dayTime.format(i.atZone(zone));
    }

    public String dateTime(Instant i) {
        return i == null ? "—" : dayTime.format(i.atZone(zone));
    }

    public String day(Instant i) {
        return i == null ? "—" : day.format(i.atZone(zone));
    }

    public String day(LocalDate d) {
        return d == null ? "—" : day.format(d);
    }

    public String shortDay(LocalDate d) {
        return d == null ? "—" : shortDay.format(d);
    }

    public String inputValue(Instant i) {
        return i == null ? "" : inputDateTime.format(i.atZone(zone));
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.now(), zone);
    }

    public String ago(Instant i) {
        if (i == null) {
            return "—";
        }
        long s = Duration.between(i, clock.now()).toSeconds();
        if (s < 60) {
            return "just now";
        }
        if (s < 3600) {
            return (s / 60) + " min ago";
        }
        if (s < 86_400) {
            return (s / 3600) + " h ago";
        }
        return dateTime(i);
    }

    public String duration(Duration d) {
        if (d == null) {
            return "—";
        }
        long m = d.toMinutes();
        return m < 60 ? m + " min" : (m / 60) + " h " + String.format("%02d", m % 60) + " min";
    }

    public String between(Instant a, Instant b) {
        return a == null || b == null ? "—" : duration(Duration.between(a, b));
    }

    /** Signed minutes: "+12 min", "−3 min", "on plan". */
    public String delta(Integer minutes) {
        if (minutes == null) {
            return "—";
        }
        if (minutes == 0) {
            return "on plan";
        }
        return (minutes > 0 ? "+" : "−") + Math.abs(minutes) + " min";
    }

    public long minutesBetween(Instant from, Instant to) {
        return from == null || to == null ? 0 : Duration.between(from, to).toMinutes();
    }

    public String km(Number meters) {
        return meters == null ? "—" : String.format(Locale.ENGLISH, "%.1f km", meters.doubleValue() / 1000);
    }

    public String grid(Double lat, Double lng) {
        return lat == null || lng == null ? "—" : GridReference.of(lat, lng);
    }

    public String pct(double v) {
        return String.format(Locale.ENGLISH, "%.1f%%", v);
    }

    public String one(double v) {
        return String.format(Locale.ENGLISH, "%.1f", v);
    }

    /** Stored risk explanations are lines like "+40 Projected late — FL41 projected 25 min late". */
    public java.util.List<String[]> reasonLines(String text) {
        if (text == null || text.isBlank()) {
            return java.util.List.of();
        }
        return text.lines().filter(l -> !l.isBlank()).map(l -> {
            int space = l.indexOf(' ');
            return space > 0 ? new String[] {l.substring(0, space), l.substring(space + 1)} : new String[] {"", l};
        }).toList();
    }

    /** A duration ratio (actual / planned) as a signed percentage: 1.12 -> "+12.0%". */
    public String ratioDelta(Object ratio) {
        if (ratio == null) {
            return "—";
        }
        double r = ((Number) ratio).doubleValue();
        double pct = (r - 1) * 100;
        return (pct >= 0 ? "+" : "−") + String.format(Locale.ENGLISH, "%.1f%%", Math.abs(pct));
    }

    /** "OUT_FOR_DELIVERY" -> "Out for delivery". */
    public String humanize(String constant) {
        if (constant == null || constant.isBlank()) {
            return "—";
        }
        String s = constant.replace('_', ' ').toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public String firstLine(String text) {
        return text == null ? "" : text.lines().findFirst().orElse("");
    }
}

package ua.com.dtek.scraper.dto;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public record TimeInterval(
        String startTime,
        String endTime
) implements Comparable<TimeInterval> {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public int compareTo(TimeInterval other) {
        LocalTime thisStart = LocalTime.parse(this.startTime, TIME_FORMATTER);
        LocalTime otherStart = LocalTime.parse(other.startTime, TIME_FORMATTER);
        return thisStart.compareTo(otherStart);
    }

    public boolean isAdjacentTo(TimeInterval other) {
        return Objects.equals(this.endTime, other.startTime);
    }

    public TimeInterval mergeWith(TimeInterval other) {
        return new TimeInterval(this.startTime, other.endTime);
    }

    @Override
    public String toString() {
        return String.format("Power OFF: %s - %s", startTime, endTime);
    }
}
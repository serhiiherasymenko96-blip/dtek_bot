package ua.com.dtek.scraper.dto;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * DTO (Data Transfer Object) to represent a time interval.
 * Implements Comparable to allow sorting by start time, which is
 * essential for the "merge intervals" logic.
 *
 * @param startTime The start of the interval (e.g., "14:30")
 * @param endTime   The end of the interval (e.g., "15:00")
 */
public record TimeInterval(
        String startTime,
        String endTime
) implements Comparable<TimeInterval> {

    // Formatter for easy parsing of "HH:mm"
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Comparison logic to sort intervals by their start time.
     */
    @Override
    public int compareTo(TimeInterval other) {
        LocalTime thisStart = LocalTime.parse(this.startTime, TIME_FORMATTER);
        LocalTime otherStart = LocalTime.parse(other.startTime, TIME_FORMATTER);
        return thisStart.compareTo(otherStart);
    }

    /**
     * Checks if two intervals are perfectly adjacent (e.g., one ends at "15:00"
     * and the next one starts at "15:00").
     *
     * @param other The next time interval.
     * @return true if adjacent, false otherwise.
     */
    public boolean isAdjacentTo(TimeInterval other) {
        return Objects.equals(this.endTime, other.startTime);
    }

    /**
     * Merges this interval with an adjacent one.
     * (e.g., ["14:00", "15:00"] merged with ["15:00", "16:00"] becomes ["14:00", "16:00"])
     *
     * @param other The adjacent interval to merge with.
     * @return A new, combined TimeInterval.
     */
    public TimeInterval mergeWith(TimeInterval other) {
        return new TimeInterval(this.startTime, other.endTime);
    }

    /**
     * Returns a human-readable string representation.
     */
    @Override
    public String toString() {
        return String.format("Power OFF: %s - %s", startTime, endTime);
    }
}
package ua.com.dtek.scraper.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ua.com.dtek.scraper.dto.TimeInterval;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Handles the business logic of parsing the DTEK schedule table HTML.
 * <p>
 * This class is decoupled from Selenide and works purely with HTML strings,
 * making it easy to test independently.
 * <p>
 * v4.0.0 Update: This parser now returns a List of TimeInterval objects
 * and includes logic to merge adjacent intervals.
 */
public class ScheduleParser {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Parses the HTML content of a schedule table into a list of TimeInterval objects.
     *
     * @param tableHtml The inner HTML of the '.discon-fact-table.active' div.
     * @return A sorted, merged list of TimeInterval objects representing outages.
     */
    public List<TimeInterval> parse(String tableHtml) {
        System.out.println("--- Parsing table HTML ---");
        Document doc = Jsoup.parse(tableHtml);

        Elements cells = doc.select("tbody td[class^=cell-]");
        Elements headers = doc.select("thead th[scope=col] div");

        if (cells.size() != headers.size()) {
            System.err.println("Error: Mismatch in cell/header count. " +
                    "Cells: " + cells.size() + ", Headers: " + headers.size());
            // Return empty list on error, service will handle it
            return Collections.emptyList();
        }

        List<TimeInterval> intervals = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            Element cell = cells.get(i);
            String timeSlot = headers.get(i).text(); // e.g., "00-01"
            String className = cell.className();

            TimeInterval interval = parseCellClass(className, timeSlot);
            if (interval != null) {
                intervals.add(interval);
            }
        }

        // Sort intervals by start time (required for merging)
        Collections.sort(intervals);

        System.out.println("--- Parsing complete, merging " + intervals.size() + " intervals... ---");
        return mergeIntervals(intervals);
    }

    /**
     * Merges a sorted list of adjacent or overlapping time intervals.
     *
     * @param intervals A sorted list of TimeIntervals.
     * @return A new list of merged TimeIntervals.
     */
    private List<TimeInterval> mergeIntervals(List<TimeInterval> intervals) {
        if (intervals.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedList<TimeInterval> merged = new LinkedList<>();

        for (TimeInterval interval : intervals) {
            // If the list is empty or the current interval does not touch the previous one
            if (merged.isEmpty() || !merged.getLast().isAdjacentTo(interval)) {
                merged.add(interval);
            } else {
                // Merge the current interval with the last one in the list
                TimeInterval last = merged.removeLast();
                merged.add(last.mergeWith(interval));
            }
        }

        return merged;
    }

    /**
     * Helper method to interpret the CSS class of a table cell.
     *
     * @param className The CSS class of the cell (e.g., "cell-scheduled").
     * @param timeSlot  The time slot from the header (e.g., "14-15").
     * @return A TimeInterval object, or null if there is power.
     */
    private TimeInterval parseCellClass(String className, String timeSlot) {
        String[] timeParts = timeSlot.split("-");

        // --- Robustness Fix ---
        if (timeParts.length < 2) {
            System.err.println("Warning: Skipping cell with unexpected timeSlot format: '" + timeSlot + "'");
            return null; // Skip this cell
        }
        // --- End Fix ---

        String startTimeStr = timeParts[0].length() == 1 ? "0" + timeParts[0] : timeParts[0];
        String endTimeStr = timeParts[1].length() == 1 ? "0" + timeParts[1] : timeParts[1];

        if (className.contains("cell-non-scheduled")) {
            return null;
        } else if (className.contains("cell-scheduled")) {
            return new TimeInterval(startTimeStr + ":00", endTimeStr + ":00");
        } else if (className.contains("cell-first-half")) {
            return new TimeInterval(startTimeStr + ":00", startTimeStr + ":30");
        } else if (className.contains("cell-second-half")) {
            return new TimeInterval(startTimeStr + ":30", endTimeStr + ":00");
        }
        return null; // Unknown or unhandled status
    }

    /**
     * Finds shutdowns that are starting within the next 30-40 minutes.
     *
     * @param schedule The list of time intervals for the day.
     * @param now      The current time.
     * @return A list of TimeIntervals that are about to start.
     */
    public List<TimeInterval> findUpcomingShutdowns(List<TimeInterval> schedule, LocalTime now) {
        List<TimeInterval> upcoming = new ArrayList<>();

        // Define the "warning window"
        LocalTime warningWindowStart = now.plusMinutes(29); // e.g., 14:00 -> 14:29
        LocalTime warningWindowEnd = now.plusMinutes(40);   // e.g., 14:00 -> 14:40
        // We check for shutdowns starting 14:30

        for (TimeInterval interval : schedule) {
            try {
                LocalTime startTime = LocalTime.parse(interval.startTime(), TIME_FORMATTER);

                // Check if the shutdown start time falls *within* our 11-minute warning window
                // (e.g., is 14:30 after 14:29 AND before 14:40?)
                if (startTime.isAfter(warningWindowStart) && startTime.isBefore(warningWindowEnd)) {
                    upcoming.add(interval);
                }
            } catch (Exception e) {
                System.err.println("Error parsing time in findUpcomingShutdowns: " + interval.startTime());
            }
        }
        return upcoming;
    }
}
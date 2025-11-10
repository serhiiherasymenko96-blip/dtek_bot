package ua.com.dtek.scraper.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ua.com.dtek.scraper.dto.TimeInterval;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the business logic of parsing the DTEK schedule table HTML
 * and merging outage intervals.
 */
public class ScheduleParser {

    // Formatter for "HH:mm" (e.g., "14:30")
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Parses the HTML content of a schedule table into a merged list of TimeIntervals.
     *
     * @param tableHtml The inner HTML of the '.discon-fact-table.active' div.
     * @return A merged list of TimeIntervals.
     * @throws RuntimeException if a parsing error occurs (e.g., table structure mismatch).
     */
    public List<TimeInterval> parse(String tableHtml) {
        System.out.println("--- Parsing table HTML ---");
        Document doc = Jsoup.parse(tableHtml);

        Elements cells = doc.select("tbody td[class^=cell-]");
        Elements headers = doc.select("thead th[scope=col] div");

        // --- BUG FIX (v4.0.2) ---
        // Previously, this returned an empty list, causing silent failure.
        // Now, it throws an exception, which NotificationService will catch.
        if (cells.size() != headers.size()) {
            String errorMessage = "Parser Error: Mismatch in cell/header count. " +
                    "Cells: " + cells.size() + ", Headers: " + headers.size() + ". " +
                    "The website HTML structure has likely changed.";
            System.err.println(errorMessage);
            // This exception will be caught by NotificationService,
            // preventing a false notification from being sent.
            throw new RuntimeException(errorMessage);
        }
        // --- END BUG FIX ---

        if (cells.isEmpty()) {
            // This is not an error, it just means the table is empty (e.g., for "tomorrow")
            System.out.println("--- Parsing complete: Table is empty. ---");
            return List.of(); // Return an empty list
        }

        List<TimeInterval> outageIntervals = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            Element cell = cells.get(i);
            String timeSlot = headers.get(i).text(); // e.g., "00-01"
            String className = cell.className();

            TimeInterval interval = parseCellClass(className, timeSlot);
            if (interval != null) {
                outageIntervals.add(interval);
            }
        }

        if (outageIntervals.isEmpty()) {
            System.out.println("--- Parsing complete: No outages found. ---");
            return List.of(); // Return empty list
        }

        // Sort intervals by start time. This is crucial for merging.
        outageIntervals.sort(Comparator.comparing(TimeInterval::startTime));

        // Merge adjacent intervals
        List<TimeInterval> mergedIntervals = mergeIntervals(outageIntervals);

        System.out.println("--- Parsing complete: Found " + mergedIntervals.size() + " merged intervals. ---");
        return mergedIntervals;
    }

    /**
     * Finds shutdowns that are starting within the next 30-40 minutes.
     *
     * @param schedule The list of time intervals for the day.
     * @return A list of intervals that are about to start.
     */
    public List<TimeInterval> findUpcomingShutdowns(List<TimeInterval> schedule) {
        // Get the current time in "HH:mm" format (e.g., "14:25")
        // We use the system default time zone (assuming server is in EET/EEST)
        LocalTime now = LocalTime.now();

        // We check for outages starting between now+30 and now+40 minutes.
        // This gives a 10-minute window for the notification to be processed and seen.
        LocalTime warningStartTime = now.plusMinutes(30);
        LocalTime warningEndTime = now.plusMinutes(40);

        return schedule.stream()
                .filter(interval -> {
                    LocalTime intervalStart = LocalTime.parse(interval.startTime(), TIME_FORMATTER);
                    // Check if the interval start time is within our 10-minute warning window
                    return !intervalStart.isBefore(warningStartTime) && intervalStart.isBefore(warningEndTime);
                })
                .collect(Collectors.toList());
    }


    /**
     * Merges a sorted list of time intervals.
     * E.g., [14:30-15:00, 15:00-16:00] becomes [14:30-16:00].
     *
     * @param intervals A list of TimeIntervals, MUST be sorted by start time.
     * @return A new list with merged intervals.
     */
    private List<TimeInterval> mergeIntervals(List<TimeInterval> intervals) {
        if (intervals.isEmpty()) {
            return new ArrayList<>();
        }

        LinkedList<TimeInterval> merged = new LinkedList<>();
        merged.add(intervals.get(0));

        for (int i = 1; i < intervals.size(); i++) {
            TimeInterval current = intervals.get(i);
            TimeInterval last = merged.getLast();

            // Check if the end time of the last interval matches the start time of the current one
            if (last.endTime().equals(current.startTime())) {
                // Merge: Update the end time of the 'last' interval in the merged list
                merged.set(merged.size() - 1, new TimeInterval(last.startTime(), current.endTime()));
            } else {
                // No overlap, add the current interval as a new one
                merged.add(current);
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
            return null; // Treat as power ON
        }
        // --- End Fix ---

        String startTime = timeParts[0].length() == 1 ? "0" + timeParts[0] : timeParts[0];
        String endTime = timeParts[1].length() == 1 ? "0" + timeParts[1] : timeParts[1];

        if (className.contains("cell-non-scheduled")) {
            return null; // Power is ON
        } else if (className.contains("cell-scheduled")) {
            // "14-15" -> 14:00 - 15:00
            return new TimeInterval(startTime + ":00", endTime + ":00");
        } else if (className.contains("cell-first-half")) {
            // "16-17" -> 16:00 - 16:30
            return new TimeInterval(startTime + ":00", startTime + ":30");
        } else if (className.contains("cell-second-half")) {
            // "14-15" -> 14:30 - 15:00
            return new TimeInterval(startTime + ":30", endTime + ":00");
        }

        // Unknown class
        System.err.println("Warning: Unknown cell class found: '" + className + "'");
        return null; // Treat unknown as Power ON
    }
}
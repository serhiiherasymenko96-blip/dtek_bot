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
 * Parser for DTEK electricity outage schedules.
 * Extracts time intervals from HTML tables and processes them into a list of outage periods.
 * 
 * @version 8.0.0
 */
public class ScheduleParser {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Parses HTML table content into a list of time intervals representing outage periods.
     * 
     * @param tableHtml The HTML content of the schedule table
     * @return A sorted list of time intervals representing outage periods
     * @throws RuntimeException If there's a mismatch between cells and headers
     */
    public List<TimeInterval> parse(String tableHtml) {
        System.out.println("[DEBUG_LOG] ScheduleParser.parse: Starting HTML table parsing");
        Document doc = Jsoup.parse(tableHtml);

        Elements cells = doc.select("tbody td[class^=cell-]");
        Elements headers = doc.select("thead th[scope=col] div");

        System.out.println("[DEBUG_LOG] ScheduleParser.parse: Found " + cells.size() + " cells and " + headers.size() + " headers");

        if (cells.size() != headers.size()) {
            String msg = "Parser Error: Mismatch in cell/header count. Cells: " + cells.size() + ", Headers: " + headers.size();
            System.err.println("[DEBUG_LOG] ScheduleParser.parse: " + msg);
            throw new RuntimeException(msg);
        }

        if (cells.isEmpty()) {
            System.out.println("[DEBUG_LOG] ScheduleParser.parse: No cells found, returning empty list");
            return new ArrayList<>();
        }

        List<TimeInterval> outageIntervals = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            Element cell = cells.get(i);
            String timeSlot = headers.get(i).text();
            String className = cell.className();

            System.out.println("[DEBUG_LOG] ScheduleParser.parse: Processing cell #" + i + " - timeSlot: " + timeSlot + ", className: " + className);

            TimeInterval interval = parseCellClass(className, timeSlot);
            if (interval != null) {
                System.out.println("[DEBUG_LOG] ScheduleParser.parse: Added outage interval: " + interval.startTime() + " - " + interval.endTime());
                outageIntervals.add(interval);
            }
        }

        System.out.println("[DEBUG_LOG] ScheduleParser.parse: Total raw outage intervals before merging: " + outageIntervals.size());

        // No need to sort before merging as we'll sort after
        List<TimeInterval> mergedIntervals = mergeIntervals(outageIntervals);

        // Sort after merging to ensure consistency
        mergedIntervals.sort(Comparator.comparing(TimeInterval::startTime));

        System.out.println("[DEBUG_LOG] ScheduleParser.parse: Parsing complete. Found " + mergedIntervals.size() + " merged intervals.");
        return mergedIntervals;
    }

    /**
     * Finds upcoming shutdowns that are scheduled to start in the next 30-40 minutes.
     * Used for sending pre-shutdown warning notifications to users.
     * 
     * @param schedule The list of time intervals to check
     * @return A list of time intervals that are scheduled to start in the next 30-40 minutes
     */
    public List<TimeInterval> findUpcomingShutdowns(List<TimeInterval> schedule) {
        LocalTime now = LocalTime.now(java.time.ZoneId.of("Europe/Kyiv"));
        LocalTime warningStartTime = now.plusMinutes(30);
        LocalTime warningEndTime = now.plusMinutes(40);

        System.out.println("[DEBUG_LOG] ScheduleParser.findUpcomingShutdowns: Current time (Kyiv): " + now.format(TIME_FORMATTER));
        System.out.println("[DEBUG_LOG] ScheduleParser.findUpcomingShutdowns: Warning window: " + warningStartTime.format(TIME_FORMATTER) + " - " + warningEndTime.format(TIME_FORMATTER));
        System.out.println("[DEBUG_LOG] ScheduleParser.findUpcomingShutdowns: Checking " + schedule.size() + " intervals");

        List<TimeInterval> upcomingShutdowns = schedule.stream()
                .filter(interval -> {
                    try {
                        LocalTime start = LocalTime.parse(interval.startTime(), TIME_FORMATTER);
                        boolean isUpcoming = !start.isBefore(warningStartTime) && start.isBefore(warningEndTime);
                        if (isUpcoming) {
                            System.out.println("[DEBUG_LOG] ScheduleParser.findUpcomingShutdowns: Found upcoming shutdown: " + interval.startTime() + " - " + interval.endTime());
                        }
                        return isUpcoming;
                    } catch (Exception e) { 
                        System.err.println("[DEBUG_LOG] ScheduleParser.findUpcomingShutdowns: Error parsing interval " + interval.startTime() + ": " + e.getMessage());
                        return false; 
                    }
                })
                .collect(Collectors.toList());

        System.out.println("[DEBUG_LOG] ScheduleParser.findUpcomingShutdowns: Found " + upcomingShutdowns.size() + " upcoming shutdowns");
        return upcomingShutdowns;
    }

    /**
     * Merges adjacent time intervals into continuous periods.
     * For example, 10:00-11:00 and 11:00-12:00 would be merged into 10:00-12:00.
     * 
     * @param intervals The list of time intervals to merge
     * @return A list of merged time intervals
     */
    private List<TimeInterval> mergeIntervals(List<TimeInterval> intervals) {
        System.out.println("[DEBUG_LOG] ScheduleParser.mergeIntervals: Starting merge of " + intervals.size() + " intervals");
        if (intervals.isEmpty()) {
            System.out.println("[DEBUG_LOG] ScheduleParser.mergeIntervals: No intervals to merge, returning empty list");
            return new ArrayList<>();
        }
        LinkedList<TimeInterval> merged = new LinkedList<>();
        merged.add(intervals.get(0));
        System.out.println("[DEBUG_LOG] ScheduleParser.mergeIntervals: Initial interval: " + intervals.get(0).startTime() + " - " + intervals.get(0).endTime());
        
        int mergeCount = 0;
        for (int i = 1; i < intervals.size(); i++) {
            TimeInterval current = intervals.get(i);
            TimeInterval last = merged.getLast();
            if (last.endTime().equals(current.startTime())) {
                TimeInterval mergedInterval = new TimeInterval(last.startTime(), current.endTime());
                merged.set(merged.size() - 1, mergedInterval);
                mergeCount++;
                System.out.println("[DEBUG_LOG] ScheduleParser.mergeIntervals: Merged [" + last.startTime() + " - " + last.endTime() + "] + [" + current.startTime() + " - " + current.endTime() + "] -> [" + mergedInterval.startTime() + " - " + mergedInterval.endTime() + "]");
            } else {
                merged.add(current);
                System.out.println("[DEBUG_LOG] ScheduleParser.mergeIntervals: Added separate interval: " + current.startTime() + " - " + current.endTime());
            }
        }
        System.out.println("[DEBUG_LOG] ScheduleParser.mergeIntervals: Merge complete. Performed " + mergeCount + " merges. Result: " + merged.size() + " intervals");
        return merged;
    }

    /**
     * Parses a cell's class name and time slot to determine if it represents an outage period.
     * Different cell classes represent different types of outages (full hour, first half, second half).
     * 
     * @param className The CSS class name of the cell
     * @param timeSlot The time slot string (e.g., "10-11")
     * @return A TimeInterval object if the cell represents an outage, null otherwise
     */
    private TimeInterval parseCellClass(String className, String timeSlot) {
        String[] timeParts = timeSlot.split("-");
        if (timeParts.length < 2) return null;

        // ANTI-SPAM: Parse to int to remove spaces
        String startTimeStr, endTimeStr, halfHourStr;
        try {
            int start = Integer.parseInt(timeParts[0].trim());
            int end = Integer.parseInt(timeParts[1].trim());
            startTimeStr = String.format("%02d:00", start);
            endTimeStr = String.format("%02d:00", end);
            halfHourStr = String.format("%02d:30", start);
        } catch (NumberFormatException e) {
            System.err.println("Warning: Could not parse time: " + timeSlot);
            return null;
        }

        if (className.contains("cell-non-scheduled")) return null;
        if (className.contains("cell-scheduled")) return new TimeInterval(startTimeStr, endTimeStr);
        if (className.contains("cell-first-half")) return new TimeInterval(startTimeStr, halfHourStr);
        if (className.contains("cell-second-half")) return new TimeInterval(halfHourStr, endTimeStr);
        return null;
    }
}

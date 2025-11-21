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

public class ScheduleParser {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public List<TimeInterval> parse(String tableHtml) {
        System.out.println("--- Parsing table HTML ---");
        Document doc = Jsoup.parse(tableHtml);

        Elements cells = doc.select("tbody td[class^=cell-]");
        Elements headers = doc.select("thead th[scope=col] div");

        if (cells.size() != headers.size()) {
            String msg = "Parser Error: Mismatch in cell/header count.";
            System.err.println(msg);
            throw new RuntimeException(msg);
        }

        if (cells.isEmpty()) return new ArrayList<>();

        List<TimeInterval> outageIntervals = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            Element cell = cells.get(i);
            String timeSlot = headers.get(i).text();
            String className = cell.className();

            TimeInterval interval = parseCellClass(className, timeSlot);
            if (interval != null) {
                outageIntervals.add(interval);
            }
        }

        // ANTI-SPAM: Sort intervals to ensure consistency
        outageIntervals.sort(Comparator.comparing(TimeInterval::startTime));

        List<TimeInterval> mergedIntervals = mergeIntervals(outageIntervals);

        // Sort again after merging
        mergedIntervals.sort(Comparator.comparing(TimeInterval::startTime));

        System.out.println("--- Parsing complete: Found " + mergedIntervals.size() + " intervals. ---");
        return mergedIntervals;
    }

    public List<TimeInterval> findUpcomingShutdowns(List<TimeInterval> schedule) {
        LocalTime now = LocalTime.now(java.time.ZoneId.of("Europe/Kyiv"));
        LocalTime warningStartTime = now.plusMinutes(30);
        LocalTime warningEndTime = now.plusMinutes(40);

        return schedule.stream()
                .filter(interval -> {
                    try {
                        LocalTime start = LocalTime.parse(interval.startTime(), TIME_FORMATTER);
                        return !start.isBefore(warningStartTime) && start.isBefore(warningEndTime);
                    } catch (Exception e) { return false; }
                })
                .collect(Collectors.toList());
    }

    private List<TimeInterval> mergeIntervals(List<TimeInterval> intervals) {
        if (intervals.isEmpty()) return new ArrayList<>();
        LinkedList<TimeInterval> merged = new LinkedList<>();
        merged.add(intervals.get(0));
        for (int i = 1; i < intervals.size(); i++) {
            TimeInterval current = intervals.get(i);
            TimeInterval last = merged.getLast();
            if (last.endTime().equals(current.startTime())) {
                merged.set(merged.size() - 1, new TimeInterval(last.startTime(), current.endTime()));
            } else {
                merged.add(current);
            }
        }
        return merged;
    }

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
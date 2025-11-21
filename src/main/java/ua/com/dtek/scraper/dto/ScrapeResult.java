package ua.com.dtek.scraper.dto;

import java.util.List;

/**
 * Результат скрейпінгу: назва групи та графік.
 */
public record ScrapeResult(
        String groupName,
        List<TimeInterval> schedule
) {
}
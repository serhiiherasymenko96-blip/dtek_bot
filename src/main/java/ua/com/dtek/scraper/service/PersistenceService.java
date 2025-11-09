package ua.com.dtek.scraper.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles persistence of the schedule state.
 * <p>
 * This service is responsible for saving the last known schedule to a cache file
 * and loading it for comparison, allowing the application to detect changes.
 */
public class PersistenceService {

    /**
     * The file used to cache the last seen schedule.
     * It will be created in the directory where the application is run.
     */
    private static final Path CACHE_FILE = Paths.get("schedule.cache");

    /**
     * Saves the provided schedule list to the cache file.
     *
     * @param schedule The list of schedule strings to save.
     */
    public void saveSchedule(List<String> schedule) {
        System.out.println("Saving new schedule to cache file: " + CACHE_FILE.toAbsolutePath());
        try {
            Files.write(CACHE_FILE, schedule, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("CRITICAL: Failed to write to cache file " + CACHE_FILE + ": " + e.getMessage());
        }
    }

    /**
     * Loads the last known schedule from the cache file.
     *
     * @return A list of strings representing the cached schedule, or an empty list if no cache exists.
     */
    public List<String> loadLastSchedule() {
        System.out.println("Loading last schedule from cache file: " + CACHE_FILE.toAbsolutePath());
        try {
            return Files.readAllLines(CACHE_FILE, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            System.out.println("Cache file not found. This must be the first run.");
            return Collections.emptyList();
        } catch (IOException e) {
            System.err.println("Warning: Failed to read from cache file " + CACHE_FILE + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Compares two schedules to see if they are different.
     * This comparison is order-independent (uses Sets).
     *
     * @param oldSchedule The cached schedule.
     * @param newSchedule The newly scraped schedule.
     * @return true if the schedules are different, false otherwise.
     */
    public boolean areSchedulesDifferent(List<String> oldSchedule, List<String> newSchedule) {
        // Use Sets for order-independent comparison
        Set<String> oldSet = new HashSet<>(oldSchedule);
        Set<String> newSet = new HashSet<>(newSchedule);

        return !oldSet.equals(newSet);
    }
}
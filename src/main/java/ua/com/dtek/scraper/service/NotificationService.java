package ua.com.dtek.scraper.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ua.com.dtek.scraper.DtekScraperBot;
import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.TimeInterval;
import ua.com.dtek.scraper.parser.ScheduleParser;

import java.lang.reflect.Type;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.codeborne.selenide.Selenide.closeWebDriver;

/**
 * Handles all background monitoring tasks.
 *
 * @version 5.1.0 (Fixes concurrency crash on e2-micro)
 */
public class NotificationService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final DatabaseService dbService;
    private final DtekScraperService scraperService;
    private final ScheduleParser scheduleParser;
    private final Map<String, Address> monitoredAddresses;
    private final Gson gson = new Gson();
    private final Type scheduleListType = new TypeToken<List<TimeInterval>>() {}.getType();

    // --- (FIX v5.1.0) ---
    // Use a SINGLE-THREAD scheduler for ALL tasks (hourly, pre-warn, force-check)
    // This serializes all scraping tasks and prevents multiple Firefox instances
    // from running at the same time and crashing the e2-micro server.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    // --- END FIX ---

    private DtekScraperBot bot;

    public NotificationService(DatabaseService dbService,
                               DtekScraperService scraperService,
                               ScheduleParser parser,
                               Map<String, Address> addresses) {
        this.dbService = dbService;
        this.scraperService = scraperService;
        this.scheduleParser = parser;
        this.monitoredAddresses = addresses;
    }

    public void setBot(DtekScraperBot bot) {
        this.bot = bot;
    }

    public void startMonitoring() {
        System.out.println("Starting background monitoring tasks...");

        // Task 1: Check for schedule changes (every 30 minutes)
        scheduler.scheduleAtFixedRate(this::runFullScheduleCheck, 2, 30, TimeUnit.MINUTES);

        // Task 2: Check for pre-shutdown warnings (every 10 minutes)
        scheduler.scheduleAtFixedRate(this::runPreShutdownWarningCheck, 1, 10, TimeUnit.MINUTES);
    }

    /**
     * (v5.0.0) Public method to queue a check for a single address,
     * typically triggered by a new user subscription.
     *
     * @param addressKey   The address to check.
     * @param notifyChatId The user who triggered this check and needs to be notified.
     */
    public void forceCheckAddress(String addressKey, long notifyChatId) {
        System.out.println("Triggering async check for " + addressKey + " for user " + notifyChatId);

        // --- (FIX v5.1.0) ---
        // Submit the task to the SAME single-thread scheduler.
        // It will run as soon as the current task (if any) is finished.
        scheduler.submit(() -> {
            Address address = monitoredAddresses.get(addressKey);
            if (address != null) {
                runCheckForSingleAddress(addressKey, address, notifyChatId);
            }
        });
        // --- END FIX ---
    }

    /**
     * Task 1: Runs every 30 minutes to check ALL monitored addresses.
     */
    private void runFullScheduleCheck() {
        System.out.println("\n[SCHEDULE CHECK] Running full schedule change check...");

        try {
            for (Map.Entry<String, Address> entry : monitoredAddresses.entrySet()) {
                // Run the check, notifying NO specific user (notifyChatId = 0)
                runCheckForSingleAddress(entry.getKey(), entry.getValue(), 0);
            }
        } finally {
            // --- MEMORY LEAK FIX (v4.3.0) ---
            closeWebDriver();
            System.out.println("[SCHEDULE CHECK] Browser closed. Resources freed.");
            // --- END FIX ---
        }
    }

    /**
     * This is the core scraping logic, now centralized.
     * It checks one address, compares it to the cache, and notifies relevant users.
     *
     * @param addressKey   The ID of the address (e.g., "address.1")
     * @param address      The Address DTO
     * @param notifyChatId The single user to notify (or 0 if it's a general check)
     */
    private void runCheckForSingleAddress(String addressKey, Address address, long notifyChatId) {
        System.out.println("[CHECK] Checking address: " + address.name());
        List<TimeInterval> newSchedule = null;
        List<TimeInterval> oldSchedule = null; // (v5.0.2 fix)
        boolean scrapeSuccess = false;
        boolean hasChanged = false; // (v5.0.2 fix)

        try {
            // 1. Get the new schedule from the website
            newSchedule = scraperService.getShutdownSchedule(
                    address.city(), address.street(), address.houseNum()
            );
            scrapeSuccess = true;

            // 2. Get the old schedule from our database
            String oldScheduleJson = dbService.getSchedule(addressKey);
            oldSchedule = (oldScheduleJson == null)
                    ? Collections.emptyList()
                    : gson.fromJson(oldScheduleJson, scheduleListType);

            // 3. Compare
            hasChanged = !oldSchedule.equals(newSchedule);

            if (hasChanged) {
                System.out.println("[CHECK] CHANGES DETECTED for " + address.name());

                // 4. Save the new schedule
                dbService.saveSchedule(addressKey, newSchedule);

                // 5. Notify ALL subscribed users
                notifyUsersOfChange(addressKey, address.name(), newSchedule, oldSchedule);

                // 6. Clear old warning flags
                dbService.clearWarnedFlags(addressKey);

            } else {
                System.out.println("[CHECK] No changes for " + address.name());
            }

        } catch (RuntimeException e) {
            // This catches the RuntimeException from ScheduleParser (v4.0.2)
            // or Selenide errors
            System.err.println("[CHECK] CRITICAL FAILURE for " + address.name() + ": " + e.getMessage());
            // We DO NOT send a notification and DO NOT update the cache
        }

        // --- (FIX v5.0.2) ---
        // If this was a "force check" (triggered by a user),
        // AND the scrape was successful,
        // AND this wasn't a "change" notification (which already sent a message),
        // we must send the current schedule to the user who asked.
        if (notifyChatId != 0 && scrapeSuccess && !hasChanged) {
            System.out.println("[FORCE CHECK] Sending current (unchanged) schedule to user " + notifyChatId);
            String scheduleString = formatSchedule(newSchedule);
            String message = "💡 *Поточний графік для " + address.name() + ":*\n\n" + scheduleString;
            bot.sendMessage(notifyChatId, message);
        }

        // --- (FIX v5.1.0) ---
        // If this was a "force check", we must close the browser *now*
        // because the full check (which also closes) might be 30 mins away.
        if (notifyChatId != 0) {
            closeWebDriver();
            System.out.println("[FORCE CHECK] Browser closed. Resources freed for user " + notifyChatId);
        }
        // --- END FIX ---
    }

    /**
     * Helper to format and send the "schedule changed" notification.
     */
    private void notifyUsersOfChange(String addressKey, String addressName, List<TimeInterval> newSchedule, List<TimeInterval> oldSchedule) {
        if (bot == null) return; // Bot not ready

        List<Long> userIds = dbService.getUsersForAddress(addressKey);
        System.out.println("[NOTIFY] Sending change notification to " + userIds.size() + " users for " + addressKey);
        if (userIds.isEmpty()) return;

        // Build the message
        String scheduleString = formatSchedule(newSchedule);
        String message = "✅ *Оновлення графіку!*\n\n" +
                "За адресою *" + addressName + "* " +
                (newSchedule.isEmpty() ? "відключень на сьогодні більше немає.\n\n" : "новий графік відключень:\n" + scheduleString + "\n") +
                (!oldSchedule.isEmpty() ? "_(Минулий графік був іншим)._" : "");

        for (Long chatId : userIds) {
            bot.sendMessage(chatId, message);
        }
    }

    /**
     * Task 2: Runs every 10 minutes to check for upcoming shutdowns.
     */
    private void runPreShutdownWarningCheck() {
        String now = LocalTime.now().format(TIME_FORMATTER);
        System.out.println("\n[PRE-WARN CHECK] Running pre-shutdown warning check at " + now);

        List<String> addressesWithUpcomingShutdowns = new ArrayList<>();

        try {
            // --- (FIX v4.3.0) ---
            // We check the DB *first* to avoid opening the browser if not needed.
            for (String addressKey : monitoredAddresses.keySet()) {
                String scheduleJson = dbService.getSchedule(addressKey);
                if (scheduleJson == null) continue;

                List<TimeInterval> schedule = gson.fromJson(scheduleJson, scheduleListType);
                if (schedule.isEmpty()) continue;

                List<TimeInterval> upcoming = scheduleParser.findUpcomingShutdowns(schedule);
                if (!upcoming.isEmpty()) {
                    addressesWithUpcomingShutdowns.add(addressKey);
                }
            }

            if (addressesWithUpcomingShutdowns.isEmpty()) {
                System.out.println("[PRE-WARN CHECK] No upcoming shutdowns found for any address. Skipping.");
                return; // No browser opened, no resources used.
            }
            // --- END FIX ---

            System.out.println("[PRE-WARN] Found upcoming shutdowns across " + addressesWithUpcomingShutdowns.size() + " addresses.");

            for (String addressKey : addressesWithUpcomingShutdowns) {
                String scheduleJson = dbService.getSchedule(addressKey);
                if (scheduleJson == null) { continue; } // Should be impossible due to check above, but safe

                List<TimeInterval> schedule = gson.fromJson(scheduleJson, scheduleListType);
                if (schedule.isEmpty()) { continue; }

                List<TimeInterval> upcomingShutdowns = scheduleParser.findUpcomingShutdowns(schedule);
                if (upcomingShutdowns.isEmpty()) { continue; }

                String addressName = monitoredAddresses.get(addressKey).name();

                for (TimeInterval interval : upcomingShutdowns) {
                    String startTime = interval.startTime();
                    System.out.println("[PRE-WARN] Found upcoming shutdown for " + addressKey + " at " + startTime);

                    List<Long> usersToWarn = dbService.getUsersToWarn(addressKey, startTime);
                    if (usersToWarn.isEmpty()) {
                        System.out.println("[PRE-WARN] All users already warned for this interval.");
                        continue; // Everyone already warned
                    }

                    System.out.println("[NOTIFY] Sending pre-warn notification to " + usersToWarn.size() + " users for " + addressKey);

                    String message = "❗️ *Увага! Попередження!*\n\n" +
                            "За вашою адресою (*" + addressName + "*)\n" +
                            "планується відключення о `" + startTime + "`.";

                    for (Long chatId : usersToWarn) {
                        bot.sendMessage(chatId, message);
                    }

                    dbService.markUsersAsWarned(usersToWarn, addressKey, startTime);
                }
            }
        } catch (Exception e) {
            System.err.println("[PRE-WARN CHECK] CRITICAL FAILURE: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // --- (FIX v5.1.0) ---
            // No browser is opened in this check (we trust the cache),
            // so no closeWebDriver() is needed here.
        }
    }

    /**
     * Helper method to format a list of intervals into a clean string.
     */
    private String formatSchedule(List<TimeInterval> schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return "Відключень на сьогодні не заплановано.";
        }
        return schedule.stream()
                .map(interval -> "•  `" + interval.startTime() + " - " + interval.endTime() + "`")
                .collect(Collectors.joining("\n"));
    }
}
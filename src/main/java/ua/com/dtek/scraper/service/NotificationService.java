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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Handles all background monitoring tasks.
 * This service runs in scheduled threads and is responsible for:
 * 1. Checking for schedule changes every hour.
 * 2. Sending pre-shutdown warnings 30 minutes before an outage.
 *
 * @version 4.1.0
 */
public class NotificationService {

    // (FIX 2) Define TIME_FORMATTER locally as it's needed for logging
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final DatabaseService dbService;
    private final DtekScraperService scraperService;
    private final ScheduleParser scheduleParser;
    private final Map<String, Address> monitoredAddresses;
    private final Gson gson = new Gson(); // For deserializing JSON from DB
    private final Type scheduleListType = new TypeToken<List<TimeInterval>>() {}.getType();

    // A single-threaded scheduler for all background tasks
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private DtekScraperBot bot; // Reference to the bot to send messages

    /**
     * Constructs the notification service.
     *
     * @param dbService      The database service.
     * @param scraperService The scraping service.
     * @param parser         The schedule parser.
     * @param addresses      The map of monitored addresses.
     */
    public NotificationService(DatabaseService dbService,
                               DtekScraperService scraperService,
                               ScheduleParser parser, // (FIX 1) Inject parser
                               Map<String, Address> addresses) {
        this.dbService = dbService;
        this.scraperService = scraperService;
        this.scheduleParser = parser; // (FIX 1) Assign parser
        this.monitoredAddresses = addresses;
    }

    /**
     * Sets the bot instance. Must be called after the bot is created.
     */
    public void setBot(DtekScraperBot bot) {
        this.bot = bot;
    }

    /**
     * Starts the two scheduled monitoring tasks.
     */
    public void startMonitoring() {
        System.out.println("Starting background monitoring tasks...");

        // --- CHANGE (v4.1.0) ---
        // Task 1: Check for schedule changes (every 30 minutes, at minute 2 and 32)
        // We run at a weird minute to avoid peak load on DTEK's servers
        scheduler.scheduleAtFixedRate(this::runHourlyCheck, 2, 30, TimeUnit.MINUTES);
        // --- END CHANGE ---

        // Task 2: Check for pre-shutdown warnings (every 10 minutes)
        scheduler.scheduleAtFixedRate(this::runPreShutdownWarningCheck, 1, 10, TimeUnit.MINUTES);
    }

    /**
     * Task 1: Runs every 30 minutes to check all 4 addresses for schedule changes.
     */
    private void runHourlyCheck() {
        System.out.println("\n[SCHEDULE CHECK] Running schedule change check...");

        for (Map.Entry<String, Address> entry : monitoredAddresses.entrySet()) {
            String addressKey = entry.getKey();
            Address address = entry.getValue();
            System.out.println("[SCHEDULE CHECK] Checking address: " + address.name());

            try {
                // 1. Get the new schedule from the website
                List<TimeInterval> newSchedule = scraperService.getShutdownSchedule(
                        address.city(), address.street(), address.houseNum()
                );

                // 2. Get the old schedule from our database
                String oldScheduleJson = dbService.getSchedule(addressKey);
                List<TimeInterval> oldSchedule = (oldScheduleJson == null)
                        ? Collections.emptyList()
                        : gson.fromJson(oldScheduleJson, scheduleListType);

                // 3. Compare
                if (!oldSchedule.equals(newSchedule)) {
                    System.out.println("[SCHEDULE CHECK] CHANGES DETECTED for " + address.name());

                    // 4. Save the new schedule
                    dbService.saveSchedule(addressKey, newSchedule);

                    // 5. Notify all subscribed users
                    notifyUsersOfChange(addressKey, address.name(), newSchedule, oldSchedule);

                    // 6. Clear old warning flags (so they can be warned about new times)
                    dbService.clearWarnedFlags(addressKey);
                } else {
                    System.out.println("[SCHEDULE CHECK] No changes for " + address.name());
                }

            } catch (RuntimeException e) {
                // This catches the RuntimeException from ScheduleParser (v4.0.2)
                System.err.println("[SCHEDULE CHECK] CRITICAL FAILURE for " + address.name() + ": " + e.getMessage());
                // We DO NOT send a notification and DO NOT update the cache
                // This prevents "silent failures" from wiping the schedule
            }
        }
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
        StringBuilder msgBuilder = new StringBuilder();
        msgBuilder.append("✅ *Оновлення графіку!*\n\n");
        msgBuilder.append("За адресою *").append(addressName).append("*");

        if (newSchedule.isEmpty()) {
            msgBuilder.append(" відключень на сьогодні більше немає.\n\n");
        } else {
            msgBuilder.append(" новий графік відключень:\n");
            for (TimeInterval interval : newSchedule) {
                msgBuilder.append("•  `").append(interval.startTime()).append(" - ").append(interval.endTime()).append("`\n");
            }
            msgBuilder.append("\n");
        }

        // Add previous schedule for context, if it existed
        if (!oldSchedule.isEmpty()) {
            msgBuilder.append("_(Минулий графік був іншим)._");
        }

        String message = msgBuilder.toString();
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

        for (String addressKey : monitoredAddresses.keySet()) {
            // 1. Get the current schedule from our DB
            String scheduleJson = dbService.getSchedule(addressKey);
            if (scheduleJson == null) continue; // No schedule cached for this address

            List<TimeInterval> schedule = gson.fromJson(scheduleJson, scheduleListType);
            if (schedule.isEmpty()) continue; // No shutdowns planned

            // 2. Find shutdowns starting in the next 30-40 mins
            // (FIX) Call with one argument
            List<TimeInterval> upcomingShutdowns = scheduleParser.findUpcomingShutdowns(schedule);
            if (upcomingShutdowns.isEmpty()) continue;

            // --- FIX ---
            // Changed 'appConfig.getAddresses()' to the correct local field 'monitoredAddresses'
            String addressName = monitoredAddresses.get(addressKey).name();
            // --- END FIX ---

            for (TimeInterval interval : upcomingShutdowns) {
                String startTime = interval.startTime();
                System.out.println("[PRE-WARN] Found upcoming shutdown for " + addressKey + " at " + startTime);

                // 3. Find users who are subscribed AND haven't been warned yet
                List<Long> usersToWarn = dbService.getUsersToWarn(addressKey, startTime);
                if (usersToWarn.isEmpty()) continue; // Everyone already warned

                System.out.println("[NOTIFY] Sending pre-warn notification to " + usersToWarn.size() + " users for " + addressKey);

                // 4. Send the warning
                String message = "❗️ *Увага! Попередження!*\n\n" +
                        "За вашою адресою (*" + addressName + "*)\n" +
                        "планується відключення о `" + startTime + "`.";

                for (Long chatId : usersToWarn) {
                    bot.sendMessage(chatId, message);
                }

                // 5. Mark these users as "warned" to prevent spam
                dbService.markUsersAsWarned(usersToWarn, addressKey, startTime);
            }
        }
    }
}
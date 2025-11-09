package ua.com.dtek.scraper.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ua.com.dtek.scraper.DtekScraperBot;
import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.TimeInterval;
import ua.com.dtek.scraper.parser.ScheduleParser;

import java.lang.reflect.Type;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter; // <-- (FIX 2) ADD THIS IMPORT
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service responsible for background monitoring tasks.
 * It schedules hourly checks for schedule changes and 10-minute checks
 * for pre-shutdown warnings.
 * <p>
 * v4.0.1: Updated to use Gson for schedule deserialization.
 */
public class NotificationService {

    // Schedulers for background tasks
    private final ScheduledExecutorService hourlyScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService warningScheduler = Executors.newSingleThreadScheduledExecutor();

    // Injected services
    private final DatabaseService dbService;
    private final DtekScraperService scraperService;
    private final ScheduleParser scheduleParser; // We get this from the scraperService
    private final Map<String, Address> addresses; // All addresses from config
    private DtekScraperBot bot; // Reference to the bot to send messages

    // (FIX 2) ADD THIS CONSTANT
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    // Gson instance and Type for (de)serializing List<TimeInterval>
    private final Gson gson = new Gson();
    private final Type scheduleListType = new TypeToken<List<TimeInterval>>() {}.getType();

    /**
     * Constructs the notification service.
     *
     * @param dbService      The database service.
     * @param scraperService The scraping service.
     * @param scheduleParser The schedule parser (FIX 1: Injected)
     * @param addresses      The map of all monitored addresses.
     */
    // (FIX 1) ADD 'ScheduleParser scheduleParser' to the constructor
    public NotificationService(DatabaseService dbService, DtekScraperService scraperService, ScheduleParser scheduleParser, Map<String, Address> addresses) {
        this.dbService = dbService;
        this.scraperService = scraperService;
        this.addresses = addresses;
        // (FIX 1) Use the injected parser
        this.scheduleParser = scheduleParser;
    }

    /**
     * Injects the bot instance (using setter-injection) after the bot is created,
     * so this service can send messages.
     *
     * @param bot The main bot instance.
     */
    public void setBot(DtekScraperBot bot) {
        this.bot = bot;
    }

    /**
     * Starts the two scheduled monitoring tasks.
     */
    public void startMonitoring() {
        System.out.println("Starting background monitoring tasks...");

        // Task 1: Check for schedule *changes* every hour, starting 5 minutes from now
        hourlyScheduler.scheduleAtFixedRate(this::runHourlyCheck, 5, 60, TimeUnit.MINUTES);

        // Task 2: Check for *upcoming* shutdowns every 10 minutes, starting 1 minute from now
        warningScheduler.scheduleAtFixedRate(this::runPreShutdownWarningCheck, 1, 10, TimeUnit.MINUTES);
    }

    /**
     * TASK 1: Runs every hour to check all configured addresses for schedule changes.
     * Implements user requirement #1.
     */
    private void runHourlyCheck() {
        System.out.println("\n[HOURLY CHECK] Running hourly schedule change check...");
        if (bot == null) {
            System.err.println("[HOURLY CHECK] Bot is not set (yet?). Skipping check.");
            return;
        }

        // Check every address defined in config.properties
        for (Map.Entry<String, Address> entry : addresses.entrySet()) {
            String addressKey = entry.getKey();
            Address address = entry.getValue();

            try {
                System.out.println("[HOURLY CHECK] Checking address: " + address.name());
                // 1. Get new schedule from DTEK (this is a live web scrape)
                List<TimeInterval> newSchedule = scraperService.getShutdownSchedule(
                        address.city(),
                        address.street(),
                        address.houseNum()
                );

                // 2. Get old schedule from DB
                String oldScheduleJson = dbService.getSchedule(addressKey);
                List<TimeInterval> oldSchedule = gson.fromJson(oldScheduleJson, scheduleListType);

                // 3. Compare (Note: List.equals() works correctly for records)
                if (oldSchedule == null || !oldSchedule.equals(newSchedule)) {
                    System.out.println("[HOURLY CHECK] CHANGES DETECTED for " + address.name());

                    // 4. Save new schedule to DB (as JSON)
                    dbService.saveSchedule(addressKey, newSchedule);

                    // 5. Notify subscribed users
                    notifyUsersOfChange(addressKey, newSchedule);
                } else {
                    System.out.println("[HOURLY CHECK] No changes for " + address.name());
                    // 6. Notify users that there are NO changes (as requested)
                    notifyUsersOfNoChange(addressKey);
                }
            } catch (Exception e) {
                // Catch exceptions per-address so one failed scrape doesn't stop the whole loop
                System.err.println("[HOURLY CHECK] Failed to check address " + address.name() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * TASK 2: Runs every 10 minutes to check for shutdowns starting soon.
     * Implements user requirement #2.
     */
    private void runPreShutdownWarningCheck() {
        LocalTime now = LocalTime.now();
        // This check runs every 10 minutes (e.g., at 14:00, 14:10, 14:20...)
        // The parser logic (findUpcomingShutdowns) looks for shutdowns
        // starting between 29 and 40 minutes from 'now'.
        // Example:
        // - Run at 14:20 -> Window is 14:49-15:00 -> Catches 15:00 shutdown.
        // - Run at 14:00 -> Window is 14:29-14:40 -> Catches 14:30 shutdown.

        System.out.println("\n[PRE-WARN CHECK] Running pre-shutdown warning check at " + now.format(TIME_FORMATTER));
        if (bot == null) {
            System.err.println("[PRE-WARN CHECK] Bot is not set. Skipping check.");
            return;
        }

        for (String addressKey : addresses.keySet()) {
            try {
                // 1. Get current schedule from DB (fast, no scrape)
                String currentScheduleJson = dbService.getSchedule(addressKey);
                if (currentScheduleJson == null || currentScheduleJson.isEmpty()) {
                    continue; // No schedule for this address
                }

                List<TimeInterval> schedule = gson.fromJson(currentScheduleJson, scheduleListType);

                // 2. Check for upcoming shutdowns using the parser's logic
                List<TimeInterval> upcoming = scheduleParser.findUpcomingShutdowns(schedule, now);

                if (!upcoming.isEmpty()) {
                    for (TimeInterval upcomingInterval : upcoming) {
                        // 3. Find users who are subscribed AND *have not* been warned for this specific time
                        String startTime = upcomingInterval.startTime(); // e.g., "15:00"
                        List<Long> usersToWarn = dbService.getUsersToWarn(addressKey, startTime);

                        if (!usersToWarn.isEmpty()) {
                            System.out.println("[PRE-WARN CHECK] Found upcoming shutdown for " + addressKey + " at " + startTime + ". Warning " + usersToWarn.size() + " users.");

                            // 4. Send warnings
                            String message = "💡 *Попередження про відключення!*\n\n" +
                                    "За вашою адресою **" + addresses.get(addressKey).name() + "**\n" +
                                    "очікується відключення:\n\n" +
                                    "➡️ " + upcomingInterval; // Uses TimeInterval.toString()

                            for (Long chatId : usersToWarn) {
                                bot.sendMessage(chatId, message);
                            }

                            // 5. Mark these users as warned *for this specific time*
                            dbService.markUsersAsWarned(usersToWarn, addressKey, startTime);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[PRE-WARN CHECK] Failed to check address " + addressKey + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Formats and sends a "changes detected" message to all subscribers of an address.
     */
    private void notifyUsersOfChange(String addressKey, List<TimeInterval> newSchedule) {
        String addressName = addresses.get(addressKey).name();
        String message;

        if (newSchedule.isEmpty()) {
            message = "✅ *Оновлення графіку!*\n\n" +
                    "За адресою **" + addressName + "** відключень на сьогодні більше *немає*." +
                    "\n\n_(Минулий графік був іншим)._";
        } else {
            message = "❗️ *Оновлення графіку!*\n\n" +
                    "За адресою **" + addressName + "** діє **новий** графік відключень:\n\n" +
                    newSchedule.stream()
                            .map(TimeInterval::toString)
                            .collect(Collectors.joining("\n"));
        }

        List<Long> subscribers = dbService.getUsersForAddress(addressKey);
        System.out.println("[NOTIFY] Sending change notification to " + subscribers.size() + " users for " + addressKey);
        for (Long chatId : subscribers) {
            bot.sendMessage(chatId, message);
        }

        // Clear all "warned" flags for this address, as the schedule has changed
        dbService.clearWarnedFlags(addressKey);
    }

    /**
     * Formats and sends a "no changes" message to all subscribers of an address.
     */
    private void notifyUsersOfNoChange(String addressKey) {
        String addressName = addresses.get(addressKey).name();
        String message = "ℹ️ *Перевірка графіку*\n\n" +
                "За адресою **" + addressName + "** змін у графіку відключень *немає*.\n\n" +
                "Все стабільно.";

        List<Long> subscribers = dbService.getUsersForAddress(addressKey);
        System.out.println("[NOTIFY] Sending 'no change' notification to " + subscribers.size() + " users for " + addressKey);
        for (Long chatId : subscribers) {
            bot.sendMessage(chatId, message);
        }
    }
}
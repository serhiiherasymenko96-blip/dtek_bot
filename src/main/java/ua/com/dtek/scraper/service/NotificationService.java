package ua.com.dtek.scraper.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ua.com.dtek.scraper.DtekScraperBot;
import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.AddressInfo;
import ua.com.dtek.scraper.dto.ScrapeResult;
import ua.com.dtek.scraper.dto.TimeInterval;
import ua.com.dtek.scraper.parser.ScheduleParser;

import java.lang.reflect.Type;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Service responsible for monitoring electricity outage schedules and notifying users about changes.
 * Handles scheduled checks, user notifications, and pre-shutdown warnings.
 * 
 * @version 8.0.0
 */
public class NotificationService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId TIME_ZONE = ZoneId.of("Europe/Kyiv");
    private static final long GROUP_CACHE_EXPIRATION_MINUTES = 25;
    private static final long GROUP_REVERIFICATION_DAYS = 30;

    private final DatabaseService dbService;
    private final DtekScraperService scraperService;
    private final ScheduleParser scheduleParser;
    private final Map<String, Address> monitoredAddresses;
    private final Gson gson = new Gson();
    private final Type scheduleListType = new TypeToken<List<TimeInterval>>() {}.getType();
    // Parallelism limits
    private final int MAX_CONCURRENT_CHECKS = 3;  // Maximum number of concurrent checks

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    // Separate thread pool for address checking
    private final ExecutorService checkExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_CHECKS);
    private DtekScraperBot bot;

    private final Semaphore checkSemaphore = new Semaphore(MAX_CONCURRENT_CHECKS);

    // Task queue
    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean isProcessingQueue = new AtomicBoolean(false);

    /**
     * Safely runs a task with exception handling to prevent scheduler from stopping.
     * This is a critical method that ensures background tasks don't crash the scheduler.
     * 
     * @param task The task to run
     */
    private void safeRun(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            System.err.println("[SAFE_RUN] Caught exception in scheduled task: " + t.getMessage());
            t.printStackTrace();

            // If the error is related to Telegram API, mark the bot as unhealthy
            if (t instanceof org.telegram.telegrambots.meta.exceptions.TelegramApiException) {
                if (bot != null) {
                    bot.markBotUnhealthy();
                }
            }
        }
    }

    /**
     * Constructor for the NotificationService
     * 
     * @param dbService The database service for storing and retrieving data
     * @param scraperService The scraper service for checking addresses
     * @param parser The parser for processing schedule data
     * @param addresses Map of addresses to monitor
     */
    public NotificationService(DatabaseService dbService, DtekScraperService scraperService, ScheduleParser parser, Map<String, Address> addresses) {
        this.dbService = dbService;
        this.scraperService = scraperService;
        this.scheduleParser = parser;
        this.monitoredAddresses = addresses;
    }

    /**
     * Set the bot instance for sending notifications
     * 
     * @param bot The Telegram bot instance
     */
    public void setBot(DtekScraperBot bot) { this.bot = bot; }

    /**
     * Processes tasks from the queue.
     * Guarantees that only one thread processes the queue at a time.
     */
    public void processTaskQueue() {
        if (isProcessingQueue.compareAndSet(false, true)) {
            try {
                Runnable task;
                while ((task = taskQueue.poll()) != null) {
                    safeRun(task);
                }
            } finally {
                isProcessingQueue.set(false);
            }
        }
    }

    /**
     * Adds a task to the queue instead of executing it directly.
     * 
     * @param task The task to be executed
     */
    private void queueTask(Runnable task) {
        taskQueue.offer(task);
    }

    /**
     * Starts the background monitoring tasks for schedule checks and warnings.
     */
    public void startMonitoring() {
        System.out.println("Starting background monitoring tasks...");

        // Start the queue processor
        scheduler.scheduleWithFixedDelay(this::processTaskQueue, 0, 1, TimeUnit.SECONDS);

        // Scheduled checks
        scheduler.scheduleAtFixedRate(() -> safeRun(this::runFullScheduleCheck), 2, 45, TimeUnit.MINUTES);
        scheduler.scheduleAtFixedRate(() -> safeRun(this::runPreShutdownWarningCheck), 1, 10, TimeUnit.MINUTES);

        // Schedule next day checks (hourly after 20:00)
        scheduler.scheduleAtFixedRate(() -> safeRun(this::checkAndRunNextDaySchedule), 1, 60, TimeUnit.MINUTES);

        // Schedule midnight task to copy next day schedules to main schedules
        scheduler.scheduleAtFixedRate(() -> safeRun(this::checkAndCopyNextDaySchedules), 1, 60, TimeUnit.MINUTES);
    }

    /**
     * Checks if it's after 20:00 and before midnight, and if so, runs the next day schedule check.
     */
    private void checkAndRunNextDaySchedule() {
        LocalTime now = LocalTime.now(TIME_ZONE);
        if (now.isAfter(LocalTime.of(20, 0)) && now.isBefore(LocalTime.of(23, 59))) {
            System.out.println("[NEXT DAY CHECK] It's after 20:00, checking next day schedule...");
            runNextDayScheduleCheck();
        } else {
            System.out.println("[NEXT DAY CHECK] It's not after 20:00 yet, skipping next day schedule check.");
        }
    }

    /**
     * Checks the next day's schedule for all addresses and saves the results to the next_day_groups table.
     * This method is similar to runFullScheduleCheck() but uses checkNextDayAddressInSession() and saves to next_day_groups.
     */
    private void runNextDayScheduleCheck() {
        System.out.println("\n[NEXT DAY CHECK] Running next day schedule check...");
        List<AddressInfo> addressesToCheck = dbService.getAddressesForFullCheck(GROUP_CACHE_EXPIRATION_MINUTES, GROUP_REVERIFICATION_DAYS);

        if (addressesToCheck.isEmpty()) {
            System.out.println("[NEXT DAY CHECK] No addresses to check.");
            return;
        }

        System.out.println("[NEXT DAY CHECK] Checking " + addressesToCheck.size() + " addresses for next day schedule.");

        // Set for storing groups that we have ALREADY updated in this run cycle
        Set<String> groupsUpdatedInCurrentCycle = new HashSet<>();

        // Tracking error count for addresses
        Map<String, Integer> addressFailureCount = new ConcurrentHashMap<>();
        final int MAX_FAILURES = 3;

        // Use CountDownLatch to track completion of all tasks
        CountDownLatch completionLatch = new CountDownLatch(addressesToCheck.size());

        // Limit the number of concurrent checks
        for (AddressInfo info : addressesToCheck) {
            String addressName = info.address().name();
            String knownGroup = info.groupName(); // Group that we know from previous checks

            // --- OPTIMIZATION ---
            // If we know the group of this address, and this group has already been updated
            // during the check of another address in this cycle -> SKIP the browser.
            if (knownGroup != null && groupsUpdatedInCurrentCycle.contains(knownGroup)) {
                System.out.println(">>> Optimization: Skipping browser for next day check of " + addressName + " (Group " + knownGroup + " already updated).");
                completionLatch.countDown();
                continue;
            }
            // -------------------

            // Submit the task to the checkExecutor instead of the scheduler
            checkExecutor.submit(() -> {
                try {
                    // Wait for an available slot with timeout
                    System.out.println(">>> Waiting for available check slot for next day check of " + addressName);
                    boolean acquired = checkSemaphore.tryAcquire(5, TimeUnit.MINUTES);
                    if (!acquired) {
                        System.err.println(">>> Timed out waiting for semaphore for next day check of " + addressName);
                        completionLatch.countDown();
                        return;
                    }

                    try {
                        System.out.println(">>> Processing next day check for address: " + addressName);
                        try {
                            // Open a session
                            scraperService.openSession();

                            // Use the checkNextDayAddressInSession method to get the next day's schedule
                            ScrapeResult result = scraperService.checkNextDayAddressInSession(
                                    info.address().city(), info.address().street(), info.address().houseNum());

                            // Handle the next day scrape result
                            handleNextDayScrapeResult(info.addressKey(), info.address(), result);

                            // Reset error counter on successful check
                            addressFailureCount.remove(info.addressKey());

                            // Add the updated group to the set to avoid checking it again for other addresses
                            if (result.groupName() != null) {
                                synchronized (groupsUpdatedInCurrentCycle) {
                                    groupsUpdatedInCurrentCycle.add(result.groupName());
                                }
                            }

                        } catch (Exception e) {
                            System.err.println("[NEXT DAY CHECK] Failed to check " + addressName + ": " + e.getMessage());

                            // Track error count for this address
                            int failures = addressFailureCount.getOrDefault(info.addressKey(), 0) + 1;
                            addressFailureCount.put(info.addressKey(), failures);

                            // If the address consistently causes errors, temporarily disable its checking
                            if (failures >= MAX_FAILURES) {
                                System.err.println("[NEXT DAY CHECK] Address " + addressName + " has failed " + failures + 
                                                " times. Temporarily disabling checks for this address.");
                            }
                        } finally {
                            try {
                                scraperService.closeSession();
                                Thread.sleep(1000); // Give the OS time to free resources
                            } catch (Exception e) {
                                System.err.println("Error closing session: " + e.getMessage());
                            }
                        }
                    } finally {
                        // Always release the semaphore and count down the latch
                        checkSemaphore.release();
                        completionLatch.countDown();
                        System.out.println(">>> Completed next day check for " + addressName + ". Remaining tasks: " + completionLatch.getCount());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("[NEXT DAY CHECK] Interrupted while waiting for semaphore: " + e.getMessage());
                    completionLatch.countDown(); // Ensure latch is counted down even on error
                    System.out.println(">>> Error for next day check of " + addressName + ". Remaining tasks: " + completionLatch.getCount());
                } catch (Throwable t) {
                    System.err.println("[NEXT DAY CHECK] Unexpected error: " + t.getMessage());
                    t.printStackTrace();
                    completionLatch.countDown(); // Ensure latch is counted down even on error
                    System.out.println(">>> Error for next day check of " + addressName + ". Remaining tasks: " + completionLatch.getCount());
                }
            });
        }

        // Log the number of tasks submitted
        System.out.println("[NEXT DAY CHECK] Submitted " + addressesToCheck.size() + " tasks to the check executor.");

        // Wait for all checks to complete using the CountDownLatch
        try {
            System.out.println("[NEXT DAY CHECK] Waiting for all checks to complete...");
            boolean completed = completionLatch.await(30, TimeUnit.MINUTES); // Add a reasonable timeout
            if (completed) {
                System.out.println("[NEXT DAY CHECK] All checks completed successfully.");
            } else {
                System.err.println("[NEXT DAY CHECK] Timed out waiting for all checks to complete!");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[NEXT DAY CHECK] Interrupted while waiting for checks to complete: " + e.getMessage());
        }

        System.out.println("[NEXT DAY CHECK] Cycle complete.");
    }

    /**
     * Checks if it's after midnight (00:00) and before 00:05, and if so, copies the next day schedules to the main schedules.
     */
    private void checkAndCopyNextDaySchedules() {
        LocalTime now = LocalTime.now(TIME_ZONE);
        if (now.isAfter(LocalTime.of(0, 0)) && now.isBefore(LocalTime.of(0, 5))) {
            System.out.println("[MIDNIGHT TASK] It's after midnight, copying next day schedules to main schedules...");
            dbService.copyNextDaySchedulesToMainSchedules();
            System.out.println("[MIDNIGHT TASK] Next day schedules copied to main schedules.");
        }
    }

    /**
     * Handles the result of a next day scrape operation, updating the next_day_groups table.
     * This method is similar to handleScrapeResult() but saves to the next_day_groups table.
     * 
     * @param addressKey The key of the address that was checked
     * @param address The address object that was checked
     * @param result The result of the scrape operation
     */
    private void handleNextDayScrapeResult(String addressKey, Address address, ScrapeResult result) {
        List<TimeInterval> newSchedule = result.schedule();
        String newGroupName = result.groupName();

        // Update the address-to-group binding (we don't update the timestamp here as it's for the next day)
        // We still want the regular schedule check to run for today

        // Get the current next day schedule for this group
        DatabaseService.GroupSchedule oldGroupSched = dbService.getNextDayScheduleForGroup(newGroupName);
        List<TimeInterval> oldSchedule = (oldGroupSched == null || oldGroupSched.scheduleJson() == null)
                ? Collections.emptyList() : gson.fromJson(oldGroupSched.scheduleJson(), scheduleListType);

        boolean hasChanged = !oldSchedule.equals(newSchedule);

        if (hasChanged) {
            System.out.println("[NEXT DAY CHECK] CHANGES DETECTED for next day schedule of group " + newGroupName);
            // Save the new next day group schedule
            dbService.saveNextDayScheduleForGroup(newGroupName, newSchedule);

            // We don't notify users here as this is just the next day's schedule
            // Users will be notified when the schedule becomes the main one after midnight
        } else {
            System.out.println("[NEXT DAY CHECK] No changes for next day schedule of group " + newGroupName);
            // Update the next day group timestamp to mark it as current
            dbService.saveNextDayScheduleForGroup(newGroupName, newSchedule);
        }
    }

    /**
     * Properly shuts down the scheduler and executor to prevent memory leaks.
     * Should be called when the application is shutting down.
     */
    public void shutdown() {
        ua.com.dtek.scraper.util.SchedulerUtil.shutdownScheduler(scheduler, 10, "[NOTIFICATION] ");

        // Shutdown the check executor
        System.out.println("[NOTIFICATION] Shutting down check executor...");
        try {
            checkExecutor.shutdown();
            if (!checkExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("[NOTIFICATION] Check executor did not terminate in time, forcing shutdown...");
                checkExecutor.shutdownNow();
            }
            System.out.println("[NOTIFICATION] Check executor shut down successfully.");
        } catch (InterruptedException e) {
            System.err.println("[NOTIFICATION] Check executor shutdown interrupted: " + e.getMessage());
            checkExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Forces a check for a single address and notifies the specified user.
     * 
     * @param addressKey The key of the address to check
     * @param notifyChatId The chat ID to notify with the results
     */
    public void forceCheckAddress(String addressKey, long notifyChatId) {
        System.out.println("Triggering async check for " + addressKey + " for user " + notifyChatId);
        queueTask(() -> {
            Address address = monitoredAddresses.get(addressKey);
            if (address != null) runCheckForSingleAddress_Isolated(addressKey, address, notifyChatId);
        });
    }

    /**
     * Forces a check for the next day's schedule for a single address and notifies the specified user.
     * 
     * @param addressKey The key of the address to check
     * @param notifyChatId The chat ID to notify with the results
     */
    public void forceCheckNextDayAddress(String addressKey, long notifyChatId) {
        System.out.println("Triggering async next day check for " + addressKey + " for user " + notifyChatId);
        queueTask(() -> {
            Address address = monitoredAddresses.get(addressKey);
            if (address != null) runNextDayCheckForSingleAddress_Isolated(addressKey, address, notifyChatId);
        });
    }

    /**
     * Initiates an unscheduled check of all schedules and notifies about changes.
     * 
     * @param initiatorChatId ID of the user who initiated the check (0 if system check)
     */
    public void forceCheckAllAddresses(long initiatorChatId) {
        System.out.println("[FORCE CHECK ALL] Triggering manual check for all addresses initiated by user " + initiatorChatId);
        queueTask(() -> {
            if (initiatorChatId != 0) {
                sendMessageSafely(initiatorChatId, "🔄 Розпочато перевірку графіків для всіх адрес. Це може зайняти деякий час...", "FORCE CHECK ALL");
            }

            runFullScheduleCheck();

            if (initiatorChatId != 0) {
                sendMessageSafely(initiatorChatId, "✅ Перевірку графіків завершено. Якщо були зміни, користувачі отримали сповіщення.", "FORCE CHECK ALL");
            }
        });
    }

    /**
     * OPTIMIZED check method.
     * 1. Session isolation (open/close) for stability.
     * 2. Group caching within the cycle (we don't check the same group twice in one run).
     */
    private void runFullScheduleCheck() {
        System.out.println("\n[SCHEDULE CHECK] Running full schedule change check...");
        List<AddressInfo> addressesToCheck = dbService.getAddressesForFullCheck(GROUP_CACHE_EXPIRATION_MINUTES, GROUP_REVERIFICATION_DAYS);

        if (addressesToCheck.isEmpty()) {
            System.out.println("[SCHEDULE CHECK] All group caches are fresh.");
            return;
        }

        System.out.println("[SCHEDULE CHECK] Checking " + addressesToCheck.size() + " addresses.");

        // Set for storing groups that we have ALREADY updated in this run cycle
        Set<String> groupsUpdatedInCurrentCycle = new HashSet<>();

        // Tracking error count for addresses
        Map<String, Integer> addressFailureCount = new ConcurrentHashMap<>();
        final int MAX_FAILURES = 3;

        // Use CountDownLatch to track completion of all tasks
        CountDownLatch completionLatch = new CountDownLatch(addressesToCheck.size());

        // Limit the number of concurrent checks
        for (AddressInfo info : addressesToCheck) {
            String addressName = info.address().name();
            String knownGroup = info.groupName(); // Group that we know from previous checks

            // --- OPTIMIZATION ---
            // If we know the group of this address, and this group has already been updated
            // during the check of another address in this cycle -> SKIP the browser.
            if (knownGroup != null && groupsUpdatedInCurrentCycle.contains(knownGroup)) {
                System.out.println(">>> Optimization: Skipping browser for " + addressName + " (Group " + knownGroup + " already updated).");

                // Important: Update timestamp for this address so it doesn't appear as "outdated"
                dbService.updateAddressGroupAndTimestamp(info.addressKey(), knownGroup);

                // Count this as completed since we're skipping it
                completionLatch.countDown();
                continue;
            }
            // -------------------

            // Submit the task to the checkExecutor instead of the scheduler
            checkExecutor.submit(() -> {
                try {
                    // Wait for an available slot with timeout
                    System.out.println(">>> Waiting for available check slot for " + addressName);
                    boolean acquired = checkSemaphore.tryAcquire(5, TimeUnit.MINUTES);
                    if (!acquired) {
                        System.err.println(">>> Timed out waiting for semaphore for " + addressName);
                        completionLatch.countDown();
                        return;
                    }

                    try {
                        System.out.println(">>> Processing address: " + addressName);
                        try {
                            // Open a session (using our CustomFirefoxProvider from BrowserConfig)
                            scraperService.openSession();

                            ScrapeResult result = scraperService.checkAddressInSession(
                                    info.address().city(), info.address().street(), info.address().houseNum());

                            handleScrapeResult(info.addressKey(), info.address(), 0, result);

                            // Reset error counter on successful check
                            addressFailureCount.remove(info.addressKey());

                            // Add the updated group to the set to avoid checking it again for other addresses
                            if (result.groupName() != null) {
                                synchronized (groupsUpdatedInCurrentCycle) {
                                    groupsUpdatedInCurrentCycle.add(result.groupName());
                                }
                            }

                        } catch (Exception e) {
                            System.err.println("[SCHEDULE CHECK] Failed to check " + addressName + ": " + e.getMessage());

                            // Track error count for this address
                            int failures = addressFailureCount.getOrDefault(info.addressKey(), 0) + 1;
                            addressFailureCount.put(info.addressKey(), failures);

                            // If the address consistently causes errors, temporarily disable its checking
                            if (failures >= MAX_FAILURES) {
                                System.err.println("[SCHEDULE CHECK] Address " + addressName + " has failed " + failures + 
                                                " times. Temporarily disabling checks for this address.");
                            }
                        } finally {
                            try {
                                scraperService.closeSession();
                                Thread.sleep(1000); // Give the OS time to free resources
                            } catch (Exception e) {
                                System.err.println("Error closing session: " + e.getMessage());
                            }
                        }
                    } finally {
                        // Always release the semaphore and count down the latch
                        checkSemaphore.release();
                        completionLatch.countDown();
                        System.out.println(">>> Completed check for " + addressName + ". Remaining tasks: " + completionLatch.getCount());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("[SCHEDULE CHECK] Interrupted while waiting for semaphore: " + e.getMessage());
                    completionLatch.countDown(); // Ensure latch is counted down even on error
                    System.out.println(">>> Error for " + addressName + ". Remaining tasks: " + completionLatch.getCount());
                } catch (Throwable t) {
                    System.err.println("[SCHEDULE CHECK] Unexpected error: " + t.getMessage());
                    t.printStackTrace();
                    completionLatch.countDown(); // Ensure latch is counted down even on error
                    System.out.println(">>> Error for " + addressName + ". Remaining tasks: " + completionLatch.getCount());
                }
            });
        }

        // Log the number of tasks submitted
        System.out.println("[SCHEDULE CHECK] Submitted " + addressesToCheck.size() + " tasks to the check executor.");

        // Wait for all checks to complete using the CountDownLatch
        try {
            System.out.println("[SCHEDULE CHECK] Waiting for all checks to complete...");
            boolean completed = completionLatch.await(30, TimeUnit.MINUTES); // Add a reasonable timeout
            if (completed) {
                System.out.println("[SCHEDULE CHECK] All checks completed successfully.");
            } else {
                System.err.println("[SCHEDULE CHECK] Timed out waiting for all checks to complete!");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[SCHEDULE CHECK] Interrupted while waiting for checks to complete: " + e.getMessage());
        }

        System.out.println("[SCHEDULE CHECK] Cycle complete.");
    }

    /**
     * Run an isolated check for a single address.
     * This method opens a new browser session, performs the check, and closes the session.
     * 
     * @param addressKey The key of the address to check
     * @param address The address object to check
     * @param notifyChatId The chat ID to notify with the results (0 if no notification needed)
     */
    private void runCheckForSingleAddress_Isolated(String addressKey, Address address, long notifyChatId) {
        System.out.println("[FORCE CHECK] Running isolated check for: " + address.name());
        try {
            scraperService.openSession();
            ScrapeResult result = scraperService.checkAddressInSession(address.city(), address.street(), address.houseNum());
            handleScrapeResult(addressKey, address, notifyChatId, result);
        } catch (Exception e) {
            System.err.println("[FORCE CHECK] FAILURE: " + e.getMessage());
            if (notifyChatId != 0) {
                sendMessageSafely(notifyChatId, "❌ Помилка при отриманні даних (" + e.getMessage() + "). Спробуйте пізніше.", "FORCE CHECK");
            }
        } finally {
            scraperService.closeSession();
            System.out.println("[FORCE CHECK] Browser closed.");
        }
    }

    /**
     * Run an isolated check for the next day's schedule for a single address.
     * This method opens a new browser session, performs the check, and closes the session.
     * 
     * @param addressKey The key of the address to check
     * @param address The address object to check
     * @param notifyChatId The chat ID to notify with the results (0 if no notification needed)
     */
    private void runNextDayCheckForSingleAddress_Isolated(String addressKey, Address address, long notifyChatId) {
        System.out.println("[FORCE NEXT DAY CHECK] Running isolated next day check for: " + address.name());
        try {
            scraperService.openSession();
            ScrapeResult result = scraperService.checkNextDayAddressInSession(address.city(), address.street(), address.houseNum());

            // Get the next day schedule
            DatabaseService.GroupSchedule nextDaySchedule = dbService.getNextDayScheduleForGroup(result.groupName());
            List<TimeInterval> schedule = (nextDaySchedule == null || nextDaySchedule.scheduleJson() == null)
                    ? Collections.emptyList() : gson.fromJson(nextDaySchedule.scheduleJson(), scheduleListType);

            // Update the next day schedule in the database
            handleNextDayScrapeResult(addressKey, address, result);

            // Notify the user
            if (notifyChatId != 0) {
                String msg = "💡 *Графік на завтра для " + address.name() + ":*\n\n" + formatSchedule(result.schedule());
                sendMessageSafely(notifyChatId, msg, "FORCE NEXT DAY CHECK");
            }
        } catch (Exception e) {
            System.err.println("[FORCE NEXT DAY CHECK] FAILURE: " + e.getMessage());
            if (notifyChatId != 0) {
                sendMessageSafely(notifyChatId, "❌ Помилка при отриманні даних на завтра (" + e.getMessage() + "). Спробуйте пізніше.", "FORCE NEXT DAY CHECK");
            }
        } finally {
            scraperService.closeSession();
            System.out.println("[FORCE NEXT DAY CHECK] Browser closed.");
        }
    }

    /**
     * Handles the result of a scrape operation, updating the database and notifying users if needed.
     * 
     * @param addressKey The key of the address that was checked
     * @param address The address object that was checked
     * @param notifyChatId The chat ID to notify with the results (0 if no notification needed)
     * @param result The result of the scrape operation
     */
    private void handleScrapeResult(String addressKey, Address address, long notifyChatId, ScrapeResult result) {
        List<TimeInterval> newSchedule = result.schedule();
        String newGroupName = result.groupName();

        // Update the address-to-group binding and check timestamp
        dbService.updateAddressGroupAndTimestamp(addressKey, newGroupName);

        DatabaseService.GroupSchedule oldGroupSched = dbService.getScheduleForGroup(newGroupName);
        List<TimeInterval> oldSchedule = (oldGroupSched == null || oldGroupSched.scheduleJson() == null)
                ? Collections.emptyList() : gson.fromJson(oldGroupSched.scheduleJson(), scheduleListType);

        boolean hasChanged = !oldSchedule.equals(newSchedule);

        if (hasChanged) {
            System.out.println("[CHECK] CHANGES DETECTED for group " + newGroupName);
            // Save the new group schedule
            dbService.saveScheduleForGroup(newGroupName, newSchedule);

            // Notify ALL users of this group
            List<Long> users = dbService.getUsersForGroup(newGroupName);
            if (notifyChatId != 0) users.remove(notifyChatId); // To avoid duplicate messages to the initiator
            notifyUsers(users, newGroupName, newSchedule, true);

            dbService.clearWarnedFlagsForGroup(newGroupName);
        } else {
            System.out.println("[CHECK] No changes for group " + newGroupName);
            // Update the group timestamp ("last_checked") to mark it as current
            dbService.saveScheduleForGroup(newGroupName, newSchedule);
        }

        if (notifyChatId != 0) {
            String msg = "💡 *Поточний графік для " + address.name() + ":*\n\n" + formatSchedule(newSchedule);
            sendMessageSafely(notifyChatId, msg, "HANDLE RESULT");
        }
    }

    /**
     * Notifies users about schedule changes
     * 
     * @param users List of user IDs to notify
     * @param groupName The group name for the schedule
     * @param schedule The new schedule
     * @param isChange Whether this is a change from the previous schedule
     */
    private void notifyUsers(List<Long> users, String groupName, List<TimeInterval> schedule, boolean isChange) {
        if (bot == null || users.isEmpty()) return;
        String msg = "✅ *Оновлення графіку!*\nГрупа: *" + groupName + "*\n\n" +
                (schedule.isEmpty() ? "Відключень немає.\n" : "Новий графік:\n" + formatSchedule(schedule) + "\n") +
                (isChange ? "_(Минулий графік був іншим)._" : "");
        sendMessageToUsers(users, msg, "NOTIFICATION");
    }

    /**
     * Runs a check for upcoming outages to send pre-shutdown warnings
     */
    private void runPreShutdownWarningCheck() {
        String now = LocalTime.now(TIME_ZONE).format(TIME_FORMATTER);
        System.out.println("\n[PRE-WARN CHECK] " + now);

        try {
            for (String key : monitoredAddresses.keySet()) {
                processAddressForWarning(key);
            }
        } catch (Exception e) {
            System.err.println("[PRE-WARN] Critical error in warning check: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Process a single address for pre-shutdown warnings.
     * Checks if there are any upcoming outages for the address and sends warnings to users.
     * 
     * @param key The address key to process
     */
    private void processAddressForWarning(String key) {
        try {
            String group = dbService.getGroupForAddress(key);
            if (group == null) return;

            DatabaseService.GroupSchedule gs = dbService.getScheduleForGroup(group);
            if (gs == null || gs.scheduleJson() == null) return;

            List<TimeInterval> sched = gson.fromJson(gs.scheduleJson(), scheduleListType);
            List<TimeInterval> upcoming = scheduleParser.findUpcomingShutdowns(sched);

            for (TimeInterval ti : upcoming) {
                processTimeIntervalWarning(key, ti);
            }
        } catch (Exception e) {
            System.err.println("[PRE-WARN] Error processing address key " + key + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Process warnings for a specific time interval.
     * 
     * @param key The address key
     * @param ti The time interval
     */
    private void processTimeIntervalWarning(String key, TimeInterval ti) {
        try {
            List<Long> users = dbService.getUsersToWarn(key, ti.startTime());
            if (users.isEmpty()) return;

            String msg = "❗️ *Увага!* (" + monitoredAddresses.get(key).name() + ")\nПланується відключення о `" + ti.startTime() + "`.";
            sendWarningToUsers(users, msg);
            dbService.markUsersAsWarned(users, key, ti.startTime());
        } catch (Exception e) {
            System.err.println("[PRE-WARN] Error processing time interval " + ti + " for key " + key + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Helper method to safely send a message to a user with error handling
     * 
     * @param userId The user's chat ID
     * @param message The message to send
     * @param logTag The tag to use in error logs
     * @return true if the message was sent successfully, false otherwise
     */
    private boolean sendMessageSafely(long userId, String message, String logTag) {
        try {
            return bot.sendMessage(userId, message);
        } catch (Exception e) {
            System.err.println("[" + logTag + "] Failed to send message to user " + userId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Send messages to multiple users with error handling for each user
     * 
     * @param users The list of user IDs
     * @param message The message to send
     * @param logTag The tag to use in error logs
     */
    private void sendMessageToUsers(List<Long> users, String message, String logTag) {
        for (Long userId : users) {
            sendMessageSafely(userId, message, logTag);
        }
    }

    /**
     * Send warning messages to multiple users
     * 
     * @param users The list of user IDs
     * @param message The warning message to send
     */
    private void sendWarningToUsers(List<Long> users, String message) {
        sendMessageToUsers(users, message, "PRE-WARN");
    }

    /**
     * Broadcast a message to all users
     * 
     * @param message The message to broadcast
     * @param initiatorChatId The chat ID of the user who initiated the broadcast (will be excluded from recipients)
     * @return The number of users who successfully received the message
     */
    public int broadcastToAllUsers(String message, long initiatorChatId) {
        if (bot == null) return 0;

        List<Long> allUsers = dbService.getAllUsers();
        int successCount = 0;

        for (Long userId : allUsers) {
            // Skip sending to the initiator
            if (userId == initiatorChatId) continue;

            try {
                if (sendMessageSafely(userId, message, "BROADCAST")) {
                    successCount++;
                }

                // Add a small delay to avoid hitting Telegram API limits
                Thread.sleep(50);
            } catch (Exception e) {
                System.err.println("[BROADCAST] Failed to send message to user " + userId + ": " + e.getMessage());
            }
        }

        return successCount;
    }

    /**
     * Format a list of time intervals into a readable schedule string
     * 
     * @param schedule The list of time intervals
     * @return A formatted string representation of the schedule
     */
    private String formatSchedule(List<TimeInterval> schedule) {
        if (schedule == null || schedule.isEmpty()) return "Відключень немає.";
        return schedule.stream().map(i -> "•  `" + i.startTime() + " - " + i.endTime() + "`").collect(Collectors.joining("\n"));
    }
}

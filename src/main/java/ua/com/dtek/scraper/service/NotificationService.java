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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.codeborne.selenide.Selenide.closeWebDriver;

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
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private DtekScraperBot bot;

    public NotificationService(DatabaseService dbService, DtekScraperService scraperService, ScheduleParser parser, Map<String, Address> addresses) {
        this.dbService = dbService;
        this.scraperService = scraperService;
        this.scheduleParser = parser;
        this.monitoredAddresses = addresses;
    }

    public void setBot(DtekScraperBot bot) { this.bot = bot; }

    public void startMonitoring() {
        System.out.println("Starting background monitoring tasks...");
        scheduler.scheduleAtFixedRate(this::runFullScheduleCheck, 2, 30, TimeUnit.MINUTES);
        scheduler.scheduleAtFixedRate(this::runPreShutdownWarningCheck, 1, 10, TimeUnit.MINUTES);
    }

    public void forceCheckAddress(String addressKey, long notifyChatId) {
        System.out.println("Triggering async check for " + addressKey + " for user " + notifyChatId);
        scheduler.submit(() -> {
            Address address = monitoredAddresses.get(addressKey);
            if (address != null) runCheckForSingleAddress_Isolated(addressKey, address, notifyChatId);
        });
    }

    private void runFullScheduleCheck() {
        System.out.println("\n[SCHEDULE CHECK] Running full schedule change check...");
        List<AddressInfo> addressesToCheck = dbService.getAddressesForFullCheck(GROUP_CACHE_EXPIRATION_MINUTES, GROUP_REVERIFICATION_DAYS);
        if (addressesToCheck.isEmpty()) {
            System.out.println("[SCHEDULE CHECK] All group caches are fresh.");
            return;
        }

        System.out.println("[SCHEDULE CHECK] Checking " + addressesToCheck.size() + " addresses.");
        try {
            scraperService.openSession();
            for (AddressInfo info : addressesToCheck) {
                handleScrapeResult(info.addressKey(), info.address(), 0,
                        scraperService.checkAddressInSession(
                                info.address().city(), info.address().street(), info.address().houseNum()));
            }
        } catch (Exception e) {
            System.err.println("[SCHEDULE CHECK] CRITICAL SESSION FAILURE: " + e.getMessage());
        } finally {
            scraperService.closeSession();
            System.out.println("[SCHEDULE CHECK] Browser closed.");
        }
    }

    private void runCheckForSingleAddress_Isolated(String addressKey, Address address, long notifyChatId) {
        System.out.println("[FORCE CHECK] Running isolated check for: " + address.name());
        try {
            scraperService.openSession();
            ScrapeResult result = scraperService.checkAddressInSession(address.city(), address.street(), address.houseNum());
            handleScrapeResult(addressKey, address, notifyChatId, result);
        } catch (Exception e) {
            System.err.println("[FORCE CHECK] FAILURE: " + e.getMessage());
            if (notifyChatId != 0) bot.sendMessage(notifyChatId, "❌ Помилка. Спробуйте пізніше.");
        } finally {
            scraperService.closeSession();
            System.out.println("[FORCE CHECK] Browser closed.");
        }
    }

    private void handleScrapeResult(String addressKey, Address address, long notifyChatId, ScrapeResult result) {
        List<TimeInterval> newSchedule = result.schedule();
        String newGroupName = result.groupName();

        dbService.updateAddressGroupAndTimestamp(addressKey, newGroupName);
        DatabaseService.GroupSchedule oldGroupSched = dbService.getScheduleForGroup(newGroupName);
        List<TimeInterval> oldSchedule = (oldGroupSched == null || oldGroupSched.scheduleJson() == null)
                ? Collections.emptyList() : gson.fromJson(oldGroupSched.scheduleJson(), scheduleListType);

        boolean hasChanged = !oldSchedule.equals(newSchedule);

        if (hasChanged) {
            System.out.println("[CHECK] CHANGES DETECTED for group " + newGroupName);
            dbService.saveScheduleForGroup(newGroupName, newSchedule);
            List<Long> users = dbService.getUsersForGroup(newGroupName);
            if (notifyChatId != 0) users.remove(notifyChatId);
            notifyUsers(users, newGroupName, newSchedule, true);
            dbService.clearWarnedFlagsForGroup(newGroupName);
        } else {
            System.out.println("[CHECK] No changes for group " + newGroupName);
            dbService.saveScheduleForGroup(newGroupName, newSchedule);
        }

        if (notifyChatId != 0) {
            String msg = "💡 *Поточний графік для " + address.name() + ":*\n\n" + formatSchedule(newSchedule);
            bot.sendMessage(notifyChatId, msg);
        }
    }

    private void notifyUsers(List<Long> users, String groupName, List<TimeInterval> schedule, boolean isChange) {
        if (bot == null || users.isEmpty()) return;
        String msg = "✅ *Оновлення графіку!*\nГрупа: *" + groupName + "*\n\n" +
                (schedule.isEmpty() ? "Відключень немає.\n" : "Новий графік:\n" + formatSchedule(schedule) + "\n") +
                (isChange ? "_(Минулий графік був іншим)._" : "");
        for (Long id : users) bot.sendMessage(id, msg);
    }

    private void runPreShutdownWarningCheck() {
        String now = LocalTime.now(TIME_ZONE).format(TIME_FORMATTER);
        System.out.println("\n[PRE-WARN CHECK] " + now);
        // (Спрощена логіка: беремо всі адреси, перевіряємо групи і ворнінги)
        try {
            for (String key : monitoredAddresses.keySet()) {
                String group = dbService.getGroupForAddress(key);
                if (group == null) continue;
                DatabaseService.GroupSchedule gs = dbService.getScheduleForGroup(group);
                if (gs == null || gs.scheduleJson() == null) continue;
                List<TimeInterval> sched = gson.fromJson(gs.scheduleJson(), scheduleListType);
                List<TimeInterval> upcoming = scheduleParser.findUpcomingShutdowns(sched);
                for (TimeInterval ti : upcoming) {
                    List<Long> users = dbService.getUsersToWarn(key, ti.startTime());
                    if (users.isEmpty()) continue;
                    String msg = "❗️ *Увага!* (" + monitoredAddresses.get(key).name() + ")\nПланується відключення о `" + ti.startTime() + "`.";
                    for (Long u : users) bot.sendMessage(u, msg);
                    dbService.markUsersAsWarned(users, key, ti.startTime());
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private String formatSchedule(List<TimeInterval> schedule) {
        if (schedule == null || schedule.isEmpty()) return "Відключень немає.";
        return schedule.stream().map(i -> "•  `" + i.startTime() + " - " + i.endTime() + "`").collect(Collectors.joining("\n"));
    }
}
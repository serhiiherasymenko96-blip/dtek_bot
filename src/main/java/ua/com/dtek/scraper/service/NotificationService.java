package ua.com.dtek.scraper.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
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

// Імпортуємо closeWebDriver для виправлення витоку ресурсів
import static com.codeborne.selenide.Selenide.closeWebDriver;

/**
 * Handles all background monitoring tasks.
 *
 * @version 5.2.0 (Fixes concurrency crash on e2-micro)
 */
public class NotificationService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final DatabaseService dbService;
    private final DtekScraperService scraperService;
    private final ScheduleParser scheduleParser;
    private final Map<String, Address> monitoredAddresses;
    private final Gson gson = new Gson();
    private final Type scheduleListType = new TypeToken<List<TimeInterval>>() {}.getType();

    // --- (FIX v5.2.0) ---
    // Використовуємо ОДНОПОТОКОВИЙ планувальник для ВСІХ завдань (фонових, попереджень, форсованих)
    // Це серіалізує всі завдання скрейпінгу і запобігає одночасному запуску
    // кількох екземплярів Firefox, що "вбивало" сервер e2-micro.
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

        // Завдання 1: Перевірка змін у графіку (кожні 30 хвилин)
        scheduler.scheduleAtFixedRate(this::runFullScheduleCheck, 2, 30, TimeUnit.MINUTES);

        // Завдання 2: Перевірка попереджень про відключення (кожні 10 хвилин)
        scheduler.scheduleAtFixedRate(this::runPreShutdownWarningCheck, 1, 10, TimeUnit.MINUTES);
    }

    /**
     * (v5.0.0) Публічний метод для постановки в чергу перевірки однієї адреси,
     * зазвичай викликається при підписці нового користувача.
     *
     * @param addressKey   Адреса для перевірки.
     * @param notifyChatId Користувач, який ініціював перевірку і якого треба сповістити.
     */
    public void forceCheckAddress(String addressKey, long notifyChatId) {
        System.out.println("Triggering async check for " + addressKey + " for user " + notifyChatId);

        // --- (FIX v5.1.0) ---
        // Відправляємо завдання у той самий ОДНОПОТОКОВИЙ планувальник.
        // Воно виконається, щойно поточне завдання (якщо воно є) завершиться.
        scheduler.submit(() -> {
            Address address = monitoredAddresses.get(addressKey);
            if (address != null) {
                // Запускаємо перевірку, передаючи ID користувача, якого треба сповістити
                runCheckForSingleAddress(addressKey, address, notifyChatId);
            }
        });
        // --- END FIX ---
    }

    /**
     * Завдання 1: Виконується кожні 30 хвилин для перевірки ВСІХ відстежуваних адрес.
     */
    private void runFullScheduleCheck() {
        System.out.println("\n[SCHEDULE CHECK] Running full schedule change check...");

        // (v4.3.0) Обгортаємо весь цикл перевірок у try/finally
        try {
            for (Map.Entry<String, Address> entry : monitoredAddresses.entrySet()) {
                // Запускаємо перевірку, не сповіщуючи нікого конкретно (notifyChatId = 0)
                runCheckForSingleAddress(entry.getKey(), entry.getValue(), 0);
            }
        } finally {
            // (v4.3.0) Гарантовано закриваємо браузер ПІСЛЯ
            // перевірки ВСІХ адрес, щоб звільнити ресурси.
            closeWebDriver();
            System.out.println("[SCHEDULE CHECK] Browser closed. Resources freed.");
        }
    }

    /**
     * Це ядро логіки скрейпінгу, тепер централізоване.
     * Перевіряє одну адресу, порівнює з кешем та сповіщає користувачів.
     *
     * @param addressKey   ID адреси (напр., "address.1")
     * @param address      DTO адреси
     * @param notifyChatId Один користувач для сповіщення (0, якщо це загальна перевірка)
     */
    private void runCheckForSingleAddress(String addressKey, Address address, long notifyChatId) {
        System.out.println("[CHECK] Checking address: " + address.name());
        List<TimeInterval> newSchedule = null;
        List<TimeInterval> oldSchedule = null;
        boolean scrapeSuccess = false;
        boolean hasChanged = false;

        try {
            // 1. Отримуємо новий графік з сайту
            newSchedule = scraperService.getShutdownSchedule(
                    address.city(), address.street(), address.houseNum()
            );
            scrapeSuccess = true;

            // 2. Отримуємо старий графік з БД
            String oldScheduleJson = dbService.getSchedule(addressKey);
            oldSchedule = (oldScheduleJson == null)
                    ? Collections.emptyList()
                    : gson.fromJson(oldScheduleJson, scheduleListType);

            // 3. Порівнюємо
            hasChanged = !oldSchedule.equals(newSchedule);

            if (hasChanged) {
                System.out.println("[CHECK] CHANGES DETECTED for " + address.name());

                // 4. Зберігаємо новий графік
                dbService.saveSchedule(addressKey, newSchedule);

                // 5. Сповіщаємо ВСІХ підписаних користувачів
                notifyUsersOfChange(addressKey, address.name(), newSchedule, oldSchedule);

                // 6. Очищуємо старі прапорці попереджень
                dbService.clearWarnedFlags(addressKey);

            } else {
                System.out.println("[CHECK] No changes for " + address.name());
            }

        } catch (RuntimeException e) {
            // (v4.4.3) Перехоплюємо помилки від скрейпера (напр., збій Firefox)
            // АБО помилки парсера (v4.0.2)
            System.err.println("[CHECK] CRITICAL FAILURE for " + address.name() + ": " + e.getMessage());
            // Ми НЕ сповіщуємо і НЕ оновлюємо кеш
        }

        // --- (FIX v5.0.2) ---
        // Якщо це була "форсована" перевірка (notifyChatId != 0),
        // І скрейпінг пройшов успішно (scrapeSuccess == true),
        // І змін не було (hasChanged == false) (бо якби вони були, ми б вже надіслали сповіщення),
        // ми маємо надіслати щойно завантажений графік користувачу, який чекає.
        if (notifyChatId != 0 && scrapeSuccess && !hasChanged) {
            System.out.println("[FORCE CHECK] Sending current (unchanged) schedule to user " + notifyChatId);
            String scheduleString = formatSchedule(newSchedule);
            String message = "💡 *Поточний графік для " + address.name() + ":*\n\n" + scheduleString;
            bot.sendMessage(notifyChatId, message);
        }

        // --- (FIX v5.2.0) ---
        // Якщо це була "форсована" перевірка (notifyChatId != 0),
        // ми повинні закрити браузер *зараз*,
        // оскільки 30-хвилинна перевірка (яка теж закриває браузер) може бути ще не скоро.
        if (notifyChatId != 0) {
            closeWebDriver();
            System.out.println("[FORCE CHECK] Browser closed. Resources freed for user " + notifyChatId);
        }
    }

    /**
     * Допоміжний метод для форматування та надсилання сповіщення про "зміну графіка".
     */
    private void notifyUsersOfChange(String addressKey, String addressName, List<TimeInterval> newSchedule, List<TimeInterval> oldSchedule) {
        if (bot == null) return; // Бот ще не готовий

        List<Long> userIds = dbService.getUsersForAddress(addressKey);
        System.out.println("[NOTIFY] Sending change notification to " + userIds.size() + " users for " + addressKey);
        if (userIds.isEmpty()) return;

        // Створюємо повідомлення
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
     * Завдання 2: Виконується кожні 10 хвилин для перевірки майбутніх відключень.
     */
    private void runPreShutdownWarningCheck() {
        String now = LocalTime.now(java.time.ZoneId.of("Europe/Kyiv")).format(TIME_FORMATTER); // (v5.2.0 fix)
        System.out.println("\n[PRE-WARN CHECK] Running pre-shutdown warning check at " + now);

        List<String> addressesWithUpcomingShutdowns = new ArrayList<>();

        try {
            // (v4.3.0) Ми перевіряємо БД *спочатку*, щоб не відкривати браузер без потреби.
            // Ця логіка не використовує Selenide, вона лише читає з dtek_bot.db
            for (String addressKey : monitoredAddresses.keySet()) {
                String scheduleJson = dbService.getSchedule(addressKey);
                if (scheduleJson == null) continue;

                List<TimeInterval> schedule = gson.fromJson(scheduleJson, scheduleListType);
                if (schedule.isEmpty()) continue;

                // (v4.2.2 fix)
                List<TimeInterval> upcoming = scheduleParser.findUpcomingShutdowns(schedule);
                if (!upcoming.isEmpty()) {
                    addressesWithUpcomingShutdowns.add(addressKey);
                }
            }

            if (addressesWithUpcomingShutdowns.isEmpty()) {
                System.out.println("[PRE-WARN CHECK] No upcoming shutdowns found for any address. Skipping.");
                return; // Браузер не відкрито, ресурси зекономлено.
            }

            System.out.println("[PRE-WARN] Found upcoming shutdowns across " + addressesWithUpcomingShutdowns.size() + " addresses.");

            // Тепер ми надсилаємо сповіщення
            for (String addressKey : addressesWithUpcomingShutdowns) {
                String scheduleJson = dbService.getSchedule(addressKey);
                if (scheduleJson == null) { continue; }

                List<TimeInterval> schedule = gson.fromJson(scheduleJson, scheduleListType);
                if (schedule.isEmpty()) { continue; }

                List<TimeInterval> upcomingShutdowns = scheduleParser.findUpcomingShutdowns(schedule);
                if (upcomingShutdowns.isEmpty()) { continue; }

                String addressName = monitoredAddresses.get(addressKey).name();

                for (TimeInterval interval : upcomingShutdowns) {
                    String startTime = interval.startTime();
                    System.out.println("[PRE-WARN] Found upcoming shutdown for " + addressKey + " at " + startTime);

                    // Знаходимо користувачів, які підписані І яких ще НЕ попереджали
                    List<Long> usersToWarn = dbService.getUsersToWarn(addressKey, startTime);
                    if (usersToWarn.isEmpty()) {
                        System.out.println("[PRE-WARN] All users already warned for this interval.");
                        continue;
                    }

                    System.out.println("[NOTIFY] Sending pre-warn notification to " + usersToWarn.size() + " users for " + addressKey);

                    // Надсилаємо попередження
                    String message = "❗️ *Увага! Попередження!*\n\n" +
                            "За вашою адресою (*" + addressName + "*)\n" +
                            "планується відключення о `" + startTime + "`.";

                    for (Long chatId : usersToWarn) {
                        bot.sendMessage(chatId, message);
                    }

                    // Позначаємо, що ми їх попередили (щоб не спамити кожні 10 хв)
                    dbService.markUsersAsWarned(usersToWarn, addressKey, startTime);
                }
            }
        } catch (Exception e) {
            System.err.println("[PRE-WARN CHECK] CRITICAL FAILURE: " + e.getMessage());
            e.printStackTrace();
        }
        // (v5.1.0) Тут НЕМАЄ 'finally { closeWebDriver() }',
        // оскільки ця перевірка працює лише з БД і не запускає браузер.
    }

    /**
     * Допоміжний метод для форматування списку інтервалів у чистий рядок.
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
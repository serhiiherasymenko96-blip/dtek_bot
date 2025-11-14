package ua.com.dtek.scraper.service;

import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.TimeInterval;
import ua.com.dtek.scraper.page.SchedulePage;
import ua.com.dtek.scraper.parser.ScheduleParser;
import java.util.List;

/**
 * Оркеструє процес скрейпінгу.
 * Координує дії Page Object (SchedulePage) та парсера (ScheduleParser).
 *
 * @version 4.4.3 (Fix silent failure on scrape error)
 */
public class DtekScraperService {

    private final SchedulePage schedulePage;
    private final ScheduleParser scheduleParser;

    /**
     * Конструктор сервісу.
     * @param parser Ініціалізований ScheduleParser.
     */
    public DtekScraperService(ScheduleParser parser) {
        this.schedulePage = new SchedulePage();
        this.scheduleParser = parser;
    }

    /**
     * Виконує повний цикл скрейпінгу для однієї адреси.
     *
     * @return Список TimeIntervals.
     * @throws RuntimeException якщо скрейпінг або парсинг не вдався.
     */
    public List<TimeInterval> getShutdownSchedule(String city, String street, String houseNum) {
        System.out.println("--- Starting new scrape task for: " + city + ", " + street + ", " + houseNum + " ---");
        try {
            // 1. Відкрити сторінку
            schedulePage.openPage();

            // 2. Закрити pop-up (якщо є)
            schedulePage.closeModalPopupIfPresent();

            // 3. Заповнити форму
            schedulePage.fillAddressForm(city, street, houseNum);

            // 4. Отримати назву групи (для логування)
            String groupName = schedulePage.getGroupName();
            System.out.println("Found group in #group-name: " + groupName);

            // 5. Отримати HTML таблиці
            String tableHtml = schedulePage.getActiveScheduleTableHtml();

            // 6. Розпарсити HTML та повернути результат
            return scheduleParser.parse(tableHtml);

        } catch (Exception e) {
            // --- (FIX v4.4.3) ---
            // "Тиха відмова" - це погано.
            // Замість повернення Collections.emptyList(), ми "кидаємо" виняток.
            // NotificationService перехопить це і НЕ буде оновлювати кеш
            // або надсилати хибне сповіщення "No changes".
            String errorMessage = "[SCRAPER ERROR] Failed to get schedule for " + city + ", " + street + ": " + e.getMessage();
            System.err.println(errorMessage);
            throw new RuntimeException(errorMessage, e);
            // --- END FIX ---
        }
    }
}
package ua.com.dtek.scraper.service;

import ua.com.dtek.scraper.dto.TimeInterval;
import ua.com.dtek.scraper.page.SchedulePage;
import ua.com.dtek.scraper.parser.ScheduleParser;

import java.util.List;

/**
 * Orchestrates the scraping process.
 * <p>
 * This service class coordinates the actions of the Page Object (SchedulePage)
 * and the parser (ScheduleParser) to execute the full business logic.
 * <p>
 * v4.0.0 Update: This service is now stateless. It no longer depends on AppConfig
 * and instead receives address parameters for its methods.
 */
public class DtekScraperService {

    private final SchedulePage schedulePage;
    private final ScheduleParser scheduleParser;

    /**
     * Constructs the service, initializing its dependencies.
     * We use Dependency Injection for the parser.
     *
     * @param parser The pre-initialized schedule parser.
     */
    public DtekScraperService(ScheduleParser parser) {
        this.schedulePage = new SchedulePage(); // POM is internal to the service
        this.scheduleParser = parser;         // Parser is injected
    }

    /**
     * Executes the main scraping flow for a specific address.
     * <p>
     * NOTE: This method is synchronized to prevent multiple threads (from the
     * notification service) from using the single Selenide browser instance
     * at the same time.
     *
     * @param city     The city to check.
     * @param street   The street to check.
     * @param houseNum The house number to check.
     * @return A list of TimeInterval objects describing the shutdown schedule.
     */
    public synchronized List<TimeInterval> getShutdownSchedule(String city, String street, String houseNum) {
        System.out.println("\n--- Starting new scrape task for: " + city + ", " + street + ", " + houseNum + " ---");
        try {
            // 1. Open the page
            schedulePage.openPage();

            // 2. Close popup
            schedulePage.closeModalPopupIfPresent();

            // 3. Fill the form using provided data
            schedulePage.fillAddressForm(city, street, houseNum);

            // 4. Get the group name (and log it)
            String groupName = schedulePage.getGroupName();
            System.out.println("Found group in #group-name: " + groupName);

            // 5. Get the table HTML from the page
            String tableHtml = schedulePage.getActiveScheduleTableHtml();

            // 6. Pass the HTML to the parser and return the result
            return scheduleParser.parse(tableHtml);

        } catch (Exception e) {
            System.err.println("[SCRAPER ERROR] Failed to get schedule for " + city + ", " + street + ": " + e.getMessage());
            // It's important to return an empty list, not null,
            // so the notification service can compare it.
            return List.of();
        }
    }
}
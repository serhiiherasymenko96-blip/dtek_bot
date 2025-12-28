package ua.com.dtek.scraper.service;

import ua.com.dtek.scraper.dto.ScrapeResult;
import ua.com.dtek.scraper.dto.TimeInterval;
import ua.com.dtek.scraper.page.SchedulePage;
import ua.com.dtek.scraper.parser.ScheduleParser;
import ua.com.dtek.scraper.util.RetryUtil;
import java.util.List;

import static com.codeborne.selenide.Selenide.closeWebDriver;

public class DtekScraperService {

    private final SchedulePage schedulePage;
    private final ScheduleParser scheduleParser;

    public DtekScraperService(ScheduleParser parser) {
        this.schedulePage = new SchedulePage();
        this.scheduleParser = parser;
    }

    public void openSession() {
        System.out.println("--- Opening Browser Session ---");
        try {
            schedulePage.openPage();
            schedulePage.closeModalPopupIfPresent();
        } catch (Exception e) {
            closeSession();
            throw new RuntimeException("Failed to open browser: " + e.getMessage(), e);
        }
    }

    public ScrapeResult checkAddressInSession(String city, String street, String houseNum) {
        System.out.println("--- Checking address in session: " + city + ", " + street + " ---");

        String operationName = "address check for " + city + ", " + street;
        return RetryUtil.withRetry(() -> {
            // Step 1: Fill the address form
            System.out.println("Step 1: Filling address form...");
            schedulePage.fillAddressForm(city, street, houseNum);

            // Step 2: Get the group name
            System.out.println("Step 2: Getting group name...");
            String groupName = schedulePage.getGroupName();
            System.out.println("Found group in #group-name: " + groupName);

            // Step 3: Get the schedule table HTML
            System.out.println("Step 3: Getting schedule table...");
            String tableHtml = schedulePage.getActiveScheduleTableHtml();

            // Step 4: Parse the schedule
            System.out.println("Step 4: Parsing schedule...");
            List<TimeInterval> schedule = scheduleParser.parse(tableHtml);

            System.out.println("Successfully completed all steps for " + city + ", " + street);
            return new ScrapeResult(groupName, schedule);
        }, 2, operationName, 2000);
    }

    public void closeSession() {
        System.out.println("--- Closing Browser Session ---");
        try {
            closeWebDriver();
            // Явно викликаємо збирач сміття після закриття браузера
            System.gc();
        } catch (Exception e) {
            System.err.println("Error closing session: " + e.getMessage());
        }
    }
}

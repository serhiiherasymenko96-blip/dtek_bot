package ua.com.dtek.scraper.service;

import ua.com.dtek.scraper.dto.ScrapeResult;
import ua.com.dtek.scraper.dto.TimeInterval;
import ua.com.dtek.scraper.page.SchedulePage;
import ua.com.dtek.scraper.parser.ScheduleParser;
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
        try {
            schedulePage.fillAddressForm(city, street, houseNum);
            String groupName = schedulePage.getGroupName();
            System.out.println("Found group in #group-name: " + groupName);
            String tableHtml = schedulePage.getActiveScheduleTableHtml();
            List<TimeInterval> schedule = scheduleParser.parse(tableHtml);
            return new ScrapeResult(groupName, schedule);
        } catch (Exception e) {
            String msg = "[SCRAPER ERROR] Failed to check " + city + ", " + street + ": " + e.getMessage();
            System.err.println(msg);
            throw new RuntimeException(msg, e);
        }
    }

    public void closeSession() {
        System.out.println("--- Closing Browser Session ---");
        closeWebDriver();
    }
}
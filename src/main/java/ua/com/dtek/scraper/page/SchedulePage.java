package ua.com.dtek.scraper.page;

import com.codeborne.selenide.SelenideElement;

import java.time.Duration;

import static com.codeborne.selenide.Condition.*;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;
import ua.com.dtek.scraper.config.BrowserConfig;

/**
 * Page Object Model (POM) for the DTEK Shutdowns page.
 * <p>
 * This class encapsulates all web elements and interaction methods
 * for the schedule page, hiding Selenide-specific logic from the service layer.
 */
public class SchedulePage {

    // --- Element Selectors ---
    private final SelenideElement modalOverlay = $("div.modal__overlay--opacity");
    private final SelenideElement modalCloseButton = $("button.modal__close[data-micromodal-close]");

    private final SelenideElement cityInput = $("#city");
    private final SelenideElement streetInput = $("#street");
    private final SelenideElement houseInput = $("#house_num");

    private final SelenideElement cityDropdownItem = $("div#cityautocomplete-list.autocomplete-items div:first-child");
    private final SelenideElement streetDropdownItem = $("div#streetautocomplete-list.autocomplete-items div:first-child");
    private final SelenideElement houseDropdownItem = $("div#house_numautocomplete-list.autocomplete-items div:first-child");

    private final SelenideElement groupNameSpan = $("#group-name span");
    private final SelenideElement activeTableContainer = $(".discon-fact-table.active");

    // --- Page Actions ---

    /**
     * Opens the target URL in the browser.
     */
    public void openPage() {
        System.out.println("Opening DTEK URL: " + BrowserConfig.DTEK_URL);
        open(BrowserConfig.DTEK_URL);
    }

    /**
     * Checks for the modal overlay and closes it if present.
     */
    public void closeModalPopupIfPresent() {
        System.out.println("Checking for modal popup...");
        if (modalOverlay.is(visible, Duration.ofSeconds(10))) {
            System.out.println("Modal popup found. Closing...");
            modalCloseButton.click();
            modalOverlay.should(disappear);
            System.out.println("Modal popup closed successfully.");
        } else {
            System.out.println("Modal popup was not found.");
        }
    }

    /**
     * Fills the entire address form using the provided data.
     *
     * @param city     The city name.
     * @param street   The street name.
     * @param houseNum The house number.
     */
    public void fillAddressForm(String city, String street, String houseNum) {
        System.out.println("Filling address form...");

        // Fill City
        fillAndSelect(cityInput, city, cityDropdownItem);
        System.out.println("Filled city: " + city);

        // Fill Street
        fillAndSelect(streetInput, street, streetDropdownItem);
        System.out.println("Filled street: " + street);

        // Fill House Number
        fillAndSelect(houseInput, houseNum, houseDropdownItem);
        System.out.println("Filled house number: " + houseNum);
    }

    /**
     * Helper method to perform the click -> type -> select workflow.
     */
    private void fillAndSelect(SelenideElement input, String text, SelenideElement dropdownItem) {
        input.click();
        input.sendKeys(text);
        dropdownItem.shouldBe(visible).click();
    }

    /**
     * Waits for and retrieves the text from the group name element.
     *
     * @return The text of the group (e.g., "Group 5.1").
     */
    public String getGroupName() {
        try {
            groupNameSpan.shouldBe(visible).shouldNotBe(empty, Duration.ofSeconds(10));
            return groupNameSpan.getText();
        } catch (Exception e) {
            System.err.println("Could not find #group-name: " + e.getMessage());
            return "Group not found";
        }
    }

    /**
     * Waits for the active schedule table to be visible and returns its inner HTML.
     *
     * @return The HTML content of the active schedule table.
     */
    public String getActiveScheduleTableHtml() {
        System.out.println("\n--- Waiting for active schedule table ---");
        return activeTableContainer.shouldBe(visible).innerHtml();
    }
}
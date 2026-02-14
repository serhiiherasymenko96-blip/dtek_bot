package ua.com.dtek.scraper.config;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.WebDriverProvider;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

public final class BrowserConfig {

    public static final String DTEK_URL = "https://www.dtek-dnem.com.ua/ua/shutdowns";

    private BrowserConfig() {
    }

    public static void setupSelenide() {
        System.out.println("[DEBUG_LOG] BrowserConfig.setupSelenide: Configuring Selenide browser settings");
        Configuration.browser = CustomFirefoxProvider.class.getName();
        Configuration.browserSize = "1366x768";

        // Вмикаємо headless режим для продакшену
        Configuration.headless = true;
        System.out.println("[DEBUG_LOG] BrowserConfig.setupSelenide: Headless mode: " + Configuration.headless);

        Configuration.timeout = 20000;
        Configuration.pageLoadTimeout = 60000;
        Configuration.screenshots = false;
        Configuration.savePageSource = false;
        System.out.println("[DEBUG_LOG] BrowserConfig.setupSelenide: Timeout: " + Configuration.timeout + "ms, PageLoadTimeout: " + Configuration.pageLoadTimeout + "ms");
        System.out.println("[DEBUG_LOG] BrowserConfig.setupSelenide: Screenshots: " + Configuration.screenshots + ", SavePageSource: " + Configuration.savePageSource);
    }

    public static class CustomFirefoxProvider implements WebDriverProvider {

        @Override
        public WebDriver createDriver(Capabilities capabilities) {
            System.out.println("[DEBUG_LOG] BrowserConfig.createDriver: Starting Firefox driver initialization");
            FirefoxOptions options = new FirefoxOptions();

            System.out.println("[DEBUG_LOG] BrowserConfig.createDriver: Applying headless mode arguments");
            options.addArguments("-headless");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");

            // Вимикаємо BiDi (Fix для WebSocket error)
            System.out.println("[DEBUG_LOG] BrowserConfig.createDriver: Disabling BiDi and WebSocket");
            options.setCapability("webSocketUrl", (Object) null);
            options.setCapability("se:bidiEnabled", false);

            // Оптимізації Firefox для зменшення використання пам'яті
            System.out.println("[DEBUG_LOG] BrowserConfig.createDriver: Applying memory optimization preferences");
            options.addPreference("dom.ipc.processCount", 1); // Обмежуємо кількість процесів
            options.addPreference("permissions.default.image", 2); // Вимикаємо завантаження зображень
            System.out.println("[DEBUG_LOG] BrowserConfig.createDriver: Process count limited to 1, images disabled");

            // Додаткові оптимізації для зменшення споживання ресурсів
            System.out.println("[DEBUG_LOG] BrowserConfig.createDriver: Applying cache and session optimizations");
            options.addPreference("browser.cache.disk.enable", false);  // Вимкнути кеш на диску
            options.addPreference("browser.cache.memory.enable", false);  // Вимкнути кеш в пам'яті
            options.addPreference("browser.sessionhistory.max_entries", 2);  // Обмежити історію сесії
            options.addPreference("network.http.pipelining", true);  // Увімкнути HTTP pipelining
            options.addPreference("network.http.proxy.pipelining", true);
            options.addPreference("network.http.pipelining.maxrequests", 8);
            options.addPreference("browser.sessionstore.interval", 60000 * 30);  // Рідше зберігати стан сесії

            // User Agent
            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/109.0";
            options.addPreference("general.useragent.override", userAgent);
            System.out.println("[DEBUG_LOG] BrowserConfig.createDriver: User agent set: " + userAgent);

            options.merge(capabilities);
            System.out.println("[DEBUG_LOG] BrowserConfig.createDriver: Merged capabilities, creating FirefoxDriver instance");

            FirefoxDriver driver = new FirefoxDriver(options);
            System.out.println("[DEBUG_LOG] BrowserConfig.createDriver: FirefoxDriver successfully created");
            return driver;
        }
    }
}

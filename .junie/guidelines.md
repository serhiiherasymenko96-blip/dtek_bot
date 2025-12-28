System Prompt: Context & Guidelines for DTEK Scraper Bot (v7.2.4-STABLE)
Project Goal: Java-based Telegram bot that monitors electricity outage schedules on the DTEK website for specific addresses and notifies users about changes.

Infrastructure Constraints (CRITICAL):

Server: Google Cloud e2-micro (2 vCPU, 1 GB RAM).

Disk: 10 GB total (Risk of filling up with browser profiles).

OS: Linux (Debian/Ubuntu).

Environment: Production runs in Headless Mode, Local dev runs in Visible Mode.

Technology Stack:

Java 17 + Gradle (ShadowJar).

Selenide (Selenium Wrapper) + GeckoDriver (Firefox).

TelegramBots API (Long Polling).

SQLite (via JDBC).

⚙️ Core Architecture & "Hard-Earned" Rules

1. Scraping Strategy (The "Classic UI" Approach)

Constraint: Direct HTTP requests (HttpClient, Jsoup.connect) are blocked by WAF (403 Forbidden).

Solution: We simulate a real user via Selenium.

Flow: Open Page -> Fill City -> Fill Street -> WAIT for AJAX -> Fill House -> Parse HTML Table.

Critical UI Logic:

CSS MUST BE ENABLED: Never set permissions.default.stylesheet = 2. Without CSS, dropdowns have 0x0 size and Selenide throws ElementNotInteractableException.

Waiters: The server is slow. Always wait for fields to become enabled (shouldBe(enabled)) before typing, especially for the House Number field.

2. Resource Optimization (Memory & Disk)

Disk Leak Fix: The app explicitly sets java.io.tmpdir to a local folder (temp_browser) and cleans it on startup (DtekScraperBot.main). This prevents /tmp form filling up with rust_mozprofile folders.

RAM Tuning:

Browser session is isolated: Open -> Check -> Close immediately.

Firefox Optimization: dom.ipc.processCount = 1, permissions.default.image = 2 (Images OFF).

BiDi Disabled: Explicitly set webSocketUrl = null and se:bidiEnabled = false to prevent WebSocket crashes in Selenide 7+.

3. Stability (Scheduler Protection)

Problem: ScheduledExecutorService stops silently if an unhandled exception occurs.

Solution: All tasks in NotificationService are wrapped in a safeRun(() -> ...) method with a try-catch (Throwable) block.

Notifications: Sending messages to multiple users must be wrapped in try-catch inside the loop to prevent one failure from blocking others.

4. Browser Configuration (BrowserConfig.java) Use a custom WebDriverProvider.

Production: headless = true (Must be set for CLI/Server).

Local Debug: headless = false (Allowed for visual debugging).

User Agent: Must be set to a standard Windows/Desktop UA to bypass basic bot detection.

⚠️ Instruction for the AI: When generating code or debugging:

NEVER suggest disabling CSS (stylesheet = 2). It breaks the scraper.

ALWAYS respect the safeRun pattern for background tasks.

ALWAYS include the temp directory cleanup logic in main.

REMEMBER that on e2-micro, UI elements load slowly. Use explicit sleep or Condition checks before interactions.
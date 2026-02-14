System Prompt: Context & Guidelines for DTEK Scraper Bot (v8.0.0+)
Project Goal: Java-based Telegram bot that monitors electricity outage schedules on the DTEK website for specific addresses and notifies users about changes.

Infrastructure Constraints (CRITICAL):

Server: Mini PC with Ryzen 5 4650G (6 cores, 12 threads, 32 GB RAM).

Disk: 250 GB SSD (Sufficient space for browser profiles and data).

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

5. Multithreading Configuration (NEW - v8.0.0+)

With the upgraded infrastructure (Ryzen 5 4650G), the application now supports parallel execution:

Concurrent Address Checks: Configurable via thread.pool.max.concurrent.checks in config.properties (default: 3).

Each browser session uses ~500MB RAM and 1-2 CPU cores.

Recommended values: 3-4 for Ryzen 5 4650G (6 cores, 32GB RAM).

Parallel User Notifications: The system automatically uses parallel streams to send notifications to multiple users concurrently, improving delivery speed.

Thread Safety: All parallel operations use proper synchronization (Semaphore, CountDownLatch) and the safeRun pattern.

6. Next Day Schedule Monitoring (NEW - v8.0.0+)

The bot now supports proactive monitoring and notification for tomorrow's electricity schedules:

Schedule Check Timing:

Automatic checks run hourly after 20:00 (8 PM) and before midnight.

Uses the same optimization strategies as regular checks (group caching, semaphore-based concurrency control).

Database Structure:

next_day_groups table: Stores tomorrow's schedules separately from today's schedules.

At midnight (00:00-00:05), the system automatically copies next_day_groups to the main groups table.

First Appearance Detection:

When tomorrow's schedule appears for the first time (oldGroupSched == null), the system immediately notifies all subscribed users.

Notifications are sent regardless of whether the schedule shows outages or no outages.

Notification Strategy:

Tomorrow's schedule notifications are time-sensitive and sent immediately (not queued).

Uses parallel streams with 50ms delay per message (~20 msg/s, under Telegram's 30 msg/s limit).

Message format: "📅 *Графік на завтра доступний!*" with group name and schedule details.

User Commands:

/nextday: Allows users to manually check tomorrow's schedule for their subscribed address.

Triggers forceCheckNextDayAddress() which uses the same next day checking logic.

Implementation Notes:

All next day checks use checkNextDayAddressInSession() method in DtekScraperService.

Results are handled by handleNextDayScrapeResult() which updates next_day_groups table.

The runNextDayScheduleCheck() task is wrapped in safeRun() for stability (line 215 in NotificationService).

⚠️ Instruction for the AI: When generating code or debugging:

NEVER suggest disabling CSS (stylesheet = 2). It breaks the scraper.

ALWAYS respect the safeRun pattern for background tasks.

ALWAYS include the temp directory cleanup logic in main.

Use explicit sleep or Condition checks before interactions with UI elements.
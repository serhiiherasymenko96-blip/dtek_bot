package ua.com.dtek.scraper.util;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Utility class for monitoring and managing bot health.
 * Provides methods for marking the bot as unhealthy and scheduling health checks.
 */
public class BotHealthMonitor {

    private boolean isBotHealthy = true;
    private final Object healthLock = new Object();
    private final ScheduledExecutorService scheduler;
    private final TelegramLongPollingBot bot;

    /**
     * Creates a new BotHealthMonitor.
     *
     * @param bot The Telegram bot instance to monitor
     * @param scheduler The scheduler to use for health checks
     */
    public BotHealthMonitor(TelegramLongPollingBot bot, ScheduledExecutorService scheduler) {
        this.bot = bot;
        this.scheduler = scheduler;
    }

    /**
     * Marks the bot as unhealthy and schedules a health check.
     */
    public void markBotUnhealthy() {
        synchronized (healthLock) {
            if (isBotHealthy) {
                isBotHealthy = false;
                System.err.println("[BOT HEALTH] Bot marked as unhealthy, scheduling recovery check");
                scheduler.schedule(this::checkBotHealth, 60, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Checks if the bot is healthy by attempting to execute a simple operation.
     * If the bot is healthy, marks it as such. Otherwise, schedules another health check.
     */
    private void checkBotHealth() {
        try {
            // Attempt to execute a simple operation to check the bot's state
            bot.getMe();
            synchronized (healthLock) {
                isBotHealthy = true;
                System.out.println("[BOT HEALTH] Bot recovered successfully");
            }
        } catch (Exception e) {
            System.err.println("[BOT HEALTH] Bot still unhealthy: " + e.getMessage());
            scheduler.schedule(this::checkBotHealth, 60, TimeUnit.SECONDS);
        }
    }

    /**
     * Checks if the bot is currently healthy.
     *
     * @return true if the bot is healthy, false otherwise
     */
    public boolean isBotHealthy() {
        synchronized (healthLock) {
            return isBotHealthy;
        }
    }
}
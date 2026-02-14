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
        System.out.println("[DEBUG_LOG] BotHealthMonitor: Initializing health monitor");
        this.bot = bot;
        this.scheduler = scheduler;
        System.out.println("[DEBUG_LOG] BotHealthMonitor: Health monitor initialized, status: healthy");
    }

    /**
     * Marks the bot as unhealthy and schedules a health check.
     */
    public void markBotUnhealthy() {
        System.out.println("[DEBUG_LOG] BotHealthMonitor.markBotUnhealthy: Attempting to mark bot as unhealthy");
        synchronized (healthLock) {
            if (isBotHealthy) {
                isBotHealthy = false;
                System.err.println("[DEBUG_LOG] BotHealthMonitor.markBotUnhealthy: Bot marked as UNHEALTHY, scheduling recovery check in 60 seconds");
                scheduler.schedule(this::checkBotHealth, 60, TimeUnit.SECONDS);
            } else {
                System.out.println("[DEBUG_LOG] BotHealthMonitor.markBotUnhealthy: Bot already marked as unhealthy, skipping");
            }
        }
    }

    /**
     * Checks if the bot is healthy by attempting to execute a simple operation.
     * If the bot is healthy, marks it as such. Otherwise, schedules another health check.
     */
    private void checkBotHealth() {
        System.out.println("[DEBUG_LOG] BotHealthMonitor.checkBotHealth: Running health check");
        try {
            // Attempt to execute a simple operation to check the bot's state
            bot.getMe();
            synchronized (healthLock) {
                isBotHealthy = true;
                System.out.println("[DEBUG_LOG] BotHealthMonitor.checkBotHealth: Bot RECOVERED successfully, marked as healthy");
            }
        } catch (Exception e) {
            System.err.println("[DEBUG_LOG] BotHealthMonitor.checkBotHealth: Bot still UNHEALTHY: " + e.getMessage());
            System.out.println("[DEBUG_LOG] BotHealthMonitor.checkBotHealth: Scheduling another health check in 60 seconds");
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
            System.out.println("[DEBUG_LOG] BotHealthMonitor.isBotHealthy: Current health status queried: " + (isBotHealthy ? "HEALTHY" : "UNHEALTHY"));
            return isBotHealthy;
        }
    }
}
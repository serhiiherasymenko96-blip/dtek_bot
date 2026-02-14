package ua.com.dtek.scraper.util;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Utility class for handling Telegram message operations.
 * Provides methods for sending messages with retry logic and error handling.
 */
public class TelegramMessageHandler {

    private TelegramMessageHandler() {
        // Utility class, prevent instantiation
    }

    /**
     * Sends a message to a Telegram chat.
     *
     * @param bot The Telegram bot instance
     * @param chatId The chat ID to send the message to
     * @param message The message text
     * @return true if the message was sent successfully, false otherwise
     */
    public static boolean sendMessage(TelegramLongPollingBot bot, long chatId, String message) {
        System.out.println("[DEBUG_LOG] TelegramMessageHandler.sendMessage: Sending message to chatId " + chatId + " (length: " + message.length() + " chars)");
        SendMessage sm = new SendMessage();
        sm.setChatId(Long.toString(chatId));
        sm.setText(message);
        sm.setParseMode("Markdown");
        try { 
            bot.execute(sm);
            System.out.println("[DEBUG_LOG] TelegramMessageHandler.sendMessage: Successfully sent message to chatId " + chatId);
            return true;
        } catch (TelegramApiException e) { 
            System.err.println("[DEBUG_LOG] TelegramMessageHandler.sendMessage: Failed to send message to chatId " + chatId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Sends a message to a Telegram chat with retry logic.
     *
     * @param bot The Telegram bot instance
     * @param chatId The chat ID to send the message to
     * @param message The message text
     * @param maxRetries The maximum number of retry attempts
     * @return true if the message was sent successfully, false otherwise
     */
    public static boolean sendMessageWithRetry(TelegramLongPollingBot bot, long chatId, String message, int maxRetries) {
        System.out.println("[DEBUG_LOG] TelegramMessageHandler.sendMessageWithRetry: Starting message send with retry to chatId " + chatId + " (max retries: " + maxRetries + ", length: " + message.length() + " chars)");
        int attempts = 0;
        while (attempts < maxRetries) {
            try {
                attempts++;
                System.out.println("[DEBUG_LOG] TelegramMessageHandler.sendMessageWithRetry: Attempt " + attempts + "/" + maxRetries + " for chatId " + chatId);
                SendMessage sm = new SendMessage();
                sm.setChatId(Long.toString(chatId));
                sm.setText(message);
                sm.setParseMode("Markdown");
                bot.execute(sm);
                System.out.println("[DEBUG_LOG] TelegramMessageHandler.sendMessageWithRetry: Successfully sent message to chatId " + chatId + " on attempt " + attempts);
                return true;
            } catch (TelegramApiException e) {
                System.err.println("[DEBUG_LOG] TelegramMessageHandler.sendMessageWithRetry: Attempt " + attempts + "/" + maxRetries + " failed for chatId " + chatId + ": " + e.getMessage());
                if (attempts < maxRetries) {
                    long sleepMs = 1000L * attempts;
                    System.out.println("[DEBUG_LOG] TelegramMessageHandler.sendMessageWithRetry: Will retry after " + sleepMs + "ms (exponential backoff)");
                    try {
                        // Exponential backoff between retry attempts
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        System.err.println("[DEBUG_LOG] TelegramMessageHandler.sendMessageWithRetry: Sleep interrupted during retry backoff");
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    System.err.println("[DEBUG_LOG] TelegramMessageHandler.sendMessageWithRetry: All retry attempts exhausted for chatId " + chatId);
                }
            }
        }
        return false;
    }
}
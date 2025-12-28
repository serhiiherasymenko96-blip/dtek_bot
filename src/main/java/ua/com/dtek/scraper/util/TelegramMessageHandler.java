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
        SendMessage sm = new SendMessage();
        sm.setChatId(Long.toString(chatId));
        sm.setText(message);
        sm.setParseMode("Markdown");
        try { 
            bot.execute(sm); 
            return true;
        } catch (TelegramApiException e) { 
            System.err.println("[TELEGRAM ERROR] Failed to send message to " + chatId + ": " + e.getMessage());
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
        int attempts = 0;
        while (attempts < maxRetries) {
            try {
                SendMessage sm = new SendMessage();
                sm.setChatId(Long.toString(chatId));
                sm.setText(message);
                sm.setParseMode("Markdown");
                bot.execute(sm);
                return true;
            } catch (TelegramApiException e) {
                attempts++;
                System.err.println("[TELEGRAM RETRY " + attempts + "/" + maxRetries + "] Failed to send message: " + e.getMessage());
                if (attempts < maxRetries) {
                    try {
                        // Exponential backoff between retry attempts
                        Thread.sleep(1000 * attempts);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return false;
    }
}
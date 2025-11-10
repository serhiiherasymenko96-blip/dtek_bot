package ua.com.dtek.scraper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ua.com.dtek.scraper.config.AppConfig;
import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.TimeInterval;
import ua.com.dtek.scraper.service.DatabaseService;
import ua.com.dtek.scraper.service.DtekScraperService;
import ua.com.dtek.scraper.service.NotificationService;
import ua.com.dtek.scraper.parser.ScheduleParser;
import ua.com.dtek.scraper.config.BrowserConfig;

// --- NEW IMPORT (v4.2.1) ---
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
// --- END NEW IMPORT ---

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main entry point and the Telegram Bot class.
 * <p>
 * This class is responsible for:
 * 1. Starting the application (main method).
 * 2. Registering the bot with the Telegram API.
 * 3. Handling incoming user commands (like /start) and button clicks.
 * 4. Initializing and starting the background monitoring services.
 *
 * @author Serhii Herasymenko
 * @version 4.2.1
 */
public class DtekScraperBot extends TelegramLongPollingBot {

    private final AppConfig appConfig;
    private final DatabaseService dbService;
    private final NotificationService notificationService;

    // --- NEW (v4.2.0) ---
    // Added Gson and Type to deserialize schedules from DB on user request
    private final Gson gson = new Gson();
    private final Type scheduleListType = new TypeToken<List<TimeInterval>>() {}.getType();
    // --- END NEW ---

    public DtekScraperBot(AppConfig appConfig, DatabaseService dbService, NotificationService notificationService) {
        super(appConfig.getBotToken());
        this.appConfig = appConfig;
        this.dbService = dbService;
        this.notificationService = notificationService;
        // Pass a reference of the bot (this) to the notification service
        // so it can send messages.
        this.notificationService.setBot(this);
    }

    /**
     * Main application entry point.
     * Initializes and launches the bot and monitoring services.
     */
    public static void main(String[] args) {
        System.out.println("Starting DTEK Scraper Bot Service (v4.0.0)...");

        try {
            // 1. Load application configuration
            AppConfig config = new AppConfig();
            config.loadConfig();

            // 2. Configure the browser (headless, etc.)
            BrowserConfig.setupSelenide();

            // 3. Initialize core services
            DatabaseService db = new DatabaseService(config.getDatabasePath());
            db.initDatabase(config.getAddresses()); // Initialize DB and load addresses

            ScheduleParser parser = new ScheduleParser();
            DtekScraperService scraperService = new DtekScraperService(parser);

            // (FIX 1) Pass the 'parser' instance to the NotificationService constructor
            NotificationService notificationSvc = new NotificationService(db, scraperService, parser, config.getAddresses());

            // 4. Register and start the Telegram Bot
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            DtekScraperBot bot = new DtekScraperBot(config, db, notificationSvc);
            botsApi.registerBot(bot);

            // 5. Start the background monitoring tasks
            notificationSvc.startMonitoring();

            System.out.println("Bot successfully started and monitoring tasks are scheduled.");

        } catch (Exception e) {
            System.err.println("[FATAL] A top-level critical error occurred during startup:");
            e.printStackTrace();
            System.exit(1); // Exit if startup fails
        }
    }

    /**
     * Main handler for all incoming Telegram updates (messages, button clicks).
     */
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                // Handle text commands
                handleTextMessage(update);
            } else if (update.hasCallbackQuery()) {
                // Handle button clicks
                handleCallbackQuery(update.getCallbackQuery());
            }
        } catch (Exception e) {
            System.err.println("Error processing update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handles incoming text messages (e.g., /start).
     */
    private void handleTextMessage(Update update) throws TelegramApiException {
        long chatId = update.getMessage().getChatId();
        String messageText = update.getMessage().getText();
        String firstName = update.getMessage().getFrom().getFirstName();

        if ("/start".equals(messageText)) {
            System.out.println("Received /start command from user: " + chatId);
            dbService.registerUser(chatId, firstName); // Register user in DB
            sendWelcomeMessage(chatId);
        } else {
            SendMessage message = new SendMessage(String.valueOf(chatId), "Будь ласка, використайте команду /start, щоб почати.");
            execute(message);
        }
    }

    /**
     * Sends the initial welcome message with address selection buttons.
     */
    private void sendWelcomeMessage(Long chatId) throws TelegramApiException {
        String welcomeText = "Вітаю! 👋\n\n" +
                "Я бот для моніторингу графіків відключень ДТЕК.\n" +
                "Будь ласка, оберіть вашу адресу зі списку, щоб я міг надсилати вам сповіщення:";

        SendMessage message = new SendMessage(String.valueOf(chatId), welcomeText);
        message.setReplyMarkup(buildAddressKeyboard());
        execute(message);
    }

    /**
     * Creates the dynamic inline keyboard with address buttons from config.
     */
    private InlineKeyboardMarkup buildAddressKeyboard() {
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        Map<String, Address> addresses = appConfig.getAddresses();

        for (Map.Entry<String, Address> entry : addresses.entrySet()) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(entry.getValue().name()); // Button text (e.g., "Виконкомівська, 24/А")
            button.setCallbackData(entry.getKey());  // Button ID (e.g., "address_1")
            keyboard.add(List.of(button));
        }

        return new InlineKeyboardMarkup(keyboard);
    }

    /**
     * Handles button clicks (CallbackQuery).
     *
     * v4.2.1 Update: Added AnswerCallbackQuery to prevent duplicate client requests.
     */
    private void handleCallbackQuery(org.telegram.telegrambots.meta.api.objects.CallbackQuery callbackQuery) throws TelegramApiException {
        long chatId = callbackQuery.getMessage().getChatId();
        long messageId = callbackQuery.getMessage().getMessageId();
        String addressKey = callbackQuery.getData(); // e.g., "address.1"
        String callbackQueryId = callbackQuery.getId();

        // --- FIX (v4.2.1) ---
        // 1. Immediately answer the callback query.
        // This stops the "loading" spinner on the user's client
        // and prevents Telegram from re-sending the same query.
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        execute(answer);
        // --- END FIX ---

        System.out.println("User " + chatId + " selected address key: " + addressKey);

        // 1. Get the address details from the config
        Address selectedAddress = appConfig.getAddresses().get(addressKey);
        if (selectedAddress == null) {
            System.err.println("Critical: User " + chatId + " clicked unknown address key: " + addressKey);
            return;
        }

        // 2. Save the user's choice to the database
        dbService.setUserAddress(chatId, addressKey);
        System.out.println("User " + chatId + " subscribed to " + addressKey);

        // --- NEW LOGIC (v4.2.0) ---

        // 3. Get the most recent schedule from our *own* database (fast!)
        String scheduleJson = dbService.getSchedule(addressKey);
        List<TimeInterval> schedule = null;
        if (scheduleJson != null) {
            schedule = gson.fromJson(scheduleJson, scheduleListType);
        }

        // 4. Build the confirmation message *with* the current schedule
        String confirmationText = "✅ *Чудово! Ви підписалися на адресу:*\n" +
                "*" + selectedAddress.name() + "*\n\n" +
                formatScheduleForMessage(schedule); // Use new helper to format the schedule

        // --- END NEW LOGIC ---

        // 5. Send a confirmation message by editing the original message
        EditMessageText editMessage = new EditMessageText();
        editMessage.setChatId(String.valueOf(chatId));
        editMessage.setMessageId((int) messageId);
        editMessage.setText(confirmationText);
        editMessage.setParseMode("Markdown"); // Allow bold text
        editMessage.setReplyMarkup(null); // Remove buttons

        execute(editMessage);
    }

    /**
     * NEW (v4.2.0)
     * Helper method to format a schedule list into a human-readable string for a message.
     *
     * @param schedule The schedule list (can be null or empty).
     * @return A formatted string.
     */
    private String formatScheduleForMessage(List<TimeInterval> schedule) {
        if (schedule == null) {
            // Case 1: Bot has just started, no schedule scraped yet.
            return "Обробка запиту...\n_Поточний графік буде завантажено та показано тут протягом 30 хвилин._";
        }

        if (schedule.isEmpty()) {
            // Case 2: We scraped, and there are no shutdowns.
            return "💡 *Поточний графік:*\nВідключень на сьогодні не заплановано.";
        }

        // Case 3: We have a schedule.
        StringBuilder sb = new StringBuilder("💡 *Поточний графік:*\n");
        for (TimeInterval interval : schedule) {
            sb.append("•  `").append(interval.startTime()).append(" - ").append(interval.endTime()).append("`\n");
        }
        return sb.toString();
    }

    /**
     * Public method for NotificationService to send messages.
     *
     * @param chatId  The target user's chat ID.
     * @param message The text to send.
     */
    public void sendMessage(Long chatId, String message) {
        try {
            SendMessage sendMessage = new SendMessage(String.valueOf(chatId), message);
            sendMessage.setParseMode("Markdown"); // Enable formatting for all messages
            execute(sendMessage);
        } catch (TelegramApiException e) {
            System.err.println("Failed to send message to user " + chatId + ": " + e.getMessage());
            // TODO: Add logic here to handle blocked bots (e.g., remove user from DB)
        }
    }


    @Override
    public String getBotUsername() {
        return appConfig.getBotUsername();
    }
}
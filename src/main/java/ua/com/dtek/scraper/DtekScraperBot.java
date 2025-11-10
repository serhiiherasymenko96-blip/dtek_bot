package ua.com.dtek.scraper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ua.com.dtek.scraper.config.AppConfig;
import ua.com.dtek.scraper.config.BrowserConfig;
import ua.com.dtek.scraper.dto.Address;
import ua.com.dtek.scraper.dto.TimeInterval;
import ua.com.dtek.scraper.parser.ScheduleParser;
import ua.com.dtek.scraper.service.DatabaseService;
import ua.com.dtek.scraper.service.DtekScraperService;
import ua.com.dtek.scraper.service.NotificationService;

import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Main entry point and the Telegram Bot class.
 *
 * @author Serhii Herasymenko (Updated by Senior Dev)
 * @version 4.3.0 (Fixes memory leak)
 */
public class DtekScraperBot extends TelegramLongPollingBot {

    private final AppConfig appConfig;
    private final DatabaseService dbService;
    private final NotificationService notificationService;
    private final Gson gson = new Gson();
    private final Type scheduleListType = new TypeToken<List<TimeInterval>>() {}.getType();

    public DtekScraperBot(AppConfig appConfig, DatabaseService dbService, NotificationService notificationService) {
        super(appConfig.getBotToken());
        this.appConfig = appConfig;
        this.dbService = dbService;
        this.notificationService = notificationService;
        this.notificationService.setBot(this);
    }

    @Override
    public String getBotUsername() {
        return appConfig.getBotUsername();
    }

    public static void main(String[] args) {
        Instant start = Instant.now();
        System.out.println("Starting DTEK Scraper Bot Service (v4.3.0)...");

        try {
            // 1. Load application configuration
            AppConfig config = new AppConfig();
            config.loadConfig();

            // 2. Configure the browser (Selenide)
            BrowserConfig.setupSelenide();

            // 3. Initialize Database Service
            DatabaseService dbService = new DatabaseService(
                    config.getDatabasePath(),
                    config.getAddresses()
            );

            // 4. Initialize Database
            dbService.initDatabase();

            // 5. Initialize other services
            ScheduleParser parser = new ScheduleParser();
            DtekScraperService scraperService = new DtekScraperService(parser);
            NotificationService notificationService = new NotificationService(
                    dbService,
                    scraperService,
                    parser,
                    config.getAddresses()
            );

            // 6. Register and start the bot
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            DtekScraperBot bot = new DtekScraperBot(config, dbService, notificationService);
            botsApi.registerBot(bot);

            // 7. Start background monitoring tasks
            notificationService.startMonitoring();

            System.out.println("Bot successfully started and monitoring tasks are scheduled.");
            System.out.printf("Startup complete in %.3f s.\n",
                    Duration.between(start, Instant.now()).toMillis() / 1000.0);

        } catch (Exception e) {
            System.err.println("\n[FATAL] A top-level critical error occurred during startup:");
            e.printStackTrace();
            System.exit(1); // Exit if startup fails
        }
        // --- MEMORY LEAK FIX (v4.3.0) ---
        // We DO NOT call closeWebDriver() here anymore.
        // The bot is a long-running service.
        // NotificationService is now responsible for closing the browser
        // after its scheduled tasks.
        // --- END FIX ---
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleTextMessage(update);
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
            }
        } catch (TelegramApiException e) {
            System.err.println("Error processing update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleTextMessage(Update update) throws TelegramApiException {
        Message message = update.getMessage();
        long chatId = message.getChatId();

        if ("/start".equals(message.getText())) {
            System.out.println("Received /start command from user: " + chatId);
            sendWelcomeMessage(chatId);
        }
    }

    private void sendWelcomeMessage(Long chatId) throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(Long.toString(chatId)); // Use Long.toString for safety
        message.setText("👋 Вітаю!\n\nОберіть адресу, за якою ви хочете відслідковувати графіки відключень:");
        message.setReplyMarkup(buildAddressKeyboard());
        execute(message);
    }

    private InlineKeyboardMarkup buildAddressKeyboard() {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboardBuilder = InlineKeyboardMarkup.builder();
        Map<String, Address> addresses = appConfig.getAddresses();

        for (Map.Entry<String, Address> entry : addresses.entrySet()) {
            keyboardBuilder.keyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(entry.getValue().name())
                            .callbackData(entry.getKey()) // e.g., "address.1"
                            .build()
            ));
        }
        return keyboardBuilder.build();
    }

    /**
     * Handles button clicks (CallbackQuery).
     *
     * v4.2.3 Update: Fixes all compilation errors.
     */
    private void handleCallbackQuery(CallbackQuery callbackQuery) throws TelegramApiException {
        long chatId = callbackQuery.getMessage().getChatId();
        long messageId = callbackQuery.getMessage().getMessageId(); // This is 'long'
        String addressKey = callbackQuery.getData(); // e.g., "address.1"
        String callbackQueryId = callbackQuery.getId();
        User user = callbackQuery.getFrom();

        // 1. Immediately answer the callback query to stop the "loading" spinner
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        execute(answer);

        System.out.println("User " + chatId + " selected address key: " + addressKey);

        // 2. Get the address details from the config
        Address selectedAddress = appConfig.getAddresses().get(addressKey);
        if (selectedAddress == null) {
            System.err.println("Error: User " + chatId + " selected unknown address key: " + addressKey);
            return;
        }

        // 3. Save the user's choice to the database
        dbService.setUserAddress(chatId, addressKey);
        dbService.updateUserName(chatId, user.getFirstName());
        // (v4.2.1 bug fix: we no longer log "subscribed" here, removed duplicate log)

        // 4. Get the *current* cached schedule from the DB
        String scheduleJson = dbService.getSchedule(addressKey);

        // 5. Build the confirmation message
        EditMessageText editedMessage = new EditMessageText();
        editedMessage.setChatId(Long.toString(chatId)); // Use Long.toString
        editedMessage.setMessageId((int) messageId); // Cast long to int
        editedMessage.setParseMode("Markdown");

        StringBuilder textBuilder = new StringBuilder();
        textBuilder.append("✅ *Чудово! Ви підписалися на адресу:*\n");
        textBuilder.append(selectedAddress.name()).append("\n\n");
        textBuilder.append("💡 *Поточний графік:*\n");

        if (scheduleJson == null) {
            // Case 1: (v4.2.2 fix) Schedule is NULL (unknown)
            textBuilder.append("Обробка запиту... Графік для цієї адреси завантажується.\n\n");
            textBuilder.append("Ви отримаєте сповіщення, щойно він з'явиться (зазвичай протягом 30 хв).");
        } else {
            List<TimeInterval> schedule = gson.fromJson(scheduleJson, scheduleListType);
            if (schedule.isEmpty()) {
                // Case 2: Schedule is "[]" (empty list)
                textBuilder.append("Відключень на сьогодні не заплановано.");
            } else {
                // Case 3: Schedule has intervals
                for (TimeInterval interval : schedule) {
                    textBuilder.append("•  `").append(interval.startTime()).append(" - ").append(interval.endTime()).append("`\n");
                }
            }
        }

        editedMessage.setText(textBuilder.toString());
        execute(editedMessage);
    }

    /**
     * Public method to send a message (used by NotificationService).
     *
     * @param chatId  The target chat ID.
     * @param message The message text (Markdown supported).
     */
    public void sendMessage(long chatId, String message) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(Long.toString(chatId)); // Use Long.toString
        sendMessage.setText(message);
        sendMessage.setParseMode("Markdown");
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            System.err.println("Failed to send message to " + chatId + ": " + e.getMessage());
        }
    }
}
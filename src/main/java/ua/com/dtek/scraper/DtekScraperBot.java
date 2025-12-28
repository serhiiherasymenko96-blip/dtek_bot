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
import ua.com.dtek.scraper.util.BotHealthMonitor;
import ua.com.dtek.scraper.util.KeyboardBuilder;
import ua.com.dtek.scraper.util.ResourceMonitor;
import ua.com.dtek.scraper.util.TelegramMessageHandler;
import ua.com.dtek.scraper.util.TempDirectoryManager;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main class and the Telegram bot itself.
 *
 * @author Serhii Herasymenko
 * @version 8.0.0 (Compatible with groups and GroupSchedule)
 */
public class DtekScraperBot extends TelegramLongPollingBot {

    private final AppConfig appConfig;
    private final DatabaseService dbService;
    private final NotificationService notificationService;
    private final Gson gson = new Gson();
    private final Type scheduleListType = new TypeToken<List<TimeInterval>>() {}.getType();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final BotHealthMonitor healthMonitor;

    public DtekScraperBot(AppConfig appConfig, DatabaseService dbService, NotificationService notificationService) {
        super(appConfig.getBotToken());
        this.appConfig = appConfig;
        this.dbService = dbService;
        this.notificationService = notificationService;
        this.notificationService.setBot(this);
        this.healthMonitor = new BotHealthMonitor(this, scheduler);
    }

    @Override
    public String getBotUsername() {
        return appConfig.getBotUsername();
    }

    public static void main(String[] args) {
        Instant start = Instant.now();
        System.out.println("Starting DTEK Scraper Bot Service (v8.0.0)...");

        try {
            // 0. Set up temporary directory and clean it
            TempDirectoryManager.setupTempDirectory();

            // 1. Load configuration
            AppConfig config = new AppConfig();
            config.loadConfig();

            // 2. Set up browser
            BrowserConfig.setupSelenide();

            // 3. Initialize DB Service
            DatabaseService dbService = new DatabaseService(
                    config.getDatabasePath(),
                    config.getAddresses()
            );

            // 4. Initialize the database itself (create tables)
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

            // 7. Start background tasks
            notificationService.startMonitoring();

            // 7.1 Start resource monitoring
            ResourceMonitor resourceMonitor = new ResourceMonitor(
                    bot.getScheduler(),
                    System.getProperty("java.io.tmpdir"),
                    config.getDatabasePath()
            );
            resourceMonitor.startMonitoring();

            // 7.2 Set up regular database backup
            bot.getScheduler().scheduleAtFixedRate(() -> {
                try {
                    System.out.println("[BACKUP] Starting scheduled database backup...");
                    dbService.backupDatabase();
                } catch (Exception e) {
                    System.err.println("[BACKUP] Error during scheduled backup: " + e.getMessage());
                }
            }, 1, 24, TimeUnit.HOURS);

            // 7.3 Set up regular deep cleaning of temporary files
            bot.getScheduler().scheduleAtFixedRate(() -> {
                try {
                    System.out.println("[CLEANUP] Starting scheduled deep cleanup of temporary files...");
                    TempDirectoryManager.deepCleanTempDirectory();
                } catch (Exception e) {
                    System.err.println("[CLEANUP] Error during scheduled cleanup: " + e.getMessage());
                }
            }, 2, 12, TimeUnit.HOURS);

            // 8. Add hook for proper shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down bot...");
                try {
                    // Shutdown notification service first
                    notificationService.shutdown();

                    // Then shutdown the bot's scheduler
                    if (bot.getScheduler() != null) {
                        bot.shutdownScheduler();
                    }
                } catch (Exception e) {
                    System.err.println("Error during shutdown: " + e.getMessage());
                    e.printStackTrace();
                }
                System.out.println("Bot shutdown complete.");
            }));

            System.out.println("Bot successfully started and monitoring tasks are scheduled.");
            System.out.printf("Startup complete in %.3f s.\n",
                    Duration.between(start, Instant.now()).toMillis() / 1000.0);

        } catch (Exception e) {
            System.err.println("\n[FATAL] A top-level critical error occurred during startup:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Handle updates from Telegram
     * 
     * @param update The update from Telegram
     */
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleTextMessage(update);
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
            }
        } catch (Exception e) {
            System.err.println("[Handler ERROR] Error processing update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle text messages from users
     * 
     * @param update The update from Telegram
     * @throws TelegramApiException If there's an error sending a message
     */
    private void handleTextMessage(Update update) throws TelegramApiException {
        Message message = update.getMessage();
        long chatId = message.getChatId();
        String text = message.getText();

        if ("/start".equals(text)) {
            System.out.println("Received /start command from user: " + chatId);
            sendWelcomeMessage(chatId);
        } else if ("/check".equals(text)) {
            System.out.println("Received /check command from user: " + chatId);
            sendMessage(chatId, "🔍 Запускаю незаплановану перевірку графіків...");
            notificationService.forceCheckAllAddresses(chatId);
        } else if (text.startsWith("/broadcast")) {
            // Check if user is admin (you can add admin check here)
            System.out.println("Received /broadcast command from user: " + chatId);
            handleBroadcastCommand(chatId, text);
        }
    }

    /**
     * Handle the broadcast command to send a message to all subscribers
     * 
     * @param chatId The chat ID of the user who sent the command
     * @param text The full command text
     */
    private void handleBroadcastCommand(long chatId, String text) {
        // Extract the message from the command
        String broadcastMessage = text.substring("/broadcast".length()).trim();

        if (broadcastMessage.isEmpty()) {
            sendMessage(chatId, "⚠️ Будь ласка, вкажіть текст повідомлення після команди /broadcast");
            return;
        }

        // Get all users from the database
        List<Long> allUsers = dbService.getAllUsers();

        if (allUsers.isEmpty()) {
            sendMessage(chatId, "⚠️ Немає підписників для розсилки повідомлення");
            return;
        }

        // Send confirmation to the admin
        sendMessage(chatId, "📣 Розпочинаю розсилку повідомлення для " + allUsers.size() + " користувачів...");

        // Start a background task to send the broadcast
        scheduler.submit(() -> {
            try {
                // Use the NotificationService to broadcast the message
                int successCount = notificationService.broadcastToAllUsers(broadcastMessage, chatId);

                // Send completion notification to the admin
                sendMessage(chatId, "✅ Розсилку завершено. Повідомлення успішно доставлено " + successCount + " з " + (allUsers.size() - 1) + " користувачів.");
            } catch (Exception e) {
                System.err.println("[BROADCAST] Error during broadcast: " + e.getMessage());
                e.printStackTrace();
                sendMessage(chatId, "❌ Помилка під час розсилки: " + e.getMessage());
            }
        });
    }

    /**
     * Send welcome message to a new user
     * 
     * @param chatId The chat ID to send the message to
     */
    private void sendWelcomeMessage(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(Long.toString(chatId));
        message.setText("👋 Вітаю!\n\nОберіть адресу, за якою ви хочете відслідковувати графіки відключень:");
        message.setReplyMarkup(buildAddressKeyboard());
        try {
            execute(message);
        } catch (TelegramApiException e) {
            System.err.println("[TELEGRAM ERROR] Failed to send welcome message to " + chatId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Build the keyboard with address buttons
     * 
     * @return InlineKeyboardMarkup with address buttons
     */
    private InlineKeyboardMarkup buildAddressKeyboard() {
        return KeyboardBuilder.buildAddressKeyboard(appConfig.getAddresses());
    }

    /**
     * Handle button press.
     * (v8.0.0) Adapted for the new GroupSchedule structure.
     * @param callbackQuery The callback query from Telegram
     */
    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        long chatId = callbackQuery.getMessage().getChatId();
        long messageId = callbackQuery.getMessage().getMessageId();
        String addressKey = callbackQuery.getData();
        String callbackQueryId = callbackQuery.getId();
        User user = callbackQuery.getFrom();

        // 1. Respond immediately
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        try {
            execute(answer);
        } catch (TelegramApiException e) {
            System.err.println("[TELEGRAM ERROR] Failed to answer callback query " + callbackQueryId + ": " + e.getMessage());
            e.printStackTrace();
            return; // Exit early if we can't answer the callback query
        }

        System.out.println("User " + chatId + " selected address key: " + addressKey);

        // 2. Get address details
        Address selectedAddress = appConfig.getAddresses().get(addressKey);
        if (selectedAddress == null) {
            System.err.println("Error: User " + chatId + " selected unknown address key: " + addressKey);
            return;
        }

        // 3. Save subscription and name
        dbService.setUserAddress(chatId, addressKey);
        dbService.updateUserName(chatId, user.getFirstName());

        // 4. Get schedule (Group Logic v6/v7)
        String groupName = dbService.getGroupForAddress(addressKey);
        String scheduleJson = null;

        if (groupName != null) {
            // --- FIX: Working with GroupSchedule object ---
            DatabaseService.GroupSchedule groupSchedule = dbService.getScheduleForGroup(groupName);
            if (groupSchedule != null) {
                scheduleJson = groupSchedule.scheduleJson();
            }
            // --- END FIX ---
        }

        // 5. Prepare response
        EditMessageText editedMessage = new EditMessageText();
        editedMessage.setChatId(Long.toString(chatId));
        editedMessage.setMessageId((int) messageId);
        editedMessage.setParseMode("Markdown");

        StringBuilder textBuilder = new StringBuilder();
        textBuilder.append("✅ *Чудово! Ви підписалися на адресу:*\n");
        textBuilder.append(selectedAddress.name()).append("\n\n");
        textBuilder.append("💡 *Поточний графік:*\n");

        if (scheduleJson == null) {
            // Case 1: Schedule or group unknown
            textBuilder.append("⏳ Група невідома або графік завантажується...\n");
            textBuilder.append("_Зачекайте 1-2 хв, я надішлю сповіщення._");

            // Trigger forced check
            System.out.println("Triggering async check for " + addressKey + " for user " + chatId);
            notificationService.forceCheckAddress(addressKey, chatId);

        } else {
            List<TimeInterval> schedule = gson.fromJson(scheduleJson, scheduleListType);
            if (schedule.isEmpty()) {
                // Case 2: Schedule is empty
                textBuilder.append("Відключень на сьогодні не заплановано.");
            } else {
                // Case 3: Schedule exists
                textBuilder.append("_(Група: ").append(groupName).append(")_\n");
                for (TimeInterval interval : schedule) {
                    textBuilder.append("•  `").append(interval.startTime()).append(" - ").append(interval.endTime()).append("`\n");
                }
            }
        }

        editedMessage.setText(textBuilder.toString());
        try {
            execute(editedMessage);
        } catch (TelegramApiException e) {
            System.err.println("[TELEGRAM ERROR] Failed to edit message for " + chatId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Send a message to a user with retry mechanism
     * 
     * @param chatId The chat ID to send the message to
     * @param message The message text to send
     * @param maxRetries Maximum number of retry attempts
     * @return true if the message was sent successfully, false otherwise
     */
    public boolean sendMessageWithRetry(long chatId, String message, int maxRetries) {
        return TelegramMessageHandler.sendMessageWithRetry(this, chatId, message, maxRetries);
    }

    /**
     * Send a message to a user with default retry count (3)
     * 
     * @param chatId The chat ID to send the message to
     * @param message The message text to send
     * @return true if the message was sent successfully, false otherwise
     */
    public boolean sendMessage(long chatId, String message) {
        return sendMessageWithRetry(chatId, message, 3);
    }

    /**
     * Mark the bot as unhealthy, triggering health monitoring actions
     */
    public void markBotUnhealthy() {
        healthMonitor.markBotUnhealthy();
    }

    /**
     * Get the bot's scheduler for background tasks
     * 
     * @return The ScheduledExecutorService used by the bot
     */
    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    /**
     * Shutdown the bot's scheduler gracefully
     */
    public void shutdownScheduler() {
        ua.com.dtek.scraper.util.SchedulerUtil.shutdownScheduler(scheduler, 10, "[BOT] ");
    }
}

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

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Головний клас та сам Telegram-бот.
 *
 * @author Serhii Herasymenko
 * @version 7.0.0 (Сумісність з групами та GroupSchedule)
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
        System.out.println("Starting DTEK Scraper Bot Service (v7.0.0)...");

        try {
            // 1. Завантажити конфігурацію
            AppConfig config = new AppConfig();
            config.loadConfig();

            // 2. Налаштувати браузер
            BrowserConfig.setupSelenide();

            // 3. Ініціалізувати Сервіс БД
            DatabaseService dbService = new DatabaseService(
                    config.getDatabasePath(),
                    config.getAddresses()
            );

            // 4. Ініціалізувати саму БД (створити таблиці)
            dbService.initDatabase();

            // 5. Ініціалізувати інші сервіси
            ScheduleParser parser = new ScheduleParser();
            DtekScraperService scraperService = new DtekScraperService(parser);
            NotificationService notificationService = new NotificationService(
                    dbService,
                    scraperService,
                    parser,
                    config.getAddresses()
            );

            // 6. Зареєструвати та запустити бота
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            DtekScraperBot bot = new DtekScraperBot(config, dbService, notificationService);
            botsApi.registerBot(bot);

            // 7. Запустити фонові завдання
            notificationService.startMonitoring();

            System.out.println("Bot successfully started and monitoring tasks are scheduled.");
            System.out.printf("Startup complete in %.3f s.\n",
                    Duration.between(start, Instant.now()).toMillis() / 1000.0);

        } catch (Exception e) {
            System.err.println("\n[FATAL] A top-level critical error occurred during startup:");
            e.printStackTrace();
            System.exit(1);
        }
    }

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
        message.setChatId(Long.toString(chatId));
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
                            .callbackData(entry.getKey())
                            .build()
            ));
        }
        return keyboardBuilder.build();
    }

    /**
     * Обробка натискання кнопки.
     * (v7.0.0) Адаптовано під нову структуру GroupSchedule.
     */
    private void handleCallbackQuery(CallbackQuery callbackQuery) throws TelegramApiException {
        long chatId = callbackQuery.getMessage().getChatId();
        long messageId = callbackQuery.getMessage().getMessageId();
        String addressKey = callbackQuery.getData();
        String callbackQueryId = callbackQuery.getId();
        User user = callbackQuery.getFrom();

        // 1. Негайно відповідаємо
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        execute(answer);

        System.out.println("User " + chatId + " selected address key: " + addressKey);

        // 2. Отримуємо деталі адреси
        Address selectedAddress = appConfig.getAddresses().get(addressKey);
        if (selectedAddress == null) {
            System.err.println("Error: User " + chatId + " selected unknown address key: " + addressKey);
            return;
        }

        // 3. Зберігаємо підписку та ім'я
        dbService.setUserAddress(chatId, addressKey);
        dbService.updateUserName(chatId, user.getFirstName());

        // 4. Отримуємо графік (Логіка Груп v6/v7)
        String groupName = dbService.getGroupForAddress(addressKey);
        String scheduleJson = null;

        if (groupName != null) {
            // --- FIX: Працюємо з об'єктом GroupSchedule ---
            DatabaseService.GroupSchedule groupSchedule = dbService.getScheduleForGroup(groupName);
            if (groupSchedule != null) {
                scheduleJson = groupSchedule.scheduleJson();
            }
            // --- END FIX ---
        }

        // 5. Готуємо відповідь
        EditMessageText editedMessage = new EditMessageText();
        editedMessage.setChatId(Long.toString(chatId));
        editedMessage.setMessageId((int) messageId);
        editedMessage.setParseMode("Markdown");

        StringBuilder textBuilder = new StringBuilder();
        textBuilder.append("✅ *Чудово! Ви підписалися на адресу:*\n");
        textBuilder.append(selectedAddress.name()).append("\n\n");
        textBuilder.append("💡 *Поточний графік:*\n");

        if (scheduleJson == null) {
            // Case 1: Графік або група невідомі
            textBuilder.append("⏳ Група невідома або графік завантажується...\n");
            textBuilder.append("_Зачекайте 1-2 хв, я надішлю сповіщення._");

            // Запускаємо примусову перевірку
            System.out.println("Triggering async check for " + addressKey + " for user " + chatId);
            notificationService.forceCheckAddress(addressKey, chatId);

        } else {
            List<TimeInterval> schedule = gson.fromJson(scheduleJson, scheduleListType);
            if (schedule.isEmpty()) {
                // Case 2: Графік порожній
                textBuilder.append("Відключень на сьогодні не заплановано.");
            } else {
                // Case 3: Графік є
                textBuilder.append("_(Група: ").append(groupName).append(")_\n");
                for (TimeInterval interval : schedule) {
                    textBuilder.append("•  `").append(interval.startTime()).append(" - ").append(interval.endTime()).append("`\n");
                }
            }
        }

        editedMessage.setText(textBuilder.toString());
        execute(editedMessage);
    }

    public void sendMessage(long chatId, String message) {
        SendMessage sm = new SendMessage();
        sm.setChatId(Long.toString(chatId));
        sm.setText(message);
        sm.setParseMode("Markdown");
        try { execute(sm); } catch (TelegramApiException e) { e.printStackTrace(); }
    }
}
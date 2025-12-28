package ua.com.dtek.scraper.util;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ua.com.dtek.scraper.dto.Address;

import java.util.List;
import java.util.Map;

/**
 * Utility class for building Telegram keyboard markups.
 */
public class KeyboardBuilder {

    private KeyboardBuilder() {
        // Utility class, prevent instantiation
    }

    /**
     * Builds an inline keyboard with address buttons.
     *
     * @param addresses Map of address keys to Address objects
     * @return InlineKeyboardMarkup with address buttons
     */
    public static InlineKeyboardMarkup buildAddressKeyboard(Map<String, Address> addresses) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboardBuilder = InlineKeyboardMarkup.builder();

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
}
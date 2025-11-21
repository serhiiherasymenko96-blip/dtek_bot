package ua.com.dtek.scraper.dto;

/**
 * DTO для передачі інформації про адресу разом з даними про групу.
 */
public record AddressInfo(
        String addressKey,
        Address address,
        String groupName,       // Може бути null, якщо група ще не визначена
        long groupLastChecked   // Час останньої верифікації групи
) {
}
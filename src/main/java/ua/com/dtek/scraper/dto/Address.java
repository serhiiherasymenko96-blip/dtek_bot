package ua.com.dtek.scraper.dto;

/**
 * DTO (Data Transfer Object) to represent a monitored address.
 * Using a Java Record for an immutable, simple data carrier.
 *
 * @param name     The display name (e.g., "Виконкомівська, 24/А")
 * @param city     The city for the form (e.g., "м. Дніпро")
 * @param street   The street for the form (e.g., "вул. Виконкомівська")
 * @param houseNum The house number for the form (e.g., "24/А")
 */
public record Address(
        String addressKey, // Унікальний ID (напр. "address.1")
        String name,
        String city,
        String street,
        String houseNum
) {
}
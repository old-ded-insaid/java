package app.service;

/**
 * Ошибка бизнес-правила: «вес должен быть больше 0», «пульс 500 — это опечатка».
 *
 * Это НЕ техническая ошибка БД. UI должен показать message пользователю
 * спокойным тоном, а не «что-то сломалось».
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    /** Тот же формат сообщения, но с причиной — удобно для разбора логов. */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

package app.desktop.ui;

/**
 * Строка таблицы замеров.
 * Снимок данных для UI: значения + относительный путь к картинке (или null).
 */
public record MeasurementRow(
        long id,
        String dateText,
        String kindTitle,
        String valueText,
        String note,
        String imagePath
) {
}

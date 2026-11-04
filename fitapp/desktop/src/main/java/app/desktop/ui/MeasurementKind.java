package app.desktop.ui;

/**
 * Виды замеров: значение для БД (kind) + подпись для человека.
 * Список совпадает с CHECK-ограничением таблицы measurements.
 */
public enum MeasurementKind {

    MOBILITY("mobility", "Подвижность"),
    PULSE_REST("pulse_rest", "Пульс покоя"),
    BODY_WEIGHT("body_weight", "Вес тела"),
    OTHER("other", "Другое");

    private final String dbValue;
    private final String title;

    MeasurementKind(String dbValue, String title) {
        this.dbValue = dbValue;
        this.title = title;
    }

    public String dbValue() {
        return dbValue;
    }

    public String title() {
        return title;
    }

    /** "body_weight" -> "Вес тела" (для таблицы). */
    public static String titleOf(String dbValue) {
        for (MeasurementKind kind : values()) {
            if (kind.dbValue.equals(dbValue)) {
                return kind.title;
            }
        }
        return dbValue == null ? "" : dbValue;
    }

    /** Подпись в ComboBox вместо "MOBILITY". */
    @Override
    public String toString() {
        return title;
    }
}

package app.service;

import app.db.Database;
import app.model.Measurement;
import app.model.MediaItem;
import app.model.User;
import app.repository.Repositories;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * Замеры: подвижность, пульс покоя, вес тела.
 *
 * Картинка (скриншот пульса, фото наклона) хранится файлом на диске,
 * а в БД пишется строка media с относительным путём и ссылкой на замер.
 */
public class MeasurementService {

    public static final String KIND_MOBILITY = "mobility";
    public static final String KIND_PULSE_REST = "pulse_rest";
    public static final String KIND_BODY_WEIGHT = "body_weight";
    public static final String KIND_OTHER = "other";

    /** Предел колонки NUMERIC(8,2) для measurements.value_num (BUG M7). */
    public static final BigDecimal MAX_MEASUREMENT_VALUE = new BigDecimal("999999.99");

    private static final Set<String> ALLOWED_KINDS =
            Set.of(KIND_MOBILITY, KIND_PULSE_REST, KIND_BODY_WEIGHT, KIND_OTHER);

    private final Database database;

    public MeasurementService(Database database) {
        this.database = database;
    }

    /** Все замеры пользователя, новые сверху; картинка уже подгружена. */
    public List<Measurement> list(long userId) {
        return database.inTransaction(em ->
                new Repositories(em).measurements().findByUserIdWithMedia(userId));
    }

    /**
     * Замеры одного типа за период — данные для графика.
     *
     * @param kind тип замера (body_weight, pulse_rest, mobility);
     *             null — замеры «другого» типа не строятся, это ошибка вызова
     * @param from начало периода включительно; null — месяц до {@code to}
     * @param to   конец периода включительно; null — сегодня
     * @return замеры по возрастанию даты (слева направо по оси X)
     */
    public List<Measurement> getMeasurementsForChart(long userId,
                                                     String kind,
                                                     LocalDate from,
                                                     LocalDate to) {
        if (kind == null || !ALLOWED_KINDS.contains(kind)) {
            throw new ValidationException("Неизвестный тип замера: " + kind);
        }

        LocalDate end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        LocalDate start = from == null ? end.minusMonths(1) : from;

        // даты ввели «наоборот» — не ругаемся, просто читаем период правильно
        if (start.isAfter(end)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }

        LocalDateTime fromTime = start.atStartOfDay();
        // конец периода включительно: последняя секунда последнего дня
        LocalDateTime toTime = end.atTime(LocalTime.MAX);

        return database.inTransaction(em ->
                new Repositories(em).measurements()
                        .findForChart(userId, kind, fromTime, toTime)
                        .stream()
                        // замер без числа (например, только фото) на графике не рисуется
                        .filter(item -> item.getValueNum() != null)
                        .toList());
    }

    /** Замер без картинки. */
    public Measurement add(long userId,
                           String kind,
                           String name,
                           BigDecimal valueNum,
                           String valueUnit,
                           String note) {
        return add(userId, kind, name, valueNum, valueUnit, note, null);
    }

    /**
     * Замер с картинкой.
     *
     * @param imagePath относительный путь внутри media-папки
     *                  (например measurements/2026-11/abc.png) или null
     */
    public Measurement add(long userId,
                           String kind,
                           String name,
                           BigDecimal valueNum,
                           String valueUnit,
                           String note,
                           String imagePath) {

        if (kind == null || !ALLOWED_KINDS.contains(kind)) {
            throw new ValidationException("Неизвестный тип замера: " + kind);
        }
        if (name == null || name.isBlank()) {
            throw new ValidationException("Укажите название замера, например «наклон вперёд»");
        }
        if (valueNum != null && valueNum.signum() < 0) {
            throw new ValidationException("Значение замера не может быть отрицательным: " + valueNum);
        }
        if (valueNum != null && valueNum.compareTo(MAX_MEASUREMENT_VALUE) > 0) {
            throw new ValidationException("Слишком большое значение: замер не может быть больше "
                    + MAX_MEASUREMENT_VALUE);
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            User user = repos.users().findById(userId)
                    .orElseThrow(() -> new ValidationException("Пользователь не найден: " + userId));

            Measurement measurement = repos.measurements().save(Measurement.builder()
                    .user(user)
                    .measuredAt(LocalDateTime.now(ZoneOffset.UTC))
                    .kind(kind)
                    .name(name.trim())
                    .valueNum(valueNum)
                    .valueUnit(blankToNull(valueUnit))
                    .note(blankToNull(note))
                    .build());

            if (imagePath != null && !imagePath.isBlank()) {
                // сначала замер (media.measurement_id на него ссылается),
                // потом файл — и обратная ссылка measurements.media_id
                MediaItem media = repos.media().save(MediaItem.builder()
                        .measurement(measurement)
                        .purpose(purposeFor(kind))
                        .filePath(imagePath.trim())
                        .build());

                measurement.setMedia(media);
            }

            return measurement;
        });
    }

    /**
     * Удаляет замер. Строка media уходит каскадом (FK ON DELETE CASCADE),
     * файл на диске удаляет вызывающий — ему известен путь.
     */
    public void delete(long measurementId) {
        database.runInTransaction(em ->
                new Repositories(em).measurements().deleteById(measurementId));
    }

    /** Какое значение purpose писать в media для этого типа замера. */
    private static String purposeFor(String kind) {
        return switch (kind) {
            case KIND_MOBILITY -> "mobility_photo";
            case KIND_PULSE_REST -> "pulse_graph";
            default -> "other";
        };
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}

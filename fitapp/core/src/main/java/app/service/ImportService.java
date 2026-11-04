package app.service;

import app.db.Database;
import app.dto.FullBackupDto;
import app.repository.Repositories;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Восстановление базы из полного JSON-бэкапа (см. {@link FullBackupDto}).
 *
 * Главное правило: весь импорт — ОДНА транзакция. Ошибка на любом шаге
 * (битый JSON, ссылка на несуществующий план, нарушение CHECK) откатывает
 * всё, и база остаётся такой, какой была до нажатия кнопки.
 *
 * Почему JDBC, а не сущности Hibernate: бэкап хранит исходные id, а связи
 * (plan_id, workout_exercise_id, ...) на них ссылаются. С обычным persist()
 * Hibernate перегенерирует id и связи поедут, а merge() с чужим id ищет
 * строку в базе и вставляет только отсутствующее. Прямой INSERT с явным id
 * предсказуем и позволяет восстановить файл «как есть».
 *
 * Пользователи и настройки импорт не трогает: данные привязываются
 * к текущему пользователю (или к тому, кого передали явно).
 */
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /** Порядок удаления: сначала «дети», потом «родители» (иначе FOREIGN KEY). */
    private static final List<String> DELETE_ORDER = List.of(
            "DELETE FROM sets",
            "DELETE FROM workout_exercises",
            "DELETE FROM measurements",
            "DELETE FROM media",
            "DELETE FROM comments",
            "DELETE FROM workouts",
            "DELETE FROM schedule_items",
            "DELETE FROM schedule",
            "DELETE FROM plan_exercises",
            "DELETE FROM workout_plans",
            "DELETE FROM exercise_equipment",
            "DELETE FROM exercises",
            "DELETE FROM equipment");

    /** Таблицы с BIGSERIAL, у которых после импорта нужно поправить счётчик. */
    private static final List<String> SEQUENCE_TABLES = List.of(
            "equipment", "exercises", "workout_plans", "plan_exercises", "schedule",
            "schedule_items", "workouts", "workout_exercises", "sets", "measurements", "media");

    private final Database database;
    private final ExportService exportService;

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public ImportService(Database database, ExportService exportService) {
        this.database = database;
        this.exportService = exportService;
    }

    /** Итог восстановления — то, что показываем пользователю. */
    public record ImportResult(int workouts,
                               int plans,
                               int exercises,
                               int equipment,
                               int schedules,
                               int measurements,
                               int sets,
                               Path safetyCopy) {

        public String summary() {
            return "Загружено " + workouts + " тренировок, " + plans + " шаблонов, "
                    + exercises + " упражнений, " + equipment + " позиций инвентаря, "
                    + schedules + " расписаний, " + measurements + " замеров (" + sets + " подходов).";
        }
    }

    // ------------------------------------------------------------------ импорт

    /** Импорт в текущего (первого) пользователя базы. */
    public ImportResult importFullBackup(Path jsonFile) {
        return importFullBackup(jsonFile, null);
    }

    /**
     * @param jsonFile файл, созданный кнопкой «Бэкап (JSON)»
     * @param userId   кому привязать данные; null — первый пользователь базы
     */
    public ImportResult importFullBackup(Path jsonFile, Long userId) {
        FullBackupDto backup = readBackup(jsonFile);
        checkVersion(backup);
        validateStructure(backup);

        long owner = resolveOwner(userId);

        // Импорт затирает данные, поэтому ДО него сохраняем то, что есть сейчас.
        // Это страховка от «нажал импорт не того файла»: копия лежит рядом с файлом.
        Path safetyCopy = writeSafetyCopy(jsonFile, owner);

        try {
            ImportResult result = database.inTransaction(em -> em.unwrap(Session.class)
                    .doReturningWork(connection -> {
                        clearData(connection);
                        writeAll(connection, backup, owner);
                        return new ImportResult(
                                size(backup.getWorkouts()),
                                size(backup.getPlans()),
                                size(backup.getExercises()),
                                size(backup.getEquipment()),
                                size(backup.getSchedules()),
                                size(backup.getMeasurements()),
                                size(backup.getSets()),
                                safetyCopy);
                    }));

            // B5: счётчики BIGSERIAL трогаем ОТДЕЛЬНОЙ транзакцией, уже после
            // сохранения данных. setval — не критичная часть: если он не прошёл,
            // данные всё равно восстановлены, а следующий INSERT просто возьмёт
            // следующий id и, при совпадении, упадёт на PK (это видно и лечится).
            resetSequencesAfterImport();

            log.info("Импорт из {}: {}", jsonFile, result.summary());
            return result;
        } catch (ValidationException e) {
            throw e;    // уже понятное сообщение, транзакция откатилась
        } catch (RuntimeException e) {
            throw new ValidationException("Не удалось восстановить базу из файла: "
                    + rootMessage(e) + ". Изменения отменены.", e);
        }
    }

    /**
     * Обновляет счётчики id отдельной транзакцией (B5).
     *
     * Ошибку не пробрасываем: данные уже сохранены и откатывать их из-за
     * неудачного setval нельзя. В лог — предупреждение.
     */
    private void resetSequencesAfterImport() {
        try {
            database.inTransaction(em -> em.unwrap(Session.class)
                    .doReturningWork(connection -> {
                        resetSequences(connection);
                        return null;
                    }));
        } catch (RuntimeException e) {
            log.warn("Последовательности не обновлены: {}", rootMessage(e));
        }
    }

    private long resolveOwner(Long userId) {
        if (userId != null) {
            return userId;
        }
        return database.inTransaction(em -> currentUserId(new Repositories(em)));
    }

    /** Снимок текущей базы рядом с импортируемым файлом: fitapp-before-import-<дата>.json. */
    private Path writeSafetyCopy(Path source, long owner) {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(LocalDateTime.now());
        Path target = source.toAbsolutePath().resolveSibling("fitapp-before-import-" + stamp + ".json");
        try {
            return exportService.exportWorkoutsToJson(owner, target);
        } catch (RuntimeException e) {
            throw new ValidationException(
                    "Не удалось создать страховочную копию перед импортом (" + target + "): "
                            + e.getMessage() + ". Импорт отменён, данные не тронуты.", e);
        }
    }

    // ------------------------------------------------------------ чтение файла

    private FullBackupDto readBackup(Path jsonFile) {
        if (jsonFile == null || !Files.isRegularFile(jsonFile)) {
            throw new ValidationException("Файл бэкапа не найден: " + jsonFile);
        }
        try (InputStream in = Files.newInputStream(jsonFile)) {
            FullBackupDto backup = mapper.readValue(in, FullBackupDto.class);
            if (backup == null) {
                throw new ValidationException("Файл пустой: " + jsonFile);
            }
            return backup;
        } catch (IOException e) {
            throw new ValidationException("Не удалось прочитать JSON-бэкап: " + e.getMessage(), e);
        }
    }

    /** Старый формат (только тренировки, без version) восстановить нельзя. */
    private static void checkVersion(FullBackupDto backup) {
        if (backup.getVersion() <= 0) {
            throw new ValidationException("Это старый бэкап: в файле нет поля \"version\". "
                    + "Восстановление поддерживает только полные бэкапы (version "
                    + FullBackupDto.CURRENT_VERSION + ").");
        }
        if (backup.getVersion() > FullBackupDto.CURRENT_VERSION) {
            throw new ValidationException("Файл создан более новой версией fitapp (version "
                    + backup.getVersion() + "), эта версия понимает до "
                    + FullBackupDto.CURRENT_VERSION + ".");
        }
    }

    /** Явные null-списки и обязательные поля — до открытия транзакции. */
    private static void validateStructure(FullBackupDto backup) {
        backup.setEquipment(listOrEmpty(backup.getEquipment()));
        backup.setExercises(listOrEmpty(backup.getExercises()));
        backup.setExerciseEquipment(listOrEmpty(backup.getExerciseEquipment()));
        backup.setPlans(listOrEmpty(backup.getPlans()));
        backup.setPlanExercises(listOrEmpty(backup.getPlanExercises()));
        backup.setSchedules(listOrEmpty(backup.getSchedules()));
        backup.setScheduleItems(listOrEmpty(backup.getScheduleItems()));
        backup.setWorkouts(listOrEmpty(backup.getWorkouts()));
        backup.setWorkoutExercises(listOrEmpty(backup.getWorkoutExercises()));
        backup.setSets(listOrEmpty(backup.getSets()));
        backup.setMeasurements(listOrEmpty(backup.getMeasurements()));
        backup.setMedia(listOrEmpty(backup.getMedia()));

        for (FullBackupDto.EquipmentDto item : backup.getEquipment()) {
            require(item.getId(), "инвентарь без id");
            requireText(item.getName(), "инвентарь без названия");
        }
        for (FullBackupDto.ExerciseDto item : backup.getExercises()) {
            require(item.getId(), "упражнение без id");
            requireText(item.getName(), "упражнение без названия");
        }
        for (FullBackupDto.PlanDto item : backup.getPlans()) {
            require(item.getId(), "шаблон без id");
            requireText(item.getName(), "шаблон без названия");
        }
        for (FullBackupDto.PlanExerciseDto item : backup.getPlanExercises()) {
            require(item.getId(), "строка шаблона без id");
            require(item.getPlanId(), "строка шаблона без planId");
            require(item.getExerciseId(), "строка шаблона без exerciseId");
        }
        for (FullBackupDto.ScheduleDto item : backup.getSchedules()) {
            require(item.getId(), "расписание без id");
            require(item.getStartDate(), "расписание без startDate");
            require(item.getIntervalDays(), "расписание без intervalDays");
        }
        for (FullBackupDto.ScheduleItemDto item : backup.getScheduleItems()) {
            require(item.getId(), "пункт расписания без id");
            require(item.getScheduleId(), "пункт расписания без scheduleId");
            require(item.getPlanId(), "пункт расписания без planId");
        }
        for (FullBackupDto.WorkoutDto item : backup.getWorkouts()) {
            require(item.getId(), "тренировка без id");
            require(item.getWorkoutDate(), "тренировка без workoutDate");
        }
        for (FullBackupDto.WorkoutExerciseDto item : backup.getWorkoutExercises()) {
            require(item.getId(), "упражнение тренировки без id");
            require(item.getWorkoutId(), "упражнение тренировки без workoutId");
            require(item.getExerciseId(), "упражнение тренировки без exerciseId");
        }
        for (FullBackupDto.SetDto item : backup.getSets()) {
            require(item.getId(), "подход без id");
            require(item.getWorkoutExerciseId(), "подход без workoutExerciseId");
            require(item.getSetNumber(), "подход без setNumber");
        }
        for (FullBackupDto.MeasurementDto item : backup.getMeasurements()) {
            require(item.getId(), "замер без id");
            require(item.getMeasuredAt(), "замер без measuredAt");
            requireText(item.getKind(), "замер без kind");
            requireText(item.getName(), "замер без названия");
        }
        for (FullBackupDto.MediaDto item : backup.getMedia()) {
            require(item.getId(), "файл media без id");
            require(item.getMeasurementId(), "файл media без measurementId");
            requireText(item.getFilePath(), "файл media без filePath");
        }
    }

    // ------------------------------------------------------------- транзакция

    private static long currentUserId(Repositories repos) {
        return repos.users().findAll().stream()
                .map(user -> user.getId())
                .findFirst()
                .orElseThrow(() -> new ValidationException(
                        "В базе нет пользователя — сначала запустите приложение, "
                                + "чтобы был создан профиль по умолчанию"));
    }

    private static void clearData(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : DELETE_ORDER) {
                statement.executeUpdate(sql);
            }
        }
    }

    private static void writeAll(Connection connection, FullBackupDto backup, long owner)
            throws SQLException {

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 1. Инвентарь
        for (FullBackupDto.EquipmentDto item : backup.getEquipment()) {
            insert(connection,
                    "INSERT INTO equipment (id, name, weight_grams, description) VALUES (?, ?, ?, ?)",
                    item.getId(), item.getName(), item.getWeightGrams(), item.getDescription());
        }

        // 2. Справочник упражнений
        for (FullBackupDto.ExerciseDto item : backup.getExercises()) {
            insert(connection,
                    "INSERT INTO exercises (id, name, setup, movement, muscles, avoid, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    item.getId(), item.getName(), item.getSetup(), item.getMovement(),
                    item.getMuscles(), item.getAvoid(), firstNonNull(item.getCreatedAt(), now));
        }

        // 3. Инвентарь упражнений (зависит от 1 и 2)
        for (FullBackupDto.ExerciseEquipmentDto item : backup.getExerciseEquipment()) {
            insert(connection,
                    "INSERT INTO exercise_equipment (exercise_id, equipment_id, quantity)"
                            + " VALUES (?, ?, ?)",
                    item.getExerciseId(), item.getEquipmentId(),
                    item.getQuantity() == null ? 1 : item.getQuantity());
        }

        // 4. Шаблоны
        for (FullBackupDto.PlanDto item : backup.getPlans()) {
            insert(connection,
                    "INSERT INTO workout_plans (id, user_id, name, description, active, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    item.getId(), owner, item.getName(), item.getDescription(),
                    item.getActive() == null || item.getActive(), firstNonNull(item.getCreatedAt(), now));
        }

        // 5. Состав шаблонов
        for (FullBackupDto.PlanExerciseDto item : backup.getPlanExercises()) {
            insert(connection,
                    "INSERT INTO plan_exercises (id, plan_id, exercise_id, order_index,"
                            + " target_sets, target_reps, target_weight_kg, target_time_sec,"
                            + " equipment_snapshot)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    item.getId(), item.getPlanId(), item.getExerciseId(),
                    item.getOrderIndex() == null ? 1 : item.getOrderIndex(),
                    item.getTargetSets(), item.getTargetReps(),
                    item.getTargetWeightKg(), item.getTargetTimeSec(),
                    item.getEquipmentSnapshot());
        }

        // 6. Расписания (start_plan_id ссылается на шаблоны)
        for (FullBackupDto.ScheduleDto item : backup.getSchedules()) {
            insert(connection,
                    "INSERT INTO schedule (id, user_id, name, start_date, interval_days, active, start_plan_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    item.getId(), owner, item.getName(), item.getStartDate(),
                    item.getIntervalDays(), item.getActive() == null || item.getActive(),
                    item.getStartPlanId());
        }

        // 7. Очередь планов в расписании
        for (FullBackupDto.ScheduleItemDto item : backup.getScheduleItems()) {
            insert(connection,
                    "INSERT INTO schedule_items (id, schedule_id, position, plan_id)"
                            + " VALUES (?, ?, ?, ?)",
                    item.getId(), item.getScheduleId(),
                    item.getPosition() == null ? 1 : item.getPosition(), item.getPlanId());
        }

        // 8. Тренировки
        for (FullBackupDto.WorkoutDto item : backup.getWorkouts()) {
            LocalDateTime createdAt = item.getCreatedAt() == null
                    ? firstNonNull(item.getStartedAt(), now)
                    : item.getCreatedAt();
            insert(connection,
                    "INSERT INTO workouts (id, user_id, plan_id, workout_date, name, started_at,"
                            + " ended_at, duration_sec, note, avg_pulse, total_calories,"
                            + " created_at, updated_at, draft)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    item.getId(), owner, item.getPlanId(), item.getWorkoutDate(), item.getName(),
                    item.getStartedAt(), item.getEndedAt(), item.getDurationSec(), item.getNote(),
                    item.getAvgPulse(), item.getTotalCalories(), createdAt, item.getUpdatedAt(),
                    item.getDraft() != null && item.getDraft());
        }

        // 9. Упражнения тренировок
        for (FullBackupDto.WorkoutExerciseDto item : backup.getWorkoutExercises()) {
            insert(connection,
                    "INSERT INTO workout_exercises (id, workout_id, exercise_id, order_index,"
                            + " equipment_snapshot) VALUES (?, ?, ?, ?, ?)",
                    item.getId(), item.getWorkoutId(), item.getExerciseId(),
                    item.getOrderIndex() == null ? 1 : item.getOrderIndex(),
                    item.getEquipmentSnapshot());
        }

        // 10. Подходы
        for (FullBackupDto.SetDto item : backup.getSets()) {
            insert(connection,
                    "INSERT INTO sets (id, workout_exercise_id, set_number, reps, weight_kg,"
                            + " time_marker_sec, duration_sec, pulse_bpm, calories, note, skipped)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    item.getId(), item.getWorkoutExerciseId(), item.getSetNumber(),
                    item.getReps(), item.getWeightKg(), item.getTimeMarkerSec(),
                    item.getDurationSec(), item.getPulseBpm(), item.getCalories(),
                    item.getNote(), item.getSkipped() != null && item.getSkipped());
        }

        // 11. Замеры (media_id проставим после вставки media — см. шаг 12)
        for (FullBackupDto.MeasurementDto item : backup.getMeasurements()) {
            insert(connection,
                    "INSERT INTO measurements (id, user_id, measured_at, kind, name, value_num,"
                            + " value_unit, note, media_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                    item.getId(), owner, item.getMeasuredAt(), item.getKind(), item.getName(),
                    item.getValueNum(), item.getValueUnit(), item.getNote());
        }

        // 12. B3: картинки замеров. media ссылается на замер, поэтому вставляем
        //     после measurements и только потом проставляем measurements.media_id.
        for (FullBackupDto.MediaDto item : backup.getMedia()) {
            insert(connection,
                    "INSERT INTO media (id, workout_id, workout_exercise_id, measurement_id,"
                            + " purpose, file_path, uploaded_at) VALUES (?, NULL, NULL, ?, ?, ?, ?)",
                    item.getId(), item.getMeasurementId(),
                    item.getPurpose() == null || item.getPurpose().isBlank()
                            ? "other" : item.getPurpose(),
                    item.getFilePath(),
                    firstNonNull(item.getUploadedAt(), now));

            // обратная ссылка: замер знает свою картинку
            setMeasurementMedia(connection, item.getMeasurementId(), item.getId());
        }
    }

    /** UPDATE measurements.media_id — обратная ссылка на только что вставленный файл. */
    private static void setMeasurementMedia(Connection connection,
                                            Long measurementId,
                                            Long mediaId) throws SQLException {

        if (measurementId == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE measurements SET media_id = ? WHERE id = ?")) {
            statement.setLong(1, mediaId);
            statement.setLong(2, measurementId);
            statement.executeUpdate();
        }
    }

    /**
     * После вставки с явными id счётчики BIGSERIAL «застряли» на старых
     * значениях — следующий INSERT из приложения наткнулся бы на дубль id.
     */
    private static void resetSequences(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String table : SEQUENCE_TABLES) {
                // именно execute: setval возвращает значение, executeUpdate на SELECT падает
                statement.execute(
                        "SELECT setval(pg_get_serial_sequence('" + table + "', 'id'),"
                                + " COALESCE((SELECT MAX(id) FROM " + table + "), 0) + 1, false)");
            }
        }
    }

    // ------------------------------------------------------------ JDBC-утилиты

    private static void insert(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                // setObject(null) сам по себе не работает в pgjdbc — ставим NULL явно
                if (values[i] == null) {
                    statement.setNull(i + 1, java.sql.Types.OTHER);
                } else {
                    statement.setObject(i + 1, values[i]);
                }
            }
            statement.executeUpdate();
        }
    }

    private static <T> T firstNonNull(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static <T> List<T> listOrEmpty(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static int size(List<?> value) {
        return value == null ? 0 : value.size();
    }

    private static void require(Object value, String message) {
        if (value == null) {
            throw new ValidationException("Файл бэкапа повреждён: " + message);
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("Файл бэкапа повреждён: " + message);
        }
    }

    /** Самое глубокое сообщение цепочки: у Hibernate сверху «could not execute statement». */
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message.lines().findFirst().orElse(message);
    }
}

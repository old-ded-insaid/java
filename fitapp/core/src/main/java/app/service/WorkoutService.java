package app.service;

import app.db.Database;
import app.dto.ExerciseExportDto;
import app.dto.SetExportDto;
import app.dto.WorkoutExportDto;
import app.model.Exercise;
import app.model.ExerciseEquipment;
import app.model.PlanExercise;
import app.model.User;
import app.model.Workout;
import app.model.WorkoutExercise;
import app.model.WorkoutPlan;
import app.model.WorkoutSet;
import app.repository.Repositories;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hibernate.Hibernate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * «Мозг» тренировки: создать, добавить упражнение, добавить подход, завершить.
 *
 * Правила (единые для ПК и будущего сервера):
 *  - повторы > 0
 *  - вес > 0, если указан (bodyweight-подход пишется без веса: null)
 *  - пульс 30..250 уд/мин
 *  - время подхода > 0, метка времени >= 0
 *  - калории >= 0
 *
 * Экран не имеет права писать в БД напрямую — только через эти методы.
 */
public class WorkoutService {

    /** Меньше 30 — скорее всего опечатка или датчик не приложили. */
    public static final int MIN_PULSE_BPM = 30;
    /** Больше 250 в покое/на тренировке — почти наверняка опечатка. */
    public static final int MAX_PULSE_BPM = 250;

    /** Предел колонки NUMERIC(6,2): больше нельзя записать в БД (BUG M7). */
    public static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("9999.99");

    /** Максимум подходов, которые импорт разворачивает из одной JSON-группы. */
    private static final int MAX_IMPORTED_SETS = 50;

    /** Текст автоматической пометки пропуска: его и только его снимает unskipSet. */
    public static final String AUTO_SKIP_NOTE = "Пропущено";

    private final Database database;
    private final AuditService auditService;

    /**
     * Разбор JSON тренировки (импорт факта).
     *
     * Неизвестные поля игнорируются: файл мог быть сохранён более новой
     * версией приложения, и падать из-за лишнего ключа нельзя.
     * JavaTimeModule нужен для дат/времени в шапке файла (LocalDate/LocalDateTime).
     */
    private final ObjectMapper jsonMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public WorkoutService(Database database) {
        this(database, new AuditService());
    }

    public WorkoutService(Database database, AuditService auditService) {
        this.database = database;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ создание

    /**
     * Создаёт новую тренировку и сразу проставляет started_at = сейчас (UTC).
     *
     * @param planId может быть null — «свободная» тренировка без шаблона
     * @param name   если пусто, берём имя плана или «Тренировка»
     */
    public Workout createWorkout(long userId, Long planId, LocalDate workoutDate, String name) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            User user = repos.users().findById(userId)
                    .orElseThrow(() -> new ValidationException("Пользователь не найден: " + userId));

            WorkoutPlan plan = null;
            if (planId != null) {
                plan = repos.plans().findById(planId)
                        .orElseThrow(() -> new ValidationException("План не найден: " + planId));
            }

            LocalDate date = (workoutDate == null) ? LocalDate.now(ZoneOffset.UTC) : workoutDate;
            String workoutName = (name == null || name.isBlank())
                    ? (plan != null ? plan.getName() : "Тренировка")
                    : name.trim();

            Workout workout = Workout.builder()
                    .user(user)
                    .plan(plan)
                    .workoutDate(date)
                    .name(workoutName)
                    .startedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                    .draft(true)          // черновик: автоочистка может его убрать
                    .build();

            Workout saved = repos.workouts().save(workout);
            auditService.record(em, userId, "workouts", saved.getId(), "INSERT", null, workoutSnapshot(saved));
            return saved;
        });
    }

    // ---------------------------------------------------- создание из шаблона

    /**
     * Создаёт тренировку по шаблону: копирует упражнения и создаёт пустые подходы.
     *
     * Подходы создаются ПУСТЫМИ (только номер), без повторов и веса.
     * Это осознанно: если заполнить их из плана, isWorkoutEmpty() сразу вернёт
     * false и автоочистка «случайно открытой» тренировки перестанет работать.
     * Целевые подходы/повторы остаются в шаблоне (PlanExercise).
     */
    public Workout createFromPlan(long userId, long planId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            User user = repos.users().findById(userId)
                    .orElseThrow(() -> new ValidationException("Пользователь не найден: " + userId));
            WorkoutPlan plan = repos.plans().findById(planId)
                    .orElseThrow(() -> new ValidationException("План не найден: " + planId));

            List<PlanExercise> planItems = repos.planExercises().findByPlanIdOrdered(planId);

            Workout workout = repos.workouts().save(Workout.builder()
                    .user(user)
                    .plan(plan)
                    .workoutDate(LocalDate.now(ZoneOffset.UTC))
                    .name(plan.getName())
                    .startedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                    .draft(true)          // черновик: автоочистка может его убрать
                    .build());

            int orderIndex = 1;
            for (PlanExercise item : planItems) {
                WorkoutExercise link = repos.workoutExercises().save(WorkoutExercise.builder()
                        .workout(workout)
                        .exercise(item.getExercise())
                        .orderIndex(orderIndex++)
                        // B4: снимок берём из ШАБЛОНА — он зафиксирован при правке
                        // шаблона. Для строк без снимка (старые данные) — из
                        // справочника упражнения, чтобы колонка не была пустой.
                        .equipmentSnapshot(item.getEquipmentSnapshot() != null
                                ? item.getEquipmentSnapshot()
                                : equipmentText(item.getExercise()))
                        .build());

                int setCount = item.getTargetSets() == null ? 1 : Math.max(1, item.getTargetSets());

                // целевые значения из шаблона: подставляем в подходы, чтобы не вводить вручную.
                // reps = null, если в шаблоне не задано: CHECK в БД требует reps > 0.
                // weight = null (BUG-03): 0 кг — это не «без веса», а некорректное значение.
                Integer reps = item.getTargetReps();
                BigDecimal weight = item.getTargetWeightKg();

                for (int setNumber = 1; setNumber <= setCount; setNumber++) {
                    repos.sets().save(WorkoutSet.builder()
                            .workoutExercise(link)
                            .setNumber(setNumber)
                            .reps(reps)
                            .weightKg(weight)
                            .build());
                }
            }

            auditService.record(em, userId, "workouts", workout.getId(), "INSERT", null,
                    workoutSnapshot(workout));
            return workout;
        });
    }

    // ------------------------------------------------- импорт факта тренировки

    /**
     * Импортирует выполненную тренировку из JSON (формат {@link WorkoutExportDto}).
     *
     * Данные привязываются к первому пользователю базы: приложение однопользовательское,
     * а файл не содержит владельца.
     */
    public Workout importWorkoutFromJson(String json) {
        return importWorkoutFromJson(null, json);
    }

    /**
     * Импорт тренировки из JSON.
     *
     * Поведение:
     *  - тренировка создаётся завершённой (draft = false) и попадает в историю;
     *  - упражнение ищется по имени, при отсутствии — создаётся вместе с описанием;
     *  - одинаковые подходы из файла разворачиваются обратно: targetSets = 2
     *    означает два подхода с одинаковыми значениями;
     *  - пустой список sets означает «был только план»: берутся target* упражнения.
     *
     * @param userId кому привязать тренировку; null — первый пользователь базы
     */
    public Workout importWorkoutFromJson(Long userId, String json) {
        if (json == null || json.isBlank()) {
            throw new ValidationException("Файл пустой — вставьте JSON тренировки");
        }

        WorkoutExportDto dto;
        try {
            dto = jsonMapper.readValue(json, WorkoutExportDto.class);
        } catch (JsonProcessingException e) {
            throw new ValidationException("Не удалось разобрать JSON: " + e.getOriginalMessage()
                    + System.lineSeparator()
                    + "Проверьте кавычки, запятые и что это объект { ... }, а не список [ ... ]");
        }

        validateImportedWorkout(dto);

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            User user = userId != null
                    ? repos.users().findById(userId)
                    .orElseThrow(() -> new ValidationException("Пользователь не найден: " + userId))
                    : repos.users().findAll().stream().findFirst()
                    .orElseThrow(() -> new ValidationException(
                            "В базе нет пользователя — сначала запустите приложение"));

            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            // B1: берём шапку из файла, если она есть; иначе — разумный fallback
            LocalDate workoutDate = dto.getWorkoutDate() != null
                    ? dto.getWorkoutDate()
                    : LocalDate.now(ZoneOffset.UTC);

            Integer duration = dto.getDurationSec();
            if (duration == null || duration <= 0) {
                int fromSets = importedDurationSeconds(dto);
                duration = fromSets > 0 ? fromSets : null;
            }

            LocalDateTime startedAt = dto.getStartedAt();
            if (startedAt == null) {
                startedAt = duration != null ? now.minusSeconds(duration) : now;
            }
            LocalDateTime endedAt = dto.getEndedAt() != null ? dto.getEndedAt() : now;

            // в БД CHECK: ended_at >= started_at
            if (endedAt.isBefore(startedAt)) {
                endedAt = startedAt.plusSeconds(duration == null ? 1 : duration);
            }

            Workout workout = repos.workouts().save(Workout.builder()
                    .user(user)
                    .workoutDate(workoutDate)
                    .name(dto.getName().trim())
                    .startedAt(startedAt)
                    .endedAt(endedAt)
                    .durationSec(duration)
                    .avgPulse(dto.getAvgPulse() != null ? dto.getAvgPulse() : importedAvgPulse(dto))
                    .totalCalories(dto.getTotalCalories() != null
                            ? dto.getTotalCalories() : importedCalories(dto))
                    .note(blankToNull(dto.getDescription()))
                    // импорт — это запись в истории, автоочистка её не тронет
                    .draft(false)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());

            int orderIndex = 1;
            for (ExerciseExportDto item : dto.getExercises()) {
                Exercise exercise = findOrCreateExercise(repos, item);

                WorkoutExercise link = repos.workoutExercises().save(WorkoutExercise.builder()
                        .workout(workout)
                        .exercise(exercise)
                        .orderIndex(orderIndex++)
                        .equipmentSnapshot(blankToNull(item.getInventory()))
                        .build());

                addImportedSets(repos, link, item);

                // B4: складываем связь в коллекцию тренировки сразу. Иначе после
                // закрытия транзакции workout.getExercises() — ленивый прокси,
                // и UI падает на простом .size() в сообщении об успехе.
                workout.getExercises().add(link);
            }

            auditService.record(em, user.getId(), "workouts", workout.getId(), "INSERT", null,
                    workoutSnapshot(workout));
            return workout;
        });
    }

    /** Обязательные поля: иначе непонятно, что именно импортировать. */
    private static void validateImportedWorkout(WorkoutExportDto dto) {
        if (dto == null) {
            throw new ValidationException("JSON пустой: ожидается объект { \"name\": ... }");
        }
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new ValidationException("В JSON нет поля \"name\" — названия тренировки");
        }
        if (dto.getExercises() == null || dto.getExercises().isEmpty()) {
            throw new ValidationException("В JSON нет упражнений: поле \"exercises\" пустое");
        }

        int index = 1;
        for (ExerciseExportDto item : dto.getExercises()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                throw new ValidationException("Упражнение №" + index + ": не указано поле \"name\"");
            }

            // Защита от JSON, который развернёт миллион подходов в цикле.
            requireSetCount(item.getTargetSets(), "Упражнение №" + index);
            if (item.getSets() != null) {
                int setIndex = 1;
                for (SetExportDto set : item.getSets()) {
                    if (set != null) {
                        requireSetCount(set.getTargetSets(),
                                "Упражнение №" + index + ", подход №" + setIndex);
                    }
                    setIndex++;
                }
            }
            index++;
        }
    }

    private static void requireSetCount(Integer count, String context) {
        if (count != null && count > MAX_IMPORTED_SETS) {
            throw new ValidationException(context + ": targetSets не может быть больше "
                    + MAX_IMPORTED_SETS + ", получено: " + count);
        }
    }

    /** Упражнение по имени; если такого нет — создаём с описанием из файла. */
    private static Exercise findOrCreateExercise(Repositories repos, ExerciseExportDto item) {
        String name = item.getName().trim();
        return repos.exercises().findByName(name)
                .orElseGet(() -> repos.exercises().save(Exercise.builder()
                        .name(name)
                        .setup(blankToNull(item.getSetup()))
                        .movement(blankToNull(item.getMovement()))
                        .muscles(blankToNull(item.getMuscles()))
                        .avoid(blankToNull(item.getAvoid()))
                        .build()));
    }

    /** Разворачивает сгруппированные подходы обратно в строки sets. */
    private static void addImportedSets(Repositories repos,
                                        WorkoutExercise link,
                                        ExerciseExportDto item) {

        List<SetExportDto> sets = item.getSets() == null ? List.of() : item.getSets();
        int setNumber = 1;

        if (sets.isEmpty()) {
            // в файле только цели упражнения (шаблон без факта) — создаём подходы по ним
            int count = item.getTargetSets() == null ? 1 : Math.max(1, item.getTargetSets());
            for (int i = 0; i < count; i++) {
                saveImportedSet(repos, link, setNumber++, item.getTargetReps(),
                        item.getTargetWeight(), item.getTargetTimeSec(),
                        null, null, null, null, false, item.getName());
            }
            return;
        }

        for (SetExportDto set : sets) {
            if (set == null) {
                continue;
            }
            int count = set.getTargetSets() == null ? 1 : Math.max(1, set.getTargetSets());
            for (int i = 0; i < count; i++) {
                saveImportedSet(repos, link, setNumber++,
                        set.getTargetReps(), set.getTargetWeight(), set.getDurationSec(),
                        set.getTimeMarkerSec(), set.getPulseBpm(), set.getCalories(),
                        set.getNote(), Boolean.TRUE.equals(set.getSkipped()), item.getName());
            }
        }
    }

    private static void saveImportedSet(Repositories repos,
                                        WorkoutExercise link,
                                        int setNumber,
                                        Integer reps,
                                        BigDecimal weightKg,
                                        Integer durationSec,
                                        Integer timeMarkerSec,
                                        Integer pulseBpm,
                                        Integer calories,
                                        String note,
                                        boolean skipped,
                                        String exerciseName) {

        // B8: пропущенный подход — это не время. Если в файле skipped = true,
        // но остались duration/marker (бывает в кривых файлах), чистим их —
        // ровно как markSkipped() при обычном пропуске.
        Integer duration = skipped ? null : durationSec;
        Integer marker = skipped ? null : timeMarkerSec;

        try {
            validateSet(reps, weightKg, marker, duration, pulseBpm, calories);
        } catch (ValidationException e) {
            // добавляем контекст: в каком упражнении и подходе проблема
            throw new ValidationException("«" + exerciseName + "», подход " + setNumber
                    + ": " + e.getMessage());
        }

        repos.sets().save(WorkoutSet.builder()
                .workoutExercise(link)
                .setNumber(setNumber)
                .reps(reps)
                .weightKg(weightKg)
                .durationSec(duration)
                .timeMarkerSec(marker)
                .pulseBpm(pulseBpm)
                .calories(calories)
                .note(blankToNull(note))
                .skipped(skipped)
                .build());
    }

    /** Средний пульс по файлу — если в шапке его не было. */
    private static Integer importedAvgPulse(WorkoutExportDto dto) {
        int sum = 0;
        int count = 0;

        for (ExerciseExportDto item : dto.getExercises()) {
            if (item.getSets() == null) {
                continue;
            }
            for (SetExportDto set : item.getSets()) {
                if (set == null || Boolean.TRUE.equals(set.getSkipped()) || set.getPulseBpm() == null) {
                    continue;
                }
                int copies = set.getTargetSets() == null ? 1 : Math.max(1, set.getTargetSets());
                sum += set.getPulseBpm() * copies;
                count += copies;
            }
        }
        return count == 0 ? null : (int) Math.round((double) sum / count);
    }

    /** Суммарные калории по файлу — если в шапке их не было. */
    private static Integer importedCalories(WorkoutExportDto dto) {
        int total = 0;
        boolean any = false;

        for (ExerciseExportDto item : dto.getExercises()) {
            if (item.getSets() == null) {
                continue;
            }
            for (SetExportDto set : item.getSets()) {
                if (set == null || set.getCalories() == null) {
                    continue;
                }
                int copies = set.getTargetSets() == null ? 1 : Math.max(1, set.getTargetSets());
                total += set.getCalories() * copies;
                any = true;
            }
        }
        return any ? total : null;
    }

    /** Суммарное время подходов: по нему восстанавливаем длительность тренировки. */
    private static int importedDurationSeconds(WorkoutExportDto dto) {
        int total = 0;
        for (ExerciseExportDto item : dto.getExercises()) {
            if (item.getSets() == null) {
                continue;
            }
            for (SetExportDto set : item.getSets()) {
                if (set == null || set.getDurationSec() == null) {
                    continue;
                }
                int count = set.getTargetSets() == null ? 1 : Math.max(1, set.getTargetSets());
                total += set.getDurationSec() * count;
            }
        }
        return total;
    }

    /** Пустая строка -> null: не засоряем БД пустыми значениями. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Тренировка пустая, если пользователь ничего не сделал:
     * нет времени ни в одном подходе, нет заметок, а повторы и вес
     * остались такими, какими их заполнил шаблон.
     *
     * Сравнение с шаблоном обязательно: подходы теперь создаются с цифрами
     * из плана, и «просто открыл и закрыл» не должно превращаться в запись
     * в истории, но и правка повторов/веса вручную — это уже работа.
     */
    public boolean isWorkoutEmpty(long workoutId) {
        return database.inTransaction(em ->
                isWorkoutEmpty(new Repositories(em), workoutId));
    }

    /**
     * Удаляет тренировку, только если она пустая.
     *
     * @return true, если тренировка была удалена
     */
    public boolean deleteIfEmpty(long workoutId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            Workout workout = repos.workouts().findById(workoutId).orElse(null);
            if (workout == null) {
                return false; // уже удалена — не ошибка
            }

            // BUG-01: автоочистка трогает ТОЛЬКО незавершённые черновики.
            // Завершённую тренировку (endedAt != null) и записи из истории не удаляем никогда.
            if (!workout.isDraft() || workout.getEndedAt() != null) {
                return false;
            }

            if (!isWorkoutEmpty(repos, workoutId)) {
                return false;
            }

            repos.workouts().delete(workout);
            return true;
        });
    }

    /**
     * Помечает тренировку «не черновиком»: открыта из истории,
     * автоочистка её больше не тронет (BUG-01).
     */
    public void markNotDraft(long workoutId) {
        database.runInTransaction(em -> {
            Workout workout = new Repositories(em).workouts().findById(workoutId).orElse(null);
            if (workout != null) {
                workout.setDraft(false);
            }
        });
    }

    /**
     * Пропуск подхода (BUG-07): ставим флаг, а не «0 секунд».
     * Время подхода не пишем — это не выполнено, а осознанно пропущено.
     *
     * M1: вместе с флагом очищаем duration_sec и time_marker_sec.
     * Иначе у пропущенного подхода остаётся старое время (например, если его
     * записали, а потом решили пропустить), и оно попадает в график пульса
     * и в статистику тренировки.
     */
    public WorkoutSet markSkipped(long setId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            WorkoutSet set = repos.sets().findById(setId)
                    .orElseThrow(() -> new ValidationException("Подход не найден: " + setId));

            set.setSkipped(true);
            set.setDurationSec(null);
            set.setTimeMarkerSec(null);

            if (set.getNote() == null || set.getNote().isBlank()) {
                set.setNote(AUTO_SKIP_NOTE);
            }
            return set;
        });
    }

    /**
     * Отмена пропуска: подход снова считается обычным.
     *
     * Заметку снимаем только если это наша автоматическая «Пропущено» —
     * текст, который пользователь написал сам, не трогаем.
     */
    public WorkoutSet unskipSet(long setId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            WorkoutSet set = repos.sets().findById(setId)
                    .orElseThrow(() -> new ValidationException("Подход не найден: " + setId));

            set.setSkipped(false);

            if (AUTO_SKIP_NOTE.equals(set.getNote())) {
                set.setNote(null);
            }
            return set;
        });
    }

    /**
     * Меняет инвентарь конкретного упражнения В ТРЕНИРОВКЕ (BUG-11):
     * правится снимок workout_exercises.equipment_snapshot, справочник не трогаем.
     *
     * @param snapshot «Гантель 6 кг» или null, чтобы очистить
     */
    public void updateWorkoutEquipment(long workoutExerciseId, String snapshot) {
        database.runInTransaction(em -> {
            WorkoutExercise link = new Repositories(em).workoutExercises().findById(workoutExerciseId)
                    .orElseThrow(() -> new ValidationException(
                            "Упражнение тренировки не найдено: " + workoutExerciseId));

            link.setEquipmentSnapshot(snapshot == null || snapshot.isBlank() ? null : snapshot.trim());
        });
    }

    /** Удаляет тренировку со всеми упражнениями и подходами (каскад в БД). */
    public void deleteWorkout(long workoutId) {
        database.runInTransaction(em -> {
            Repositories repos = new Repositories(em);

            Workout workout = repos.workouts().findById(workoutId)
                    .orElseThrow(() -> new ValidationException("Тренировка не найдена: " + workoutId));

            repos.workouts().delete(workout);
        });
    }

    private static boolean isWorkoutEmpty(Repositories repos, long workoutId) {
        Workout workout = repos.workouts().findById(workoutId).orElse(null);
        if (workout == null) {
            return true;
        }

        // BUG-01: заметка ко всей тренировке — это данные, а не пустота
        if (workout.getNote() != null && !workout.getNote().isBlank()) {
            return false;
        }

        // целевые значения шаблона: предзаполненные повторы/вес работой не считаются
        Map<Long, PlanExercise> targets = new HashMap<>();
        if (workout.getPlan() != null) {
            for (PlanExercise item : repos.planExercises().findByPlanIdOrdered(workout.getPlan().getId())) {
                targets.put(item.getExercise().getId(), item);
            }
        }

        for (WorkoutExercise link : repos.workoutExercises().findByWorkoutIdOrdered(workoutId)) {
            PlanExercise target = targets.get(link.getExercise().getId());

            // B4: снимок инвентаря — тоже данные пользователя. Сравниваем его
            // со снимком ШАБЛОНА (он зафиксирован при правке шаблона), а не с
            // текущим справочником: иначе правка справочника «задним числом»
            // делала бы нетронутую тренировку «непустой».
            String baseline = target != null && target.getEquipmentSnapshot() != null
                    ? target.getEquipmentSnapshot()
                    : equipmentText(link.getExercise());
            if (!Objects.equals(link.getEquipmentSnapshot(), baseline)) {
                return false;
            }

            for (WorkoutSet set : link.getSets()) {
                if (set.isSkipped()) {
                    return false;   // BUG-07: осознанный пропуск — это действие пользователя
                }
                if (set.getDurationSec() != null) {
                    return false;   // время записано
                }
                // B3: метка на графике пульса, пульс и калории — тоже данные,
                // даже если время подхода не выставлено
                if (set.getTimeMarkerSec() != null
                        || set.getPulseBpm() != null
                        || set.getCalories() != null) {
                    return false;
                }
                if (set.getNote() != null && !set.getNote().isBlank()) {
                    return false;
                }
                if (!sameAsTarget(set, target)) {
                    return false;   // повторы/вес правили вручную
                }
            }
        }
        return true;
    }

    /** Снимок инвентаря для истории: «Гантель 8 кг, Скамья (2 шт)» или null. */
    private static String equipmentText(Exercise exercise) {
        if (exercise.getEquipmentLinks() == null || exercise.getEquipmentLinks().isEmpty()) {
            return null;
        }
        return exercise.getEquipmentLinks().stream()
                .map(link -> link.getQuantity() > 1
                        ? link.getEquipment().getName() + " (" + link.getQuantity() + " шт)"
                        : link.getEquipment().getName())
                .collect(Collectors.joining(", "));
    }

    /** Совпадают ли повторы и вес подхода с целевыми значениями шаблона. */
    private static boolean sameAsTarget(WorkoutSet set, PlanExercise target) {
        int reps = set.getReps() == null ? 0 : set.getReps();
        int expectedReps = (target == null || target.getTargetReps() == null)
                ? 0
                : target.getTargetReps();
        if (reps != expectedReps) {
            return false;
        }

        BigDecimal weight = set.getWeightKg() == null ? BigDecimal.ZERO : set.getWeightKg();
        BigDecimal expectedWeight = (target == null || target.getTargetWeightKg() == null)
                ? BigDecimal.ZERO
                : target.getTargetWeightKg();
        return weight.compareTo(expectedWeight) == 0;
    }

    /** Добавляет упражнение в тренировку (в конец списка). */
    public WorkoutExercise addExercise(long workoutId, long exerciseId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            Workout workout = requireWorkout(repos, workoutId);
            Exercise exercise = repos.exercises().findById(exerciseId)
                    .orElseThrow(() -> new ValidationException("Упражнение не найдено: " + exerciseId));

            WorkoutExercise link = WorkoutExercise.builder()
                    .workout(workout)
                    .exercise(exercise)
                    .orderIndex(repos.workoutExercises().nextOrderIndex(workoutId))
                    .equipmentSnapshot(equipmentText(exercise))
                    .build();

            WorkoutExercise saved = repos.workoutExercises().save(link);
            auditService.record(em, workout.getUser().getId(), "workout_exercises", saved.getId(), "INSERT", null,
                    Map.of("workoutId", workoutId,
                            "exerciseId", exerciseId,
                            "orderIndex", saved.getOrderIndex()));
            return saved;
        });
    }

    // ------------------------------------------------------------------- подходы

    /** Короткий вариант: повторы, вес, пульс, время. */
    public WorkoutSet addSet(long workoutExerciseId,
                             Integer reps,
                             BigDecimal weightKg,
                             Integer pulseBpm,
                             Integer durationSec) {
        return addSet(workoutExerciseId, reps, weightKg, null, durationSec, pulseBpm, null, null);
    }

    /**
     * Полный вариант. Любой параметр, кроме номера упражнения, может быть null.
     * Номер подхода (set_number) считается автоматически: max + 1.
     */
    public WorkoutSet addSet(long workoutExerciseId,
                             Integer reps,
                             BigDecimal weightKg,
                             Integer timeMarkerSec,
                             Integer durationSec,
                             Integer pulseBpm,
                             Integer calories,
                             String note) {

        // валидируем ДО открытия транзакции: незачем ходить в БД с заведомо плохими данными
        validateSet(reps, weightKg, timeMarkerSec, durationSec, pulseBpm, calories);

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            WorkoutExercise link = repos.workoutExercises().findById(workoutExerciseId)
                    .orElseThrow(() -> new ValidationException(
                            "Упражнение тренировки не найдено: " + workoutExerciseId));

            WorkoutSet set = WorkoutSet.builder()
                    .workoutExercise(link)
                    .setNumber(repos.sets().nextSetNumber(workoutExerciseId))
                    .reps(reps)
                    .weightKg(weightKg)
                    .timeMarkerSec(timeMarkerSec)
                    .durationSec(durationSec)
                    .pulseBpm(pulseBpm)
                    .calories(calories)
                    .note(note)
                    .build();

            WorkoutSet saved = repos.sets().save(set);
            auditService.record(em, userIdOf(link), "sets", saved.getId(), "INSERT", null, setSnapshot(saved));
            return saved;
        });
    }

    /**
     * Обновляет подход. Переданные null означают «не менять»,
     * поэтому чтобы стереть значение, используйте {@link #clearReps},
     * {@link #clearWeight} и {@link #clearDuration} (BUG M4).
     */
    public WorkoutSet updateSet(long setId,
                                Integer reps,
                                BigDecimal weightKg,
                                Integer pulseBpm,
                                Integer durationSec,
                                String note) {
        return updateSet(setId, reps, weightKg, pulseBpm, durationSec, null, note);
    }

    /**
     * Полная версия (BUG-06): отдельно длительность подхода и метка времени
     * на графике пульса (сколько прошло от старта тренировки).
     */
    public WorkoutSet updateSet(long setId,
                                Integer reps,
                                BigDecimal weightKg,
                                Integer pulseBpm,
                                Integer durationSec,
                                Integer timeMarkerSec,
                                String note) {

        validateSet(reps, weightKg, timeMarkerSec, durationSec, pulseBpm, null);

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            WorkoutSet set = repos.sets().findById(setId)
                    .orElseThrow(() -> new ValidationException("Подход не найден: " + setId));

            Map<String, Object> before = setSnapshot(set);

            if (reps != null) {
                set.setReps(reps);
            }
            if (weightKg != null) {
                set.setWeightKg(weightKg);
            }
            if (pulseBpm != null) {
                set.setPulseBpm(pulseBpm);
            }
            if (durationSec != null) {
                set.setDurationSec(durationSec);
            }
            if (timeMarkerSec != null) {
                set.setTimeMarkerSec(timeMarkerSec);
            }
            if (note != null) {
                set.setNote(note);
            }

            // сущность управляемая: UPDATE уйдёт в БД при commit
            auditService.record(em, userIdOf(set.getWorkoutExercise()), "sets", setId,
                    "UPDATE", before, setSnapshot(set));
            return set;
        });
    }

    /**
     * Стереть повторы у подхода (M4): в {@link #updateSet} {@code null} значит
     * «не менять», поэтому для очистки нужен отдельный метод.
     */
    public void clearReps(long setId) {
        database.runInTransaction(em -> {
            WorkoutSet set = new Repositories(em).sets().findById(setId)
                    .orElseThrow(() -> new ValidationException("Подход не найден: " + setId));
            set.setReps(null);
        });
    }

    /** Стереть вес у подхода (M4). */
    public void clearWeight(long setId) {
        database.runInTransaction(em -> {
            WorkoutSet set = new Repositories(em).sets().findById(setId)
                    .orElseThrow(() -> new ValidationException("Подход не найден: " + setId));
            set.setWeightKg(null);
        });
    }

    /**
     * Стереть время подхода (M4).
     *
     * Чистим И длительность, И метку секундомера: в таблице колонка «Время»
     * показывает метку, и если оставить её, «очищенная» ячейка выглядела бы
     * неизменной.
     */
    public void clearDuration(long setId) {
        database.runInTransaction(em -> {
            WorkoutSet set = new Repositories(em).sets().findById(setId)
                    .orElseThrow(() -> new ValidationException("Подход не найден: " + setId));
            set.setDurationSec(null);
            set.setTimeMarkerSec(null);
        });
    }

    /**
     * Записать метку секундомера (общее время от старта тренировки),
     * НЕ трогая длительность подхода.
     *
     * Нужно для колонки «Время»: она показывает именно метку, и правка
     * этой ячейки не должна переписывать длительность подхода.
     */
    public void updateTimeMarker(long setId, int seconds) {
        if (seconds < 0) {
            throw new ValidationException("Метка времени не может быть отрицательной");
        }
        database.runInTransaction(em -> {
            WorkoutSet set = new Repositories(em).sets().findById(setId)
                    .orElseThrow(() -> new ValidationException("Подход не найден: " + setId));
            set.setTimeMarkerSec(seconds);
        });
    }

    /** Записать длительность подхода, НЕ трогая метку секундомера. */
    public void updateDuration(long setId, int seconds) {
        if (seconds < 0) {
            throw new ValidationException("Время подхода не может быть отрицательным");
        }
        database.runInTransaction(em -> {
            WorkoutSet set = new Repositories(em).sets().findById(setId)
                    .orElseThrow(() -> new ValidationException("Подход не найден: " + setId));
            set.setDurationSec(seconds);
        });
    }

    /** Удаляет подход (жёстко: это строка дневника, восстанавливать её не нужно). */
    public void deleteSet(long setId) {
        database.runInTransaction(em -> {
            Repositories repos = new Repositories(em);
            WorkoutSet set = repos.sets().findById(setId)
                    .orElseThrow(() -> new ValidationException("Подход не найден: " + setId));

            Long userId = userIdOf(set.getWorkoutExercise());
            Map<String, Object> before = setSnapshot(set);

            repos.sets().delete(set);
            auditService.record(em, userId, "sets", setId, "DELETE", before, null);
        });
    }

    // ---------------------------------------------------------------- завершение

    /**
     * Завершает тренировку: ставит ended_at, считает длительность,
     * суммарные калории и средний пульс по подходам.
     *
     * B2: длительность — только по wall-clock (started_at -> ended_at).
     * Значение главного таймера больше не принимается: пользователь может
     * поставить его на паузу, перезапустить или вообще не запускать, и тогда
     * «время тренировки» в истории не имело ничего общего с реальностью.
     * Сумма duration_sec подходов тоже не годится — в ней нет отдыха.
     */
    public Workout finishWorkout(long workoutId, String note) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            Workout workout = requireWorkout(repos, workoutId);
            Map<String, Object> before = workoutSnapshot(workout);

            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            workout.setEndedAt(now);
            workout.setUpdatedAt(now);
            workout.setDraft(false);   // завершённая тренировка — уже не черновик

            if (note != null && !note.isBlank()) {
                workout.setNote(note.trim());
            }

            // в БД duration_sec >= 0, но 0 секунд — бессмысленно, ставим минимум 1
            if (workout.getStartedAt() != null) {
                long seconds = Duration.between(workout.getStartedAt(), now).getSeconds();
                workout.setDurationSec((int) Math.max(1, seconds));
            }

            int calories = 0;
            int pulseSum = 0;
            int pulseCount = 0;

            for (WorkoutExercise link : repos.workoutExercises().findByWorkoutIdOrdered(workoutId)) {
                for (WorkoutSet set : link.getSets()) {
                    // M1: пропущенный подход — это не нагрузка. Его пульс и калории
                    // не должны разбавлять средние значения по тренировке.
                    if (set.isSkipped()) {
                        continue;
                    }
                    if (set.getCalories() != null) {
                        calories += set.getCalories();
                    }
                    if (set.getPulseBpm() != null) {
                        pulseSum += set.getPulseBpm();
                        pulseCount++;
                    }
                }
            }

            workout.setTotalCalories(calories);
            workout.setAvgPulse(pulseCount == 0 ? null : (int) Math.round((double) pulseSum / pulseCount));

            auditService.record(em, workout.getUser().getId(), "workouts", workoutId,
                    "UPDATE", before, workoutSnapshot(workout));
            return workout;
        });
    }

    // -------------------------------------------------------------------- чтение

    /** История тренировок за период. null = «за последний месяц» / «по сегодня». */
    public List<Workout> history(long userId, LocalDate from, LocalDate to) {
        LocalDate start = (from == null) ? LocalDate.now(ZoneOffset.UTC).minusMonths(1) : from;
        LocalDate end = (to == null) ? LocalDate.now(ZoneOffset.UTC) : to;
        return database.inTransaction(em ->
                new Repositories(em).workouts().findByUserAndPeriod(userId, start, end));
    }

    /**
     * Тренировка со всеми упражнениями и подходами, готовыми к показу в UI.
     *
     * Зачем отдельный метод: связи LAZY, и после закрытия транзакции
     * обратиться к ним нельзя. Здесь мы «прогреваем» их внутри транзакции.
     */
    public Workout loadWorkoutDetails(long workoutId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            Workout workout = requireWorkout(repos, workoutId);

            Hibernate.initialize(workout.getExercises());
            for (WorkoutExercise link : workout.getExercises()) {
                Hibernate.initialize(link.getExercise());
                Hibernate.initialize(link.getSets());

                // инвентарь упражнения — для строки «Инвентарь:» на экране
                Hibernate.initialize(link.getExercise().getEquipmentLinks());
                for (ExerciseEquipment equipmentLink : link.getExercise().getEquipmentLinks()) {
                    Hibernate.initialize(equipmentLink.getEquipment());
                }
            }
            return workout;
        });
    }

    // ------------------------------------------------------------------ проверки

    /** Все правила для одного подхода в одном месте. */
    private static void validateSet(Integer reps,
                                    BigDecimal weightKg,
                                    Integer timeMarkerSec,
                                    Integer durationSec,
                                    Integer pulseBpm,
                                    Integer calories) {

        if (reps != null && reps <= 0) {
            throw new ValidationException("Повторы должны быть больше 0, получено: " + reps);
        }
        if (weightKg != null && weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Вес должен быть больше 0 кг, получено: " + weightKg);
        }
        if (weightKg != null && weightKg.compareTo(MAX_WEIGHT_KG) > 0) {
            throw new ValidationException("Слишком большое значение: вес не может быть больше "
                    + MAX_WEIGHT_KG + " кг");
        }
        if (timeMarkerSec != null && timeMarkerSec < 0) {
            throw new ValidationException("Метка времени не может быть отрицательной");
        }
        if (durationSec != null && durationSec < 0) {
            throw new ValidationException("Время подхода не может быть отрицательным");
        }
        if (pulseBpm != null && (pulseBpm < MIN_PULSE_BPM || pulseBpm > MAX_PULSE_BPM)) {
            throw new ValidationException("Пульс должен быть от " + MIN_PULSE_BPM
                    + " до " + MAX_PULSE_BPM + " уд/мин, получено: " + pulseBpm);
        }
        if (calories != null && calories < 0) {
            throw new ValidationException("Калории не могут быть отрицательными");
        }
    }

    private static Workout requireWorkout(Repositories repos, long workoutId) {
        return repos.workouts().findById(workoutId)
                .orElseThrow(() -> new ValidationException("Тренировка не найдена: " + workoutId));
    }

    private static Long userIdOf(WorkoutExercise link) {
        return link.getWorkout().getUser().getId();
    }

    // ------------------------------------------------- снимки для audit_log (JSON)

    private static Map<String, Object> workoutSnapshot(Workout workout) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", workout.getId());
        snapshot.put("workoutDate", workout.getWorkoutDate());
        snapshot.put("name", workout.getName());
        snapshot.put("startedAt", workout.getStartedAt());
        snapshot.put("endedAt", workout.getEndedAt());
        snapshot.put("durationSec", workout.getDurationSec());
        snapshot.put("avgPulse", workout.getAvgPulse());
        snapshot.put("totalCalories", workout.getTotalCalories());
        snapshot.put("note", workout.getNote());
        return snapshot;
    }

    private static Map<String, Object> setSnapshot(WorkoutSet set) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", set.getId());
        snapshot.put("setNumber", set.getSetNumber());
        snapshot.put("reps", set.getReps());
        snapshot.put("weightKg", set.getWeightKg());
        snapshot.put("timeMarkerSec", set.getTimeMarkerSec());
        snapshot.put("durationSec", set.getDurationSec());
        snapshot.put("pulseBpm", set.getPulseBpm());
        snapshot.put("calories", set.getCalories());
        snapshot.put("note", set.getNote());
        return snapshot;
    }
}

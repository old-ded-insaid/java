package app.service;

import app.db.Database;
import app.dto.ExerciseImportDto;
import app.dto.PlanImportDto;
import app.model.Equipment;
import app.model.Exercise;
import app.model.ExerciseEquipment;
import app.model.ExerciseEquipmentId;
import app.model.PlanExercise;
import app.model.User;
import app.model.WorkoutPlan;
import app.repository.Repositories;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Конструктор шаблонов тренировок (plan editor).
 *
 * Заменяет ручное «лазание» экрана в репозитории: экран вызывает только
 * методы этого сервиса. Логики про кнопки и FXML здесь нет — только данные
 * и правила (название не пустое, номер по порядку, подходы > 0 и т.д.).
 *
 * Отдельный сервис, а не методы в WorkoutService: WorkoutService отвечает
 * за проведение тренировки, и трогать его ради редактора шаблонов не нужно.
 */
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    /** Разумный предел подходов в шаблоне: защита от опечаток вида 1000 (BUG-02). */
    public static final int MAX_TARGET_SETS = 50;

    /** Предел колонки NUMERIC(6,2) для target_weight_kg (BUG M7). */
    public static final BigDecimal MAX_TARGET_WEIGHT_KG = new BigDecimal("9999.99");

    private final Database database;

    /**
     * Разбор JSON для импорта шаблона.
     * FAIL_ON_UNKNOWN_PROPERTIES выключен: если в JSON окажется лишнее поле
     * (например, "comment"), импорт не должен падать.
     */
    private final ObjectMapper jsonMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public PlanService(Database database) {
        this.database = database;
    }

    // ------------------------------------------------------------- шаблоны

    /** Все шаблоны пользователя (активные и скрытые). */
    public List<WorkoutPlan> plans(long userId) {
        return database.inTransaction(em -> new Repositories(em).plans().findByUserId(userId));
    }

    /**
     * Создаёт шаблон для конкретного пользователя.
     * Название уникально в пределах пользователя (это же есть в БД).
     */
    public WorkoutPlan createPlan(long userId, String name, String description) {
        return database.inTransaction(em -> createPlan(new Repositories(em), userId, name, description));
    }

    /**
     * Вариант без userId — для однопользовательского режима.
     * Берёт первого пользователя (в приложении он один, "owner").
     */
    public WorkoutPlan createPlan(String name, String description) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            User owner = repos.users().findAll().stream().findFirst()
                    .orElseThrow(() -> new ValidationException("В базе нет ни одного пользователя"));
            return createPlan(repos, owner.getId(), name, description);
        });
    }

    /** Меняет название и/или описание существующего шаблона. */
    public WorkoutPlan updatePlan(long planId, String name, String description) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            WorkoutPlan plan = requirePlan(repos, planId);

            if (name != null && !name.isBlank()) {
                String cleanName = name.trim();
                boolean duplicated = repos.plans().findByUserId(plan.getUser().getId()).stream()
                        .anyMatch(other -> !other.getId().equals(planId)
                                && other.getName().equalsIgnoreCase(cleanName));
                if (duplicated) {
                    throw new ValidationException("Шаблон с названием «" + cleanName + "» уже есть");
                }
                plan.setName(cleanName);
            }

            plan.setDescription(description == null || description.isBlank() ? null : description.trim());
            return plan;
        });
    }

    /** Мягкое удаление шаблона: active = false, история тренировок остаётся целой. */
    public void archivePlan(long planId) {
        database.runInTransaction(em ->
                new Repositories(em).plans().archive(planId));
    }

    // ------------------------------------------------------ импорт из JSON

    /**
     * Создаёт шаблон из JSON-текста.
     *
     * Поведение:
     *  - упражнение ищется в справочнике по имени; если его нет — создаётся;
     *  - пустые строки в JSON не пишутся в БД (становятся NULL);
     *  - всё выполняется в ОДНОЙ транзакции: либо шаблон целиком, либо ничего.
     *    Иначе при ошибке в середине списка остался бы «полуимпортированный» план.
     *
     * @return созданный шаблон (уже с id)
     */
    public WorkoutPlan importPlanFromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new ValidationException("Поле JSON пустое — вставьте текст шаблона");
        }

        PlanImportDto dto;
        try {
            dto = jsonMapper.readValue(json, PlanImportDto.class);
        } catch (JsonProcessingException e) {
            throw new ValidationException("Не удалось разобрать JSON: " + e.getOriginalMessage()
                    + System.lineSeparator()
                    + "Проверьте кавычки, запятые и что это объект { ... }, а не список [ ... ]");
        }

        if (dto == null) {
            throw new ValidationException("JSON пустой: ожидается объект { \"name\": ... }");
        }
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new ValidationException("В JSON нет поля \"name\" — названия шаблона");
        }
        if (dto.getExercises() == null || dto.getExercises().isEmpty()) {
            throw new ValidationException("В JSON нет упражнений: поле \"exercises\" пустое");
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            User owner = repos.users().findAll().stream().findFirst()
                    .orElseThrow(() -> new ValidationException("В базе нет ни одного пользователя"));

            // проверка имени шаблона и создание — тот же код, что и у кнопки «Сохранить»
            WorkoutPlan plan = createPlan(repos, owner.getId(), dto.getName(), dto.getDescription());

            int index = 1;
            for (ExerciseImportDto item : dto.getExercises()) {
                if (item == null || item.getName() == null || item.getName().isBlank()) {
                    throw new ValidationException("Упражнение №" + index + ": не указано поле \"name\"");
                }

                final ExerciseImportDto current = item;
                String exerciseName = current.getName().trim();

                Exercise exercise = repos.exercises().findByName(exerciseName)
                        .orElseGet(() -> repos.exercises().save(Exercise.builder()
                                .name(exerciseName)
                                .setup(blankToNull(current.getSetup()))
                                .movement(blankToNull(current.getMovement()))
                                .muscles(blankToNull(current.getMuscles()))
                                .avoid(blankToNull(current.getAvoid()))
                                .build()));

                validateTargets(current.getTargetSets(), current.getTargetReps(),
                        current.getTargetWeight(), exerciseName);

                // инвентарь из JSON: «Гантель, Скамья» -> связи exercise_equipment
                attachInventoryFromJson(repos, exercise, current.getInventory(), exerciseName);

                PlanExercise link = repos.planExercises().save(PlanExercise.builder()
                        .plan(plan)
                        .exercise(exercise)
                        .orderIndex(index++)
                        // B4: снимок инвентаря шаблона — с учётом только что созданных связей
                        .equipmentSnapshot(equipmentSnapshot(repos, exercise.getId()))
                        .build());

                // сущность управляемая: значения уйдут в БД при commit
                link.setTargetSets(current.getTargetSets());
                link.setTargetReps(current.getTargetReps());
                link.setTargetWeightKg(current.getTargetWeight());
            }

            return plan;
        });
    }

    /**
     * Привязывает инвентарь из поля JSON {@code inventory} к упражнению.
     *
     * Строка — названия через запятую: «Гантель, Скамья». Название ищется
     * в справочнике без учёта регистра; уже привязанные предметы повторно
     * не добавляются (импорт того же шаблона дважды не создаёт дублей).
     *
     * Неопознанное название — это предупреждение в логе, а НЕ ошибка импорта:
     * из-за опечатки в JSON не должно откатываться создание всего шаблона.
     */
    private static void attachInventoryFromJson(Repositories repos,
                                                Exercise exercise,
                                                String inventory,
                                                String exerciseName) {
        if (inventory == null || inventory.isBlank()) {
            return;
        }

        Set<Long> alreadyLinked = repos.exerciseEquipment().findByExerciseId(exercise.getId()).stream()
                .map(link -> link.getEquipment().getId())
                .collect(Collectors.toCollection(HashSet::new));

        for (String raw : inventory.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }

            Equipment equipment = repos.equipment().findByNameIgnoreCase(name).orElse(null);
            if (equipment == null) {
                log.warn("Инвентарь не найден: {} (упражнение «{}»)", name, exerciseName);
                continue;
            }

            if (!alreadyLinked.add(equipment.getId())) {
                continue;   // уже привязан — второй раз не нужно
            }

            repos.exerciseEquipment().save(ExerciseEquipment.link(exercise, equipment, 1));
        }
    }

    // -------------------------------------------------- упражнения шаблона

    /** Состав шаблона по порядку (упражнение уже подгружено). */
    public List<PlanExercise> planExercises(long planId) {
        return database.inTransaction(em ->
                new Repositories(em).planExercises().findByPlanIdOrdered(planId));
    }

    /**
     * Добавляет упражнение в шаблон.
     *
     * @param orderIndex 1, 2, 3... Если передать 0 или меньше — посчитаем сами (max + 1)
     */
    public PlanExercise addExerciseToPlan(long planId, long exerciseId, int orderIndex) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            WorkoutPlan plan = requirePlan(repos, planId);
            Exercise exercise = repos.exercises().findById(exerciseId)
                    .orElseThrow(() -> new ValidationException("Упражнение не найдено: " + exerciseId));

            int index = orderIndex > 0 ? orderIndex : repos.planExercises().nextOrderIndex(planId);

            // одно и то же упражнение можно добавить дважды (суперсет) — не запрещаем
            PlanExercise link = PlanExercise.builder()
                    .plan(plan)
                    .exercise(exercise)
                    .orderIndex(index)
                    // B4: инвентарь фиксируем в шаблоне, а не читаем «живой» справочник
                    .equipmentSnapshot(equipmentSnapshot(repos, exerciseId))
                    .build();

            return repos.planExercises().save(link);
        });
    }

    /** Целевые подходы и повторы для строки шаблона. */
    public PlanExercise setTargets(long planExerciseId, Integer targetSets, Integer targetReps) {
        if (targetSets != null && targetSets <= 0) {
            throw new ValidationException("Подходов должно быть больше 0, получено: " + targetSets);
        }
        if (targetSets != null && targetSets > MAX_TARGET_SETS) {
            throw new ValidationException("Подходов не может быть больше " + MAX_TARGET_SETS
                    + ", получено: " + targetSets);
        }
        if (targetReps != null && targetReps <= 0) {
            throw new ValidationException("Повторов должно быть больше 0, получено: " + targetReps);
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            PlanExercise link = repos.planExercises().findById(planExerciseId)
                    .orElseThrow(() -> new ValidationException(
                            "Строка шаблона не найдена: " + planExerciseId));

            link.setTargetSets(targetSets);
            link.setTargetReps(targetReps);
            return link;
        });
    }

    /** Целевой вес для строки шаблона (кг). */
    public PlanExercise setTargetWeight(long planExerciseId, BigDecimal targetWeightKg) {
        if (targetWeightKg != null && targetWeightKg.signum() < 0) {
            throw new ValidationException("Целевой вес не может быть отрицательным");
        }
        if (targetWeightKg != null && targetWeightKg.compareTo(MAX_TARGET_WEIGHT_KG) > 0) {
            throw new ValidationException("Слишком большое значение: вес не может быть больше "
                    + MAX_TARGET_WEIGHT_KG + " кг");
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            PlanExercise link = repos.planExercises().findById(planExerciseId)
                    .orElseThrow(() -> new ValidationException(
                            "Строка шаблона не найдена: " + planExerciseId));

            link.setTargetWeightKg(targetWeightKg);
            return link;
        });
    }

    /** Убирает упражнение из шаблона (само упражнение в справочнике остаётся). */
    public void removeFromPlan(long planExerciseId) {
        database.runInTransaction(em ->
                new Repositories(em).planExercises().deleteById(planExerciseId));
    }

    /**
     * Перемещает строку шаблона на одну позицию: -1 — вверх, +1 — вниз.
     *
     * Обмен order_index нельзя сделать «в лоб»: в БД стоит
     * UNIQUE(plan_id, order_index). Поэтому сначала выносим строку
     * во временный отрицательный индекс, затем переставляем соседа.
     */
    public void movePlanExercise(long planExerciseId, int direction) {
        if (direction != -1 && direction != 1) {
            throw new ValidationException("Направление перемещения: -1 (вверх) или +1 (вниз)");
        }

        database.runInTransaction(em -> {
            Repositories repos = new Repositories(em);

            PlanExercise current = repos.planExercises().findById(planExerciseId)
                    .orElseThrow(() -> new ValidationException(
                            "Строка шаблона не найдена: " + planExerciseId));

            long planId = current.getPlan().getId();
            List<PlanExercise> items = repos.planExercises().findByPlanIdOrdered(planId);

            int index = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getId().equals(planExerciseId)) {
                    index = i;
                    break;
                }
            }

            int neighborIndex = index + direction;
            if (index < 0 || neighborIndex < 0 || neighborIndex >= items.size()) {
                return; // уже первый или последний — двигать некуда
            }

            PlanExercise neighbor = items.get(neighborIndex);
            int currentOrder = current.getOrderIndex();
            int neighborOrder = neighbor.getOrderIndex();

            // Временный индекс ДОЛЖЕН быть > 0 (CHECK order_index > 0) и не занятым,
            // поэтому берём «максимальный + 1», а не -1.
            int temporary = items.stream()
                    .mapToInt(PlanExercise::getOrderIndex)
                    .max()
                    .orElse(0) + 1;

            current.setOrderIndex(temporary);
            em.flush();

            neighbor.setOrderIndex(currentOrder);
            em.flush();

            current.setOrderIndex(neighborOrder);
        });
    }

    // ------------------------------------------------------------ справочник

    /** Все упражнения по алфавиту — для выпадающего списка. */
    public List<Exercise> getAllExercises() {
        return database.inTransaction(em -> new Repositories(em).exercises().findAllOrdered());
    }

    /** Значение equipmentId для фильтра «без инвентаря». */
    public static final long EQUIPMENT_NONE = -1L;

    /**
     * Поиск упражнений для справочника.
     *
     * @param query       подстрока названия или мышц (регистр не важен); пусто = все
     * @param equipmentId null — любой инвентарь, {@link #EQUIPMENT_NONE} — без инвентаря,
     *                    иначе id конкретного инвентаря
     */
    public List<Exercise> searchExercises(String query, Long equipmentId) {
        String text = query == null ? "" : query.trim().toLowerCase();

        return database.inTransaction(em ->
                        new Repositories(em).exercises().findAllWithEquipment())
                .stream()
                .filter(exercise -> matchesText(exercise, text))
                .filter(exercise -> matchesEquipment(exercise, equipmentId))
                .toList();
    }

    private static boolean matchesText(Exercise exercise, String text) {
        if (text.isEmpty()) {
            return true;
        }
        String name = exercise.getName() == null ? "" : exercise.getName().toLowerCase();
        String muscles = exercise.getMuscles() == null ? "" : exercise.getMuscles().toLowerCase();
        return name.contains(text) || muscles.contains(text);
    }

    private static boolean matchesEquipment(Exercise exercise, Long equipmentId) {
        if (equipmentId == null) {
            return true;
        }
        if (equipmentId == EQUIPMENT_NONE) {
            return exercise.getEquipmentLinks().isEmpty();
        }
        return exercise.getEquipmentLinks().stream()
                .anyMatch(link -> link.getEquipment().getId().equals(equipmentId));
    }

    /** Новое упражнение: название обязательно, остальное — по желанию. */
    public Exercise createExercise(String name,
                                   String setup,
                                   String movement,
                                   String muscles,
                                   String avoid) {

        if (name == null || name.isBlank()) {
            throw new ValidationException("Укажите название упражнения");
        }
        String cleanName = name.trim();

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            if (repos.exercises().findByName(cleanName).isPresent()) {
                throw new ValidationException("Упражнение «" + cleanName
                        + "» уже есть в справочнике — выберите его из списка");
            }

            Exercise exercise = Exercise.builder()
                    .name(cleanName)
                    .setup(blankToNull(setup))
                    .movement(blankToNull(movement))
                    .muscles(blankToNull(muscles))
                    .avoid(blankToNull(avoid))
                    .build();

            return repos.exercises().save(exercise);
        });
    }

    /** Сохраняет описание существующего упражнения (ИП, движение, мышцы, чего избегать). */
    public Exercise saveExerciseDetails(long exerciseId,
                                        String setup,
                                        String movement,
                                        String muscles,
                                        String avoid) {

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            Exercise exercise = repos.exercises().findById(exerciseId)
                    .orElseThrow(() -> new ValidationException("Упражнение не найдено: " + exerciseId));

            exercise.setSetup(blankToNull(setup));
            exercise.setMovement(blankToNull(movement));
            exercise.setMuscles(blankToNull(muscles));
            exercise.setAvoid(blankToNull(avoid));
            return exercise;
        });
    }

    /** Весь инвентарь по алфавиту — для ComboBox. */
    public List<Equipment> getAllEquipment() {
        return database.inTransaction(em -> new Repositories(em).equipment().findAllOrdered());
    }

    /** Инвентарь, прикреплённый к упражнению (с количеством). */
    public List<ExerciseEquipment> getEquipmentForExercise(long exerciseId) {
        return database.inTransaction(em -> new Repositories(em)
                .exerciseEquipment()
                .findByExerciseId(exerciseId));
    }

    /** Отвязывает инвентарь от упражнения. Возвращает true, если связь была. */
    public boolean removeEquipment(long exerciseId, long equipmentId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            ExerciseEquipmentId id = new ExerciseEquipmentId(exerciseId, equipmentId);

            return repos.exerciseEquipment().findById(id)
                    .map(link -> {
                        repos.exerciseEquipment().delete(link);
                        return true;
                    })
                    .orElse(false);
        });
    }

    /**
     * Заменяет весь инвентарь упражнения одним предметом
     * («взял гантель 6 кг вместо 8 кг»).
     */
    public void replaceEquipment(long exerciseId, long newEquipmentId) {
        database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            Exercise exercise = repos.exercises().findById(exerciseId)
                    .orElseThrow(() -> new ValidationException("Упражнение не найдено: " + exerciseId));
            Equipment equipment = repos.equipment().findById(newEquipmentId)
                    .orElseThrow(() -> new ValidationException("Инвентарь не найден: " + newEquipmentId));

            deleteLinks(repos, exerciseId);
            // удаляем до вставки: PK (exercise_id, equipment_id) может совпасть
            em.flush();

            repos.exerciseEquipment().save(ExerciseEquipment.link(exercise, equipment, 1));
            em.flush();

            refreshPlanSnapshots(repos, exerciseId);
            return exercise;
        });
    }

    /** Полностью очищает инвентарь упражнения. */
    public void clearEquipment(long exerciseId) {
        database.runInTransaction(em -> {
            Repositories repos = new Repositories(em);
            deleteLinks(repos, exerciseId);
            em.flush();

            refreshPlanSnapshots(repos, exerciseId);
        });
    }

    private static void deleteLinks(Repositories repos, long exerciseId) {
        for (ExerciseEquipment link : repos.exerciseEquipment().findByExerciseId(exerciseId)) {
            repos.exerciseEquipment().delete(link);
        }
    }

    /**
     * Привязывает инвентарь к упражнению (3 резинки = quantity 3).
     * Если связь уже есть — обновляет количество.
     */
    public Exercise addEquipment(long exerciseId, long equipmentId, int quantity) {
        if (quantity < 1) {
            throw new ValidationException("Количество инвентаря должно быть не меньше 1");
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            Exercise exercise = repos.exercises().findById(exerciseId)
                    .orElseThrow(() -> new ValidationException("Упражнение не найдено: " + exerciseId));
            Equipment equipment = repos.equipment().findById(equipmentId)
                    .orElseThrow(() -> new ValidationException("Инвентарь не найден: " + equipmentId));

            ExerciseEquipment existing = repos.exerciseEquipment().findByExerciseId(exerciseId).stream()
                    .filter(link -> link.getEquipment().getId().equals(equipmentId))
                    .findFirst()
                    .orElse(null);

            if (existing == null) {
                repos.exerciseEquipment().save(ExerciseEquipment.link(exercise, equipment, quantity));
            } else {
                existing.setQuantity(quantity);
            }
            em.flush();

            refreshPlanSnapshots(repos, exerciseId);
            return exercise;
        });
    }

    // ------------------------------------------------- снимок инвентаря (B4)

    /** Инвентарь упражнения строкой: «Гантель, Скамья (2 шт)» или null. */
    private static String equipmentSnapshot(Repositories repos, long exerciseId) {
        List<ExerciseEquipment> links = repos.exerciseEquipment().findByExerciseId(exerciseId);
        if (links.isEmpty()) {
            return null;
        }
        return links.stream()
                .map(link -> link.getQuantity() > 1
                        ? link.getEquipment().getName() + " (" + link.getQuantity() + " шт)"
                        : link.getEquipment().getName())
                .collect(Collectors.joining(", "));
    }

    /**
     * Обновляет снимок инвентаря во всех строках шаблонов этого упражнения.
     *
     * Нужно, когда пользователь меняет инвентарь в редакторе: шаблон —
     * это «чертёж» тренировки, и снимок должен соответствовать правке.
     * Уже созданные тренировки при этом не трогаются (у них свой снимок).
     */
    private static void refreshPlanSnapshots(Repositories repos, long exerciseId) {
        String snapshot = equipmentSnapshot(repos, exerciseId);
        for (PlanExercise item : repos.planExercises().findByExerciseId(exerciseId)) {
            item.setEquipmentSnapshot(snapshot);
        }
    }

    // -------------------------------------------------------------- служебное

    private static WorkoutPlan createPlan(Repositories repos, long userId, String name, String description) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Укажите название шаблона, например «Тренировка Г»");
        }
        String cleanName = name.trim();

        boolean duplicated = repos.plans().findByUserId(userId).stream()
                .anyMatch(plan -> plan.getName().equalsIgnoreCase(cleanName));
        if (duplicated) {
            throw new ValidationException("Шаблон с названием «" + cleanName + "» уже есть");
        }

        User user = repos.users().findById(userId)
                .orElseThrow(() -> new ValidationException("Пользователь не найден: " + userId));

        WorkoutPlan plan = WorkoutPlan.builder()
                .user(user)
                .name(cleanName)
                .description(description == null || description.isBlank() ? null : description.trim())
                .active(true)
                .build();

        return repos.plans().save(plan);
    }

    private static WorkoutPlan requirePlan(Repositories repos, long planId) {
        return repos.plans().findById(planId)
                .orElseThrow(() -> new ValidationException("Шаблон не найден: " + planId));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** Проверка целевых чисел из JSON (у публичного setTargets — своя, с другими сообщениями). */
    private static void validateTargets(Integer targetSets, Integer targetReps,
                                        BigDecimal targetWeightKg, String exerciseName) {
        if (targetSets != null && targetSets <= 0) {
            throw new ValidationException("«" + exerciseName + "»: подходов должно быть больше 0, получено "
                    + targetSets);
        }
        if (targetSets != null && targetSets > MAX_TARGET_SETS) {
            throw new ValidationException("«" + exerciseName + "»: подходов не может быть больше "
                    + MAX_TARGET_SETS + ", получено " + targetSets);
        }
        if (targetReps != null && targetReps <= 0) {
            throw new ValidationException("«" + exerciseName + "»: повторов должно быть больше 0, получено "
                    + targetReps);
        }
        if (targetWeightKg != null && targetWeightKg.signum() < 0) {
            throw new ValidationException("«" + exerciseName + "»: вес не может быть отрицательным");
        }
        if (targetWeightKg != null && targetWeightKg.compareTo(MAX_TARGET_WEIGHT_KG) > 0) {
            throw new ValidationException("«" + exerciseName + "»: слишком большое значение, вес не может быть больше "
                    + MAX_TARGET_WEIGHT_KG + " кг");
        }
    }
}

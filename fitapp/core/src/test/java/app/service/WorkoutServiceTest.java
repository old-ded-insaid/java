package app.service;

import app.model.Exercise;
import app.model.PlanExercise;
import app.model.User;
import app.model.Workout;
import app.model.WorkoutExercise;
import app.model.WorkoutPlan;
import app.model.WorkoutSet;
import app.support.FakePersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit-тесты WorkoutService на Mockito (без реальной PostgreSQL).
 *
 * Проверяем именно бизнес-правила сервиса; SQL-часть подменена
 * {@link FakePersistence}: сервис создаёт репозитории сам, поэтому
 * единственная точка подмены — EntityManager.
 */
class WorkoutServiceTest {

    private static final long USER_ID = 1L;
    private static final long PLAN_ID = 10L;
    private static final long WORKOUT_ID = 500L;

    private FakePersistence fake;
    private WorkoutService service;
    private User user;
    private WorkoutPlan plan;

    @BeforeEach
    void setUp() {
        fake = new FakePersistence();
        service = new WorkoutService(FakePersistence.mockDatabase(fake.em()), mock(AuditService.class));

        user = User.builder().id(USER_ID).username("owner").build();
        plan = WorkoutPlan.builder().id(PLAN_ID).user(user).name("Тренировка А").build();

        fake.register(User.class, USER_ID, user);
        fake.register(WorkoutPlan.class, PLAN_ID, plan);
    }

    // ------------------------------------------------------ createFromPlan

    @Test
    @DisplayName("createFromPlan: копирует подходы, повторы и вес из шаблона")
    void createFromPlan_copiesSetsRepsAndWeight() {
        Exercise squat = exercise(100L, "Присед");
        Exercise press = exercise(101L, "Жим");

        PlanExercise first = planItem(plan, squat, 1, 3, 12, new BigDecimal("20.00"));
        PlanExercise second = planItem(plan, press, 2, null, 8, null);
        fake.onQuery(PlanExercise.class, "from PlanExercise", params -> List.of(first, second));

        Workout workout = service.createFromPlan(USER_ID, PLAN_ID);

        assertNotNull(workout.getId());
        assertEquals("Тренировка А", workout.getName());
        assertEquals(LocalDate.now(ZoneOffset.UTC), workout.getWorkoutDate());
        assertNotNull(workout.getStartedAt());

        List<WorkoutSet> allSets = fake.mergedOf(WorkoutSet.class);
        assertEquals(4, allSets.size(), "3 подхода + 1 подход по умолчанию");

        List<WorkoutSet> squatSets = setsOf(allSets, 100L);
        assertEquals(List.of(1, 2, 3), squatSets.stream().map(WorkoutSet::getSetNumber).toList());
        assertTrue(squatSets.stream().allMatch(set -> Integer.valueOf(12).equals(set.getReps())));
        assertTrue(squatSets.stream().allMatch(set ->
                set.getWeightKg() != null && set.getWeightKg().compareTo(new BigDecimal("20.00")) == 0));

        List<WorkoutSet> pressSets = setsOf(allSets, 101L);
        assertEquals(1, pressSets.size(), "targetSets = null даёт один подход");
        assertEquals(Integer.valueOf(8), pressSets.get(0).getReps());
    }

    @Test
    @DisplayName("createFromPlan: неизвестный шаблон — ValidationException и ни одной тренировки")
    void createFromPlan_unknownPlan_throwsAndCreatesNothing() {
        assertThrows(ValidationException.class, () -> service.createFromPlan(USER_ID, 999L));

        assertTrue(fake.mergedOf(Workout.class).isEmpty());
        assertTrue(fake.mergedOf(WorkoutSet.class).isEmpty());
    }

    @Test
    @DisplayName("createFromPlan: шаблон без упражнений создаёт пустую тренировку")
    void createFromPlan_emptyPlan_createsWorkoutWithoutSets() {
        fake.onQuery(PlanExercise.class, "from PlanExercise", params -> List.of());

        Workout workout = service.createFromPlan(USER_ID, PLAN_ID);

        assertNotNull(workout.getId());
        assertTrue(fake.mergedOf(WorkoutSet.class).isEmpty());
    }

    /**
     * Баг: для плана без целевого веса createFromPlan пишет weightKg = 0,
     * хотя контракт WorkoutService говорит «bodyweight-подход пишется без веса: null»,
     * а updateSet() отвергает вес 0. В БД все 154 plan_exercises имеют target_weight_kg = NULL,
     * поэтому на реальных данных каждый подход получит 0 кг.
     */
    @Test
    @DisplayName("createFromPlan: подход без веса должен иметь weightKg = null")
    void createFromPlan_bodyweightSet_hasNullWeight() {
        Exercise plank = exercise(102L, "Планка");
        fake.onQuery(PlanExercise.class, "from PlanExercise",
                params -> List.of(planItem(plan, plank, 1, 1, null, null)));

        service.createFromPlan(USER_ID, PLAN_ID);

        assertNull(fake.mergedOf(WorkoutSet.class).get(0).getWeightKg());
    }

    // --------------------------------------------------------- deleteWorkout

    @Test
    @DisplayName("deleteWorkout: удаляет тренировку (подходы уходят каскадом)")
    void deleteWorkout_removesWorkout() {
        Workout workout = workout(WORKOUT_ID, null);
        fake.register(Workout.class, WORKOUT_ID, workout);

        service.deleteWorkout(WORKOUT_ID);

        assertTrue(fake.wasRemoved(workout));
        verify(fake.em()).remove(workout);
    }

    @Test
    @DisplayName("deleteWorkout: несуществующая тренировка — ValidationException")
    void deleteWorkout_unknownId_throws() {
        assertThrows(ValidationException.class, () -> service.deleteWorkout(999L));
        assertTrue(fake.removed().isEmpty());
    }

    // ---------------------------------------------------------- deleteIfEmpty

    @Test
    @DisplayName("deleteIfEmpty: пустая тренировка удаляется")
    void deleteIfEmpty_emptyWorkout_isDeleted() {
        Workout workout = workout(WORKOUT_ID, null);
        fake.register(Workout.class, WORKOUT_ID, workout);

        assertTrue(service.deleteIfEmpty(WORKOUT_ID));
        assertTrue(fake.wasRemoved(workout));
    }

    /**
     * BUG-01: завершённая тренировка с реальной длительностью 600 секунд
     * НЕ должна удаляться автоочисткой, даже если подходы пустые.
     */
    @Test
    @DisplayName("deleteIfEmpty: завершённая тренировка не удаляется")
    void deleteIfEmpty_finishedWorkoutWithDuration_isNotDeleted() {
        Workout workout = workout(WORKOUT_ID, null);
        workout.setEndedAt(LocalDateTime.now(ZoneOffset.UTC));
        workout.setDurationSec(600);
        fake.register(Workout.class, WORKOUT_ID, workout);

        assertFalse(service.deleteIfEmpty(WORKOUT_ID));
        assertFalse(fake.wasRemoved(workout));
    }

    @Test
    @DisplayName("deleteIfEmpty: завершённая тренировка с пустыми подходами не удаляется")
    void deleteIfEmpty_finishedWorkoutWithEmptySets_isNotDeleted() {
        Workout workout = workout(WORKOUT_ID, null);
        workout.setEndedAt(LocalDateTime.now(ZoneOffset.UTC));

        // Все подходы «пустые»: ни времени, ни заметок, ни ручных правок.
        WorkoutExercise link = link(workout, exercise(100L, "Присед"),
                set(null, 1, null, null, null, null));

        fake.register(Workout.class, WORKOUT_ID, workout);
        fake.onQuery(WorkoutExercise.class, "from WorkoutExercise", params -> List.of(link));

        assertFalse(service.deleteIfEmpty(WORKOUT_ID),
                "завершённая тренировка — это история, а не черновик");
        assertFalse(fake.wasRemoved(workout));
        verify(fake.em(), never()).remove(workout);
    }

    @Test
    @DisplayName("deleteIfEmpty: тренировка с записанным временем остаётся")
    void deleteIfEmpty_workoutWithTime_isKept() {
        Workout workout = workout(WORKOUT_ID, null);
        WorkoutExercise link = link(workout, exercise(100L, "Присед"),
                set(null, 1, null, null, 30, null));

        fake.register(Workout.class, WORKOUT_ID, workout);
        fake.onQuery(WorkoutExercise.class, "from WorkoutExercise", params -> List.of(link));

        assertFalse(service.deleteIfEmpty(WORKOUT_ID));
        assertFalse(fake.wasRemoved(workout));
        verify(fake.em(), never()).remove(workout);
    }

    // -------------------------------------------------------- skip / unskip

    @Test
    @DisplayName("markSkipped: ставит флаг, обнуляет время и ставит заметку")
    void markSkipped_setsFlagAndClearsTimes() {
        WorkoutSet set = WorkoutSet.builder()
                .id(700L)
                .setNumber(1)
                .durationSec(42)
                .timeMarkerSec(120)
                .build();
        fake.register(WorkoutSet.class, 700L, set);

        WorkoutSet skipped = service.markSkipped(700L);

        assertTrue(skipped.isSkipped());
        assertNull(skipped.getDurationSec(), "duration_sec должен стать null");
        assertNull(skipped.getTimeMarkerSec(), "time_marker_sec должен стать null");
        assertEquals(WorkoutService.AUTO_SKIP_NOTE, skipped.getNote());
    }

    @Test
    @DisplayName("markSkipped: не затирает заметку, которую написал пользователь")
    void markSkipped_preservesUserNote() {
        WorkoutSet set = WorkoutSet.builder()
                .id(701L)
                .setNumber(1)
                .note("Колено щёлкнуло")
                .build();
        fake.register(WorkoutSet.class, 701L, set);

        WorkoutSet skipped = service.markSkipped(701L);

        assertTrue(skipped.isSkipped());
        assertEquals("Колено щёлкнуло", skipped.getNote());
    }

    @Test
    @DisplayName("unskipSet: снимает флаг и очищает автоматическую заметку")
    void unskipSet_clearsFlagAndAutoNote() {
        WorkoutSet set = WorkoutSet.builder()
                .id(702L)
                .setNumber(1)
                .skipped(true)
                .note(WorkoutService.AUTO_SKIP_NOTE)
                .build();
        fake.register(WorkoutSet.class, 702L, set);

        WorkoutSet unskipped = service.unskipSet(702L);

        assertFalse(unskipped.isSkipped());
        assertNull(unskipped.getNote());
    }

    @Test
    @DisplayName("unskipSet: сохраняет пользовательскую заметку")
    void unskipSet_preservesUserNote() {
        WorkoutSet set = WorkoutSet.builder()
                .id(703L)
                .setNumber(1)
                .skipped(true)
                .note("Сделал половину")
                .build();
        fake.register(WorkoutSet.class, 703L, set);

        WorkoutSet unskipped = service.unskipSet(703L);

        assertFalse(unskipped.isSkipped());
        assertEquals("Сделал половину", unskipped.getNote());
    }

    // ---------------------------------------------------------- isWorkoutEmpty

    @Test
    @DisplayName("isWorkoutEmpty: подходы совпадают с шаблоном — тренировка пустая")
    void isWorkoutEmpty_untouchedPlanWorkout_isEmpty() {
        Workout workout = workout(WORKOUT_ID, plan);
        WorkoutExercise link = link(workout, exercise(100L, "Присед"),
                set(null, 1, 12, new BigDecimal("20.00"), null, null));

        registerWorkoutTree(workout, link);
        fake.onQuery(PlanExercise.class, "from PlanExercise",
                params -> List.of(planItem(plan, exercise(100L, "Присед"), 1, 3, 12, new BigDecimal("20.00"))));

        assertTrue(service.isWorkoutEmpty(WORKOUT_ID));
    }

    @Test
    @DisplayName("isWorkoutEmpty: записанное время делает тренировку непустой")
    void isWorkoutEmpty_recordedDuration_isNotEmpty() {
        Workout workout = workout(WORKOUT_ID, null);
        WorkoutExercise link = link(workout, exercise(100L, "Присед"),
                set(null, 1, null, null, 1, null));

        registerWorkoutTree(workout, link);

        assertFalse(service.isWorkoutEmpty(WORKOUT_ID));
    }

    @Test
    @DisplayName("isWorkoutEmpty: заметка «Пропущено» — это уже работа, тренировка непустая")
    void isWorkoutEmpty_skippedSet_isNotEmpty() {
        Workout workout = workout(WORKOUT_ID, null);
        WorkoutExercise link = link(workout, exercise(100L, "Присед"),
                set(null, 1, null, null, 0, "Пропущено"));

        registerWorkoutTree(workout, link);

        assertFalse(service.isWorkoutEmpty(WORKOUT_ID));
    }

    @Test
    @DisplayName("isWorkoutEmpty: ручная правка повторов делает тренировку непустой")
    void isWorkoutEmpty_manualRepsChange_isNotEmpty() {
        Workout workout = workout(WORKOUT_ID, plan);
        WorkoutExercise link = link(workout, exercise(100L, "Присед"),
                set(null, 1, 10, new BigDecimal("20.00"), null, null));

        registerWorkoutTree(workout, link);
        fake.onQuery(PlanExercise.class, "from PlanExercise",
                params -> List.of(planItem(plan, exercise(100L, "Присед"), 1, 3, 12, new BigDecimal("20.00"))));

        assertFalse(service.isWorkoutEmpty(WORKOUT_ID));
    }

    @Test
    @DisplayName("isWorkoutEmpty: изменённый equipmentSnapshot — это данные пользователя")
    void isWorkoutEmpty_changedEquipmentSnapshot_isNotEmpty() {
        Workout workout = workout(WORKOUT_ID, null);
        WorkoutExercise link = WorkoutExercise.builder()
                .id(210L)
                .workout(workout)
                .exercise(exercise(100L, "Присед"))
                .orderIndex(1)
                .equipmentSnapshot("Гантель 8 кг")
                .build();
        link.getSets().add(set(link, 1, null, null, null, null));

        registerWorkoutTree(workout, link);

        assertFalse(service.isWorkoutEmpty(WORKOUT_ID),
                "снимок инвентаря отличается от справочника — тренировка не пустая");
    }

    @Test
    @DisplayName("isWorkoutEmpty: несуществующая тренировка считается пустой")
    void isWorkoutEmpty_unknownWorkout_isEmpty() {
        assertTrue(service.isWorkoutEmpty(999L));
    }

    /**
     * Баг: isWorkoutEmpty не смотрит на workouts.note, time_marker_sec,
     * pulse_bpm и calories. Тренировка с общей заметкой и без подходов
     * считается «пустой» и может быть удалена автоочисткой.
     */
    @Test
    @DisplayName("isWorkoutEmpty: заметка ко всей тренировке — данные, а не пустота")
    void isWorkoutEmpty_workoutNote_isNotEmpty() {
        Workout workout = workout(WORKOUT_ID, null);
        workout.setNote("Самочувствие хорошее, колено не беспокоило");
        fake.register(Workout.class, WORKOUT_ID, workout);

        assertFalse(service.isWorkoutEmpty(WORKOUT_ID));
    }

    /**
     * Баг: шаблон читается «на момент проверки». Если пользователь поправил
     * шаблон после создания тренировки, сравнение с новыми целями может
     * пометить реальные данные как «пустые» (и наоборот).
     */
    @Disabled("BUG-04: isWorkoutEmpty сравнивает с текущим шаблоном, а не со снимком на момент создания")
    @Test
    @DisplayName("isWorkoutEmpty: не должен зависеть от последующей правки шаблона")
    void isWorkoutEmpty_doesNotDependOnTemplateEdits() {
        Workout workout = workout(WORKOUT_ID, plan);
        // Тренировка создана из шаблона 3x12 с весом 20. Пользователь ничего не вводил.
        WorkoutExercise link = link(workout, exercise(100L, "Присед"),
                set(null, 1, 12, new BigDecimal("20.00"), null, null));

        registerWorkoutTree(workout, link);
        // Потом шаблон поменяли на 3x10 с весом 30.
        fake.onQuery(PlanExercise.class, "from PlanExercise",
                params -> List.of(planItem(plan, exercise(100L, "Присед"), 1, 3, 10, new BigDecimal("30.00"))));

        // Подходы всё ещё равны СТАРОМУ шаблону, то есть пользователь ничего не делал.
        assertTrue(service.isWorkoutEmpty(WORKOUT_ID),
                "незатронутая тренировка должна остаться пустой после правки шаблона");
    }

    // ----------------------------------------------------------- finishWorkout

    @Test
    @DisplayName("finishWorkout: считает длительность, калории и средний пульс")
    void finishWorkout_aggregatesSetsAndDuration() {
        Workout workout = workout(WORKOUT_ID, null);
        workout.setStartedAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(90));

        WorkoutExercise link = link(workout, exercise(100L, "Присед"),
                set(null, 1, 10, null, 30, null));

        WorkoutSet first = link.getSets().get(0);
        first.setPulseBpm(100);
        first.setCalories(10);

        WorkoutSet second = set(link, 2, 10, null, null, null);
        second.setPulseBpm(120);
        second.setCalories(20);
        link.getSets().add(second);

        fake.register(Workout.class, WORKOUT_ID, workout);
        fake.onQuery(WorkoutExercise.class, "from WorkoutExercise", params -> List.of(link));

        Workout finished = service.finishWorkout(WORKOUT_ID, "  хорошая тренировка  ");

        assertNotNull(finished.getEndedAt());
        assertTrue(finished.getDurationSec() >= 89);
        assertEquals(30, finished.getTotalCalories());
        assertEquals(110, finished.getAvgPulse());
        assertEquals("хорошая тренировка", finished.getNote());
    }

    // ---------------------------------------------------- importWorkoutFromJson

    @Test
    @DisplayName("importWorkoutFromJson: создаёт тренировку, подходы и timeMarkerSec")
    void importWorkoutFromJson_validJson_createsWorkoutAndSets() {
        String json = """
                {
                  "name": "Тренировка Г - Факт",
                  "description": "Из файла",
                  "exercises": [
                    {
                      "name": "Присед",
                      "inventory": "Гантель 8 кг",
                      "targetSets": 2,
                      "targetReps": 10,
                      "targetWeight": 20.0,
                      "sets": [
                        {
                          "setNumber": 1,
                          "targetSets": 1,
                          "targetReps": 10,
                          "targetWeight": 20.0,
                          "durationSec": 30,
                          "timeMarkerSec": 30,
                          "pulseBpm": 120,
                          "calories": 10,
                          "note": "рабочий"
                        },
                        {
                          "setNumber": 2,
                          "targetSets": 1,
                          "targetReps": 10,
                          "targetWeight": 20.0,
                          "durationSec": 30,
                          "timeMarkerSec": 60,
                          "pulseBpm": 120,
                          "calories": 10,
                          "note": "рабочий"
                        }
                      ]
                    }
                  ]
                }
                """;

        Workout workout = service.importWorkoutFromJson(USER_ID, json);

        assertNotNull(workout.getId());
        assertFalse(workout.isDraft(), "импорт — запись истории, а не черновик");
        assertEquals("Тренировка Г - Факт", workout.getName());
        assertEquals(60, workout.getDurationSec(), "30 сек × 2 подхода");

        List<WorkoutSet> sets = fake.mergedOf(WorkoutSet.class);
        assertEquals(2, sets.size());
        assertEquals(List.of(1, 2), sets.stream().map(WorkoutSet::getSetNumber).toList());
        assertEquals(List.of(30, 60),
                sets.stream().map(WorkoutSet::getTimeMarkerSec).toList(),
                "timeMarkerSec из JSON должен сохраниться и быть накопительным");
        assertTrue(sets.stream().allMatch(set -> Integer.valueOf(120).equals(set.getPulseBpm())));
        assertTrue(sets.stream().allMatch(set -> Integer.valueOf(10).equals(set.getCalories())));

        WorkoutExercise link = fake.mergedOf(WorkoutExercise.class).get(0);
        assertEquals("Гантель 8 кг", link.getEquipmentSnapshot());
    }

    @Test
    @DisplayName("importWorkoutFromJson: новое упражнение создаётся в справочнике")
    void importWorkoutFromJson_newExercise_isCreatedInCatalog() {
        String json = """
                {
                  "name": "Импорт",
                  "exercises": [
                    {
                      "name": "Новое упражнение",
                      "setup": "ИП",
                      "movement": "вверх",
                      "targetSets": 1,
                      "targetReps": 5
                    }
                  ]
                }
                """;

        service.importWorkoutFromJson(USER_ID, json);

        List<Exercise> created = fake.mergedOf(Exercise.class);
        assertEquals(1, created.size());
        assertEquals("Новое упражнение", created.get(0).getName());
        assertEquals("ИП", created.get(0).getSetup());
        assertEquals("вверх", created.get(0).getMovement());
    }

    @Test
    @DisplayName("importWorkoutFromJson: битый JSON — ValidationException без записи в БД")
    void importWorkoutFromJson_brokenJson_throws() {
        assertThrows(ValidationException.class,
                () -> service.importWorkoutFromJson(USER_ID, "{\"name\":\"X\",\"exercises\": ["));

        assertTrue(fake.mergedOf(Workout.class).isEmpty());
        assertTrue(fake.mergedOf(WorkoutSet.class).isEmpty());
    }

    @Test
    @DisplayName("importWorkoutFromJson: огромный targetSets отклоняется до записи")
    void importWorkoutFromJson_hugeTargetSets_isRejected() {
        String json = """
                {
                  "name": "Слишком много подходов",
                  "exercises": [
                    { "name": "Присед", "targetSets": 1000, "targetReps": 10 }
                  ]
                }
                """;

        assertThrows(ValidationException.class,
                () -> service.importWorkoutFromJson(USER_ID, json));

        assertTrue(fake.mergedOf(Workout.class).isEmpty(),
                "валидация должна сработать до открытия транзакции");
    }

    @Test
    @DisplayName("finishWorkout: durationSec считается по startedAt/endedAt, а не по сумме подходов")
    void finishWorkout_durationIsWallClockNotSetSum() {
        Workout workout = workout(WORKOUT_ID, null);
        workout.setStartedAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(125));

        WorkoutExercise link = link(workout, exercise(100L, "Присед"),
                set(null, 1, null, null, 600, null));
        WorkoutSet second = set(link, 2, null, null, 400, null);
        link.getSets().add(second);

        fake.register(Workout.class, WORKOUT_ID, workout);
        fake.onQuery(WorkoutExercise.class, "from WorkoutExercise", params -> List.of(link));

        Workout finished = service.finishWorkout(WORKOUT_ID, null);
        int duration = finished.getDurationSec();

        assertTrue(duration >= 124 && duration <= 130,
                "ожидали ~125 секунд по wall-clock, получили " + duration);
        assertNotEquals(1000, duration,
                "сумма duration_sec подходов (600 + 400) не должна попадать в workout.duration_sec");
    }

    // ---------------------------------------------------------------- helpers

    private Workout workout(long id, WorkoutPlan sourcePlan) {
        return Workout.builder()
                .id(id)
                .user(user)
                .plan(sourcePlan)
                .workoutDate(LocalDate.now(ZoneOffset.UTC))
                .name("Тренировка")
                .startedAt(LocalDateTime.now(ZoneOffset.UTC))
                .draft(true)   // создана кнопкой «Новая тренировка»
                .build();
    }

    private void registerWorkoutTree(Workout workout, WorkoutExercise link) {
        fake.register(Workout.class, workout.getId(), workout);
        fake.onQuery(WorkoutExercise.class, "from WorkoutExercise", params -> List.of(link));
    }

    private static Exercise exercise(long id, String name) {
        return Exercise.builder().id(id).name(name).build();
    }

    private static PlanExercise planItem(WorkoutPlan plan,
                                         Exercise exercise,
                                         int orderIndex,
                                         Integer sets,
                                         Integer reps,
                                         BigDecimal weight) {
        return PlanExercise.builder()
                .id((long) orderIndex)
                .plan(plan)
                .exercise(exercise)
                .orderIndex(orderIndex)
                .targetSets(sets)
                .targetReps(reps)
                .targetWeightKg(weight)
                .build();
    }

    private static WorkoutExercise link(Workout workout, Exercise exercise, WorkoutSet firstSet) {
        WorkoutExercise link = WorkoutExercise.builder()
                .id(200L)
                .workout(workout)
                .exercise(exercise)
                .orderIndex(1)
                .build();
        firstSet.setWorkoutExercise(link);
        link.getSets().add(firstSet);
        return link;
    }

    private static WorkoutSet set(WorkoutExercise link,
                                  int number,
                                  Integer reps,
                                  BigDecimal weight,
                                  Integer duration,
                                  String note) {
        return WorkoutSet.builder()
                .id(300L + number)
                .workoutExercise(link)
                .setNumber(number)
                .reps(reps)
                .weightKg(weight)
                .durationSec(duration)
                .note(note)
                .build();
    }

    private static List<WorkoutSet> setsOf(List<WorkoutSet> sets, long exerciseId) {
        return sets.stream()
                .filter(set -> set.getWorkoutExercise() != null)
                .filter(set -> set.getWorkoutExercise().getExercise() != null)
                .filter(set -> Long.valueOf(exerciseId).equals(set.getWorkoutExercise().getExercise().getId()))
                .toList();
    }
}

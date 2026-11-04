package app.service;

import app.db.Database;
import app.model.Equipment;
import app.model.Exercise;
import app.model.ExerciseEquipment;
import app.model.ExerciseEquipmentId;
import app.model.PlanExercise;
import app.model.User;
import app.model.WorkoutPlan;
import app.support.FakePersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PlanServiceTest {

    private static final long USER_ID = 1L;

    private FakePersistence fake;
    private Database database;
    private PlanService service;
    private User owner;
    private final Map<String, Exercise> catalog = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        fake = new FakePersistence();
        database = FakePersistence.mockDatabase(fake.em());
        service = new PlanService(database);

        owner = User.builder().id(USER_ID).username("owner").build();
        fake.register(User.class, USER_ID, owner);
        fake.onQuery(User.class, "from User", params -> List.of(owner));
        fake.onQuery(WorkoutPlan.class, "from WorkoutPlan", params -> List.of());
        fake.onQuery(Exercise.class, "where e.name = :name", params -> {
            String name = (String) params.get("name");
            Exercise found = catalog.get(name);
            return found == null ? List.of() : List.of(found);
        });
    }

    // ------------------------------------------------------- importPlanFromJson

    @Test
    @DisplayName("importPlanFromJson: нормальный JSON создаёт шаблон и упражнения")
    void importPlanFromJson_normalJson_createsPlan() {
        String json = """
                {
                  "name": "  Тренировка Д  ",
                  "description": "  Сила и координация  ",
                  "unknownField": [1, 2, 3],
                  "exercises": [
                    {
                      "name": " Присед ",
                      "movement": " вниз-вверх ",
                      "muscles": " Ноги ",
                      "setup": "   ",
                      "avoid": "   ",
                      "targetSets": 3,
                      "targetReps": 10,
                      "targetWeight": "40.00"
                    },
                    {
                      "name": "Жим",
                      "targetReps": 5,
                      "targetWeightKg": 20.5
                    }
                  ]
                }
                """;

        WorkoutPlan plan = service.importPlanFromJson(json);

        assertNotNull(plan.getId());
        assertEquals("Тренировка Д", plan.getName());
        assertEquals("Сила и координация", plan.getDescription());

        List<Exercise> exercises = fake.mergedOf(Exercise.class);
        assertEquals(2, exercises.size());
        Exercise squat = exercises.get(0);
        assertEquals("Присед", squat.getName());
        assertNull(squat.getSetup(), "пустая строка в JSON -> NULL");
        assertEquals("вниз-вверх", squat.getMovement());
        assertEquals("Ноги", squat.getMuscles());
        assertNull(squat.getAvoid(), "пустая строка в JSON -> NULL");

        List<PlanExercise> links = fake.mergedOf(PlanExercise.class);
        assertEquals(2, links.size());

        PlanExercise first = links.get(0);
        assertEquals(1, first.getOrderIndex());
        assertEquals(3, first.getTargetSets());
        assertEquals(10, first.getTargetReps());
        assertEquals(new BigDecimal("40.00"), first.getTargetWeightKg());
        assertSame(squat, first.getExercise());

        PlanExercise second = links.get(1);
        assertEquals(2, second.getOrderIndex());
        assertNull(second.getTargetSets());
        assertEquals(5, second.getTargetReps());
        assertEquals(new BigDecimal("20.5"), second.getTargetWeightKg());
    }

    @Test
    @DisplayName("importPlanFromJson: битый JSON — ValidationException до обращения к БД")
    void importPlanFromJson_brokenJson_throwsBeforeDatabase() {
        assertThrows(ValidationException.class,
                () -> service.importPlanFromJson("{ \"name\": \"X\", \"exercises\": ["));

        verifyNoInteractions(database);
    }

    @Test
    @DisplayName("importPlanFromJson: JSON-массив вместо объекта — ValidationException")
    void importPlanFromJson_jsonArray_throws() {
        assertThrows(ValidationException.class, () -> service.importPlanFromJson("[]"));
        verifyNoInteractions(database);
    }

    @Test
    @DisplayName("importPlanFromJson: пустой массив упражнений — ValidationException")
    void importPlanFromJson_emptyExercises_throws() {
        assertThrows(ValidationException.class,
                () -> service.importPlanFromJson("{\"name\":\"Тренировка\",\"exercises\":[]}"));

        verifyNoInteractions(database);
    }

    @Test
    @DisplayName("importPlanFromJson: пустое название — ValidationException")
    void importPlanFromJson_blankName_throws() {
        assertThrows(ValidationException.class,
                () -> service.importPlanFromJson("{\"name\":\"   \",\"exercises\":[{\"name\":\"Присед\"}]}"));

        verifyNoInteractions(database);
    }

    @Test
    @DisplayName("importPlanFromJson: targetSets = 0 — ValidationException")
    void importPlanFromJson_zeroTargetSets_throws() {
        String json = """
                {
                  "name": "Тренировка",
                  "exercises": [ { "name": "Присед", "targetSets": 0 } ]
                }
                """;

        assertThrows(ValidationException.class, () -> service.importPlanFromJson(json));
    }

    /**
     * Баг: targetSets не ограничен сверху. JSON с targetSets = 1000 пройдёт
     * валидацию, а WorkoutService.createFromPlan() затем создаст 1000 подходов.
     * При больших значениях это OOM/зависание приложения.
     */
    @Test
    @DisplayName("importPlanFromJson: огромный targetSets должен отклоняться")
    void importPlanFromJson_hugeTargetSets_isRejected() {
        String json = """
                {
                  "name": "Тренировка",
                  "exercises": [ { "name": "Присед", "targetSets": 1000, "targetReps": 10 } ]
                }
                """;

        assertThrows(ValidationException.class, () -> service.importPlanFromJson(json));
    }

    @Test
    @DisplayName("importPlanFromJson: существующее упражнение переиспользуется по имени")
    void importPlanFromJson_existingExercise_isReused() {
        Exercise existing = Exercise.builder().id(100L).name("Присед").build();
        catalog.put("Присед", existing);
        fake.register(Exercise.class, 100L, existing);

        String json = """
                {
                  "name": "Тренировка",
                  "exercises": [ { "name": "Присед", "targetSets": 2, "targetReps": 8 } ]
                }
                """;

        WorkoutPlan plan = service.importPlanFromJson(json);

        assertNotNull(plan.getId());
        assertTrue(fake.mergedOf(Exercise.class).isEmpty(), "дубликат упражнения создавать нельзя");
        assertEquals(1, fake.mergedOf(PlanExercise.class).size());
        assertSame(existing, fake.mergedOf(PlanExercise.class).get(0).getExercise());
    }

    @Test
    @DisplayName("importPlanFromJson: inventory привязывает инвентарь без учёта регистра")
    void importPlanFromJson_linksInventory() {
        Equipment dumbbell = Equipment.builder().id(10L).name("Гантель").build();
        Equipment bench = Equipment.builder().id(11L).name("Скамья").build();

        fake.onQuery(Equipment.class, "lower(e.name) = lower(:name)", params -> {
            String name = String.valueOf(params.get("name"));
            return List.of(dumbbell, bench).stream()
                    .filter(item -> item.getName().equalsIgnoreCase(name))
                    .toList();
        });
        fake.onQuery(ExerciseEquipment.class, "ee.id.exerciseId = :exerciseId", params -> List.of());

        String json = """
                {
                  "name": "Инвентарная",
                  "exercises": [
                    {
                      "name": "Жим",
                      "inventory": "гантель, Скамья, Штанга",
                      "targetSets": 3,
                      "targetReps": 10
                    }
                  ]
                }
                """;

        service.importPlanFromJson(json);

        List<ExerciseEquipment> links = fake.persistedOf(ExerciseEquipment.class);
        assertEquals(2, links.size(), "«Штанга» отсутствует в справочнике — импорт не падает");
        assertEquals("Гантель", links.get(0).getEquipment().getName(), "регистр не важен");
        assertEquals("Скамья", links.get(1).getEquipment().getName());
        assertEquals(1, links.get(0).getQuantity());
    }

    // ------------------------------------------------------ movePlanExercise

    @Test
    @DisplayName("movePlanExercise: вверх меняет orderIndex с предыдущей строкой")
    void movePlanExercise_up_swapsOrder() {
        List<PlanExercise> items = planItems();
        assertUniqueOnEachFlush(items);

        service.movePlanExercise(101L, -1); // B вверх

        assertEquals(1, items.get(1).getOrderIndex(), "B становится первым");
        assertEquals(2, items.get(0).getOrderIndex(), "A становится вторым");
        assertEquals(3, items.get(2).getOrderIndex(), "C не меняется");
        assertEquals(3, items.stream().map(PlanExercise::getOrderIndex).distinct().count(),
                "orderIndex остаются уникальными");
        verify(fake.em(), times(2)).flush();
    }

    @Test
    @DisplayName("movePlanExercise: вниз меняет orderIndex со следующей строкой")
    void movePlanExercise_down_swapsOrder() {
        List<PlanExercise> items = planItems();
        assertUniqueOnEachFlush(items);

        service.movePlanExercise(101L, 1); // B вниз

        assertEquals(1, items.get(0).getOrderIndex(), "A остаётся первым");
        assertEquals(3, items.get(1).getOrderIndex(), "B становится третьим");
        assertEquals(2, items.get(2).getOrderIndex(), "C становится вторым");
        assertEquals(3, items.stream().map(PlanExercise::getOrderIndex).distinct().count());
        verify(fake.em(), times(2)).flush();
    }

    @Test
    @DisplayName("movePlanExercise: первую строку вверх двигать некуда")
    void movePlanExercise_firstUp_doesNothing() {
        List<PlanExercise> items = planItems();

        service.movePlanExercise(100L, -1);

        assertEquals(1, items.get(0).getOrderIndex());
        assertEquals(2, items.get(1).getOrderIndex());
        assertEquals(3, items.get(2).getOrderIndex());
        verify(fake.em(), never()).flush();
    }

    @Test
    @DisplayName("movePlanExercise: направление кроме -1/+1 отклоняется")
    void movePlanExercise_invalidDirection_throws() {
        assertThrows(ValidationException.class, () -> service.movePlanExercise(100L, 0));
        verifyNoInteractions(database);
    }

    /**
     * В БД UNIQUE(plan_id, order_index), а миграция/сервис используют
     * промежуточный flush. Проверяем, что на каждом flush значения уникальны.
     */
    private void assertUniqueOnEachFlush(List<PlanExercise> items) {
        doAnswer(invocation -> {
            long unique = items.stream()
                    .map(PlanExercise::getOrderIndex)
                    .distinct()
                    .count();
            assertEquals(items.size(), unique,
                    "orderIndex должны оставаться уникальными на момент flush");
            return null;
        }).when(fake.em()).flush();
    }

    private List<PlanExercise> planItems() {
        WorkoutPlan plan = WorkoutPlan.builder().id(50L).user(owner).name("План").build();

        PlanExercise a = PlanExercise.builder()
                .id(100L)
                .plan(plan)
                .exercise(Exercise.builder().id(1L).name("A").build())
                .orderIndex(1)
                .build();
        PlanExercise b = PlanExercise.builder()
                .id(101L)
                .plan(plan)
                .exercise(Exercise.builder().id(2L).name("B").build())
                .orderIndex(2)
                .build();
        PlanExercise c = PlanExercise.builder()
                .id(102L)
                .plan(plan)
                .exercise(Exercise.builder().id(3L).name("C").build())
                .orderIndex(3)
                .build();

        fake.register(PlanExercise.class, 100L, a);
        fake.register(PlanExercise.class, 101L, b);
        fake.register(PlanExercise.class, 102L, c);
        fake.onQuery(PlanExercise.class, "from PlanExercise", params -> List.of(a, b, c));

        return List.of(a, b, c);
    }

    // ----------------------------------------------------------- replaceEquipment
    @Test
    @DisplayName("replaceEquipment: старые связи удаляются, новая вставляется после flush")
    void replaceEquipment_replacesLinks() {
        Exercise exercise = Exercise.builder().id(100L).name("Присед").build();
        Equipment oldEquipment = Equipment.builder().id(1L).name("Гантель 8 кг").build();
        Equipment newEquipment = Equipment.builder().id(2L).name("Гантель 6 кг").build();

        fake.register(Exercise.class, 100L, exercise);
        fake.register(Equipment.class, 1L, oldEquipment);
        fake.register(Equipment.class, 2L, newEquipment);

        ExerciseEquipment oldLink = ExerciseEquipment.link(exercise, oldEquipment, 2);
        oldLink.setId(new ExerciseEquipmentId(100L, 1L));
        fake.onQuery(ExerciseEquipment.class, "from ExerciseEquipment", params -> List.of(oldLink));

        service.replaceEquipment(100L, 2L);

        assertTrue(fake.wasRemoved(oldLink));
        List<ExerciseEquipment> savedLinks = fake.persistedOf(ExerciseEquipment.class);
        assertEquals(1, savedLinks.size());
        assertSame(newEquipment, savedLinks.get(0).getEquipment());
        assertEquals(1, savedLinks.get(0).getQuantity());

        InOrder order = inOrder(fake.em());
        order.verify(fake.em()).remove(oldLink);
        order.verify(fake.em()).flush();
        order.verify(fake.em()).persist(savedLinks.get(0));
    }

    @Test
    @DisplayName("replaceEquipment: неизвестное упражнение — ValidationException")
    void replaceEquipment_unknownExercise_throws() {
        assertThrows(ValidationException.class, () -> service.replaceEquipment(999L, 1L));

        verify(fake.em(), never()).flush();
        assertTrue(fake.removed().isEmpty());
    }

    @Test
    @DisplayName("replaceEquipment: неизвестный инвентарь — ValidationException")
    void replaceEquipment_unknownEquipment_throws() {
        Exercise exercise = Exercise.builder().id(100L).name("Присед").build();
        fake.register(Exercise.class, 100L, exercise);

        assertThrows(ValidationException.class, () -> service.replaceEquipment(100L, 999L));

        verify(fake.em(), never()).flush();
        assertTrue(fake.removed().isEmpty());
    }

    @Test
    @DisplayName("replaceEquipment: упражнение без старого инвентаря — просто добавляет новый")
    void replaceEquipment_noOldLinks_addsNew() {
        Exercise exercise = Exercise.builder().id(100L).name("Присед").build();
        Equipment equipment = Equipment.builder().id(2L).name("Гантель 6 кг").build();
        fake.register(Exercise.class, 100L, exercise);
        fake.register(Equipment.class, 2L, equipment);
        fake.onQuery(ExerciseEquipment.class, "from ExerciseEquipment", params -> List.of());

        service.replaceEquipment(100L, 2L);

        assertTrue(fake.removed().isEmpty());
        assertEquals(1, fake.persistedOf(ExerciseEquipment.class).size());
        assertSame(equipment, fake.persistedOf(ExerciseEquipment.class).get(0).getEquipment());
    }

    // ------------------------------------------------------------------ plans

    @Test
    @DisplayName("createPlan: дубликат названия без учёта регистра — ValidationException")
    void createPlan_duplicateNameIgnoringCase_throws() {
        WorkoutPlan existing = WorkoutPlan.builder().id(20L).user(owner).name("Тренировка А").build();
        fake.onQuery(WorkoutPlan.class, "from WorkoutPlan", params -> List.of(existing));

        assertThrows(ValidationException.class, () -> service.createPlan(USER_ID, "тренировка а", null));

        assertTrue(fake.mergedOf(WorkoutPlan.class).isEmpty());
    }
}

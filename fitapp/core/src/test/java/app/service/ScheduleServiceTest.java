package app.service;

import app.model.Schedule;
import app.model.ScheduleItem;
import app.model.User;
import app.model.WorkoutPlan;
import app.support.FakePersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleServiceTest {

    private static final long SCHEDULE_ID = 7L;
    private static final LocalDate START = LocalDate.of(2026, 12, 1);

    private FakePersistence fake;
    private ScheduleService service;
    private Schedule schedule;
    private WorkoutPlan planA;
    private WorkoutPlan planB;
    private WorkoutPlan planC;

    @BeforeEach
    void setUp() {
        fake = new FakePersistence();
        service = new ScheduleService(FakePersistence.mockDatabase(fake.em()));

        User owner = User.builder().id(1L).username("owner").build();
        planA = WorkoutPlan.builder().id(11L).user(owner).name("Тренировка A").build();
        planB = WorkoutPlan.builder().id(12L).user(owner).name("Тренировка B").build();
        planC = WorkoutPlan.builder().id(13L).user(owner).name("Тренировка C").build();

        schedule = Schedule.builder()
                .id(SCHEDULE_ID)
                .user(owner)
                .name("Цикл A/B/C")
                .startDate(START)
                .intervalDays(2)
                .active(true)
                .startPlan(planB)
                .build();

        List<ScheduleItem> items = List.of(
                item(1L, 1, planA),
                item(2L, 2, planB),
                item(3L, 3, planC));

        fake.register(Schedule.class, SCHEDULE_ID, schedule);
        fake.onQuery(ScheduleItem.class, "from ScheduleItem", params -> items);
    }

    // ------------------------------------------------ старт в будущем

    @Test
    @DisplayName("nextWorkout: старт в будущем — возвращает дату старта и стартовый шаблон")
    void nextWorkout_startDateInFuture_returnsStartDate() {
        ScheduleService.ScheduledWorkout next =
                service.nextWorkout(SCHEDULE_ID, LocalDate.of(2026, 11, 1)).orElseThrow();

        assertEquals(START, next.date());
        assertEquals(planB.getId(), next.plan().getId());
    }

    @Test
    @DisplayName("nextWorkout: за день до старта — всё равно дата старта")
    void nextWorkout_dayBeforeStart_returnsStartDate() {
        ScheduleService.ScheduledWorkout next =
                service.nextWorkout(SCHEDULE_ID, START.minusDays(1)).orElseThrow();

        assertEquals(START, next.date());
        assertEquals(planB.getId(), next.plan().getId());
    }

    @Test
    @DisplayName("nextWorkout: ровно в день старта — этот же день (включительно)")
    void nextWorkout_onStartDate_returnsStartDate() {
        ScheduleService.ScheduledWorkout next =
                service.nextWorkout(SCHEDULE_ID, START).orElseThrow();

        assertEquals(START, next.date());
        assertEquals(planB.getId(), next.plan().getId());
    }

    @Test
    @DisplayName("dayForDate: до старта тренировки нет")
    void dayForDate_beforeStart_isEmpty() {
        assertTrue(service.dayForDate(SCHEDULE_ID, START.minusDays(1)).isEmpty());
        assertTrue(service.planForDate(SCHEDULE_ID, START.minusDays(1)).isEmpty());
    }

    // ------------------------------------------------ ротация цикла

    @Test
    @DisplayName("dayForDate: день старта — шаблон B (startPlan) с позицией 2 из 3")
    void dayForDate_onStartDate_returnsStartPlan() {
        ScheduleService.ScheduleDay day = service.dayForDate(SCHEDULE_ID, START).orElseThrow();

        assertEquals(2, day.position());
        assertEquals(3, day.cycleLength());
        assertEquals(0, day.daysSinceStart());
        assertEquals(planB.getId(), day.plan().getId());
        assertEquals("День 2 из 3: Тренировка B", day.describe());
    }

    @Test
    @DisplayName("dayForDate: 02.12 — не тренировочный день (интервал 2)")
    void dayForDate_nonTrainingDay_isEmpty() {
        assertTrue(service.dayForDate(SCHEDULE_ID, START.plusDays(1)).isEmpty());
    }

    @Test
    @DisplayName("dayForDate: 03.12 — шаг 1, шаблон C")
    void dayForDate_nextStep_returnsPlanC() {
        ScheduleService.ScheduleDay day =
                service.dayForDate(SCHEDULE_ID, START.plusDays(2)).orElseThrow();

        assertEquals(3, day.position());
        assertEquals(2, day.daysSinceStart());
        assertEquals(planC.getId(), day.plan().getId());
    }

    @Test
    @DisplayName("dayForDate: 05.12 — цикл переходит на A")
    void dayForDate_cycleRollover_returnsPlanA() {
        ScheduleService.ScheduleDay day =
                service.dayForDate(SCHEDULE_ID, START.plusDays(4)).orElseThrow();

        assertEquals(1, day.position());
        assertEquals(planA.getId(), day.plan().getId());
    }

    @Test
    @DisplayName("nextWorkout: с нетренировочного дня — ближайший следующий день")
    void nextWorkout_fromNonTrainingDay_returnsNextTrainingDay() {
        ScheduleService.ScheduledWorkout next =
                service.nextWorkout(SCHEDULE_ID, START.plusDays(1)).orElseThrow();

        assertEquals(START.plusDays(2), next.date());
        assertEquals(planC.getId(), next.plan().getId());
    }

    @Test
    @DisplayName("nextWorkout: с тренировочного дня — этот же день, не следующий")
    void nextWorkout_fromTrainingDay_returnsSameDay() {
        ScheduleService.ScheduledWorkout next =
                service.nextWorkout(SCHEDULE_ID, START.plusDays(2)).orElseThrow();

        assertEquals(START.plusDays(2), next.date());
        assertEquals(planC.getId(), next.plan().getId());
    }

    // ------------------------------------------------ граничные случаи

    @Test
    @DisplayName("Расписание неактивно — тренировок нет")
    void inactiveSchedule_returnsEmpty() {
        schedule.setActive(false);

        assertTrue(service.dayForDate(SCHEDULE_ID, START).isEmpty());
        assertTrue(service.nextWorkout(SCHEDULE_ID, START).isEmpty());
    }

    @Test
    @DisplayName("null-дата — ValidationException")
    void nullDate_throws() {
        assertThrows(ValidationException.class, () -> service.dayForDate(SCHEDULE_ID, null));
        assertThrows(ValidationException.class, () -> service.nextWorkout(SCHEDULE_ID, null));
    }

    @Test
    @DisplayName("Несуществующее расписание — ValidationException")
    void unknownSchedule_throws() {
        assertThrows(ValidationException.class, () -> service.dayForDate(999L, START));
        assertThrows(ValidationException.class, () -> service.nextWorkout(999L, START));
    }

    @Test
    @DisplayName("cycleLength: количество шагов правила")
    void cycleLength_isNumberOfItems() {
        assertEquals(3, service.cycleLength(SCHEDULE_ID));
    }

    private ScheduleItem item(long id, int position, WorkoutPlan plan) {
        return ScheduleItem.builder()
                .id(id)
                .schedule(schedule)
                .position(position)
                .plan(plan)
                .build();
    }
}

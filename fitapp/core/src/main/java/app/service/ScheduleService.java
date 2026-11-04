package app.service;

import app.db.Database;
import app.model.Schedule;
import app.model.ScheduleItem;
import app.model.WorkoutPlan;
import app.repository.Repositories;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Расчёт расписания: «какая тренировка в этот день» и «когда следующая».
 *
 * Правило ротации (согласовано):
 *   days = дней между start_date и датой
 *   тренировка бывает только если days >= 0 и days % interval_days == 0
 *   step = days / interval_days          — номер шага цикла
 *   finalIndex = (startIndex + step) % количество шаблонов
 *   startIndex — индекс шаблона start_plan_id в списке правила (0, если не задан)
 *
 * Пример: start_date = 01.11, interval = 4, шаблоны [A, B, C], старт с B:
 *   01.11 -> step 0 -> (1+0)%3 = 1 -> B
 *   02.11 -> дней 1, не кратно 4 -> тренировки нет
 *   05.11 -> step 1 -> (1+1)%3 = 2 -> C
 *   09.11 -> step 2 -> (1+2)%3 = 0 -> A
 *   13.11 -> step 3 -> (1+3)%3 = 1 -> B
 */
public class ScheduleService {

    private final Database database;

    public ScheduleService(Database database) {
        this.database = database;
    }

    /** Какая тренировка сегодня. */
    public Optional<WorkoutPlan> planForToday(long scheduleId) {
        return planForDate(scheduleId, LocalDate.now());
    }

    /** Какая тренировка в конкретный день (пусто, если день не тренировочный). */
    public Optional<WorkoutPlan> planForDate(long scheduleId, LocalDate date) {
        return dayForDate(scheduleId, date).map(ScheduleDay::plan);
    }

    /**
     * Подробное описание дня: позиция в цикле, длина цикла, сколько дней прошло.
     * Пусто, если в этот день тренировки нет.
     */
    public Optional<ScheduleDay> dayForDate(long scheduleId, LocalDate date) {
        if (date == null) {
            throw new ValidationException("Не указана дата для расчёта расписания");
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            Schedule schedule = repos.schedules().findById(scheduleId)
                    .orElseThrow(() -> new ValidationException("Расписание не найдено: " + scheduleId));

            if (!schedule.isActive()) {
                return Optional.<ScheduleDay>empty();
            }

            List<ScheduleItem> items = repos.scheduleItems().findByScheduleIdOrdered(scheduleId);
            if (items.isEmpty()) {
                return Optional.<ScheduleDay>empty(); // в правиле нет шаблонов
            }

            int interval = Math.max(1, schedule.getIntervalDays());
            long days = ChronoUnit.DAYS.between(schedule.getStartDate(), date);

            if (days < 0) {
                return Optional.<ScheduleDay>empty(); // расписание ещё не началось
            }
            if (days % interval != 0) {
                return Optional.<ScheduleDay>empty(); // сегодня не тренировочный день
            }

            int step = (int) (days / interval);
            int startIndex = startIndexOf(items, schedule.getStartPlan());
            int finalIndex = Math.floorMod(startIndex + step, items.size());

            WorkoutPlan plan = items.get(finalIndex).getPlan();
            return Optional.of(new ScheduleDay(finalIndex + 1, items.size(), days, plan));
        });
    }

    /**
     * Ближайшая тренировка начиная с даты {@code from} (включительно).
     * Нужна для строки «Следующая: 05.11 — Тренировка C».
     *
     * Считаем без перебора дней: тренировки идут строго каждые interval дней,
     * поэтому достаточно округлить разницу вверх до кратного интервалу.
     */
    public Optional<ScheduledWorkout> nextWorkout(long scheduleId, LocalDate from) {
        if (from == null) {
            throw new ValidationException("Не указана дата для расчёта расписания");
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            Schedule schedule = repos.schedules().findById(scheduleId)
                    .orElseThrow(() -> new ValidationException("Расписание не найдено: " + scheduleId));

            if (!schedule.isActive()) {
                return Optional.<ScheduledWorkout>empty();
            }

            List<ScheduleItem> items = repos.scheduleItems().findByScheduleIdOrdered(scheduleId);
            if (items.isEmpty()) {
                return Optional.<ScheduledWorkout>empty();
            }

            int interval = Math.max(1, schedule.getIntervalDays());
            long days = ChronoUnit.DAYS.between(schedule.getStartDate(), from);

            // округление вверх: 0 остаётся 0, 1..interval -> 1 и т.д.
            long step = days <= 0 ? 0 : (days + interval - 1) / interval;
            LocalDate date = schedule.getStartDate().plusDays(step * interval);

            int startIndex = startIndexOf(items, schedule.getStartPlan());
            int finalIndex = (int) Math.floorMod((long) startIndex + step, items.size());

            return Optional.of(new ScheduledWorkout(date, items.get(finalIndex).getPlan()));
        });
    }

    /** Сколько шагов в цикле (A, B, C -> 3). */
    public int cycleLength(long scheduleId) {
        return database.inTransaction(em -> new Repositories(em)
                .scheduleItems()
                .findByScheduleIdOrdered(scheduleId)
                .size());
    }

    /** Индекс стартового шаблона; 0, если start_plan_id не задан или шаблон убрали из правила. */
    private static int startIndexOf(List<ScheduleItem> items, WorkoutPlan startPlan) {
        if (startPlan == null) {
            return 0;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getPlan().getId().equals(startPlan.getId())) {
                return i;
            }
        }
        return 0;
    }

    /** Результат расчёта для одного дня. */
    public record ScheduleDay(int position, int cycleLength, long daysSinceStart, WorkoutPlan plan) {

        /** «День 2 из 3: Тренировка B» — строка для заголовка экрана. */
        public String describe() {
            return "День " + position + " из " + cycleLength + ": " + plan.getName();
        }
    }

    /** Ближайшая тренировка: дата + шаблон. */
    public record ScheduledWorkout(LocalDate date, WorkoutPlan plan) {
    }
}

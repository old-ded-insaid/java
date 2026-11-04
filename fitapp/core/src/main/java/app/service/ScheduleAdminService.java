package app.service;

import app.db.Database;
import app.model.Schedule;
import app.model.ScheduleItem;
import app.model.User;
import app.model.WorkoutPlan;
import app.repository.Repositories;

import java.time.LocalDate;
import java.util.List;

/**
 * Создание и просмотр правил расписания.
 *
 * Сам расчёт «что сегодня» живёт в ScheduleService — здесь только запись.
 */
public class ScheduleAdminService {

    private final Database database;

    public ScheduleAdminService(Database database) {
        this.database = database;
    }

    public List<Schedule> schedules(long userId) {
        return database.inTransaction(em -> new Repositories(em).schedules().findByUserId(userId));
    }

    /** Шаблоны правила по порядку (position 1, 2, 3...) — для галочек в форме. */
    public List<Long> planIdsOf(long scheduleId) {
        return database.inTransaction(em -> new Repositories(em)
                .scheduleItems()
                .findByScheduleIdOrdered(scheduleId)
                .stream()
                .map(item -> item.getPlan().getId())
                .toList());
    }

    /**
     * id стартового шаблона правила (или null).
     * Отдельный метод, потому что startPlan — ленивая связь: читать её
     * getName() после закрытия транзакции нельзя.
     */
    public Long startPlanIdOf(long scheduleId) {
        return database.inTransaction(em -> {
            Schedule schedule = new Repositories(em).schedules().findById(scheduleId).orElse(null);
            if (schedule == null || schedule.getStartPlan() == null) {
                return null;
            }
            return schedule.getStartPlan().getId();
        });
    }

    /**
     * Создаёт правило: старт, интервал и очередь шаблонов.
     *
     * @param planIds порядок важен: первый id получит position = 1 (тренировка A)
     */
    public Schedule createSchedule(long userId,
                                   String name,
                                   LocalDate startDate,
                                   int intervalDays,
                                   List<Long> planIds) {
        return createSchedule(userId, name, startDate, intervalDays, planIds, null);
    }

    /**
     * Создаёт правило расписания.
     *
     * @param planIds     порядок важен: первый id получит position = 1
     * @param startPlanId с какого шаблона начинается ротация (null — с первого)
     */
    public Schedule createSchedule(long userId,
                                   String name,
                                   LocalDate startDate,
                                   int intervalDays,
                                   List<Long> planIds,
                                   Long startPlanId) {

        if (planIds == null || planIds.isEmpty()) {
            throw new ValidationException("Выберите хотя бы один шаблон тренировки");
        }
        if (intervalDays < 1) {
            throw new ValidationException("Интервал должен быть не меньше 1 дня");
        }

        LocalDate start = (startDate == null) ? LocalDate.now() : startDate;

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            User user = repos.users().findById(userId)
                    .orElseThrow(() -> new ValidationException("Пользователь не найден: " + userId));

            WorkoutPlan startPlan = resolveStartPlan(repos, startPlanId, planIds);

            Schedule schedule = Schedule.builder()
                    .user(user)
                    .name(name == null || name.isBlank() ? "Расписание" : name.trim())
                    .startDate(start)
                    .intervalDays(intervalDays)
                    .active(true)
                    .startPlan(startPlan)
                    .build();

            int position = 1;
            for (Long planId : planIds) {
                WorkoutPlan plan = repos.plans().findById(planId)
                        .orElseThrow(() -> new ValidationException("Шаблон не найден: " + planId));

                schedule.getItems().add(ScheduleItem.builder()
                        .schedule(schedule)
                        .position(position++)
                        .plan(plan)
                        .build());
            }

            // cascade = ALL на Schedule.items сохранит шаги вместе с правилом
            return repos.schedules().save(schedule);
        });
    }

    /**
     * Обновляет существующее правило.
     *
     * Пункты расписания (schedule_items) пересоздаются: старые удаляются,
     * новые вставляются в порядке переданных planIds (1 = A, 2 = B, ...).
     *
     * Порядок важен: сначала удаляем, потом вставляем. Иначе на одинаковых
     * position сработает UNIQUE(schedule_id, position).
     */
    public Schedule updateSchedule(long scheduleId,
                                   String name,
                                   LocalDate startDate,
                                   int intervalDays,
                                   List<Long> planIds) {
        return updateSchedule(scheduleId, name, startDate, intervalDays, planIds, null);
    }

    public Schedule updateSchedule(long scheduleId,
                                   String name,
                                   LocalDate startDate,
                                   int intervalDays,
                                   List<Long> planIds,
                                   Long startPlanId) {

        if (planIds == null || planIds.isEmpty()) {
            throw new ValidationException("Выберите хотя бы один шаблон тренировки");
        }
        if (intervalDays < 1) {
            throw new ValidationException("Интервал должен быть не меньше 1 дня");
        }

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            Schedule schedule = repos.schedules().findById(scheduleId)
                    .orElseThrow(() -> new ValidationException("Расписание не найдено: " + scheduleId));

            // пустое название не затираем — оставляем прежнее
            schedule.setName(name == null || name.isBlank() ? schedule.getName() : name.trim());
            if (startDate != null) {
                schedule.setStartDate(startDate);
            }
            schedule.setIntervalDays(intervalDays);
            // null означает «начинать с первого шаблона»
            schedule.setStartPlan(resolveStartPlan(repos, startPlanId, planIds));

            schedule.getItems().clear();
            em.flush(); // DELETE старых пунктов до INSERT новых

            int position = 1;
            for (Long planId : planIds) {
                WorkoutPlan plan = repos.plans().findById(planId)
                        .orElseThrow(() -> new ValidationException("Шаблон не найден: " + planId));

                schedule.getItems().add(ScheduleItem.builder()
                        .schedule(schedule)
                        .position(position++)
                        .plan(plan)
                        .build());
            }

            return schedule; // новые пункты сохранятся каскадом при commit
        });
    }

    /**
     * Удаляет правило вместе с его пунктами (schedule_items).
     * Сами шаблоны тренировок и история остаются на месте.
     */
    public void deleteSchedule(long scheduleId) {
        database.runInTransaction(em -> {
            Repositories repos = new Repositories(em);

            Schedule schedule = repos.schedules().findById(scheduleId)
                    .orElseThrow(() -> new ValidationException("Расписание не найдено: " + scheduleId));

            repos.schedules().delete(schedule);
        });
    }

    /** Проверяет, что стартовый шаблон выбран и существует. */
    private static WorkoutPlan resolveStartPlan(Repositories repos, Long startPlanId, List<Long> planIds) {
        if (startPlanId == null) {
            return null; // начинаем с первого шаблона правила
        }
        if (!planIds.contains(startPlanId)) {
            throw new ValidationException("Стартовая тренировка должна входить в выбранные шаблоны");
        }
        return repos.plans().findById(startPlanId)
                .orElseThrow(() -> new ValidationException("Шаблон не найден: " + startPlanId));
    }
}

package app.repository;

import app.model.WorkoutPlan;

import java.util.List;

/** Доступ к таблице workout_plans (шаблоны A/B/C/D). */
public interface WorkoutPlanRepository extends CrudRepository<WorkoutPlan, Long> {

    List<WorkoutPlan> findByUserId(long userId);

    /** Только активные планы — их показываем в интерфейсе. */
    List<WorkoutPlan> findActiveByUserId(long userId);

    /**
     * Мягкое удаление: active = false.
     * План пропадает из списков, но история тренировок и расписание остаются целыми.
     * Возвращает true, если план найден и скрыт.
     */
    boolean archive(long planId);
}

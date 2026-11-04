package app.repository;

import app.model.PlanExercise;

import java.util.List;

/** Доступ к таблице plan_exercises (состав шаблона). */
public interface PlanExerciseRepository extends CrudRepository<PlanExercise, Long> {

    /** Упражнения плана по порядку выполнения. */
    List<PlanExercise> findByPlanIdOrdered(long planId);

    /** Все строки шаблонов, где используется это упражнение (для снимка инвентаря). */
    List<PlanExercise> findByExerciseId(long exerciseId);

    /** Следующий свободный order_index внутри плана (max + 1). */
    int nextOrderIndex(long planId);
}

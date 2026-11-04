package app.desktop.ui;

import java.math.BigDecimal;

/**
 * Строка таблицы «упражнения в шаблоне».
 *
 * Снимок данных для UI: id строки plan_exercises, порядковый номер,
 * название упражнения и целевые подходы/повторы.
 */
public record PlanExerciseRow(
        long planExerciseId,
        int orderIndex,
        long exerciseId,
        String exerciseName,
        Integer targetSets,
        Integer targetReps,
        BigDecimal targetWeightKg,
        String equipment
) {
}

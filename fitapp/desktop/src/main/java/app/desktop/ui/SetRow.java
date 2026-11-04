package app.desktop.ui;

import java.math.BigDecimal;

/**
 * Строка таблицы подходов.
 *
 * Помимо данных подхода хранит то, что нужно для действий прямо в таблице:
 * id строки workout_exercises (инвентарь меняется на уровне упражнения
 * тренировки) и флаг «пропущено» (BUG-07).
 *
 * durationSec и timeMarkerSec — два разных времени:
 *  - durationSec  — сколько длился сам подход;
 *  - timeMarkerSec — положение секундомера тренировки в момент записи
 *    («01:27:22 от старта»). Именно оно показывается в колонке «Время»,
 *    поэтому пользователь видит привычный накопительный отсчёт.
 */
public record SetRow(
        long setId,
        long workoutExerciseId,
        long exerciseId,
        String exerciseName,
        int setNumber,
        Integer reps,
        BigDecimal weightKg,
        Integer durationSec,
        Integer timeMarkerSec,
        String note,
        boolean skipped,
        String setup,
        String movement,
        String muscles,
        String avoid,
        String equipment
) {
}

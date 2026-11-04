package app.repository;

import app.model.WorkoutExercise;

import java.util.List;
import java.util.Optional;

/** Доступ к таблице workout_exercises. */
public interface WorkoutExerciseRepository extends CrudRepository<WorkoutExercise, Long> {

    List<WorkoutExercise> findByWorkoutIdOrdered(long workoutId);

    /** Одно и то же упражнение можно добавить в тренировку дважды — ищем конкретную строку. */
    Optional<WorkoutExercise> findByWorkoutIdAndExerciseId(long workoutId, long exerciseId);

    /** Следующий свободный order_index внутри тренировки (max + 1). */
    int nextOrderIndex(long workoutId);
}

package app.repository;

import app.model.WorkoutSet;

import java.util.List;

/** Доступ к таблице sets (подходы). */
public interface WorkoutSetRepository extends CrudRepository<WorkoutSet, Long> {

    /** Подходы упражнения по возрастанию номера. */
    List<WorkoutSet> findByWorkoutExerciseIdOrdered(long workoutExerciseId);

    /** Следующий номер подхода внутри упражнения (max + 1). */
    int nextSetNumber(long workoutExerciseId);
}

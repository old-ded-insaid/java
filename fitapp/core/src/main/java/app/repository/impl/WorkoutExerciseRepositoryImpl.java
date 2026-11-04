package app.repository.impl;

import app.model.WorkoutExercise;
import app.repository.WorkoutExerciseRepository;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

public class WorkoutExerciseRepositoryImpl extends AbstractJpaRepository<WorkoutExercise, Long>
        implements WorkoutExerciseRepository {

    public WorkoutExerciseRepositoryImpl(EntityManager em) {
        super(em, WorkoutExercise.class);
    }

    @Override
    public List<WorkoutExercise> findByWorkoutIdOrdered(long workoutId) {
        return em.createQuery("""
                        select we from WorkoutExercise we
                        join fetch we.exercise
                        where we.workout.id = :workoutId
                        order by we.orderIndex
                        """, WorkoutExercise.class)
                .setParameter("workoutId", workoutId)
                .getResultList();
    }

    @Override
    public Optional<WorkoutExercise> findByWorkoutIdAndExerciseId(long workoutId, long exerciseId) {
        return em.createQuery("""
                        select we from WorkoutExercise we
                        where we.workout.id = :workoutId
                          and we.exercise.id = :exerciseId
                        order by we.orderIndex
                        """, WorkoutExercise.class)
                .setParameter("workoutId", workoutId)
                .setParameter("exerciseId", exerciseId)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    @Override
    public int nextOrderIndex(long workoutId) {
        Integer max = em.createQuery(
                        "select max(we.orderIndex) from WorkoutExercise we where we.workout.id = :workoutId",
                        Integer.class)
                .setParameter("workoutId", workoutId)
                .getSingleResult();
        return max == null ? 1 : max + 1;
    }
}

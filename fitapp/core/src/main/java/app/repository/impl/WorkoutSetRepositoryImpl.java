package app.repository.impl;

import app.model.WorkoutSet;
import app.repository.WorkoutSetRepository;
import jakarta.persistence.EntityManager;

import java.util.List;

public class WorkoutSetRepositoryImpl extends AbstractJpaRepository<WorkoutSet, Long>
        implements WorkoutSetRepository {

    public WorkoutSetRepositoryImpl(EntityManager em) {
        super(em, WorkoutSet.class);
    }

    @Override
    public List<WorkoutSet> findByWorkoutExerciseIdOrdered(long workoutExerciseId) {
        return em.createQuery("""
                        select s from WorkoutSet s
                        where s.workoutExercise.id = :id
                        order by s.setNumber
                        """, WorkoutSet.class)
                .setParameter("id", workoutExerciseId)
                .getResultList();
    }

    @Override
    public int nextSetNumber(long workoutExerciseId) {
        Integer max = em.createQuery(
                        "select max(s.setNumber) from WorkoutSet s where s.workoutExercise.id = :id",
                        Integer.class)
                .setParameter("id", workoutExerciseId)
                .getSingleResult();
        return max == null ? 1 : max + 1;
    }
}

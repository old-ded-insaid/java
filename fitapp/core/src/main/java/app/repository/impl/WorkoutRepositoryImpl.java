package app.repository.impl;

import app.model.Workout;
import app.repository.WorkoutRepository;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class WorkoutRepositoryImpl extends AbstractJpaRepository<Workout, Long>
        implements WorkoutRepository {

    public WorkoutRepositoryImpl(EntityManager em) {
        super(em, Workout.class);
    }

    @Override
    public List<Workout> findByUserId(long userId) {
        return em.createQuery("""
                        select w from Workout w
                        where w.user.id = :userId
                        order by w.workoutDate desc, w.id desc
                        """, Workout.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public List<Workout> findByUserAndPeriod(long userId, LocalDate from, LocalDate to) {
        return em.createQuery("""
                        select w from Workout w
                        where w.user.id = :userId
                          and w.workoutDate between :from and :to
                        order by w.workoutDate desc, w.id desc
                        """, Workout.class)
                .setParameter("userId", userId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public Optional<Workout> findLastByUserId(long userId) {
        return em.createQuery("""
                        select w from Workout w
                        where w.user.id = :userId
                        order by w.workoutDate desc, w.id desc
                        """, Workout.class)
                .setParameter("userId", userId)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }
}

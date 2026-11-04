package app.repository.impl;

import app.model.MediaItem;
import app.repository.MediaRepository;
import jakarta.persistence.EntityManager;

import java.util.List;

public class MediaRepositoryImpl extends AbstractJpaRepository<MediaItem, Long>
        implements MediaRepository {

    public MediaRepositoryImpl(EntityManager em) {
        super(em, MediaItem.class);
    }

    @Override
    public List<MediaItem> findByWorkoutId(long workoutId) {
        return em.createQuery("""
                        select m from MediaItem m
                        where m.workout.id = :id
                        order by m.uploadedAt desc
                        """, MediaItem.class)
                .setParameter("id", workoutId)
                .getResultList();
    }

    @Override
    public List<MediaItem> findByWorkoutExerciseId(long workoutExerciseId) {
        return em.createQuery("""
                        select m from MediaItem m
                        where m.workoutExercise.id = :id
                        order by m.uploadedAt desc
                        """, MediaItem.class)
                .setParameter("id", workoutExerciseId)
                .getResultList();
    }

    @Override
    public List<MediaItem> findByPurpose(String purpose) {
        return em.createQuery("""
                        select m from MediaItem m
                        where m.purpose = :purpose
                        order by m.uploadedAt desc
                        """, MediaItem.class)
                .setParameter("purpose", purpose)
                .getResultList();
    }

    @Override
    public List<MediaItem> findAllForMeasurements() {
        return em.createQuery("""
                        select m from MediaItem m
                        join fetch m.measurement
                        order by m.id
                        """, MediaItem.class)
                .getResultList();
    }
}

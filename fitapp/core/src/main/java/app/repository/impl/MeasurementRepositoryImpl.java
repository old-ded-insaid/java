package app.repository.impl;

import app.model.Measurement;
import app.repository.MeasurementRepository;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.util.List;

public class MeasurementRepositoryImpl extends AbstractJpaRepository<Measurement, Long>
        implements MeasurementRepository {

    public MeasurementRepositoryImpl(EntityManager em) {
        super(em, Measurement.class);
    }

    @Override
    public List<Measurement> findByUserId(long userId) {
        return em.createQuery("""
                        select m from Measurement m
                        where m.user.id = :userId
                        order by m.measuredAt desc
                        """, Measurement.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public List<Measurement> findByUserIdWithMedia(long userId) {
        // left join fetch: замер может быть без картинки, но если она есть —
        // file_path доступен и после закрытия транзакции
        return em.createQuery("""
                        select m from Measurement m
                        left join fetch m.media
                        where m.user.id = :userId
                        order by m.measuredAt desc
                        """, Measurement.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public List<Measurement> findByUserIdAndKind(long userId, String kind) {
        return em.createQuery("""
                        select m from Measurement m
                        where m.user.id = :userId and m.kind = :kind
                        order by m.measuredAt desc
                        """, Measurement.class)
                .setParameter("userId", userId)
                .setParameter("kind", kind)
                .getResultList();
    }

    @Override
    public List<Measurement> findByUserAndPeriod(long userId, LocalDateTime from, LocalDateTime to) {
        return em.createQuery("""
                        select m from Measurement m
                        where m.user.id = :userId
                          and m.measuredAt between :from and :to
                        order by m.measuredAt desc
                        """, Measurement.class)
                .setParameter("userId", userId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public List<Measurement> findForChart(long userId,
                                          String kind,
                                          LocalDateTime from,
                                          LocalDateTime to) {
        return em.createQuery("""
                        select m from Measurement m
                        where m.user.id = :userId
                          and m.kind = :kind
                          and m.measuredAt between :from and :to
                        order by m.measuredAt
                        """, Measurement.class)
                .setParameter("userId", userId)
                .setParameter("kind", kind)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }
}

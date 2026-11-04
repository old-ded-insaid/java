package app.repository.impl;

import app.model.ScheduleItem;
import app.repository.ScheduleItemRepository;
import jakarta.persistence.EntityManager;

import java.util.List;

public class ScheduleItemRepositoryImpl extends AbstractJpaRepository<ScheduleItem, Long>
        implements ScheduleItemRepository {

    public ScheduleItemRepositoryImpl(EntityManager em) {
        super(em, ScheduleItem.class);
    }

    @Override
    public List<ScheduleItem> findByScheduleIdOrdered(long scheduleId) {
        return em.createQuery("""
                        select si from ScheduleItem si
                        join fetch si.plan
                        where si.schedule.id = :scheduleId
                        order by si.position
                        """, ScheduleItem.class)
                .setParameter("scheduleId", scheduleId)
                .getResultList();
    }
}

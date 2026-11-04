package app.repository.impl;

import app.model.Schedule;
import app.repository.ScheduleRepository;
import jakarta.persistence.EntityManager;

import java.util.List;

public class ScheduleRepositoryImpl extends AbstractJpaRepository<Schedule, Long>
        implements ScheduleRepository {

    public ScheduleRepositoryImpl(EntityManager em) {
        super(em, Schedule.class);
    }

    @Override
    public List<Schedule> findByUserId(long userId) {
        return em.createQuery("""
                        select s from Schedule s
                        where s.user.id = :userId
                        order by s.startDate desc
                        """, Schedule.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public List<Schedule> findActiveByUserId(long userId) {
        return em.createQuery("""
                        select s from Schedule s
                        where s.user.id = :userId and s.active = true
                        order by s.startDate desc
                        """, Schedule.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public boolean archive(long scheduleId) {
        Schedule schedule = em.find(Schedule.class, scheduleId);
        if (schedule == null) {
            return false;
        }
        schedule.setActive(false);
        return true;
    }
}

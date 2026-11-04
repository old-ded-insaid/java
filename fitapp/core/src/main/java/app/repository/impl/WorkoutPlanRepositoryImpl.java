package app.repository.impl;

import app.model.WorkoutPlan;
import app.repository.WorkoutPlanRepository;
import jakarta.persistence.EntityManager;

import java.util.List;

public class WorkoutPlanRepositoryImpl extends AbstractJpaRepository<WorkoutPlan, Long>
        implements WorkoutPlanRepository {

    public WorkoutPlanRepositoryImpl(EntityManager em) {
        super(em, WorkoutPlan.class);
    }

    @Override
    public List<WorkoutPlan> findByUserId(long userId) {
        return em.createQuery("""
                        select p from WorkoutPlan p
                        where p.user.id = :userId
                        order by lower(p.name)
                        """, WorkoutPlan.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public List<WorkoutPlan> findActiveByUserId(long userId) {
        return em.createQuery("""
                        select p from WorkoutPlan p
                        where p.user.id = :userId and p.active = true
                        order by lower(p.name)
                        """, WorkoutPlan.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public boolean archive(long planId) {
        WorkoutPlan plan = em.find(WorkoutPlan.class, planId);
        if (plan == null) {
            return false;
        }
        // мягкое удаление: строка остаётся, но план больше не показывается
        plan.setActive(false);
        return true;
    }
}

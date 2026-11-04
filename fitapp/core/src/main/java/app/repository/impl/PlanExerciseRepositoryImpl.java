package app.repository.impl;

import app.model.PlanExercise;
import app.repository.PlanExerciseRepository;
import jakarta.persistence.EntityManager;

import java.util.List;

public class PlanExerciseRepositoryImpl extends AbstractJpaRepository<PlanExercise, Long>
        implements PlanExerciseRepository {

    public PlanExerciseRepositoryImpl(EntityManager em) {
        super(em, PlanExercise.class);
    }

    @Override
    public List<PlanExercise> findByPlanIdOrdered(long planId) {
        // инвентарь упражнения нужен в таблице шаблона — подтягиваем сразу
        return em.createQuery("""
                        select distinct pe from PlanExercise pe
                        join fetch pe.exercise e
                        left join fetch e.equipmentLinks el
                        left join fetch el.equipment
                        where pe.plan.id = :planId
                        order by pe.orderIndex
                        """, PlanExercise.class)
                .setParameter("planId", planId)
                .getResultList();
    }

    @Override
    public int nextOrderIndex(long planId) {
        // max() вернёт null, если в плане ещё нет упражнений
        Integer max = em.createQuery(
                        "select max(pe.orderIndex) from PlanExercise pe where pe.plan.id = :planId",
                        Integer.class)
                .setParameter("planId", planId)
                .getSingleResult();
        return max == null ? 1 : max + 1;
    }

    @Override
    public List<PlanExercise> findByExerciseId(long exerciseId) {
        return em.createQuery("""
                        select pe from PlanExercise pe
                        where pe.exercise.id = :exerciseId
                        """, PlanExercise.class)
                .setParameter("exerciseId", exerciseId)
                .getResultList();
    }
}

package app.repository.impl;

import app.model.ExerciseEquipment;
import app.model.ExerciseEquipmentId;
import app.repository.ExerciseEquipmentRepository;
import jakarta.persistence.EntityManager;

import java.util.List;

public class ExerciseEquipmentRepositoryImpl
        extends AbstractJpaRepository<ExerciseEquipment, ExerciseEquipmentId>
        implements ExerciseEquipmentRepository {

    public ExerciseEquipmentRepositoryImpl(EntityManager em) {
        super(em, ExerciseEquipment.class);
    }

    @Override
    public ExerciseEquipment save(ExerciseEquipment entity) {
        // У связи составной ключ, который заполняет @MapsId уже при вставке.
        // Сам объект ExerciseEquipmentId должен существовать: Hibernate пишет
        // в его поля id упражнения и инвентаря (иначе NPE «o is null»).
        if (entity.getId() == null) {
            entity.setId(new ExerciseEquipmentId());
            em.persist(entity);
            return entity;
        }
        return em.merge(entity);
    }

    @Override
    public List<ExerciseEquipment> findByExerciseId(long exerciseId) {
        return em.createQuery("""
                        select ee from ExerciseEquipment ee
                        join fetch ee.equipment
                        where ee.id.exerciseId = :exerciseId
                        order by lower(ee.equipment.name)
                        """, ExerciseEquipment.class)
                .setParameter("exerciseId", exerciseId)
                .getResultList();
    }

    @Override
    public List<ExerciseEquipment> findByEquipmentId(long equipmentId) {
        return em.createQuery("""
                        select ee from ExerciseEquipment ee
                        where ee.id.equipmentId = :equipmentId
                        """, ExerciseEquipment.class)
                .setParameter("equipmentId", equipmentId)
                .getResultList();
    }
}

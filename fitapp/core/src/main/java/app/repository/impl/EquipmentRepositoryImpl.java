package app.repository.impl;

import app.model.Equipment;
import app.repository.EquipmentRepository;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

public class EquipmentRepositoryImpl extends AbstractJpaRepository<Equipment, Long>
        implements EquipmentRepository {

    public EquipmentRepositoryImpl(EntityManager em) {
        super(em, Equipment.class);
    }

    @Override
    public Optional<Equipment> findByName(String name) {
        return em.createQuery("select e from Equipment e where e.name = :name", Equipment.class)
                .setParameter("name", name)
                .getResultList()
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Equipment> findByNameIgnoreCase(String name) {
        return em.createQuery("select e from Equipment e where lower(e.name) = lower(:name)",
                        Equipment.class)
                .setParameter("name", name)
                .getResultList()
                .stream()
                .findFirst();
    }

    @Override
    public List<Equipment> findAllOrdered() {
        return em.createQuery("select e from Equipment e order by lower(e.name)", Equipment.class)
                .getResultList();
    }
}

package app.repository.impl;

import app.model.Exercise;
import app.repository.ExerciseRepository;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

public class ExerciseRepositoryImpl extends AbstractJpaRepository<Exercise, Long>
        implements ExerciseRepository {

    public ExerciseRepositoryImpl(EntityManager em) {
        super(em, Exercise.class);
    }

    @Override
    public Optional<Exercise> findByName(String name) {
        return em.createQuery("select e from Exercise e where e.name = :name", Exercise.class)
                .setParameter("name", name)
                .getResultList()
                .stream()
                .findFirst();
    }

    @Override
    public List<Exercise> search(String fragment) {
        // lower() + like: поиск без учёта регистра. %...% — «содержит».
        String pattern = "%" + (fragment == null ? "" : fragment.trim().toLowerCase()) + "%";
        return em.createQuery("""
                        select e from Exercise e
                        where lower(e.name) like :pattern
                           or lower(coalesce(e.muscles, '')) like :pattern
                        order by lower(e.name)
                        """, Exercise.class)
                .setParameter("pattern", pattern)
                .getResultList();
    }

    @Override
    public List<Exercise> findAllOrdered() {
        return em.createQuery("select e from Exercise e order by lower(e.name)", Exercise.class)
                .getResultList();
    }

    @Override
    public List<Exercise> findAllWithEquipment() {
        // distinct + join fetch: без него одно упражнение вернулось бы по строке на каждый инвентарь.
        // ORDER BY — только по колонке из SELECT: с DISTINCT выражение lower(name) PostgreSQL запрещает.
        return em.createQuery("""
                        select distinct e from Exercise e
                        left join fetch e.equipmentLinks el
                        left join fetch el.equipment
                        order by e.name
                        """, Exercise.class)
                .getResultList();
    }
}

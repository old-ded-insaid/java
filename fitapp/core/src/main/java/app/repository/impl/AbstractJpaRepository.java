package app.repository.impl;

import app.repository.CrudRepository;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

/**
 * Базовый класс всех репозиториев.
 *
 * Работает через EntityManager, который живёт одну транзакцию
 * (его создаёт Database.inTransaction). Поэтому репозиторий — дешёвый объект:
 * можно создавать новый на каждую операцию.
 *
 * Никакой бизнес-логики: только чтение и запись строк.
 */
public abstract class AbstractJpaRepository<T, ID> implements CrudRepository<T, ID> {

    /** EntityManager текущей транзакции. */
    protected final EntityManager em;

    /** Класс сущности — нужен для em.find и JPQL. */
    private final Class<T> type;

    protected AbstractJpaRepository(EntityManager em, Class<T> type) {
        this.em = em;
        this.type = type;
    }

    @Override
    public T save(T entity) {
        // merge умеет и INSERT (нет id), и UPDATE (id есть).
        // Возвращает управляемую копию — её и нужно отдавать наружу.
        return em.merge(entity);
    }

    @Override
    public Optional<T> findById(ID id) {
        return Optional.ofNullable(em.find(type, id));
    }

    @Override
    public List<T> findAll() {
        String jpql = "select e from " + type.getSimpleName() + " e";
        return em.createQuery(jpql, type).getResultList();
    }

    @Override
    public void delete(T entity) {
        // remove работает только с управляемой сущностью
        em.remove(em.contains(entity) ? entity : em.merge(entity));
    }

    @Override
    public void deleteById(ID id) {
        findById(id).ifPresent(this::delete);
    }

    @Override
    public long count() {
        String jpql = "select count(e) from " + type.getSimpleName() + " e";
        return em.createQuery(jpql, Long.class).getSingleResult();
    }
}

package app.db;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Точка входа в базу со стороны сервисов.
 *
 * Идея простая: на одну операцию — один EntityManager и одна транзакция.
 * Это избавляет от «висящих» сессий и от вопросов «а когда делать flush».
 *
 * Пример:
 * <pre>
 *   Workout workout = database.inTransaction(em -> {
 *       Repositories repos = new Repositories(em);
 *       return repos.workouts().save(newWorkout);
 *   });
 * </pre>
 */
public class Database implements AutoCloseable {

    private final EntityManagerFactory entityManagerFactory;

    public Database(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    /**
     * Выполняет работу в транзакции и возвращает результат.
     * При любом исключении — rollback, исключение пробрасывается дальше.
     */
    public <T> T inTransaction(Function<EntityManager, T> work) {
        EntityManager em = entityManagerFactory.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T result = work.apply(em);
            tx.commit();
            return result;
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            em.close();
        }
    }

    /** То же самое, но без возвращаемого значения (delete, обновление флага). */
    public void runInTransaction(Consumer<EntityManager> work) {
        inTransaction(em -> {
            work.accept(em);
            return null;
        });
    }

    /** Нужен редко: например, для ручного прогрева кеша второго уровня. */
    public EntityManagerFactory entityManagerFactory() {
        return entityManagerFactory;
    }

    @Override
    public void close() {
        entityManagerFactory.close();
    }
}

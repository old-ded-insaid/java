package app.repository;

import java.util.List;
import java.util.Optional;

/**
 * Общий набор операций «дай/сохрани» для всех репозиториев.
 *
 * Никакой бизнес-логики здесь нет: репозиторий не считает итоги,
 * не проверяет пульс и не решает, можно ли удалить строку.
 *
 * ID — тип первичного ключа (обычно Long).
 */
public interface CrudRepository<T, ID> {

    /** Вставка новой записи или обновление существующей. */
    T save(T entity);

    Optional<T> findById(ID id);

    List<T> findAll();

    void delete(T entity);

    void deleteById(ID id);

    long count();
}

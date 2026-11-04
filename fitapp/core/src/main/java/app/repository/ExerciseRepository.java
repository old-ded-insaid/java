package app.repository;

import app.model.Exercise;

import java.util.List;
import java.util.Optional;

/** Доступ к таблице exercises (справочник упражнений). */
public interface ExerciseRepository extends CrudRepository<Exercise, Long> {

    Optional<Exercise> findByName(String name);

    /**
     * Поиск по названию и мышцам без учёта регистра.
     * fragment = "присед" найдёт «Приседания» и «Болгарский присед».
     */
    List<Exercise> search(String fragment);

    List<Exercise> findAllOrdered();

    /** Все упражнения вместе с привязанным инвентарём (JOIN FETCH) — для справочника. */
    List<Exercise> findAllWithEquipment();
}

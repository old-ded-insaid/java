package app.repository;

import app.model.Equipment;

import java.util.List;
import java.util.Optional;

/** Доступ к таблице equipment (инвентарь). */
public interface EquipmentRepository extends CrudRepository<Equipment, Long> {

    Optional<Equipment> findByName(String name);

    /**
     * Поиск без учёта регистра: в JSON пишут «гантель», «ГАНТЕЛЬ», «Скамья».
     * Точное совпадение тут не годится — импорт шаблона должен находить предмет.
     */
    Optional<Equipment> findByNameIgnoreCase(String name);

    /** Весь инвентарь по алфавиту — для выпадающих списков. */
    List<Equipment> findAllOrdered();
}

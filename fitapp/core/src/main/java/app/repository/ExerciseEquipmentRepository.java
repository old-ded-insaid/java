package app.repository;

import app.model.ExerciseEquipment;
import app.model.ExerciseEquipmentId;

import java.util.List;

/** Доступ к таблице exercise_equipment (M:N упражнение ↔ инвентарь). */
public interface ExerciseEquipmentRepository extends CrudRepository<ExerciseEquipment, ExerciseEquipmentId> {

    List<ExerciseEquipment> findByExerciseId(long exerciseId);

    List<ExerciseEquipment> findByEquipmentId(long equipmentId);
}

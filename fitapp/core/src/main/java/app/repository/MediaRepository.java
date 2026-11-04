package app.repository;

import app.model.MediaItem;

import java.util.List;

/** Доступ к таблице media (метаданные файлов). */
public interface MediaRepository extends CrudRepository<MediaItem, Long> {

    List<MediaItem> findByWorkoutId(long workoutId);

    List<MediaItem> findByWorkoutExerciseId(long workoutExerciseId);

    List<MediaItem> findByPurpose(String purpose);

    /**
     * Все файлы, привязанные к замерам (media.measurement_id IS NOT NULL).
     *
     * Нужно бэкапу: замер без своей записи media после восстановления покажет
     * «картинка не найдена», даже если файл лежит на месте.
     */
    List<MediaItem> findAllForMeasurements();
}

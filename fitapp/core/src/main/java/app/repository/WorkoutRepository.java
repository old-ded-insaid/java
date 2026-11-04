package app.repository;

import app.model.Workout;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Доступ к таблице workouts (история тренировок). */
public interface WorkoutRepository extends CrudRepository<Workout, Long> {

    /** Все тренировки пользователя, новые сверху. */
    List<Workout> findByUserId(long userId);

    /** Тренировки за период (оба конца включительно). */
    List<Workout> findByUserAndPeriod(long userId, LocalDate from, LocalDate to);

    /** Последняя по дате тренировка — для подсказок на главном экране. */
    Optional<Workout> findLastByUserId(long userId);
}

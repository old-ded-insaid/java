package app.service;

import app.db.Database;
import app.model.WorkoutExercise;
import app.model.WorkoutSet;
import app.repository.Repositories;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Таймер подхода.
 *
 * Состояние хранится в памяти: таймер — это «сейчас идёт подход»,
 * после перезапуска приложения он не нужен.
 *
 * Типичный сценарий в UI:
 * 1. Пользователь нажал «Старт»  → start(workoutExerciseId)
 * 2. Нажал «Стоп»               → stopAndWriteIntoNextEmptyField(id, DURATION_SEC)
 *    время само запишется в ближайший подход, где поле duration_sec пустое
 * 3. Если подходов не хватает, создаётся новый с номером max + 1
 */
public class TimerService {

    /** Куда писать время подхода. */
    public enum TimerField {
        /** Общее время выполнения подхода, секунды. */
        DURATION_SEC,
        /** Метка времени на графике пульса, секунды от старта тренировки. */
        TIME_MARKER_SEC
    }

    private final Database database;

    /** id упражнения тренировки -> момент старта. ConcurrentHashMap: таймеров может быть несколько. */
    private final Map<Long, Instant> runningTimers = new ConcurrentHashMap<>();

    public TimerService(Database database) {
        this.database = database;
    }

    /** Запускает таймер. Повторный вызов перезапускает его с нуля. */
    public Instant start(long workoutExerciseId) {
        Instant now = Instant.now();
        runningTimers.put(workoutExerciseId, now);
        return now;
    }

    public boolean isRunning(long workoutExerciseId) {
        return runningTimers.containsKey(workoutExerciseId);
    }

    /** Сколько прошло с момента старта. Если таймер не запущен — 0. */
    public Duration elapsed(long workoutExerciseId) {
        Instant startedAt = runningTimers.get(workoutExerciseId);
        if (startedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, Instant.now());
    }

    /** «01:23» — готовая строка для экрана. */
    public String elapsedText(long workoutExerciseId) {
        Duration duration = elapsed(workoutExerciseId);
        return String.format("%02d:%02d", duration.toMinutes(), duration.toSecondsPart());
    }

    /** Останавливает таймер и возвращает длительность. */
    public Duration stop(long workoutExerciseId) {
        Instant startedAt = runningTimers.remove(workoutExerciseId);
        if (startedAt == null) {
            throw new ValidationException("Таймер не был запущен для упражнения " + workoutExerciseId);
        }
        return Duration.between(startedAt, Instant.now());
    }

    /** Сбрасывает таймер, ничего не записывая. */
    public void reset(long workoutExerciseId) {
        runningTimers.remove(workoutExerciseId);
    }

    // ------------------------------------------------------------ работа с БД

    /**
     * Ближайший подход, у которого нужное поле ещё пустое.
     * Именно туда попадёт время при остановке таймера.
     */
    public Optional<WorkoutSet> findNextEmptyField(long workoutExerciseId, TimerField field) {
        return database.inTransaction(em -> new Repositories(em).sets()
                .findByWorkoutExerciseIdOrdered(workoutExerciseId)
                .stream()
                .filter(set -> readField(set, field) == null)
                .findFirst());
    }

    /**
     * Останавливает таймер и записывает секунды в ближайший «пустой» подход.
     * Если все подходы уже заполнены — создаёт новый.
     */
    public WorkoutSet stopAndWriteIntoNextEmptyField(long workoutExerciseId, TimerField field) {
        Duration elapsed = stop(workoutExerciseId);

        // 0 секунд не пройдёт проверку duration_sec > 0, поэтому минимум 1
        int seconds = (int) Math.max(1, elapsed.getSeconds());

        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            WorkoutExercise link = repos.workoutExercises().findById(workoutExerciseId)
                    .orElseThrow(() -> new ValidationException(
                            "Упражнение тренировки не найдено: " + workoutExerciseId));

            List<WorkoutSet> sets = repos.sets().findByWorkoutExerciseIdOrdered(workoutExerciseId);

            WorkoutSet target = sets.stream()
                    .filter(set -> readField(set, field) == null)
                    .findFirst()
                    .orElseGet(() -> WorkoutSet.builder()
                            .workoutExercise(link)
                            .setNumber(repos.sets().nextSetNumber(workoutExerciseId))
                            .build());

            writeField(target, field, seconds);
            return repos.sets().save(target);
        });
    }

    private static Integer readField(WorkoutSet set, TimerField field) {
        return field == TimerField.DURATION_SEC ? set.getDurationSec() : set.getTimeMarkerSec();
    }

    private static void writeField(WorkoutSet set, TimerField field, int seconds) {
        if (field == TimerField.DURATION_SEC) {
            set.setDurationSec(seconds);
        } else {
            set.setTimeMarkerSec(seconds);
        }
    }
}

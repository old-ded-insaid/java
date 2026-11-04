package app.repository;

import app.repository.impl.AppErrorLogRepositoryImpl;
import app.repository.impl.AppSettingsRepositoryImpl;
import app.repository.impl.AuditLogRepositoryImpl;
import app.repository.impl.CommentRepositoryImpl;
import app.repository.impl.EquipmentRepositoryImpl;
import app.repository.impl.ExerciseEquipmentRepositoryImpl;
import app.repository.impl.ExerciseRepositoryImpl;
import app.repository.impl.MeasurementRepositoryImpl;
import app.repository.impl.MediaRepositoryImpl;
import app.repository.impl.PlanExerciseRepositoryImpl;
import app.repository.impl.ScheduleItemRepositoryImpl;
import app.repository.impl.ScheduleRepositoryImpl;
import app.repository.impl.UserRepositoryImpl;
import app.repository.impl.WorkoutExerciseRepositoryImpl;
import app.repository.impl.WorkoutPlanRepositoryImpl;
import app.repository.impl.WorkoutRepositoryImpl;
import app.repository.impl.WorkoutSetRepositoryImpl;
import jakarta.persistence.EntityManager;

/**
 * Сборка всех репозиториев под один EntityManager.
 *
 * Зачем: сервис не должен руками создавать WorkoutRepositoryImpl,
 * WorkoutSetRepositoryImpl и так далее. Он пишет:
 *
 * <pre>
 *   Repositories repos = new Repositories(em);
 *   repos.workouts().save(workout);
 *   repos.sets().findByWorkoutExerciseIdOrdered(15);
 * </pre>
 *
 * Объект живёт ровно один Unit of Work (одну транзакцию) — вместе с EntityManager.
 */
public final class Repositories {

    private final UserRepository users;
    private final AppSettingsRepository appSettings;
    private final EquipmentRepository equipment;
    private final ExerciseRepository exercises;
    private final ExerciseEquipmentRepository exerciseEquipment;
    private final WorkoutPlanRepository plans;
    private final PlanExerciseRepository planExercises;
    private final ScheduleRepository schedules;
    private final ScheduleItemRepository scheduleItems;
    private final WorkoutRepository workouts;
    private final WorkoutExerciseRepository workoutExercises;
    private final WorkoutSetRepository sets;
    private final MediaRepository media;
    private final CommentRepository comments;
    private final MeasurementRepository measurements;
    private final AuditLogRepository auditLog;
    private final AppErrorLogRepository errorLog;

    public Repositories(EntityManager em) {
        this.users = new UserRepositoryImpl(em);
        this.appSettings = new AppSettingsRepositoryImpl(em);
        this.equipment = new EquipmentRepositoryImpl(em);
        this.exercises = new ExerciseRepositoryImpl(em);
        this.exerciseEquipment = new ExerciseEquipmentRepositoryImpl(em);
        this.plans = new WorkoutPlanRepositoryImpl(em);
        this.planExercises = new PlanExerciseRepositoryImpl(em);
        this.schedules = new ScheduleRepositoryImpl(em);
        this.scheduleItems = new ScheduleItemRepositoryImpl(em);
        this.workouts = new WorkoutRepositoryImpl(em);
        this.workoutExercises = new WorkoutExerciseRepositoryImpl(em);
        this.sets = new WorkoutSetRepositoryImpl(em);
        this.media = new MediaRepositoryImpl(em);
        this.comments = new CommentRepositoryImpl(em);
        this.measurements = new MeasurementRepositoryImpl(em);
        this.auditLog = new AuditLogRepositoryImpl(em);
        this.errorLog = new AppErrorLogRepositoryImpl(em);
    }

    public UserRepository users() {
        return users;
    }

    public AppSettingsRepository appSettings() {
        return appSettings;
    }

    public EquipmentRepository equipment() {
        return equipment;
    }

    public ExerciseRepository exercises() {
        return exercises;
    }

    public ExerciseEquipmentRepository exerciseEquipment() {
        return exerciseEquipment;
    }

    public WorkoutPlanRepository plans() {
        return plans;
    }

    public PlanExerciseRepository planExercises() {
        return planExercises;
    }

    public ScheduleRepository schedules() {
        return schedules;
    }

    public ScheduleItemRepository scheduleItems() {
        return scheduleItems;
    }

    public WorkoutRepository workouts() {
        return workouts;
    }

    public WorkoutExerciseRepository workoutExercises() {
        return workoutExercises;
    }

    public WorkoutSetRepository sets() {
        return sets;
    }

    public MediaRepository media() {
        return media;
    }

    public CommentRepository comments() {
        return comments;
    }

    public MeasurementRepository measurements() {
        return measurements;
    }

    public AuditLogRepository auditLog() {
        return auditLog;
    }

    public AppErrorLogRepository errorLog() {
        return errorLog;
    }
}

package app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Полный бэкап базы в JSON: справочники, шаблоны, расписания, история и замеры.
 *
 * Зачем плоские DTO, а не сами сущности Hibernate:
 *  - в JSON не попадают ленивые прокси и пароли;
 *  - формат не меняется, когда меняется маппинг;
 *  - импорт читает файл без открытой сессии.
 *
 * Пользователи и настройки (users, app_settings) намеренно НЕ выгружаются:
 * восстановление возвращает данные в текущего пользователя и не трогает вход.
 *
 * Ссылки между сущностями — по id (exerciseId, planId, ...), поэтому импорт
 * может сохранить исходные id и не потерять связи.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FullBackupDto {

    /** Версия формата файла. Импорт понимает только её (или более старые). */
    public static final int CURRENT_VERSION = 1;

    /**
     * Версия формата в конкретном файле.
     *
     * По умолчанию 0, а НЕ CURRENT_VERSION: если поля в файле нет (старый бэкап),
     * импорт обязан это увидеть и отказаться, а не считать файл актуальным.
     * Экспорт всегда проставляет {@link #CURRENT_VERSION} явно.
     *
     * Без @Builder.Default намеренно: у 0 Lombok не генерирует $default$version().
     */
    private int version;

    private LocalDateTime exportedAt;

    /** Чей это был бэкап (справочно: при импорте данные уходят текущему пользователю). */
    private Long userId;

    @Builder.Default
    private List<EquipmentDto> equipment = new ArrayList<>();

    @Builder.Default
    private List<ExerciseDto> exercises = new ArrayList<>();

    @Builder.Default
    private List<ExerciseEquipmentDto> exerciseEquipment = new ArrayList<>();

    @Builder.Default
    private List<PlanDto> plans = new ArrayList<>();

    @Builder.Default
    private List<PlanExerciseDto> planExercises = new ArrayList<>();

    @Builder.Default
    private List<ScheduleDto> schedules = new ArrayList<>();

    @Builder.Default
    private List<ScheduleItemDto> scheduleItems = new ArrayList<>();

    @Builder.Default
    private List<WorkoutDto> workouts = new ArrayList<>();

    @Builder.Default
    private List<WorkoutExerciseDto> workoutExercises = new ArrayList<>();

    @Builder.Default
    private List<SetDto> sets = new ArrayList<>();

    @Builder.Default
    private List<MeasurementDto> measurements = new ArrayList<>();

    /** Файлы-картинки замеров (сами файлы лежат на диске, здесь метаданные). */
    @Builder.Default
    private List<MediaDto> media = new ArrayList<>();

    // ------------------------------------------------------------- инвентарь

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EquipmentDto {
        private Long id;
        private String name;
        /** Вес в граммах: 4600 = «4.6 кг». */
        private Integer weightGrams;
        private String description;
    }

    // ------------------------------------------------------------ упражнения

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExerciseDto {
        private Long id;
        private String name;
        private String setup;
        private String movement;
        private String muscles;
        private String avoid;
        private LocalDateTime createdAt;
    }

    /** Связь «упражнение — инвентарь» с количеством. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExerciseEquipmentDto {
        private Long exerciseId;
        private Long equipmentId;
        private Integer quantity;
    }

    // --------------------------------------------------------------- шаблоны

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlanDto {
        private Long id;
        private String name;
        private String description;
        private Boolean active;
        private LocalDateTime createdAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlanExerciseDto {
        private Long id;
        private Long planId;
        private Long exerciseId;
        private Integer orderIndex;
        private Integer targetSets;
        private Integer targetReps;
        private BigDecimal targetWeightKg;
        private Integer targetTimeSec;
        /** Снимок инвентаря в шаблоне (V16): копируется в тренировку. */
        private String equipmentSnapshot;
    }

    // ------------------------------------------------------------ расписания

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ScheduleDto {
        private Long id;
        private String name;
        private LocalDate startDate;
        private Integer intervalDays;
        private Boolean active;
        private Long startPlanId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ScheduleItemDto {
        private Long id;
        private Long scheduleId;
        private Integer position;
        private Long planId;
    }

    // ---------------------------------------------------------------- история

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WorkoutDto {
        private Long id;
        private Long planId;
        private LocalDate workoutDate;
        private String name;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private Integer durationSec;
        private String note;
        private Integer avgPulse;
        private Integer totalCalories;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private Boolean draft;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WorkoutExerciseDto {
        private Long id;
        private Long workoutId;
        private Long exerciseId;
        private Integer orderIndex;
        private String equipmentSnapshot;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SetDto {
        private Long id;
        private Long workoutExerciseId;
        private Integer setNumber;
        private Integer reps;
        private BigDecimal weightKg;
        private Integer timeMarkerSec;
        private Integer durationSec;
        private Integer pulseBpm;
        private Integer calories;
        private String note;
        private Boolean skipped;
    }

    // ----------------------------------------------------------------- замеры

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MeasurementDto {
        private Long id;
        private LocalDateTime measuredAt;
        private String kind;
        private String name;
        private BigDecimal valueNum;
        private String valueUnit;
        private String note;
        /** Картинка к замеру не выгружается: файл лежит на диске, не в БД. */
        private Long mediaId;
    }

    /**
     * Метаданные картинки замера.
     *
     * Сам файл (media.file_path) хранится на диске в app.media.dir и в JSON
     * не едет — после восстановления на другом компьютере файла может не быть.
     * Запись media нужна, чтобы замер снова показывал картинку там, где файл есть.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MediaDto {
        private Long id;
        /** К какому замеру относится картинка (в нашей выгрузке media только для замеров). */
        private Long measurementId;
        private String purpose;
        private String filePath;
        private LocalDateTime uploadedAt;
    }
}

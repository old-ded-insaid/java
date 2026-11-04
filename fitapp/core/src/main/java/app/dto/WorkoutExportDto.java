package app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Факт завершённой тренировки для экспорта в JSON.
 *
 * Формат намеренно совпадает с {@link PlanImportDto}: тот же набор полей
 * у упражнений (name/setup/movement/muscles/avoid/inventory/targetSets/...).
 * Поэтому выгруженную тренировку можно сразу импортировать в редакторе
 * шаблонов и получить новый шаблон «как было на самом деле».
 *
 * Шапка тренировки (дата, время, пульс, калории) тоже выгружается: без неё
 * импорт терял историческую дату и «факт» притворялся сегодняшней тренировкой.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkoutExportDto {

    /** Название тренировки + « - Факт». */
    private String name;

    /** Заметка к тренировке или автоописание (дата, длительность, число подходов). */
    private String description;

    /** Дата тренировки (UTC-дата из БД). */
    private LocalDate workoutDate;

    /** Начало тренировки (UTC, wall-clock). */
    private LocalDateTime startedAt;

    /** Окончание тренировки (UTC, wall-clock). */
    private LocalDateTime endedAt;

    /** Длительность тренировки, сек. */
    private Integer durationSec;

    /** Средний пульс за тренировку. */
    private Integer avgPulse;

    /** Суммарные калории. */
    private Integer totalCalories;

    @Builder.Default
    private List<ExerciseExportDto> exercises = new ArrayList<>();
}

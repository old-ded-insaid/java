package app.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Одно упражнение в экспорте тренировки.
 *
 * Первые поля (name/setup/movement/muscles/avoid/inventory + target*) — те же,
 * что в {@link ExerciseImportDto}, поэтому файл подходит и для импорта шаблона.
 *
 * target* на уровне упражнения — это «шаблонные» цели по первой группе
 * подходов: удобно, если хочется превратить факт в шаблон. Сами выполненные
 * подходы (с пульсом, временем и заметками) лежат в {@link #sets}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExerciseExportDto {

    private String name;
    private String setup;
    private String movement;
    private String muscles;
    private String avoid;

    /** Инвентарь: снимок тренировки, а если его нет — текущий справочник. */
    private String inventory;

    /** Целевые подходы (по первой группе) — для импорта шаблона. */
    private Integer targetSets;

    /** Целевые повторы (по первой группе) — для импорта шаблона. */
    private Integer targetReps;

    /** Целевой вес (по первой группе) — для импорта шаблона. */
    private BigDecimal targetWeight;

    /** Целевое время подхода, сек — для временных упражнений. */
    private Integer targetTimeSec;

    /** Выполненные подходы (одинаковые сгруппированы). */
    @Builder.Default
    private List<SetExportDto> sets = new ArrayList<>();
}

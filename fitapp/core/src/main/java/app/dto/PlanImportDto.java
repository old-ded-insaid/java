package app.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Шаблон тренировки из JSON-файла.
 *
 * Пример:
 * {
 *   "name": "Тренировка Г (Взрывная сила)",
 *   "description": "Цель: максимальная скорость, реакция.",
 *   "exercises": [ { ... } ]
 * }
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) — лишние поля в JSON не ломают импорт.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanImportDto {

    private String name;
    private String description;

    /** Список упражнений; пустой список = ошибка импорта. */
    private List<ExerciseImportDto> exercises = new ArrayList<>();
}

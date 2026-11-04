package app.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Одно упражнение внутри импортируемого шаблона.
 *
 * Все текстовые поля необязательные: пустые строки и отсутствующие ключи
 * превращаются в NULL (не засоряем БД пустыми значениями).
 * targetSets / targetReps — целевые подходы и повторы (могут быть null).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExerciseImportDto {

    private String name;
    private String setup;
    private String movement;
    private String muscles;
    private String avoid;

    /**
     * Инвентарь через запятую: «Гантель, Скамья».
     *
     * После импорта каждое название ищется в справочнике equipment
     * (без учёта регистра) и привязывается к упражнению — иначе в тренировке
     * колонка «Инвентарь» останется пустой.
     */
    @JsonAlias({"equipment"})
    private String inventory;

    private Integer targetSets;
    private Integer targetReps;

    /**
     * Целевой вес, кг. Принимаем несколько имён ключа, чтобы JSON из разных
     * генераторов подходил без правок: targetWeight / targetWeightKg / weight.
     */
    @JsonAlias({"targetWeightKg", "weight"})
    private BigDecimal targetWeight;
}

package app.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Подход (или группа одинаковых подходов) в экспорте тренировки.
 *
 * Если пользователь сделал 2 подхода по 12 повторений с одним весом, в JSON
 * это ОДИН объект: {@code targetSets = 2, targetReps = 12, targetWeight = 8.0}.
 * Одинаковыми считаются подходы с совпадающими повторами, весом, временем,
 * меткой, пульсом, калориями, заметкой и флагом пропуска — иначе при склейке
 * потерялись бы данные.
 *
 * Имена targetSets/targetReps/targetWeight выбраны не случайно: те же ключи
 * читает импорт шаблонов, поэтому выгруженную тренировку можно сразу
 * импортировать обратно как шаблон.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class SetExportDto {

    /** Номер первого подхода в группе. */
    private Integer setNumber;

    /** Сколько одинаковых подходов объединено (1 — подход уникален). */
    private Integer targetSets;

    /** Повторы. */
    private Integer targetReps;

    /** Вес, кг. */
    private BigDecimal targetWeight;

    /** Время подхода, сек (для временных упражнений). */
    private Integer durationSec;

    /** Метка на графике пульса: сколько секунд прошло от старта тренировки. */
    private Integer timeMarkerSec;

    /** Пульс, уд/мин. */
    private Integer pulseBpm;

    /** Калории, ккал. */
    private Integer calories;

    /** Заметка к подходу. */
    private String note;

    /** Подход пропущен (не выполнялся). */
    private Boolean skipped;
}

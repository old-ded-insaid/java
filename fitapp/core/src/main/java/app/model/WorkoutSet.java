package app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Таблица sets — один подход.
 *
 * ВАЖНО: класс называется WorkoutSet, а не Set, чтобы не конфликтовать
 * с java.util.Set. В БД таблица по-прежнему называется sets.
 *
 * Все поля, кроме setNumber, необязательные: подход может быть «на время»
 * (durationSec) или «на повторы» (reps + weightKg).
 */
@Entity
@Table(name = "sets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class WorkoutSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workout_exercise_id", nullable = false)
    @ToString.Exclude
    private WorkoutExercise workoutExercise;

    /** Номер подхода внутри упражнения: 1, 2, 3... */
    @Column(name = "set_number", nullable = false)
    @ToString.Include
    private int setNumber;

    @Column(name = "reps")
    private Integer reps;

    /** NUMERIC(6,2): 0.50 ... 9999.99 кг. */
    @Column(name = "weight_kg", precision = 6, scale = 2)
    private BigDecimal weightKg;

    /** Метка времени на графике пульса (секунды от старта тренировки). */
    @Column(name = "time_marker_sec")
    private Integer timeMarkerSec;

    /** Сколько длился подход, секунды. */
    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "pulse_bpm")
    private Integer pulseBpm;

    @Column(name = "calories")
    private Integer calories;

    @Column(name = "note")
    private String note;

    /** Подход пропущен пользователем (BUG-07): время не пишем, но подход «закрыт». */
    @Column(name = "skipped", nullable = false)
    @Builder.Default
    private boolean skipped = false;

    /** Копия подхода для сравнения «было/стало» в журнале изменений. */
    public WorkoutSet copyForAudit() {
        return WorkoutSet.builder()
                .id(id)
                .setNumber(setNumber)
                .reps(reps)
                .weightKg(weightKg)
                .timeMarkerSec(timeMarkerSec)
                .durationSec(durationSec)
                .pulseBpm(pulseBpm)
                .calories(calories)
                .note(note)
                .build();
    }
}

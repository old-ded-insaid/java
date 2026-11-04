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
 * Таблица plan_exercises — упражнение внутри шаблона.
 * Целевые значения (подходы/повторы/вес/время) — необязательные: план может быть «на 3 подхода», а может «на время».
 */
@Entity
@Table(name = "plan_exercises")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class PlanExercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    @ToString.Exclude
    private WorkoutPlan plan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercise_id", nullable = false)
    @ToString.Include
    private Exercise exercise;

    /** Порядок в плане: 1, 2, 3... (уникален внутри плана). */
    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "target_sets")
    private Integer targetSets;

    @Column(name = "target_reps")
    private Integer targetReps;

    /** NUMERIC(6,2): максимум 9999.99 кг. */
    @Column(name = "target_weight_kg", precision = 6, scale = 2)
    private BigDecimal targetWeightKg;

    @Column(name = "target_time_sec")
    private Integer targetTimeSec;

    /**
     * Инвентарь упражнения, зафиксированный в шаблоне (V16).
     *
     * Копируется в workout_exercises.equipment_snapshot при создании тренировки,
     * чтобы правка справочника упражнений не переписывала старые шаблоны.
     */
    @Column(name = "equipment_snapshot")
    private String equipmentSnapshot;
}

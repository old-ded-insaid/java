package app.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Таблица exercise_equipment — связь «упражнение ↔ инвентарь» с количеством.
 *
 * Зачем отдельная сущность, а не @ManyToMany:
 * нужно хранить quantity («нужно 3 чёрные резинки»), а у чистой M:N-таблицы
 * кроме двух id ничего нет.
 *
 * @MapsId сам заполняет поля id из объектов exercise/equipment — id вручную не ставим.
 */
@Entity
@Table(name = "exercise_equipment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class ExerciseEquipment {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private ExerciseEquipmentId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("exerciseId")
    @JoinColumn(name = "exercise_id", nullable = false)
    @ToString.Exclude
    private Exercise exercise;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("equipmentId")
    @JoinColumn(name = "equipment_id", nullable = false)
    @ToString.Include
    private Equipment equipment;

    /** Сколько единиц инвентаря нужно: 1 гантель, 3 резинки, 1 скамья. */
    @Column(name = "quantity", nullable = false)
    @Builder.Default
    private int quantity = 1;

    /** Удобная сборка связи без ручного заполнения id. */
    public static ExerciseEquipment link(Exercise exercise, Equipment equipment, int quantity) {
        return ExerciseEquipment.builder()
                .exercise(exercise)
                .equipment(equipment)
                .quantity(quantity)
                .build();
    }
}

package app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Составной первичный ключ таблицы exercise_equipment.
 * JPA требует, чтобы такой класс был Serializable и имел equals/hashCode.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ExerciseEquipmentId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "exercise_id")
    private Long exerciseId;

    @Column(name = "equipment_id")
    private Long equipmentId;
}

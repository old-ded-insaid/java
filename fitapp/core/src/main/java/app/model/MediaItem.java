package app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Таблица media — только метаданные файла.
 * Сам файл (картинка, график) лежит на диске, в БД хранится путь.
 *
 * purpose: pulse_graph | mobility_photo | form_check | other
 * Одна из ссылок (workout или workoutExercise) должна быть заполнена —
 * это проверяет CHECK media_owner_present в БД.
 */
@Entity
@Table(name = "media")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class MediaItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workout_id")
    @ToString.Exclude
    private Workout workout;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workout_exercise_id")
    @ToString.Exclude
    private WorkoutExercise workoutExercise;

    /** Замер, к которому приложена картинка. У media ровно один владелец. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "measurement_id")
    @ToString.Exclude
    private Measurement measurement;

    @Column(name = "purpose", nullable = false)
    @ToString.Include
    private String purpose;

    /** Относительный путь от app.media.dir, например 2026/10/workout-15/pulse.png. */
    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    void fillUploadedAt() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}

package app.service;

import app.dto.SetExportDto;
import app.dto.WorkoutExportDto;
import app.model.Exercise;
import app.model.User;
import app.model.Workout;
import app.model.WorkoutExercise;
import app.model.WorkoutSet;
import app.support.FakePersistence;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportServiceTest {

    private static final long WORKOUT_ID = 500L;

    @TempDir
    Path dir;

    private FakePersistence fake;
    private ExportService service;
    private User user;

    @BeforeEach
    void setUp() {
        fake = new FakePersistence();
        service = new ExportService(FakePersistence.mockDatabase(fake.em()));
        user = User.builder().id(1L).username("owner").build();
    }

    @Test
    @DisplayName("exportWorkoutToJson: два одинаковых подхода склеиваются в targetSets = 2")
    void exportWorkoutToJson_groupsIdenticalSets() throws Exception {
        Workout workout = Workout.builder()
                .id(WORKOUT_ID)
                .user(user)
                .workoutDate(LocalDate.of(2026, 1, 15))
                .name("Тренировка Г")
                .durationSec(120)
                .build();

        WorkoutExercise link = WorkoutExercise.builder()
                .id(200L)
                .workout(workout)
                .exercise(Exercise.builder().id(100L).name("Присед").build())
                .orderIndex(1)
                .build();

        // Два подхода с одинаковыми значимыми полями — должны стать одной группой.
        link.getSets().add(set(link, 1, 10, new BigDecimal("20.00"),
                30, 30, 120, 10, null, false));
        link.getSets().add(set(link, 2, 10, new BigDecimal("20.00"),
                30, 30, 120, 10, null, false));

        fake.register(Workout.class, WORKOUT_ID, workout);
        fake.onQuery(WorkoutExercise.class, "from WorkoutExercise", params -> List.of(link));

        Path file = dir.resolve("workout.json");
        service.exportWorkoutToJson(WORKOUT_ID, file);

        WorkoutExportDto exported = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build()
                .readValue(Files.readString(file), WorkoutExportDto.class);

        // шапка тренировки тоже в файле (B1)
        assertEquals(LocalDate.of(2026, 1, 15), exported.getWorkoutDate());
        assertEquals(120, exported.getDurationSec());

        assertEquals(1, exported.getExercises().size());
        List<SetExportDto> sets = exported.getExercises().get(0).getSets();
        assertEquals(1, sets.size(), "два одинаковых подхода должны склеиться в один объект");
        assertEquals(2, sets.get(0).getTargetSets());
        assertEquals(10, sets.get(0).getTargetReps());
        assertEquals(new BigDecimal("20.00"), sets.get(0).getTargetWeight());
        assertEquals(30, sets.get(0).getTimeMarkerSec());
    }

    private static WorkoutSet set(WorkoutExercise link,
                                  int number,
                                  Integer reps,
                                  BigDecimal weight,
                                  Integer duration,
                                  Integer marker,
                                  Integer pulse,
                                  Integer calories,
                                  String note,
                                  boolean skipped) {
        return WorkoutSet.builder()
                .id((long) number)
                .workoutExercise(link)
                .setNumber(number)
                .reps(reps)
                .weightKg(weight)
                .durationSec(duration)
                .timeMarkerSec(marker)
                .pulseBpm(pulse)
                .calories(calories)
                .note(note)
                .skipped(skipped)
                .build();
    }
}

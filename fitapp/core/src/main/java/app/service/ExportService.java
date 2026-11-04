package app.service;

import app.db.Database;
import app.dto.ExerciseExportDto;
import app.dto.FullBackupDto;
import app.dto.SetExportDto;
import app.dto.WorkoutExportDto;
import app.model.Equipment;
import app.model.Exercise;
import app.model.ExerciseEquipment;
import app.model.Measurement;
import app.model.MediaItem;
import app.model.PlanExercise;
import app.model.Schedule;
import app.model.ScheduleItem;
import app.model.Workout;
import app.model.WorkoutExercise;
import app.model.WorkoutPlan;
import app.model.WorkoutSet;
import app.repository.Repositories;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Экспорт данных наружу: Excel (для себя) и JSON (для будущего сервера).
 *
 * Приватность: файлы создаются только локально, по указанному пути.
 * В JSON/Excel не попадает password_hash — выгружаем лишь тренировки и подходы.
 */
public class ExportService {

    private final Database database;
    private final ObjectMapper mapper;

    public ExportService(Database database) {
        this.database = database;
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    // ------------------------------------------------------------------- Excel

    /**
     * Книга Excel с двумя листами: «Тренировки» и «Подходы».
     *
     * @return тот же путь, куда сохранили (удобно писать в лог/UI)
     */
    public Path exportWorkoutsToExcel(long userId, Path targetFile) {
        List<Map<String, Object>> workouts = loadWorkoutRows(userId);
        List<Map<String, Object>> sets = loadSetRows(userId);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeSheet(workbook, "Тренировки",
                    List.of("Дата", "Название", "План", "Начало", "Конец",
                            "Длительность, сек", "Средний пульс", "Калории", "Заметка"),
                    workouts,
                    List.of("date", "name", "plan", "startedAt", "endedAt",
                            "durationSec", "avgPulse", "totalCalories", "note"));

            writeSheet(workbook, "Подходы",
                    List.of("Дата тренировки", "Тренировка", "Упражнение", "Подход",
                            "Повторы", "Вес, кг", "Время, сек", "Пульс", "Калории", "Заметка"),
                    sets,
                    List.of("workoutDate", "workoutName", "exercise", "setNumber",
                            "reps", "weightKg", "durationSec", "pulseBpm", "calories", "note"));

            createParentDirectories(targetFile);
            try (OutputStream out = Files.newOutputStream(targetFile)) {
                workbook.write(out);
            }
            return targetFile;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить Excel: " + targetFile, e);
        }
    }

    // ---------------------------------------------------- экспорт одной тренировки

    private static final DateTimeFormatter DATE_TEXT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Факт тренировки в JSON (формат {@link WorkoutExportDto}).
     *
     * Одинаковые подходы группируются: «2 подхода по 12 повторений с 8 кг» —
     * один объект с {@code targetSets = 2}. Одинаковыми считаются подходы, у
     * которых совпадают ВСЕ значимые поля (повторы, вес, время, метка, пульс,
     * калории, заметка, пропуск) — иначе склейка потеряла бы данные.
     *
     * @return тот же путь, куда сохранили
     */
    public Path exportWorkoutToJson(long workoutId, Path targetFile) {
        WorkoutExportDto dto = database.inTransaction(em ->
                buildWorkoutExport(new Repositories(em), workoutId));
        return exportToJson(targetFile, dto);
    }

    private static WorkoutExportDto buildWorkoutExport(Repositories repos, long workoutId) {
        Workout workout = repos.workouts().findById(workoutId)
                .orElseThrow(() -> new ValidationException("Тренировка не найдена: " + workoutId));

        List<WorkoutExercise> links = repos.workoutExercises().findByWorkoutIdOrdered(workoutId);

        WorkoutExportDto dto = WorkoutExportDto.builder()
                .name(exportName(workout))
                .description(exportDescription(workout, links))
                // B1: шапка тренировки — иначе импорт «забывает», когда она была
                .workoutDate(workout.getWorkoutDate())
                .startedAt(workout.getStartedAt())
                .endedAt(workout.getEndedAt())
                .durationSec(workout.getDurationSec())
                .avgPulse(workout.getAvgPulse())
                .totalCalories(workout.getTotalCalories())
                .build();

        for (WorkoutExercise link : links) {
            Exercise exercise = link.getExercise();
            List<SetExportDto> sets = groupSets(link);
            SetExportDto first = sets.isEmpty() ? null : sets.get(0);

            dto.getExercises().add(ExerciseExportDto.builder()
                    .name(exercise.getName())
                    .setup(exercise.getSetup())
                    .movement(exercise.getMovement())
                    .muscles(exercise.getMuscles())
                    .avoid(exercise.getAvoid())
                    .inventory(inventoryOf(link))
                    // цели «как в шаблоне»: по первой группе подходов
                    .targetSets(first == null ? null : first.getTargetSets())
                    .targetReps(first == null ? null : first.getTargetReps())
                    .targetWeight(first == null ? null : first.getTargetWeight())
                    .targetTimeSec(first == null ? null : first.getDurationSec())
                    .sets(sets)
                    .build());
        }
        return dto;
    }

    /** «Тренировка Г - Факт»: по названию сразу видно, что это выгрузка истории. */
    private static String exportName(Workout workout) {
        String name = workout.getName() == null || workout.getName().isBlank()
                ? "Тренировка"
                : workout.getName().trim();
        return name + " - Факт";
    }

    /**
     * Заметка тренировки, а если её нет — короткая сводка.
     *
     * Сводка нужна, чтобы файл читался человеком без базы: когда, сколько
     * упражнений и подходов, сколько длилось.
     */
    private static String exportDescription(Workout workout, List<WorkoutExercise> links) {
        if (workout.getNote() != null && !workout.getNote().isBlank()) {
            return workout.getNote().trim();
        }

        int sets = links.stream().mapToInt(link -> link.getSets().size()).sum();
        StringBuilder text = new StringBuilder("Дата: ").append(DATE_TEXT.format(workout.getWorkoutDate()))
                .append(". Упражнений: ").append(links.size())
                .append(", подходов: ").append(sets)
                .append(". Длительность: ").append(durationText(workout.getDurationSec()));

        if (workout.getAvgPulse() != null) {
            text.append(". Средний пульс: ").append(workout.getAvgPulse()).append(" уд/мин");
        }
        return text.toString();
    }

    /**
     * Инвентарь упражнения в тренировке.
     *
     * Приоритет — снимок (он хранит то, что реально было на тренировке);
     * если снимка нет (старые записи) — текущий справочник упражнения.
     */
    private static String inventoryOf(WorkoutExercise link) {
        String snapshot = link.getEquipmentSnapshot();
        if (snapshot != null && !snapshot.isBlank()) {
            return snapshot;
        }

        Exercise exercise = link.getExercise();
        if (exercise.getEquipmentLinks() == null || exercise.getEquipmentLinks().isEmpty()) {
            return null;
        }
        return exercise.getEquipmentLinks().stream()
                .map(item -> item.getQuantity() > 1
                        ? item.getEquipment().getName() + " (" + item.getQuantity() + " шт)"
                        : item.getEquipment().getName())
                .collect(Collectors.joining(", "));
    }

    /** Подходы упражнения, одинаковые склеены в один объект. */
    private static List<SetExportDto> groupSets(WorkoutExercise link) {
        List<WorkoutSet> sets = new ArrayList<>(link.getSets());
        sets.sort(Comparator.comparingInt(WorkoutSet::getSetNumber));

        List<SetExportDto> result = new ArrayList<>();
        SetExportDto group = null;

        for (WorkoutSet set : sets) {
            if (group != null && sameValues(group, set)) {
                group.setTargetSets(group.getTargetSets() + 1);
                continue;
            }

            group = SetExportDto.builder()
                    .setNumber(set.getSetNumber())
                    .targetSets(1)
                    .targetReps(set.getReps())
                    .targetWeight(set.getWeightKg())
                    .durationSec(set.getDurationSec())
                    .timeMarkerSec(set.getTimeMarkerSec())
                    .pulseBpm(set.getPulseBpm())
                    .calories(set.getCalories())
                    .note(set.getNote())
                    .skipped(set.isSkipped())
                    .build();
            result.add(group);
        }
        return result;
    }

    /** Совпадают ли все значимые поля: только тогда подходы можно склеить. */
    private static boolean sameValues(SetExportDto group, WorkoutSet set) {
        return Objects.equals(group.getTargetReps(), set.getReps())
                && sameDecimal(group.getTargetWeight(), set.getWeightKg())
                && Objects.equals(group.getDurationSec(), set.getDurationSec())
                && Objects.equals(group.getTimeMarkerSec(), set.getTimeMarkerSec())
                && Objects.equals(group.getPulseBpm(), set.getPulseBpm())
                && Objects.equals(group.getCalories(), set.getCalories())
                && Objects.equals(group.getNote(), set.getNote())
                && Boolean.valueOf(group.getSkipped()) == set.isSkipped();
    }

    /** 8.00 и 8.0 — один и тот же вес (compareTo, а не equals). */
    private static boolean sameDecimal(BigDecimal first, BigDecimal second) {
        if (first == null || second == null) {
            return first == null && second == null;
        }
        return first.compareTo(second) == 0;
    }

    private static String durationText(Integer seconds) {
        if (seconds == null || seconds <= 0) {
            return "—";
        }
        return String.format("%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    // -------------------------------------------------------------------- JSON

    /**
     * ПОЛНЫЙ бэкап базы в JSON (формат {@link FullBackupDto}, version 1).
     *
     * Раньше здесь были только плоские строки тренировок — такой файл нельзя
     * было восстановить. Теперь выгружаются все данные, которые пользователь
     * создаёт сам: справочник упражнений, инвентарь, шаблоны, расписания,
     * история тренировок с подходами и замеры.
     *
     * Пользователи и настройки не выгружаются: пароль в файл не попадает,
     * а тема/звук — это оформление конкретного компьютера.
     */
    public Path exportWorkoutsToJson(long userId, Path targetFile) {
        return exportToJson(targetFile, buildFullBackup(userId));
    }

    /** Снимок всех данных пользователя (без записи на диск) — удобно тестировать. */
    public FullBackupDto buildFullBackup(long userId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);

            FullBackupDto backup = FullBackupDto.builder()
                    .version(FullBackupDto.CURRENT_VERSION)
                    .exportedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .userId(userId)
                    .build();

            // 1. Инвентарь
            for (Equipment equipment : repos.equipment().findAllOrdered()) {
                backup.getEquipment().add(FullBackupDto.EquipmentDto.builder()
                        .id(equipment.getId())
                        .name(equipment.getName())
                        .weightGrams(equipment.getWeightGrams())
                        .description(equipment.getDescription())
                        .build());
            }

            // 2. Упражнения и их инвентарь (findAllWithEquipment — связи уже подгружены)
            for (Exercise exercise : repos.exercises().findAllWithEquipment()) {
                backup.getExercises().add(FullBackupDto.ExerciseDto.builder()
                        .id(exercise.getId())
                        .name(exercise.getName())
                        .setup(exercise.getSetup())
                        .movement(exercise.getMovement())
                        .muscles(exercise.getMuscles())
                        .avoid(exercise.getAvoid())
                        .createdAt(exercise.getCreatedAt())
                        .build());

                for (ExerciseEquipment link : exercise.getEquipmentLinks()) {
                    backup.getExerciseEquipment().add(FullBackupDto.ExerciseEquipmentDto.builder()
                            .exerciseId(exercise.getId())
                            .equipmentId(link.getEquipment().getId())
                            .quantity(link.getQuantity())
                            .build());
                }
            }

            // 3. Шаблоны и их состав
            for (WorkoutPlan plan : repos.plans().findByUserId(userId)) {
                backup.getPlans().add(FullBackupDto.PlanDto.builder()
                        .id(plan.getId())
                        .name(plan.getName())
                        .description(plan.getDescription())
                        .active(plan.isActive())
                        .createdAt(plan.getCreatedAt())
                        .build());

                for (PlanExercise item : repos.planExercises().findByPlanIdOrdered(plan.getId())) {
                    backup.getPlanExercises().add(FullBackupDto.PlanExerciseDto.builder()
                            .id(item.getId())
                            .planId(plan.getId())
                            .exerciseId(item.getExercise().getId())
                            .orderIndex(item.getOrderIndex())
                            .targetSets(item.getTargetSets())
                            .targetReps(item.getTargetReps())
                            .targetWeightKg(item.getTargetWeightKg())
                            .targetTimeSec(item.getTargetTimeSec())
                            .equipmentSnapshot(item.getEquipmentSnapshot())
                            .build());
                }
            }

            // 4. Расписания и их очередь планов
            for (Schedule schedule : repos.schedules().findByUserId(userId)) {
                backup.getSchedules().add(FullBackupDto.ScheduleDto.builder()
                        .id(schedule.getId())
                        .name(schedule.getName())
                        .startDate(schedule.getStartDate())
                        .intervalDays(schedule.getIntervalDays())
                        .active(schedule.isActive())
                        .startPlanId(schedule.getStartPlan() == null
                                ? null : schedule.getStartPlan().getId())
                        .build());

                for (ScheduleItem item : repos.scheduleItems().findByScheduleIdOrdered(schedule.getId())) {
                    backup.getScheduleItems().add(FullBackupDto.ScheduleItemDto.builder()
                            .id(item.getId())
                            .scheduleId(schedule.getId())
                            .position(item.getPosition())
                            .planId(item.getPlan().getId())
                            .build());
                }
            }

            // 5. История: тренировки -> упражнения -> подходы
            for (Workout workout : repos.workouts().findByUserId(userId)) {
                backup.getWorkouts().add(FullBackupDto.WorkoutDto.builder()
                        .id(workout.getId())
                        .planId(workout.getPlan() == null ? null : workout.getPlan().getId())
                        .workoutDate(workout.getWorkoutDate())
                        .name(workout.getName())
                        .startedAt(workout.getStartedAt())
                        .endedAt(workout.getEndedAt())
                        .durationSec(workout.getDurationSec())
                        .note(workout.getNote())
                        .avgPulse(workout.getAvgPulse())
                        .totalCalories(workout.getTotalCalories())
                        .createdAt(workout.getCreatedAt())
                        .updatedAt(workout.getUpdatedAt())
                        .draft(workout.isDraft())
                        .build());

                for (WorkoutExercise link : repos.workoutExercises().findByWorkoutIdOrdered(workout.getId())) {
                    backup.getWorkoutExercises().add(FullBackupDto.WorkoutExerciseDto.builder()
                            .id(link.getId())
                            .workoutId(workout.getId())
                            .exerciseId(link.getExercise().getId())
                            .orderIndex(link.getOrderIndex())
                            .equipmentSnapshot(link.getEquipmentSnapshot())
                            .build());

                    for (WorkoutSet set : link.getSets()) {
                        backup.getSets().add(FullBackupDto.SetDto.builder()
                                .id(set.getId())
                                .workoutExerciseId(link.getId())
                                .setNumber(set.getSetNumber())
                                .reps(set.getReps())
                                .weightKg(set.getWeightKg())
                                .timeMarkerSec(set.getTimeMarkerSec())
                                .durationSec(set.getDurationSec())
                                .pulseBpm(set.getPulseBpm())
                                .calories(set.getCalories())
                                .note(set.getNote())
                                .skipped(set.isSkipped())
                                .build());
                    }
                }
            }

            // 6. Замеры (картинка остаётся файлом на диске, в JSON только ссылка)
            for (Measurement measurement : repos.measurements().findByUserIdWithMedia(userId)) {
                backup.getMeasurements().add(FullBackupDto.MeasurementDto.builder()
                        .id(measurement.getId())
                        .measuredAt(measurement.getMeasuredAt())
                        .kind(measurement.getKind())
                        .name(measurement.getName())
                        .valueNum(measurement.getValueNum())
                        .valueUnit(measurement.getValueUnit())
                        .note(measurement.getNote())
                        .mediaId(measurement.getMedia() == null ? null : measurement.getMedia().getId())
                        .build());
            }

            // 7. B3: записи media для замеров. Без них восстановленный замер
            //    потеряет картинку, даже если файл лежит в app.media.dir.
            for (MediaItem media : repos.media().findAllForMeasurements()) {
                backup.getMedia().add(FullBackupDto.MediaDto.builder()
                        .id(media.getId())
                        .measurementId(media.getMeasurement() == null
                                ? null : media.getMeasurement().getId())
                        .purpose(media.getPurpose())
                        .filePath(media.getFilePath())
                        .uploadedAt(media.getUploadedAt())
                        .build());
            }

            return backup;
        });
    }

    /** Любые данные (в том числе измерения) в JSON. */
    public Path exportToJson(Path targetFile, Object data) {
        createParentDirectories(targetFile);
        try (OutputStream out = Files.newOutputStream(targetFile)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(out, data);
            return targetFile;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить JSON: " + targetFile, e);
        }
    }

    // ----------------------------------------------------------- сбор данных

    /** Плоские строки для Excel/JSON: никаких ленивых прокси, только значения. */
    private List<Map<String, Object>> loadWorkoutRows(long userId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            List<Map<String, Object>> rows = new ArrayList<>();

            for (Workout workout : repos.workouts().findByUserId(userId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", workout.getId());
                row.put("date", workout.getWorkoutDate());
                row.put("name", workout.getName());
                row.put("plan", workout.getPlan() == null ? null : workout.getPlan().getName());
                row.put("startedAt", workout.getStartedAt());
                row.put("endedAt", workout.getEndedAt());
                row.put("durationSec", workout.getDurationSec());
                row.put("avgPulse", workout.getAvgPulse());
                row.put("totalCalories", workout.getTotalCalories());
                row.put("note", workout.getNote());
                rows.add(row);
            }
            return rows;
        });
    }

    private List<Map<String, Object>> loadSetRows(long userId) {
        return database.inTransaction(em -> {
            Repositories repos = new Repositories(em);
            List<Map<String, Object>> rows = new ArrayList<>();

            for (Workout workout : repos.workouts().findByUserId(userId)) {
                for (WorkoutExercise link : repos.workoutExercises().findByWorkoutIdOrdered(workout.getId())) {
                    for (WorkoutSet set : link.getSets()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("workoutDate", workout.getWorkoutDate());
                        row.put("workoutName", workout.getName());
                        row.put("exercise", link.getExercise().getName());
                        row.put("setNumber", set.getSetNumber());
                        row.put("reps", set.getReps());
                        row.put("weightKg", set.getWeightKg());
                        row.put("durationSec", set.getDurationSec());
                        row.put("pulseBpm", set.getPulseBpm());
                        row.put("calories", set.getCalories());
                        row.put("note", set.getNote());
                        rows.add(row);
                    }
                }
            }
            return rows;
        });
    }

    // --------------------------------------------------------------- рисование

    private static void writeSheet(XSSFWorkbook workbook,
                                   String sheetName,
                                   List<String> headers,
                                   List<Map<String, Object>> rows,
                                   List<String> keys) {

        Sheet sheet = workbook.createSheet(sheetName);

        CellStyle headerStyle = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        headerStyle.setFont(bold);

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
        }

        int rowIndex = 1;
        for (Map<String, Object> row : rows) {
            Row excelRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < keys.size(); i++) {
                setCellValue(excelRow.createCell(i), row.get(keys.get(i)));
            }
        }

        for (int i = 0; i < headers.size(); i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private static void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            return;
        }
        cell.setCellValue(String.valueOf(value));
    }

    private static void createParentDirectories(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать папку для " + file, e);
        }
    }
}

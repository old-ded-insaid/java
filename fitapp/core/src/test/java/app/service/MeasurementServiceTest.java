package app.service;

import app.db.Database;
import app.model.Measurement;
import app.model.MediaItem;
import app.model.User;
import app.support.FakePersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MeasurementServiceTest {

    private static final long USER_ID = 1L;

    private FakePersistence fake;
    private Database database;
    private MeasurementService service;
    private User owner;

    @BeforeEach
    void setUp() {
        fake = new FakePersistence();
        database = FakePersistence.mockDatabase(fake.em());
        service = new MeasurementService(database);

        owner = User.builder().id(USER_ID).username("owner").build();
        fake.register(User.class, USER_ID, owner);
    }

    @Test
    @DisplayName("add: сохраняет замер и обрезает текстовые поля")
    void add_withoutImage_savesMeasurement() {
        Measurement measurement = service.add(
                USER_ID,
                MeasurementService.KIND_BODY_WEIGHT,
                "  вес утром  ",
                new BigDecimal("72.50"),
                "  кг  ",
                "  утро  ");

        assertNotNull(measurement.getId());
        assertSame(owner, measurement.getUser());
        assertEquals(MeasurementService.KIND_BODY_WEIGHT, measurement.getKind());
        assertEquals("вес утром", measurement.getName());
        assertEquals(new BigDecimal("72.50"), measurement.getValueNum());
        assertEquals("кг", measurement.getValueUnit());
        assertEquals("утро", measurement.getNote());
        assertNotNull(measurement.getMeasuredAt());
        assertNull(measurement.getMedia());
        assertTrue(fake.mergedOf(MediaItem.class).isEmpty());
    }

    @Test
    @DisplayName("add: пустая единица и заметка превращаются в NULL")
    void add_blankText_becomesNull() {
        Measurement measurement = service.add(
                USER_ID,
                MeasurementService.KIND_OTHER,
                "давление",
                null,
                "   ",
                "   ");

        assertNull(measurement.getValueUnit());
        assertNull(measurement.getNote());
        assertNull(measurement.getValueNum());
    }

    @Test
    @DisplayName("add: картинка создаёт media с правильным purpose и обратной ссылкой")
    void add_withImage_createsMedia() {
        Measurement measurement = service.add(
                USER_ID,
                MeasurementService.KIND_MOBILITY,
                "наклон вперёд",
                new BigDecimal("12.5"),
                "см",
                null,
                "  measurements/2026-11/abc.png  ");

        MediaItem media = measurement.getMedia();
        assertNotNull(media);
        assertNotNull(media.getId());
        assertEquals("measurements/2026-11/abc.png", media.getFilePath());
        assertEquals("mobility_photo", media.getPurpose());
        assertSame(measurement, media.getMeasurement());
        assertEquals(1, fake.mergedOf(MediaItem.class).size());
    }

    @Test
    @DisplayName("add: purpose зависит от типа замера")
    void add_purposeDependsOnKind() {
        assertEquals("pulse_graph",
                addWithImage("pulse_rest").getMedia().getPurpose());
        assertEquals("other",
                addWithImage("body_weight").getMedia().getPurpose());
        assertEquals("other",
                addWithImage("other").getMedia().getPurpose());
    }

    @Test
    @DisplayName("add: неизвестный тип — ValidationException без транзакции")
    void add_unknownKind_throwsBeforeDatabase() {
        assertThrows(ValidationException.class,
                () -> service.add(USER_ID, "weight", "вес", BigDecimal.TEN, "кг", null));

        verifyNoInteractions(database);
    }

    @Test
    @DisplayName("add: пустое название — ValidationException без транзакции")
    void add_blankName_throwsBeforeDatabase() {
        assertThrows(ValidationException.class,
                () -> service.add(USER_ID, MeasurementService.KIND_OTHER, "   ", null, null, null));

        verifyNoInteractions(database);
    }

    @Test
    @DisplayName("add: неизвестный пользователь — ValidationException")
    void add_unknownUser_throws() {
        assertThrows(ValidationException.class,
                () -> service.add(999L, MeasurementService.KIND_OTHER, "замер", null, null, null));

        assertTrue(fake.mergedOf(Measurement.class).isEmpty());
    }

    @Test
    @DisplayName("delete: удаляет строку замера")
    void delete_existing_removesMeasurement() {
        Measurement measurement = Measurement.builder()
                .id(500L)
                .user(owner)
                .measuredAt(LocalDateTime.now())
                .kind(MeasurementService.KIND_OTHER)
                .name("замер")
                .build();
        fake.register(Measurement.class, 500L, measurement);

        service.delete(500L);

        assertTrue(fake.wasRemoved(measurement));
        verify(fake.em()).remove(measurement);
    }

    @Test
    @DisplayName("delete: несуществующий замер не бросает исключение")
    void delete_missing_doesNothing() {
        service.delete(999L);

        assertTrue(fake.removed().isEmpty());
    }

    /**
     * Баг: valueNum не валидируется. Для body_weight отрицательное значение
     * бессмысленно; для NUMERIC(8,2) значение 1 000 000 уже даёт SQL-ошибку
     * numeric field overflow. В БД нет CHECK на measurements.value_num.
     */
    @Test
    @DisplayName("add: отрицательный вес тела должен быть отклонён")
    void add_negativeBodyWeight_throws() {
        assertThrows(ValidationException.class, () -> service.add(
                USER_ID,
                MeasurementService.KIND_BODY_WEIGHT,
                "вес",
                new BigDecimal("-5"),
                "кг",
                null));
    }

    private Measurement addWithImage(String kind) {
        return service.add(
                USER_ID,
                kind,
                "замер",
                new BigDecimal("10"),
                "ед",
                null,
                "measurements/2026-11/" + kind + ".png");
    }

    // ------------------------------------------------- данные для графика

    @Test
    @DisplayName("график: неизвестный тип замера отклоняется")
    void getMeasurementsForChart_unknownKind_isRejected() {
        assertThrows(ValidationException.class, () -> service.getMeasurementsForChart(
                USER_ID, "unknown", LocalDate.now().minusDays(1), LocalDate.now()));
        assertThrows(ValidationException.class, () -> service.getMeasurementsForChart(
                USER_ID, null, LocalDate.now().minusDays(1), LocalDate.now()));
    }

    @Test
    @DisplayName("график: пустые даты — период «месяц назад .. сегодня», конец дня включительно")
    void getMeasurementsForChart_nullDates_useLastMonth() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        fake.onQuery(Measurement.class, "m.kind = :kind", params -> {
            captured.set(Map.copyOf(params));
            return List.of();
        });

        service.getMeasurementsForChart(USER_ID, MeasurementService.KIND_BODY_WEIGHT, null, null);

        LocalDateTime from = (LocalDateTime) captured.get().get("from");
        LocalDateTime to = (LocalDateTime) captured.get().get("to");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertEquals(today.minusMonths(1).atStartOfDay(), from);
        assertEquals(today, to.toLocalDate());
        assertEquals(LocalTime.MAX, to.toLocalTime(), "последний день периода входит целиком");
    }

    @Test
    @DisplayName("график: возвращает только замеры внутри выбранного периода")
    void getMeasurementsForChart_onlyDataWithinPeriod() {
        Measurement before = measurement(1L, LocalDateTime.of(2025, 12, 31, 23, 59));
        Measurement firstDay = measurement(2L, LocalDateTime.of(2026, 1, 1, 0, 0));
        Measurement middle = measurement(3L, LocalDateTime.of(2026, 1, 15, 12, 0));
        Measurement after = measurement(4L, LocalDateTime.of(2026, 2, 1, 0, 0));

        fake.onQuery(Measurement.class, "m.kind = :kind", params -> {
            LocalDateTime from = (LocalDateTime) params.get("from");
            LocalDateTime to = (LocalDateTime) params.get("to");
            return List.of(before, firstDay, middle, after).stream()
                    .filter(item -> !item.getMeasuredAt().isBefore(from)
                            && !item.getMeasuredAt().isAfter(to))
                    .toList();
        });

        List<Measurement> chart = service.getMeasurementsForChart(
                USER_ID, MeasurementService.KIND_BODY_WEIGHT,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertEquals(2, chart.size(), "замеры до и после периода не должны попасть");
        assertEquals(List.of(2L, 3L), chart.stream().map(Measurement::getId).toList());
    }

    @Test
    @DisplayName("график: from > to — даты меняются местами, исключения нет")
    void getMeasurementsForChart_reversedDates_areSwapped() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        fake.onQuery(Measurement.class, "m.kind = :kind", params -> {
            captured.set(Map.copyOf(params));
            return List.of();
        });

        service.getMeasurementsForChart(
                USER_ID, MeasurementService.KIND_BODY_WEIGHT,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1));

        LocalDateTime from = (LocalDateTime) captured.get().get("from");
        LocalDateTime to = (LocalDateTime) captured.get().get("to");

        assertEquals(LocalDate.of(2026, 1, 1), from.toLocalDate());
        assertEquals(LocalDate.of(2026, 2, 1), to.toLocalDate());
        assertEquals(LocalTime.MAX, to.toLocalTime(), "конец периода включительно");
    }

    @Test
    @DisplayName("график: замеры без числа (только фото) в линию не попадают")
    void getMeasurementsForChart_skipsMeasurementsWithoutValue() {
        Measurement withValue = Measurement.builder()
                .id(1L).user(owner).kind(MeasurementService.KIND_BODY_WEIGHT)
                .name("вес").valueNum(new BigDecimal("72.5"))
                .measuredAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(2))
                .build();
        Measurement photoOnly = Measurement.builder()
                .id(2L).user(owner).kind(MeasurementService.KIND_BODY_WEIGHT)
                .name("фото").valueNum(null)
                .measuredAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1))
                .build();

        fake.onQuery(Measurement.class, "m.kind = :kind",
                params -> List.of(withValue, photoOnly));

        List<Measurement> chart = service.getMeasurementsForChart(
                USER_ID, MeasurementService.KIND_BODY_WEIGHT,
                LocalDate.now().minusMonths(1), LocalDate.now());

        assertEquals(1, chart.size());
        assertSame(withValue, chart.get(0));
    }

    private Measurement measurement(long id, LocalDateTime measuredAt) {
        return Measurement.builder()
                .id(id)
                .user(owner)
                .kind(MeasurementService.KIND_BODY_WEIGHT)
                .name("вес")
                .valueNum(new BigDecimal("72.5"))
                .measuredAt(measuredAt)
                .build();
    }
}

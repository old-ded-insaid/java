package app.service;

import app.db.Database;
import app.support.FakePersistence;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Проверки «защиты от опасного импорта».
 *
 * Главный сценарий: файл старого формата (без version) НЕ должен ничего
 * удалять в базе. Ошибка здесь стоит всех данных пользователя, поэтому
 * проверяем, что база вообще не открывается.
 */
class ImportServiceTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("импорт: файл без version отклонён до обращения к базе")
    void legacyBackupWithoutVersion_isRejectedBeforeDatabase() throws IOException {
        Database database = mock(Database.class);
        ExportService exportService = mock(ExportService.class);
        ImportService service = new ImportService(database, exportService);

        Path legacy = dir.resolve("legacy.json");
        Files.writeString(legacy, "{\"user_id\":1,\"workouts\":[],\"sets\":[]}");

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.importFullBackup(legacy));

        assertTrue(error.getMessage().contains("version"),
                "сообщение должно объяснять, чего не хватает: " + error.getMessage());
        verifyNoInteractions(database);
    }

    @Test
    @DisplayName("импорт: файл более новой версии отклонён")
    void newerVersion_isRejected() throws IOException {
        Database database = mock(Database.class);
        ImportService service = new ImportService(database, mock(ExportService.class));

        Path future = dir.resolve("future.json");
        Files.writeString(future, "{\"version\": 99, \"workouts\": []}");

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.importFullBackup(future));

        assertTrue(error.getMessage().contains("99"), error.getMessage());
        verifyNoInteractions(database);
    }

    @Test
    @DisplayName("импорт: отсутствующий файл — понятная ошибка, база не тронута")
    void missingFile_isReported() {
        Database database = mock(Database.class);
        ImportService service = new ImportService(database, mock(ExportService.class));

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.importFullBackup(dir.resolve("no-such.json")));

        assertTrue(error.getMessage().contains("не найден"), error.getMessage());
        verifyNoInteractions(database);
    }

    /**
     * Успешный импорт проверяем на уровне JDBC: ImportService вставляет строки
     * с явными id (иначе связи plan_id/exercise_id поедут). Захватываем
     * PreparedStatement.setObject(...) и проверяем, что в SQL ушли именно
     * id из JSON, а не сгенерированные Hibernate.
     */
    @Test
    @DisplayName("импорт: id шаблона, упражнения и связи сохраняются из JSON")
    void importFullBackup_preservesIdsFromJson() throws Exception {
        FakePersistence fake = new FakePersistence();
        Database database = FakePersistence.mockDatabase(fake.em());
        long ownerId = 123L;

        ExportService exportService = mock(ExportService.class);
        JdbcCapture jdbc = captureJdbc(fake, exportService, ownerId);

        Path backup = dir.resolve("full-backup.json");
        Files.writeString(backup, """
                {
                  "version": 1,
                  "equipment": [
                    { "id": 501, "name": "Резинка красная 9.1 кг", "weightGrams": 9100 }
                  ],
                  "exercises": [
                    { "id": 101, "name": "Присед" }
                  ],
                  "exerciseEquipment": [
                    { "exerciseId": 101, "equipmentId": 501, "quantity": 2 }
                  ],
                  "plans": [
                    { "id": 10, "name": "Тренировка А", "active": true }
                  ]
                }
                """);

        ImportService service = new ImportService(database, exportService);
        ImportService.ImportResult result = service.importFullBackup(backup, ownerId);

        assertEquals(1, result.equipment());
        assertEquals(1, result.exercises());
        assertEquals(1, result.plans());
        assertEquals(0, result.sets());
        assertNotNull(result.safetyCopy());
        verify(exportService).exportWorkoutsToJson(eq(ownerId), any(Path.class));

        Insert equipment = insert(jdbc.inserts(), "INSERT INTO equipment");
        assertEquals(501L, equipment.params().get(0));
        assertEquals(9100, equipment.params().get(2));

        Insert exercise = insert(jdbc.inserts(), "INSERT INTO exercises");
        assertEquals(101L, exercise.params().get(0));

        Insert plan = insert(jdbc.inserts(), "INSERT INTO workout_plans");
        assertEquals(10L, plan.params().get(0));
        assertEquals(ownerId, plan.params().get(1));

        Insert link = insert(jdbc.inserts(), "INSERT INTO exercise_equipment");
        assertEquals(101L, link.params().get(0), "exercise_id из JSON");
        assertEquals(501L, link.params().get(1), "equipment_id из JSON");
        assertEquals(Integer.valueOf(2), link.params().get(2), "quantity из JSON");
    }

    @Test
    @DisplayName("импорт: media и measurements.media_id восстанавливаются")
    void importFullBackup_restoresMediaLinks() throws Exception {
        FakePersistence fake = new FakePersistence();
        Database database = FakePersistence.mockDatabase(fake.em());
        long ownerId = 123L;

        ExportService exportService = mock(ExportService.class);
        JdbcCapture jdbc = captureJdbc(fake, exportService, ownerId);

        Path backup = dir.resolve("with-media.json");
        Files.writeString(backup, """
                {
                  "version": 1,
                  "measurements": [
                    { "id": 900, "measuredAt": "2026-01-01T10:00:00",
                      "kind": "other", "name": "Замер" }
                  ],
                  "media": [
                    { "id": 800, "measurementId": 900, "purpose": "other",
                      "filePath": "measurements/a.png" }
                  ]
                }
                """);

        ImportService service = new ImportService(database, exportService);
        ImportService.ImportResult result = service.importFullBackup(backup, ownerId);

        assertEquals(1, result.measurements());

        Insert media = insert(jdbc.inserts(), "INSERT INTO media");
        assertEquals(800L, media.params().get(0));
        assertEquals(900L, media.params().get(1));
        assertEquals("other", media.params().get(2));
        assertEquals("measurements/a.png", media.params().get(3));

        Insert update = insert(jdbc.inserts(), "UPDATE measurements");
        assertEquals(800L, update.params().get(0), "media_id");
        assertEquals(900L, update.params().get(1), "measurement id");
    }

    /**
     * Проверяем именно транзакцию Database: ошибка вставки должна привести
     * к rollback, а не к commit. SQLException от JDBC Hibernate обычно
     * заворачивает в HibernateException, поэтому имитируем это поведение.
     */
    @Test
    @DisplayName("импорт: ошибка вставки откатывает транзакцию")
    void importFullBackup_databaseError_rollsBack() throws Exception {
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        EntityManager em = mock(EntityManager.class);
        EntityTransaction tx = mock(EntityTransaction.class);

        when(emf.createEntityManager()).thenReturn(em);
        when(em.getTransaction()).thenReturn(tx);
        when(tx.isActive()).thenReturn(true);
        Database database = new Database(emf);

        ExportService exportService = mock(ExportService.class);
        when(exportService.exportWorkoutsToJson(anyLong(), any(Path.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        Session session = mock(Session.class);
        when(em.unwrap(Session.class)).thenReturn(session);

        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.prepareStatement(anyString()))
                .thenThrow(new SQLException("insert or update violates foreign key constraint"));

        doAnswer(invocation -> {
            ReturningWork<?> work = invocation.getArgument(0);
            try {
                return work.execute(connection);
            } catch (SQLException e) {
                throw new HibernateException("could not execute statement", e);
            }
        }).when(session).doReturningWork(any());

        Path backup = dir.resolve("broken-id.json");
        Files.writeString(backup, """
                {
                  "version": 1,
                  "equipment": [ { "id": 501, "name": "Резинка" } ],
                  "plans": [ { "id": 10, "name": "План" } ]
                }
                """);

        ImportService service = new ImportService(database, exportService);
        ValidationException error = assertThrows(ValidationException.class,
                () -> service.importFullBackup(backup, 123L));

        assertTrue(error.getMessage().contains("Изменения отменены"), error.getMessage());
        verify(tx).begin();
        verify(tx).rollback();
        verify(tx, never()).commit();
        verify(em).close();
    }

    private static JdbcCapture captureJdbc(FakePersistence fake,
                                           ExportService exportService,
                                           long ownerId) throws Exception {
        when(exportService.exportWorkoutsToJson(eq(ownerId), any(Path.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        Session session = mock(Session.class);
        when(fake.em().unwrap(Session.class)).thenReturn(session);

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeUpdate(anyString())).thenReturn(1);
        when(statement.execute(anyString())).thenReturn(true);

        List<Insert> inserts = new ArrayList<>();
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            PreparedStatement ps = mock(PreparedStatement.class);
            List<Object> params = new ArrayList<>();

            doAnswer(call -> {
                params.add(call.getArgument(1));
                return null;
            }).when(ps).setObject(anyInt(), any());
            doAnswer(call -> {
                params.add(call.getArgument(1));
                return null;
            }).when(ps).setLong(anyInt(), anyLong());
            doAnswer(call -> {
                params.add(null);
                return null;
            }).when(ps).setNull(anyInt(), anyInt());
            when(ps.executeUpdate()).thenAnswer(call -> {
                // List.copyOf запрещает null, а description/planId могут быть null.
                inserts.add(new Insert(sql, new ArrayList<>(params)));
                return 1;
            });

            return ps;
        });

        doAnswer(invocation -> {
            ReturningWork<?> work = invocation.getArgument(0);
            return work.execute(connection);
        }).when(session).doReturningWork(any());

        return new JdbcCapture(inserts);
    }

    private static Insert insert(List<Insert> inserts, String sqlPrefix) {
        return inserts.stream()
                .filter(item -> item.sql().startsWith(sqlPrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Не найден INSERT " + sqlPrefix + "; выполнены: " + inserts));
    }

    private record JdbcCapture(List<Insert> inserts) {
    }

    private record Insert(String sql, List<Object> params) {
    }
}


package app.service;

import app.db.Database;
import app.model.AppSettings;
import app.model.User;
import app.support.FakePersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileServiceTest {

    private static final long USER_ID = 1L;

    private FakePersistence fake;
    private ProfileService service;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        fake = new FakePersistence();
        Database database = FakePersistence.mockDatabase(fake.em());
        service = new ProfileService(database);

        User owner = User.builder().id(USER_ID).username("owner").build();
        settings = AppSettings.builder().user(owner).build();

        fake.register(AppSettings.class, 1L, settings);
        fake.onQuery(AppSettings.class, "from AppSettings", params -> List.of(settings));
    }

    @Test
    @DisplayName("новые настройки: отдых по умолчанию 45 секунд")
    void defaultRestTimer_is45() {
        assertEquals(45, AppSettings.builder().user(settings.getUser()).build()
                .getRestTimerSeconds());
    }

    @Test
    @DisplayName("updateRestTimer: сохраняет новое значение")
    void updateRestTimer_savesValue() {
        AppSettings saved = service.updateRestTimer(USER_ID, 75);

        assertEquals(75, saved.getRestTimerSeconds());
        assertEquals(75, settings.getRestTimerSeconds(), "меняется управляемая сущность");
    }

    @Test
    @DisplayName("updateRestTimer: 0 (без отдыха) допустим")
    void updateRestTimer_zero_isAllowed() {
        assertEquals(0, service.updateRestTimer(USER_ID, 0).getRestTimerSeconds());
    }

    @Test
    @DisplayName("updateRestTimer: 5999 (99:59) — верхняя граница включительно")
    void updateRestTimer_maxValue_isAllowed() {
        assertEquals(AppSettings.MAX_REST_TIMER_SECONDS,
                service.updateRestTimer(USER_ID, AppSettings.MAX_REST_TIMER_SECONDS)
                        .getRestTimerSeconds());
        assertEquals(5999, AppSettings.MAX_REST_TIMER_SECONDS);
    }

    @Test
    @DisplayName("updateRestTimer: значение вне диапазона отклоняется")
    void updateRestTimer_outOfRange_isRejected() {
        assertThrows(ValidationException.class, () -> service.updateRestTimer(USER_ID, -1));
        assertThrows(ValidationException.class, () -> service.updateRestTimer(USER_ID, 6000));
        assertEquals(45, settings.getRestTimerSeconds(), "при ошибке значение не меняется");
    }
}

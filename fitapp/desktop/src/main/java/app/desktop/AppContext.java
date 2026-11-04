package app.desktop;

import app.db.Database;
import app.model.AppSettings;
import app.model.User;
import app.service.CatalogService;
import app.service.ExportService;
import app.service.ImportService;
import app.service.MeasurementService;
import app.service.MediaService;
import app.service.PlanService;
import app.service.ProfileService;
import app.service.ScheduleAdminService;
import app.service.ScheduleService;
import app.service.TimerService;
import app.service.WorkoutService;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ручной DI: одно место, где создаются все сервисы.
 *
 * Контроллеры получают AppContext через конструктор
 * (см. Navigator.createController) и не создают сервисы сами.
 */
public final class AppContext implements AutoCloseable {

    private final Database database;

    private final ProfileService profileService;
    private final CatalogService catalogService;
    private final ScheduleAdminService scheduleAdminService;
    private final MeasurementService measurementService;
    private final MediaService mediaService;
    private final WorkoutService workoutService;
    private final TimerService timerService;
    private final ScheduleService scheduleService;
    private final ExportService exportService;
    private final ImportService importService;
    private final PlanService planService;

    private final ThemeManager themeManager = new ThemeManager();
    private final AppState state = new AppState();
    private final Navigator navigator;

    /**
     * B8: признак «идёт долгая операция» (экспорт/импорт/бэкап).
     *
     * Флаг общий на всё приложение, а не на экран: кнопки есть и в главном
     * меню, и в настройках, а база одна. Второй запуск по тому же файлу или
     * импорт во время экспорта — это гарантированная каша, поэтому операция
     * либо занимает флаг целиком, либо сразу отказывается стартовать.
     */
    private final AtomicBoolean isBusy = new AtomicBoolean(false);

    /**
     * Текущий размер шрифта как свойство JavaFX.
     *
     * Нужен, чтобы панель «A−/A+» в главном окне и экран настроек показывали
     * одно и то же число: оба меняют шрифт, и подписи не должны расходиться.
     */
    private final IntegerProperty fontSize =
            new SimpleIntegerProperty(ThemeManager.DEFAULT_FONT_SIZE);

    private final long userId;
    private AppSettings settings;

    public AppContext(Database database, Path mediaRoot) {
        this.database = database;

        this.profileService = new ProfileService(database);
        this.catalogService = new CatalogService(database);
        this.scheduleAdminService = new ScheduleAdminService(database);
        this.measurementService = new MeasurementService(database);
        this.mediaService = new MediaService(mediaRoot);
        this.workoutService = new WorkoutService(database);
        this.timerService = new TimerService(database);
        this.scheduleService = new ScheduleService(database);
        this.exportService = new ExportService(database);
        this.importService = new ImportService(database, exportService);
        this.planService = new PlanService(database);

        User user = profileService.ensureDefaultUser();
        this.userId = user.getId();
        this.settings = profileService.loadSettings(userId);
        this.fontSize.set(positiveFontSize(this.settings.getFontSize()));

        this.navigator = new Navigator(this);
    }

    public long userId() {
        return userId;
    }

    public AppSettings settings() {
        return settings;
    }

    /** Запоминает настройки после сохранения, чтобы не читать БД повторно. */
    public void rememberSettings(AppSettings settings) {
        this.settings = settings;
        this.fontSize.set(positiveFontSize(settings.getFontSize()));
    }

    /** Размер шрифта интерфейса как свойство: подписи в UI подписываются на него. */
    public IntegerProperty fontSizeProperty() {
        return fontSize;
    }

    /**
     * Меняет размер шрифта всего интерфейса на delta пунктов (например, +1 или -1).
     *
     * Порядок важен: сначала сохраняем в БД, потом применяем. Если запись
     * не удалась, интерфейс остаётся прежним — «шрифт побольше до перезапуска»
     * выглядел бы как потеря настройки. Верхняя/нижняя граница — как в
     * настройках (12..24), поэтому на краю диапазона метод просто ничего не делает.
     *
     * @return фактический размер шрифта после изменения
     */
    public int changeFontSize(int delta) {
        AppSettings current = this.settings;
        int base = current == null
                ? ThemeManager.DEFAULT_FONT_SIZE
                : positiveFontSize(current.getFontSize());

        int size = Math.max(ThemeManager.MIN_FONT_SIZE,
                Math.min(ThemeManager.MAX_FONT_SIZE, base + delta));
        if (size == base) {
            return base;    // уже на границе — лишний UPDATE не нужен
        }

        String themeName = current.getThemeName();
        String accent = current.getAccentColor();
        String soundPath = current.getSoundPath();

        AppSettings saved = profileService.saveAppearance(
                userId, themeName, accent, size, soundPath);
        rememberSettings(saved);
        themeManager.apply(saved.getThemeName(), saved.getAccentColor(), size);
        return size;
    }

    /** В настройках может оказаться 0/мусор — тогда берём значение по умолчанию. */
    private static int positiveFontSize(int value) {
        return value <= 0 ? ThemeManager.DEFAULT_FONT_SIZE : value;
    }

    public AppState state() {
        return state;
    }

    public ThemeManager themeManager() {
        return themeManager;
    }

    public Navigator navigator() {
        return navigator;
    }

    public ProfileService profileService() {
        return profileService;
    }

    public CatalogService catalogService() {
        return catalogService;
    }

    public ScheduleAdminService scheduleAdminService() {
        return scheduleAdminService;
    }

    public MeasurementService measurementService() {
        return measurementService;
    }

    public MediaService mediaService() {
        return mediaService;
    }

    public WorkoutService workoutService() {
        return workoutService;
    }

    public TimerService timerService() {
        return timerService;
    }

    public ScheduleService scheduleService() {
        return scheduleService;
    }

    public ExportService exportService() {
        return exportService;
    }

    public ImportService importService() {
        return importService;
    }

    /**
     * Занимает «канал» экспорта/импорта.
     *
     * @return true — операция начата; false — другая уже идёт
     */
    public boolean tryStartJob() {
        return isBusy.compareAndSet(false, true);
    }

    /** Освобождает канал: вызывается в onSucceeded/onFailed фоновой задачи. */
    public void finishJob() {
        isBusy.set(false);
    }

    /** Идёт ли сейчас экспорт/импорт (для подсказок в UI). */
    public boolean isBusy() {
        return isBusy.get();
    }

    public PlanService planService() {
        return planService;
    }

    @Override
    public void close() {
        database.close();
    }

    /**
     * Вызывается при закрытии окна приложения: если открыта пустая тренировка,
     * удаляем её, чтобы в истории не висело «не завершена» на пустом месте.
     */
    public void discardDraftIfEmpty() {
        Long workoutId = state.workoutId();
        if (workoutId == null) {
            return;
        }
        try {
            workoutService.deleteIfEmpty(workoutId);
        } catch (RuntimeException ignored) {
            // при выходе не мешаем закрытию приложения
        } finally {
            state.clearWorkout();
        }
    }
}

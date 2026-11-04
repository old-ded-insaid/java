package app.desktop.controller;

import app.desktop.AppContext;
import app.desktop.ThemeManager;
import app.desktop.ui.Alerts;
import app.model.AppSettings;
import app.service.ImportService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;

/**
 * Настройки: тема, акцент, размер шрифта, звук таймера отдыха, бэкап/экспорт.
 *
 * Предпросмотр применяется сразу, в БД уходит только по кнопке «Сохранить».
 */
public class SettingsController {

    private final AppContext context;

    @FXML private ComboBox<ThemeManager.ThemeOption> themeBox;
    @FXML private ColorPicker accentPicker;
    @FXML private Spinner<Integer> fontSizeSpinner;
    @FXML private Label previewLabel;

    @FXML private Label soundLabel;

    /** true, пока заполняем поля: иначе слушатели сразу перекрасят сцену. */
    private boolean loading;

    /** Путь к выбранному звуку (null — системный сигнал). */
    private String currentSoundPath;

    public SettingsController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        themeBox.getItems().setAll(context.themeManager().options());

        AppSettings settings = context.settings();

        fontSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                12, 24, settings.getFontSize() <= 0 ? ThemeManager.DEFAULT_FONT_SIZE : settings.getFontSize()));

        loading = true;
        themeBox.getSelectionModel().select(context.themeManager().find(settings.getThemeName()));
        accentPicker.setValue(ThemeManager.fromHex(settings.getAccentColor()));
        currentSoundPath = settings.getSoundPath();
        loading = false;

        soundLabel.setText(soundName(currentSoundPath));

        themeBox.valueProperty().addListener((observable, oldTheme, newTheme) -> {
            if (loading || newTheme == null) {
                return;
            }
            // новая тема — подставляем её акцент по умолчанию
            accentPicker.setValue(ThemeManager.fromHex(newTheme.defaultAccent()));
            preview();
        });

        accentPicker.valueProperty().addListener((observable, oldColor, newColor) -> {
            if (!loading) {
                preview();
            }
        });

        fontSizeSpinner.valueProperty().addListener((observable, oldSize, newSize) -> {
            if (!loading) {
                preview();
            }
        });

        previewLabel.setText("Тема: " + settings.getThemeName()
                + " · акцент " + settings.getAccentColor()
                + " · " + settings.getFontSize() + " px");
    }

    private void preview() {
        ThemeManager.ThemeOption theme = themeBox.getValue();
        Color color = accentPicker.getValue();
        Integer size = fontSizeSpinner.getValue();
        if (theme == null || color == null) {
            return;
        }

        int fontSize = size == null ? ThemeManager.DEFAULT_FONT_SIZE : size;
        String hex = ThemeManager.toHex(color);

        context.themeManager().apply(theme.id(), hex, fontSize);
        previewLabel.setText("Предпросмотр: " + theme.title() + " · " + hex + " · " + fontSize + " px");
    }

    // -------------------------------------------------------------- звук

    @FXML
    private void onPickSound() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Звук таймера отдыха");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Звук (*.mp3, *.wav)", "*.mp3", "*.wav"));

        File file = chooser.showOpenDialog(themeBox.getScene() == null ? null : themeBox.getScene().getWindow());
        if (file == null) {
            return;
        }

        currentSoundPath = file.getAbsolutePath();
        soundLabel.setText(file.getName());
    }

    @FXML
    private void onClearSound() {
        currentSoundPath = null;
        soundLabel.setText(soundName(null));
    }

    /** Для подписи: только имя файла, а не весь путь. */
    private static String soundName(String path) {
        if (path == null || path.isBlank()) {
            return "системный сигнал (beep)";
        }
        try {
            Path file = Path.of(path);
            Path name = file.getFileName();
            return name == null ? path : name.toString();
        } catch (RuntimeException e) {
            return path;
        }
    }

    // ---------------------------------------------------------- сохранение

    @FXML
    private void onSave() {
        ThemeManager.ThemeOption theme = themeBox.getValue();
        Color color = accentPicker.getValue();
        Integer size = fontSizeSpinner.getValue();

        if (theme == null || color == null || size == null) {
            Alerts.warn("Настройки", "Выберите тему, цвет и размер шрифта.");
            return;
        }

        try {
            AppSettings saved = context.profileService().saveAppearance(
                    context.userId(), theme.id(), ThemeManager.toHex(color), size, currentSoundPath);

            context.rememberSettings(saved);
            context.themeManager().apply(saved.getThemeName(), saved.getAccentColor(), saved.getFontSize());

            Alerts.info("Сохранено", "Тема «" + theme.title() + "», " + size + " px, звук: "
                    + soundName(saved.getSoundPath()) + ".");
        } catch (RuntimeException e) {
            Alerts.error("Не удалось сохранить настройки", e.getMessage());
        }
    }

    // ------------------------------------------------------------- данные

    @FXML
    private void onBackup() {
        // B8: один фоновый экспорт/импорт за раз
        if (!context.tryStartJob()) {
            Alerts.warn("Операция уже идёт",
                    "Дождитесь завершения текущего экспорта или импорта.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Бэкап данных в JSON");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"));
        chooser.setInitialFileName("fitapp-backup.json");

        File file = chooser.showSaveDialog(themeBox.getScene().getWindow());
        if (file == null) {
            context.finishJob();
            return;
        }

        runExport("fitapp-export-json", "Ошибка бэкапа",
                "Бэкап готов", "Данные сохранены:\n" + file.getAbsolutePath(),
                () -> context.exportService().exportWorkoutsToJson(context.userId(), file.toPath()));
    }

    @FXML
    private void onExportExcel() {
        if (!context.tryStartJob()) {
            Alerts.warn("Операция уже идёт",
                    "Дождитесь завершения текущего экспорта или импорта.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Экспорт тренировок в Excel");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
        chooser.setInitialFileName("fitapp-workouts.xlsx");

        File file = chooser.showSaveDialog(themeBox.getScene().getWindow());
        if (file == null) {
            context.finishJob();
            return;
        }

        runExport("fitapp-export-excel", "Ошибка экспорта",
                "Экспорт готов", "Файл сохранён:\n" + file.getAbsolutePath(),
                () -> context.exportService().exportWorkoutsToExcel(context.userId(), file.toPath()));
    }

    /**
     * Восстановление базы из полного JSON-бэкапа.
     *
     * Действие разрушительное (данные заменяются), поэтому сначала спрашиваем
     * подтверждение, потом выполняем в фоне и только после успеха показываем
     * статистику. Ошибка импорта ничего не портит: транзакция откатывается.
     */
    @FXML
    private void onImport() {
        // B8: импорт во время другого экспорта/импорта не запускаем
        if (!context.tryStartJob()) {
            Alerts.warn("Операция уже идёт",
                    "Дождитесь завершения текущего экспорта или импорта.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Импорт базы из JSON");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"));

        File file = chooser.showOpenDialog(themeBox.getScene().getWindow());
        if (file == null) {
            context.finishJob();
            return;
        }

        boolean confirmed = Alerts.confirm("Восстановление из бэкапа",
                "Все текущие тренировки, шаблоны, упражнения, инвентарь, расписания и замеры "
                        + "будут заменены данными из файла:\n" + file.getAbsolutePath()
                        + "\n\nПользователь и настройки останутся прежними.\n"
                        + "Перед импортом рядом с файлом сохранится копия текущей базы. Продолжить?");
        if (!confirmed) {
            context.finishJob();
            return;
        }

        Task<ImportService.ImportResult> importTask = new Task<>() {
            @Override
            protected ImportService.ImportResult call() {
                return context.importService().importFullBackup(file.toPath());
            }
        };

        importTask.setOnSucceeded(event -> {
            context.finishJob();
            ImportService.ImportResult result = importTask.getValue();
            context.state().clearWorkout();   // id из прошлой сессии больше не существует
            Alerts.info("База данных восстановлена",
                    result.summary()
                            + "\n\nПользователь и настройки не менялись."
                            + "\nКопия прежней базы сохранена:\n" + result.safetyCopy());
            context.navigator().showMain();
        });

        importTask.setOnFailed(event -> {
            context.finishJob();
            Throwable error = importTask.getException();
            Alerts.error("Не удалось восстановить базу",
                    error == null ? "Неизвестная ошибка" : error.getMessage());
        });

        Thread worker = new Thread(importTask, "fitapp-import-json");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Запись файла на диск — долгая операция, поэтому в фоновом потоке (C3):
     * окно не замирает, а результат показываем по завершении задачи.
     */
    private void runExport(String threadName,
                           String errorTitle,
                           String successTitle,
                           String successText,
                           Runnable job) {
        Task<Void> export = new Task<>() {
            @Override
            protected Void call() {
                job.run();
                return null;
            }
        };

        export.setOnSucceeded(event -> {
            context.finishJob();    // B8: канал свободен
            Alerts.info(successTitle, successText);
        });
        export.setOnFailed(event -> {
            context.finishJob();
            Throwable error = export.getException();
            Alerts.error(errorTitle, error == null ? "Неизвестная ошибка" : error.getMessage());
        });

        Thread worker = new Thread(export, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void onBack() {
        context.navigator().showMain();
    }
}

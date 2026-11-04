package app.desktop;

import app.db.Database;
import app.db.DatabaseConfig;
import app.model.AppSettings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * Запуск приложения:
 *  1. показываем окно с индикатором загрузки (без ожидания БД),
 *  2. в фоновом потоке поднимаем БД (Flyway + Hibernate) и собираем AppContext,
 *  3. применяем сохранённую тему,
 *  4. показываем главное меню.
 *
 * Раньше БД поднималась прямо в start() — окно не появлялось, пока PostgreSQL
 * не ответит, и приложение выглядело «зависшим» (BUG C3).
 */
public class MainApp extends Application {

    private AppContext context;
    private Stage stage;

    /** Пока БД поднимается, здесь показываем, на каком мы шаге. */
    private Label loadingLabel;

    /** Подсказка в полосе шрифта (например, «минимальный размер»). */
    private Label fontHint;

    /** Ошибка запуска: показываем один раз, даже если пришло и падение, и onFailed. */
    private boolean startupFailed;

    /** Окно закрыли, пока БД ещё поднималась: результат инициализации уже не нужен. */
    private boolean closing;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        // Windows: свой AppUserModelID — иначе нельзя закрепить ярлык на панели задач.
        // B6: даже если JNA недоступна (другой JDK, урезанная сборка), приложение
        // обязано запуститься — отсутствие AUMID влияет только на «закрепить».
        try {
            AppUserModelId.apply();
        } catch (Throwable ignored) {
            // молча: работаем без AppUserModelID
        }

        stage.setTitle("fitapp — домашние тренировки");
        stage.setMinWidth(1000);
        stage.setMinHeight(700);
        applyWindowIcon(stage);
        stage.setScene(buildLoadingScene());
        stage.show();

        Task<AppContext> init = createInitTask();

        // «Подключаемся…», «Применяем миграции…» — прямо в окне загрузки
        if (loadingLabel != null) {
            loadingLabel.textProperty().bind(init.messageProperty());
        }

        init.setOnSucceeded(event -> showMainWindow(init.getValue()));
        init.setOnFailed(event -> showStartupError(init.getException()));

        Thread worker = new Thread(init, "fitapp-startup");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Инициализация БД и сервисов в фоне.
     *
     * Если AppContext не собрался, уже открытый Database закрываем,
     * иначе соединения останутся висеть до выхода из процесса.
     */
    private Task<AppContext> createInitTask() {
        return new Task<>() {
            @Override
            protected AppContext call() {
                updateMessage("Подключаемся к PostgreSQL…");
                DatabaseConfig config = DatabaseConfig.load();

                updateMessage("Проверяем схему и применяем миграции…");
                Database database = config.start();

                try {
                    updateMessage("Готовим сервисы…");
                    return new AppContext(database, config.path("app.media.dir"));
                } catch (RuntimeException e) {
                    database.close();
                    throw e;
                }
            }
        };
    }

    /** Простое окно загрузки: окно должно появиться сразу. */
    private Scene buildLoadingScene() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(64, 64);

        loadingLabel = new Label("Запуск fitapp…");
        loadingLabel.getStyleClass().add("section-title");

        Label hint = new Label("Подключение к базе данных и проверка миграций.");
        hint.getStyleClass().add("caption");

        VBox box = new VBox(16, spinner, loadingLabel, hint);
        box.setAlignment(Pos.CENTER);

        BorderPane root = new BorderPane(box);
        root.getStyleClass().add("root");

        Scene scene = new Scene(root, 1180, 760);

        // base.css использует переменные темы: без theme_*.css цвета будут
        // не определены (JavaFX ругается на строках вместо Paint).
        addStylesheet(scene, "/app/desktop/css/base.css");
        addStylesheet(scene, "/app/desktop/css/theme_gray_blue.css");
        return scene;
    }

    private static void addStylesheet(Scene scene, String resource) {
        URL css = MainApp.class.getResource(resource);
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }

    /** БД поднялась: собираем обычный экран и подменяем сцену. */
    private void showMainWindow(AppContext ready) {
        // окно закрыли во время загрузки — просто освобождаем ресурсы
        if (closing || !stage.isShowing()) {
            ready.close();
            return;
        }

        context = ready;

        BorderPane shell = new BorderPane();
        shell.getStyleClass().add("root");

        Scene scene = new Scene(shell, 1180, 760);

        // Тема: base.css + текущая тема + акцентный цвет из app_settings
        context.themeManager().attach(scene);
        applySavedTheme();

        // Навигация внутри одного окна
        context.navigator().attach(shell);
        context.navigator().showMain();

        // Общие для всех экранов кнопки размера шрифта + горячие клавиши
        shell.setBottom(buildFontBar());
        installFontShortcuts(scene);

        stage.setScene(scene);

        // закрыли окно прямо во время тренировки — пустой черновик удаляем
        stage.setOnCloseRequest(event -> context.discardDraftIfEmpty());

        stage.show();
    }

    // ------------------------------------------------------- размер шрифта

    /**
     * Нижняя полоса с кнопками «A−» / «A+».
     *
     * Живёт в оболочке BorderPane, а не в каждом FXML: экраны сменяются
     * (Navigator кладёт их в center), а полоса остаётся на месте.
     */
    private HBox buildFontBar() {
        Button smaller = new Button("A−");
        smaller.getStyleClass().add("small-button");
        smaller.setTooltip(new Tooltip("Уменьшить шрифт (Ctrl + −)"));
        smaller.setOnAction(event -> changeFontSize(-1));

        Button bigger = new Button("A+");
        bigger.getStyleClass().add("small-button");
        bigger.setTooltip(new Tooltip("Увеличить шрифт (Ctrl + +)"));
        bigger.setOnAction(event -> changeFontSize(+1));

        Label sizeLabel = new Label();
        sizeLabel.getStyleClass().add("caption");
        // подпись сама следует за настройкой, даже если размер поменяли в настройках
        sizeLabel.textProperty().bind(context.fontSizeProperty().asString().concat(" px"));

        Label hint = new Label("Шрифт");
        hint.getStyleClass().add("caption");

        fontHint = new Label();
        fontHint.getStyleClass().add("caption");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, spacer, hint, smaller, sizeLabel, bigger, fontHint);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("font-bar");
        return bar;
    }

    /**
     * Ctrl + «+» / Ctrl + «−» на уровне сцены: работает на любом экране.
     *
     * Регистрируем обе раскладки клавиши «+» (цифровой блок и Shift+=),
     * иначе на ноутбуках без нампада Ctrl+= просто ничего не делает.
     */
    private void installFontShortcuts(Scene scene) {
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Plus"),
                () -> changeFontSize(+1));
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Equals"),
                () -> changeFontSize(+1));
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Minus"),
                () -> changeFontSize(-1));
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Subtract"),
                () -> changeFontSize(-1));
    }

    /** Меняет шрифт интерфейса и, если не получилось сохранить, говорит об этом. */
    private void changeFontSize(int delta) {
        try {
            int size = context.changeFontSize(delta);
            if (size == ThemeManager.MIN_FONT_SIZE && delta < 0) {
                statusHint("Минимальный размер шрифта: " + size + " px");
            } else if (size == ThemeManager.MAX_FONT_SIZE && delta > 0) {
                statusHint("Максимальный размер шрифта: " + size + " px");
            }
        } catch (RuntimeException e) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Размер шрифта");
            alert.setHeaderText("Не удалось сохранить настройку");
            alert.setContentText(e.getMessage());
            alert.initOwner(stage);
            alert.showAndWait();
        }
    }

    /** Короткая подсказка в конце полосы (границы диапазона). */
    private void statusHint(String text) {
        if (fontHint != null) {
            fontHint.setText(text);
        }
    }

    /** БД не поднялась: понятное сообщение вместо стектрейса в консоли. */
    private void showStartupError(Throwable error) {
        if (startupFailed || closing || !stage.isShowing()) {
            return;
        }
        startupFailed = true;

        String details = error == null ? "" : (error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage());

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("fitapp не запустился");
        alert.setHeaderText("Не удалось подключиться к базе данных");
        alert.setContentText("Проверьте, запущен ли PostgreSQL (служба postgresql-x64-18).\n"
                + "Также проверьте адрес, имя пользователя и пароль в application.properties.\n\n"
                + "Причина: " + details);
        alert.initOwner(stage);
        alert.showAndWait();

        stage.close();
        Platform.exit();
    }

    private void applySavedTheme() {
        AppSettings settings = context.settings();
        context.themeManager().apply(settings.getThemeName(), settings.getAccentColor(),
                settings.getFontSize());
    }

    /**
     * Иконка окна и панели задач Windows.
     *
     * Берём два размера: 256 — для переключателя окон и крупных значков,
     * 64 — для панели задач (большой PNG там масштабируется «мылом»).
     * Любая проблема с ресурсом не должна мешать запуску приложения.
     */
    private void applyWindowIcon(Stage stage) {
        addIcon(stage, "/images/icon.png");
        addIcon(stage, "/images/icon-64.png");
    }

    private void addIcon(Stage stage, String resource) {
        InputStream stream = MainApp.class.getResourceAsStream(resource);
        if (stream == null) {
            System.err.println("Иконка не найдена: " + resource);
            return;
        }
        try (InputStream in = stream) {
            stage.getIcons().add(new Image(in));
        } catch (IOException | RuntimeException e) {
            System.err.println("Не удалось загрузить иконку " + resource + ": " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        closing = true;
        if (context != null) {
            context.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}

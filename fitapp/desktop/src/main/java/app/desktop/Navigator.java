package app.desktop;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;

import java.io.IOException;

/**
 * Переключение экранов внутри одного окна.
 *
 * Каждый экран — отдельный FXML, который загружается заново,
 * поэтому данные всегда свежие. Контроллеры создаются фабрикой:
 * JavaFX сам вызовет конструктор с AppContext.
 */
public class Navigator {

    private final AppContext context;
    private BorderPane shell;

    /** Контроллер текущего экрана: при смене экрана освобождаем его ресурсы. */
    private Object currentController;

    public Navigator(AppContext context) {
        this.context = context;
    }

    /** Вызывается один раз из MainApp, когда окно уже создано. */
    public void attach(BorderPane shell) {
        this.shell = shell;
    }

    public void showMain() {
        context.state().clearWorkout();
        show("main.fxml");
    }

    public void showWorkout() {
        show("workout.fxml");
    }

    public void showSchedule() {
        show("schedule.fxml");
    }

    /** Конструктор шаблонов тренировок. */
    public void showPlanEditor() {
        show("plan-editor.fxml");
    }

    /** Справочник упражнений и их инвентаря. */
    public void showExerciseCatalog() {
        show("exercise-catalog.fxml");
    }

    public void showMeasurements() {
        show("measurements.fxml");
    }

    public void showSettings() {
        show("settings.fxml");
    }

    private void show(String fxmlName) {
        FXMLLoader loader = new FXMLLoader(
                Navigator.class.getResource("/app/desktop/fxml/" + fxmlName));
        loader.setControllerFactory(this::createController);

        try {
            Parent view = loader.load();
            Object controller = loader.getController();

            // BUG-17: у предыдущего экрана останавливаем таймеры и звук
            if (currentController instanceof Disposable disposable) {
                disposable.dispose();
            }
            currentController = controller;

            shell.setCenter(view);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось открыть экран " + fxmlName, e);
        }
    }

    /** Все контроллеры обязаны иметь конструктор (AppContext). */
    private Object createController(Class<?> type) {
        try {
            return type.getConstructor(AppContext.class).newInstance(context);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Контроллер " + type.getSimpleName()
                    + " должен иметь public конструктор с аргументом AppContext", e);
        }
    }
}

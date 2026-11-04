package app.desktop.controller;

import app.desktop.AppContext;
import app.desktop.ui.Alerts;
import app.desktop.ui.TimeMask;
import app.model.Workout;
import app.model.WorkoutPlan;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

/**
 * Главное меню: последние тренировки + переходы на другие экраны.
 */
public class MainController {

    private final AppContext context;

    @FXML
    private ListView<Workout> workoutList;

    public MainController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        workoutList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Workout workout, boolean empty) {
                super.updateItem(workout, empty);
                if (empty || workout == null) {
                    setText(null);
                    return;
                }
                // только скалярные поля: связи уже закрыты вместе с транзакцией
                String duration = workout.getDurationSec() == null
                        ? "не завершена"
                        : TimeMask.format(workout.getDurationSec());
                setText(workout.getWorkoutDate() + "    " + workout.getName() + "    ·    " + duration);
            }
        });

        // двойной клик по тренировке — открыть её
        workoutList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelectedWorkout();
            }
        });

        refresh();
    }

    private void refresh() {
        workoutList.getItems().setAll(context.workoutService()
                .history(context.userId(), LocalDate.now().minusMonths(3), LocalDate.now()));
    }

    @FXML
    private void onNewWorkout() {
        List<WorkoutPlan> plans = context.catalogService().activePlans(context.userId());
        if (plans.isEmpty()) {
            Alerts.warn("Нет шаблонов",
                    "Сначала создайте шаблон тренировки в разделе «Шаблоны тренировок».");
            return;
        }

        // имена шаблонов уникальны в пределах пользователя — выбираем по имени
        List<String> names = plans.stream().map(WorkoutPlan::getName).toList();

        ChoiceDialog<String> dialog = new ChoiceDialog<>(names.get(0), names);
        dialog.setTitle("Новая тренировка");
        dialog.setHeaderText(null);
        dialog.setContentText("Шаблон:");
        if (workoutList.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(workoutList.getScene().getStylesheets());
        }

        String chosenName = dialog.showAndWait().orElse(null);
        if (chosenName == null) {
            return; // отменили выбор
        }

        WorkoutPlan plan = plans.stream()
                .filter(item -> item.getName().equals(chosenName))
                .findFirst()
                .orElse(null);
        if (plan == null) {
            return;
        }

        try {
            Workout workout = context.workoutService()
                    .createFromPlan(context.userId(), plan.getId());

            context.state().setDraft(true);
            context.state().setWorkoutId(workout.getId());
            context.navigator().showWorkout();
        } catch (RuntimeException e) {
            Alerts.error("Не удалось создать тренировку", e.getMessage());
        }
    }

    /** Удаление ошибочной/пустой тренировки из истории. */
    @FXML
    private void onDeleteWorkout() {
        Workout selected = workoutList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Alerts.warn("Тренировка не выбрана", "Выберите тренировку в списке.");
            return;
        }

        boolean confirmed = Alerts.confirm("Удалить тренировку",
                "Удалить «" + selected.getName() + "» за " + selected.getWorkoutDate()
                        + "? Подходы и заметки тоже удалятся.");
        if (!confirmed) {
            return;
        }

        try {
            context.workoutService().deleteWorkout(selected.getId());
            refresh();
            Alerts.info("Удалено", "Тренировка удалена из истории.");
        } catch (RuntimeException e) {
            Alerts.error("Не удалось удалить тренировку", e.getMessage());
        }
    }

    @FXML
    private void onOpenWorkout() {
        openSelectedWorkout();
    }

    /**
     * Экспорт выбранной тренировки в JSON (факт: подходы, время, пульс).
     *
     * Файл можно потом импортировать в редакторе шаблонов — формат совместим.
     */
    @FXML
    private void onExportWorkoutJson() {
        Workout selected = workoutList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Alerts.warn("Тренировка не выбрана",
                    "Выберите тренировку в списке — экспортируется именно она.");
            return;
        }

        // B8: не запускаем второй экспорт/импорт параллельно
        if (!context.tryStartJob()) {
            Alerts.warn("Операция уже идёт",
                    "Дождитесь завершения текущего экспорта или импорта.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Экспорт тренировки в JSON");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"));
        chooser.setInitialFileName(defaultJsonName(selected));

        File file = chooser.showSaveDialog(workoutList.getScene().getWindow());
        if (file == null) {
            context.finishJob();    // пользователь нажал «Отмена»
            return;
        }

        Task<Void> export = new Task<>() {
            @Override
            protected Void call() {
                context.exportService().exportWorkoutToJson(selected.getId(), file.toPath());
                return null;
            }
        };

        export.setOnSucceeded(event -> {
            context.finishJob();
            Alerts.info("Экспорт готов", "Тренировка сохранена:\n" + file.getAbsolutePath());
        });

        export.setOnFailed(event -> {
            context.finishJob();
            Throwable error = export.getException();
            Alerts.error("Ошибка экспорта", error == null ? "Неизвестная ошибка" : error.getMessage());
        });

        Thread worker = new Thread(export, "fitapp-export-workout");
        worker.setDaemon(true);
        worker.start();
    }

    /** Имя файла по умолчанию: «тренировка-2026-11-01.json». */
    private static String defaultJsonName(Workout workout) {
        return "тренировка-" + workout.getWorkoutDate() + ".json";
    }

    /**
     * Импорт выполненной тренировки из JSON (файл от «Экспорт JSON»).
     *
     * Файл читается целиком в строку и разбирается в сервисе — там же проверяются
     * обязательные поля. В историю попадает завершённая тренировка.
     */
    @FXML
    private void onImportWorkoutJson() {
        if (!context.tryStartJob()) {
            Alerts.warn("Операция уже идёт",
                    "Дождитесь завершения текущего экспорта или импорта.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Импорт тренировки из JSON");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"));
        chooser.setInitialFileName("тренировка.json");

        File file = chooser.showOpenDialog(workoutList.getScene().getWindow());
        if (file == null) {
            context.finishJob();    // пользователь нажал «Отмена»
            return;
        }

        Task<Workout> importTask = new Task<>() {
            @Override
            protected Workout call() throws Exception {
                String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                return context.workoutService().importWorkoutFromJson(json);
            }
        };

        importTask.setOnSucceeded(event -> {
            context.finishJob();
            Workout workout = importTask.getValue();
            refresh();
            Alerts.info("Тренировка импортирована",
                    "«" + workout.getName() + "» за " + workout.getWorkoutDate()
                            + " добавлена в историю.\nУпражнений: " + workout.getExercises().size() + ".");
        });

        importTask.setOnFailed(event -> {
            context.finishJob();
            Throwable error = importTask.getException();
            String message = error == null ? "Неизвестная ошибка" : error.getMessage();
            if (error instanceof java.io.IOException) {
                message = "Не удалось прочитать файл: " + message;
            }
            Alerts.error("Не удалось импортировать тренировку", message);
        });

        Thread worker = new Thread(importTask, "fitapp-import-workout");
        worker.setDaemon(true);
        worker.start();
    }

    private void openSelectedWorkout() {
        Workout selected = workoutList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Alerts.warn("Тренировка не выбрана", "Выберите тренировку в списке.");
            return;
        }
        context.workoutService().markNotDraft(selected.getId());
        context.state().setDraft(false);
        context.state().setWorkoutId(selected.getId());
        context.navigator().showWorkout();
    }

    @FXML
    private void onSchedule() {
        context.navigator().showSchedule();
    }

    @FXML
    private void onPlans() {
        context.navigator().showPlanEditor();
    }

    @FXML
    private void onExerciseCatalog() {
        context.navigator().showExerciseCatalog();
    }

    @FXML
    private void onMeasurements() {
        context.navigator().showMeasurements();
    }

    @FXML
    private void onSettings() {
        context.navigator().showSettings();
    }

    /** Экспорт истории в Excel: FileChooser сам спросит, куда сохранить. */
    @FXML
    private void onExport() {
        // B8: второй экспорт/импорт параллельно не запускаем
        if (!context.tryStartJob()) {
            Alerts.warn("Операция уже идёт",
                    "Дождитесь завершения текущего экспорта или импорта.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Экспорт тренировок в Excel");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
        chooser.setInitialFileName("fitapp-workouts.xlsx");

        File file = chooser.showSaveDialog(workoutList.getScene().getWindow());
        if (file == null) {
            context.finishJob();    // пользователь нажал «Отмена»
            return;
        }

        // Запись книги Excel — долгая операция: в фоне, окно не подвисает (C3)
        Task<Void> export = new Task<>() {
            @Override
            protected Void call() {
                context.exportService().exportWorkoutsToExcel(context.userId(), file.toPath());
                return null;
            }
        };

        export.setOnSucceeded(event -> {
            context.finishJob();
            Alerts.info("Экспорт готов", "Файл сохранён:\n" + file.getAbsolutePath());
        });

        export.setOnFailed(event -> {
            context.finishJob();
            Throwable error = export.getException();
            Alerts.error("Ошибка экспорта", error == null ? "Неизвестная ошибка" : error.getMessage());
        });

        Thread worker = new Thread(export, "fitapp-export-excel");
        worker.setDaemon(true);
        worker.start();
    }
}

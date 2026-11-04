package app.desktop.controller;

import app.desktop.AppContext;
import app.desktop.Disposable;
import app.desktop.ui.Alerts;
import app.desktop.ui.EquipmentChoice;
import app.desktop.ui.SetRow;
import app.desktop.ui.TimeMask;
import app.model.AppSettings;
import app.model.Equipment;
import app.model.Exercise;
import app.model.Workout;
import app.model.WorkoutExercise;
import app.model.WorkoutSet;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.text.Text;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import java.awt.Toolkit;
/**
 * Экран тренировки: большой таймер, таблица подходов и карточка упражнения.
 *
 * Таймер — обычный JavaFX Timeline, который раз в секунду увеличивает
 * счётчик seconds. Это не «часы реального времени»: пауза честно
 * замораживает значение, сброс возвращает 00:00:00.
 */
public class WorkoutController implements Disposable {

    private final AppContext context;

    @FXML private Label titleLabel;
    @FXML private Label timerLabel;
    @FXML private Label statusLabel;

    @FXML private Label exerciseNameLabel;
    @FXML private TextArea setupLabel;
    @FXML private TextArea movementLabel;
    @FXML private TextArea musclesLabel;
    @FXML private TextArea avoidLabel;

    @FXML private TableView<SetRow> setTable;
    @FXML private TableColumn<SetRow, String> colExercise;
    @FXML private TableColumn<SetRow, Integer> colSetNumber;
    @FXML private TableColumn<SetRow, Integer> colReps;
    @FXML private TableColumn<SetRow, BigDecimal> colWeight;
    @FXML private TableColumn<SetRow, String> colTime;
    @FXML private TableColumn<SetRow, String> colEquipment;
    @FXML private TableColumn<SetRow, String> colNote;

    @FXML private ComboBox<Exercise> exerciseBox;

    /** Правая карточка упражнения: прокрутка на случай длинных описаний. */
    @FXML private ScrollPane descriptionScroll;

    // таймер отдыха
    @FXML private Label restTimerLabel;
    @FXML private Spinner<Integer> restMinutesSpinner;
    @FXML private Spinner<Integer> restSecondsSpinner;
    @FXML private ComboBox<Integer> restStepBox;

    /** Обновление цифр на экране. */
    private Timeline timeline;

    /** Обратный отсчёт отдыха — живёт независимо от главного таймера. */
    private Timeline restTimeline;

    /** Сколько секунд отдыха осталось (0 — не идёт). */
    private int restRemainingSeconds;

    /** Последнее сохранённое в настройках значение отдыха: чтобы не писать в БД зря. */
    private int savedRestSeconds;

    /** Последний сохранённый шаг стрелок отдыха (B2). */
    private int savedRestStep = AppSettings.DEFAULT_REST_STEP_SECONDS;

    /** Допустимые шаги стрелок таймера отдыха, секунды. */
    private static final List<Integer> REST_STEPS = List.of(1, 5, 10, 15, 30);

    /**
     * B1: когда начался каждый подход (setId -> секунда главного таймера).
     *
     * Без этого нельзя посчитать, сколько длился КОНКРЕТНЫЙ подход:
     * главный таймер показывает время от старта всей тренировки.
     */
    private final Map<Long, Integer> setStartSeconds = new LinkedHashMap<>();

    /** Сколько секунд натикало (пауза сохраняет значение). */
    private int seconds;

    /** Проигрыватель пользовательского звука (держим ссылку, иначе собрается GC). */
    private MediaPlayer restSoundPlayer;

    private long workoutId;

    public WorkoutController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        Long stateWorkoutId = context.state().workoutId();
        if (stateWorkoutId == null) {
            // экран открыли в обход меню — создаём пустую тренировку, чтобы не падать
            Workout workout = context.workoutService()
                    .createWorkout(context.userId(), null, LocalDate.now(), null);
            workoutId = workout.getId();
            context.state().setWorkoutId(workoutId); // чтобы автоочистка знала про черновик
            context.state().setDraft(true);
        } else {
            workoutId = stateWorkoutId;
        }

        buildTimeline();
        buildRestTimer();
        bindTableColumns();
        configureDescriptionFields();
        setTable.setEditable(true);
        // свободная ширина колонок: границы можно тянуть мышкой
        setTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        loadExercises();

        timerLabel.setText(TimeMask.ZERO);

        setTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldRow, newRow) -> {
                    // B1: запоминаем начало подхода, как только его выбрали
                    if (newRow != null) {
                        setStartSeconds.putIfAbsent(newRow.setId(), seconds);
                    }
                    showExerciseInfo(newRow);
                });

        refreshTable();
    }

    // ------------------------------------------------------------------ таймер

    private void buildTimeline() {
        timeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            seconds++;
            timerLabel.setText(TimeMask.format(seconds));
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
    }

    // ------------------------------------------------------- таймер отдыха

    private void buildRestTimer() {
        // Длительность отдыха — из настроек (по умолчанию 45 с = «00:45», V15).
        int restSecondsTotal = restTimerFromSettings();

        // B2: шаг стрелок тоже запоминается в настройках (V16)
        int restStep = restStepFromSettings();

        restMinutesSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                0, AppSettings.MAX_REST_TIMER_SECONDS / 60, restSecondsTotal / 60, 1));
        restSecondsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                0, 59, restSecondsTotal % 60, restStep));

        // шаг стрелочек: 1/5/10/15/30 секунд
        restStepBox.getItems().setAll(1, 5, 10, 15, 30);
        restStepBox.setValue(restStep);
        savedRestStep = restStep;

        restStepBox.valueProperty().addListener((observable, oldStep, newStep) -> {
            applyRestStep(newStep);
            saveRestStepSetting(newStep);
        });
        applyRestStep(restStep);

        restTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> tickRest()));
        restTimeline.setCycleCount(Animation.INDEFINITE);

        savedRestSeconds = restSecondsTotal;

        // пока отсчёт не идёт — показываем выбранный интервал;
        // в БД пишем не здесь, иначе каждый шаг спиннера давал бы UPDATE
        restMinutesSpinner.valueProperty().addListener(
                (observable, oldValue, newValue) -> refreshRestPreview());
        restSecondsSpinner.valueProperty().addListener(
                (observable, oldValue, newValue) -> refreshRestPreview());

        // потеря фокуса (Enter или уход в другое поле) — вот теперь сохраняем
        restMinutesSpinner.getEditor().focusedProperty().addListener(
                (observable, wasFocused, isFocused) -> {
                    if (!isFocused) {
                        commitSpinnerText(restMinutesSpinner);
                        saveRestTimerSetting();
                    }
                });
        restSecondsSpinner.getEditor().focusedProperty().addListener(
                (observable, wasFocused, isFocused) -> {
                    if (!isFocused) {
                        commitSpinnerText(restSecondsSpinner);
                        saveRestTimerSetting();
                    }
                });

        refreshRestPreview();
    }

    /**
     * Применяет выбранный шаг к стрелкам спиннеров.
     *
     * Минуты шагают целыми минутами (1 сек стрелкой по минутам — это 60 кликов),
     * секунды — выбранным шагом.
     */
    private void applyRestStep(Integer step) {
        int value = step == null || step < 1 ? 1 : step;

        int minuteStep = Math.max(1, value / 60);
        setAmountToStepBy(restMinutesSpinner, minuteStep);
        setAmountToStepBy(restSecondsSpinner, value);
    }

    private static void setAmountToStepBy(Spinner<Integer> spinner, int step) {
        if (spinner.getValueFactory() instanceof SpinnerValueFactory.IntegerSpinnerValueFactory factory) {
            factory.setAmountToStepBy(step);
        }
    }

    /** Сколько секунд отдыха сохранено в настройках (с разумным дефолтом). */
    private int restTimerFromSettings() {
        int seconds = context.settings() == null
                ? AppSettings.DEFAULT_REST_TIMER_SECONDS
                : context.settings().getRestTimerSeconds();

        if (seconds < 0 || seconds > AppSettings.MAX_REST_TIMER_SECONDS) {
            return AppSettings.DEFAULT_REST_TIMER_SECONDS;
        }
        return seconds;
    }

    /**
     * Шаг стрелок из настроек (V16).
     *
     * Если в базе неожиданное значение (не 1/5/10/15/30) — берём 5: список
     * шага фиксированный, чужое число в нём просто не выберется.
     */
    private int restStepFromSettings() {
        int step = context.settings() == null
                ? AppSettings.DEFAULT_REST_STEP_SECONDS
                : context.settings().getRestStepSeconds();

        return REST_STEPS.contains(step) ? step : AppSettings.DEFAULT_REST_STEP_SECONDS;
    }

    /** Пишет выбранный шаг в настройки, чтобы он не сбрасывался. */
    private void saveRestStepSetting(Integer step) {
        if (step == null || step == savedRestStep) {
            return;     // ничего не поменялось
        }

        try {
            AppSettings saved = context.profileService()
                    .updateRestStep(context.userId(), step);
            context.rememberSettings(saved);
            savedRestStep = step;
        } catch (RuntimeException e) {
            statusLabel.setText("Не удалось сохранить шаг отдыха: " + e.getMessage());
        }
    }

    /**
     * Фиксирует текст, набранный в поле спиннера.
     *
     * {@code increment(0)} — штатный способ попросить Spinner применить
     * введённое значение; если текст не число, остаётся прежнее значение.
     */
    private static void commitSpinnerText(Spinner<Integer> spinner) {
        try {
            spinner.increment(0);
        } catch (RuntimeException ignored) {
            // «abc» в поле — молча оставляем как было
        }
    }

    /**
     * Пишет текущее значение спиннеров в app_settings.
     *
     * Вызывается только по «Старт» и по потере фокуса — иначе каждое нажатие
     * стрелки спиннера давало бы отдельный UPDATE.
     */
    private void saveRestTimerSetting() {
        int seconds = chosenRestSeconds();
        if (seconds == savedRestSeconds) {
            return;     // ничего не поменялось
        }

        try {
            AppSettings saved = context.profileService()
                    .updateRestTimer(context.userId(), seconds);
            context.rememberSettings(saved);
            savedRestSeconds = seconds;
        } catch (RuntimeException e) {
            statusLabel.setText("Не удалось сохранить отдых: " + e.getMessage());
        }
    }

    private void refreshRestPreview() {
        if (restTimeline != null && restTimeline.getStatus() == Animation.Status.RUNNING) {
            return; // не перебиваем идущий отсчёт
        }
        restRemainingSeconds = 0;
        restTimerLabel.setStyle("");
        restTimerLabel.setText(restText(chosenRestSeconds()));
    }

    private int chosenRestSeconds() {
        int minutes = restMinutesSpinner.getValue() == null ? 0 : restMinutesSpinner.getValue();
        int restSeconds = restSecondsSpinner.getValue() == null ? 0 : restSecondsSpinner.getValue();
        return minutes * 60 + restSeconds;
    }

    @FXML
    private void onRestStart() {
        if (restTimeline.getStatus() == Animation.Status.RUNNING) {
            return; // уже идёт
        }

        // «Старт» — удобный момент запомнить длительность отдыха
        commitSpinnerText(restMinutesSpinner);
        commitSpinnerText(restSecondsSpinner);
        saveRestTimerSetting();

        if (restRemainingSeconds <= 0) {
            restRemainingSeconds = chosenRestSeconds();
        }
        if (restRemainingSeconds <= 0) {
            Alerts.warn("Отдых", "Укажите длительность отдыха — минуты и секунды.");
            return;
        }

        restTimerLabel.setStyle("-fx-text-fill: -fx-accent;");
        restTimerLabel.setText(restText(restRemainingSeconds));
        restTimeline.play();
        statusLabel.setText("Отдых: " + restRemainingSeconds + " с");
    }

    @FXML
    private void onRestStop() {
        restTimeline.stop();
        restRemainingSeconds = 0;
        restTimerLabel.setStyle("");
        restTimerLabel.setText(restText(chosenRestSeconds()));
        statusLabel.setText("Отдых остановлен");
    }

    private void tickRest() {
        restRemainingSeconds--;

        if (restRemainingSeconds > 0) {
            restTimerLabel.setText(restText(restRemainingSeconds));
            return;
        }

        // дошли до нуля: звук + сообщение, оба таймера независимы
        restTimeline.stop();
        restRemainingSeconds = 0;
        restTimerLabel.setStyle("");
        restTimerLabel.setText(restText(0));
        playRestSound();
        Alerts.info("Отдых закончен", "Пора начинать следующий подход.");
    }

    private void beep() {
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (RuntimeException ignored) {
            // звук — не критичная часть, продолжаем молча
        }
    }

    /**
     * Звук конца отдыха: пользовательский файл (mp3/wav) из настроек,
     * иначе системный beep.
     */
    private void playRestSound() {
        stopRestSound();

        String soundPath = context.settings() == null ? null : context.settings().getSoundPath();

        if (soundPath != null && !soundPath.isBlank()) {
            try {
                Media media = new Media(Path.of(soundPath).toUri().toString());
                restSoundPlayer = new MediaPlayer(media);
                restSoundPlayer.setOnError(this::beep);      // файл битый/удалён
                restSoundPlayer.setOnEndOfMedia(this::stopRestSound);
                restSoundPlayer.play();
                return;
            } catch (RuntimeException e) {
                // формат не поддерживается или файла нет — падаем на beep
            }
        }

        beep();
    }

    private void stopRestSound() {
        if (restSoundPlayer != null) {
            restSoundPlayer.stop();
            restSoundPlayer.dispose();
            restSoundPlayer = null;
        }
    }

    /** Отдых показываем как мм:сс, а не чч:мм:сс. */
    private static String restText(int totalSeconds) {
        int safe = Math.max(0, totalSeconds);
        return String.format("%02d:%02d", safe / 60, safe % 60);
    }

    @FXML
    private void onStart() {
        if (timeline.getStatus() == Animation.Status.RUNNING) {
            return; // уже идёт — второй раз не запускаем
        }
        timeline.play();

        // B1: подход, выбранный к моменту старта, начинается сейчас
        SetRow row = setTable.getSelectionModel().getSelectedItem();
        if (row != null) {
            setStartSeconds.putIfAbsent(row.setId(), seconds);
        }
        statusLabel.setText("Таймер идёт");
    }

    @FXML
    private void onPause() {
        timeline.pause();
        statusLabel.setText("Пауза — значение зафиксировано");
    }

    @FXML
    private void onReset() {
        timeline.pause();
        seconds = 0;
        setStartSeconds.clear();    // B1: отметки старта относятся к прежнему отсчёту
        timerLabel.setText(TimeMask.ZERO);
        statusLabel.setText("Таймер сброшен, записанные данные не тронуты");
    }

    /** Пропустить подход (BUG-07): флаг skipped, время не пишем. */
    @FXML
    private void onSkip() {
        SetRow row = setTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            Alerts.warn("Подход не выбран", "Выберите строку в таблице подходов.");
            return;
        }

        try {
            context.workoutService().markSkipped(row.setId());
            setStartSeconds.remove(row.setId());   // B1: пропуск — подход не выполнялся
        } catch (RuntimeException e) {
            Alerts.error("Не удалось пропустить подход", e.getMessage());
            return;
        }

        statusLabel.setText("Пропущено: " + row.exerciseName() + " (подход " + row.setNumber() + ")");
        refreshTable();
        selectNextEmptySet();
    }

    /** Отмена пропуска: подход снова можно заполнять (M1, «отменить пропуск»). */
    @FXML
    private void onUnskip() {
        SetRow row = setTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            Alerts.warn("Подход не выбран", "Выберите строку в таблице подходов.");
            return;
        }
        if (!row.skipped()) {
            Alerts.warn("Пропуска нет", "У выбранного подхода нет отметки «пропущено».");
            return;
        }

        try {
            context.workoutService().unskipSet(row.setId());
        } catch (RuntimeException e) {
            Alerts.error("Не удалось отменить пропуск", e.getMessage());
            return;
        }

        statusLabel.setText("Пропуск отменён: " + row.exerciseName()
                + " (подход " + row.setNumber() + ")");
        refreshTable();
    }

    /** Убирает сортировку по клику на заголовок и возвращает порядок блоков. */
    @FXML
    private void onResetSorting() {
        setTable.getSortOrder().clear();   // убрать стрелочки в заголовках
        refreshTable();                    // дефолтный порядок: блоки и круги
        statusLabel.setText("Порядок упражнений восстановлен");
    }

    /**
     * Записывает текущее время в выбранный подход и переводит фокус
     * на следующую строку без времени. Если таких строк нет — сообщает,
     * что всё заполнено: тренировку завершает пользователь (M12).
     */
    @FXML
    private void onRecord() {
        SetRow row = setTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            Alerts.warn("Подход не выбран", "Выберите строку в таблице подходов.");
            return;
        }
        if (seconds <= 0) {
            Alerts.warn("Таймер не запущен",
                    "Нажмите «Старт» — время подхода пишется с таймера. "
                            + "Ввести время вручную можно прямо в таблице: двойной клик по ячейке «Время».");
            return;
        }

        try {
            // B1: длительность = время от начала ЭТОГО подхода, а не от старта
            // тренировки и не разница с прошлой записью. Время от старта
            // тренировки уходит отдельно — как метка на графике пульса.
            int start = setStartSeconds.getOrDefault(row.setId(), seconds);
            int duration = Math.max(1, seconds - start);

            context.workoutService().updateSet(row.setId(), null, null, null, duration, seconds, null);
            setStartSeconds.remove(row.setId());    // подход записан — начинаем заново
        } catch (RuntimeException e) {
            Alerts.error("Не удалось записать время", e.getMessage());
            return;
        }

        // главный таймер НЕ сбрасываем — пусть идёт своим ходом
        refreshTable();
        selectNextEmptySet();
    }

    // ---------------------------------------------------------------- подходы

    @FXML
    private void onAddExercise() {
        Exercise exercise = exerciseBox.getValue();
        if (exercise == null) {
            Alerts.warn("Упражнение не выбрано", "Выберите упражнение в списке.");
            return;
        }

        try {
            WorkoutExercise link = context.workoutService().addExercise(workoutId, exercise.getId());
            // сразу добавляем пустой подход — есть куда записывать время
            context.workoutService().addSet(link.getId(), null, null, null, null);
        } catch (RuntimeException e) {
            Alerts.error("Не удалось добавить упражнение", e.getMessage());
            return;
        }

        refreshTable();
        statusLabel.setText("Добавлено: " + exercise.getName());
    }

    /**
     * Инвентарь упражнения: окно с галочками, можно отметить несколько
     * предметов сразу («2 чёрные + жёлтая» резинки).
     */
    @FXML
    private void onChangeEquipment() {
        SetRow row = setTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            Alerts.warn("Подход не выбран", "Выберите строку в таблице.");
            return;
        }

        openEquipmentDialog(row);
    }

    /**
     * Инвентарь упражнения в рамках ТЕКУЩЕЙ тренировки (M2/M3).
     *
     * Глобальный справочник здесь не трогаем: правка галочек не должна
     * переписывать ни карточку упражнения, ни шаблоны. Меняется только
     * снимок строки тренировки (workout_exercises.equipment_snapshot).
     */
    private void openEquipmentDialog(SetRow row) {
        List<Equipment> all = context.planService().getAllEquipment();
        if (all.isEmpty()) {
            Alerts.warn("Инвентарь", "Справочник инвентаря пуст.");
            return;
        }

        // что уже стоит в снимке этой тренировки (галочки откроются отмеченными)
        Set<String> current = new HashSet<>();
        if (row.equipment() == null) {
            // снимка нет (старая запись) — подсказываем инвентарь из справочника,
            // но только читаем его, ничего не меняя
            context.planService().getEquipmentForExercise(row.exerciseId()).stream()
                    .map(link -> link.getEquipment().getName())
                    .forEach(current::add);
        } else {
            for (String name : row.equipment().split(",")) {
                if (!name.isBlank()) {
                    current.add(name.trim());
                }
            }
        }

        List<EquipmentChoice> choices = all.stream()
                .map(equipment -> {
                    EquipmentChoice choice = new EquipmentChoice(equipment);
                    choice.setSelected(current.contains(equipment.getName()));
                    return choice;
                })
                .toList();

        ListView<EquipmentChoice> list = new ListView<>(FXCollections.observableArrayList(choices));
        list.setPrefHeight(280);
        list.setCellFactory(CheckBoxListCell.forListView(EquipmentChoice::selectedProperty));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Инвентарь упражнения");
        dialog.setHeaderText("«" + row.exerciseName() + "»\nОтметьте нужные предметы:");

        ButtonType save = new ButtonType("Сохранить", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().setContent(new VBox(8, list));
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        if (setTable.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(setTable.getScene().getStylesheets());
        }

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != save) {
            return;
        }

        List<EquipmentChoice> selected = choices.stream()
                .filter(EquipmentChoice::isSelected)
                .toList();

        String snapshot = selected.isEmpty()
                ? null
                : selected.stream()
                        .map(choice -> choice.equipment().getName())
                        .collect(Collectors.joining(", "));

        try {
            context.workoutService().updateWorkoutEquipment(row.workoutExerciseId(), snapshot);

            statusLabel.setText(selected.isEmpty()
                    ? "Инвентарь убран: " + row.exerciseName()
                    : "Инвентарь сохранён: " + snapshot);
        } catch (RuntimeException e) {
            Alerts.error("Не удалось изменить инвентарь", e.getMessage());
        }

        refreshTable();
    }

    private void refreshTable() {
        // B1: запоминаем выделенную строку по setId — после setAll() таблица
        // создаёт новые объекты SetRow, и «то же самое» выделение теряется.
        SetRow selected = setTable.getSelectionModel().getSelectedItem();
        Long keepId = selected == null ? null : selected.setId();

        Workout workout = context.workoutService().loadWorkoutDetails(workoutId);
        titleLabel.setText("Тренировка · " + workout.getWorkoutDate());

        // 1. Группируем упражнения по блоку из названия: "Гантели 1. ..." -> "Гантели"
        Map<String, List<WorkoutExercise>> blocks = new LinkedHashMap<>();
        for (WorkoutExercise link : workout.getExercises()) {
            String block = blockNameOf(link.getExercise().getName());
            blocks.computeIfAbsent(block, key -> new ArrayList<>()).add(link);
        }

        List<SetRow> rows = new ArrayList<>();

        // 2. Блок за блоком, круг за кругом:
        //    ЛФК(круг 1) -> Гантели(круг 1) -> Координация(круг 1) -> Гантели(круг 2) ...
        for (List<WorkoutExercise> blockLinks : blocks.values()) {
            int maxSets = blockLinks.stream()
                    .mapToInt(link -> link.getSets().size())
                    .max()
                    .orElse(1);

            for (int setNumber = 1; setNumber <= maxSets; setNumber++) {
                for (WorkoutExercise link : blockLinks) {
                    for (WorkoutSet set : link.getSets()) {
                        if (set.getSetNumber() != setNumber) {
                            continue;
                        }
                        Exercise exercise = link.getExercise();
                        rows.add(new SetRow(
                                set.getId(),
                                link.getId(),
                                exercise.getId(),
                                exercise.getName(),
                                set.getSetNumber(),
                                set.getReps(),
                                set.getWeightKg(),
                                set.getDurationSec(),
                                // общее время на секундомере — его показывает колонка «Время»
                                set.getTimeMarkerSec(),
                                set.getNote(),
                                set.isSkipped(),
                                exercise.getSetup(),
                                exercise.getMovement(),
                                exercise.getMuscles(),
                                exercise.getAvoid(),
                                equipmentLine(link, exercise)));
                    }
                }
            }
        }

        setTable.getItems().setAll(rows);

        // B1: возвращаем выделение на ту же строку, если она ещё существует
        if (keepId != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).setId() == keepId) {
                    setTable.getSelectionModel().select(i);
                    setTable.scrollTo(i);
                    break;
                }
            }
        }
    }

    /**
     * Блок упражнения — часть названия до первой цифры:
     * "Гантели 1. Гоблет-присед" -> "Гантели",
     * "ЛФК 1. Кошка-корова" -> "ЛФК",
     * "Планка" (без номера) -> "Другое".
     */
    private static String blockNameOf(String exerciseName) {
        if (exerciseName == null) {
            return "Другое";
        }
        Matcher matcher = BLOCK_PREFIX.matcher(exerciseName);
        return matcher.find() ? matcher.group(1).trim() : "Другое";
    }

    /** «Гантели 8 кг, Скамья (2 шт)» или «Без инвентаря». */
    private static String equipmentText(Exercise exercise) {
        if (exercise.getEquipmentLinks() == null || exercise.getEquipmentLinks().isEmpty()) {
            return "Без инвентаря";
        }

        return exercise.getEquipmentLinks().stream()
                .map(link -> {
                    String name = link.getEquipment().getName();
                    int quantity = link.getQuantity();
                    return quantity > 1 ? name + " (" + quantity + " шт)" : name;
                })
                .collect(Collectors.joining(", "));
    }

    /** Снимок инвентаря на момент тренировки; если его нет — текущий справочник. */
    private static String equipmentLine(WorkoutExercise link, Exercise exercise) {
        return link.getEquipmentSnapshot() != null
                ? link.getEquipmentSnapshot()
                : equipmentText(exercise);
    }

    /**
     * Ищем первую строку без времени, пропуская подходы с флагом «пропущено».
     * Нет такой — просто сообщаем об этом: тренировку завершает пользователь
     * кнопкой «Завершить», авто-завершения нет (M12).
     */
    private void selectNextEmptySet() {
        List<SetRow> rows = setTable.getItems();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).durationSec() == null && !rows.get(i).skipped()) {
                setTable.getSelectionModel().select(i);
                setTable.scrollTo(i);
                statusLabel.setText("Записано. Следующий подход №" + rows.get(i).setNumber());
                return;
            }
        }

        // заполнять больше нечего: кнопка «Завершить» остаётся доступной
        statusLabel.setText("Все подходы заполнены — нажмите «Завершить тренировку»");
    }

    @FXML
    private void onFinish() {
        boolean hasEmpty = setTable.getItems().stream()
                .anyMatch(row -> row.durationSec() == null && !row.skipped());

        // B9: таймеры останавливаем только после подтверждения — иначе отказ
        // «Завершить?» оставлял тренировку с уже стоящими таймерами
        if (hasEmpty && !Alerts.confirm("Завершить тренировку",
                "Есть подходы без времени. Завершить всё равно?")) {
            return;
        }

        timeline.stop();
        if (restTimeline != null) {
            restTimeline.stop();
        }
        stopRestSound();

        try {
            // B2: длительность тренировки считает сервис по started_at -> now,
            // значение главного таймера больше не передаём (оно врёт, если
            // таймер ставили на паузу или перезапускали).
            context.workoutService().finishWorkout(workoutId, null);
        } catch (RuntimeException e) {
            Alerts.error("Не удалось завершить тренировку", e.getMessage());
            return;
        }

        // B5: отметки старта подходов чистим только после успешного завершения —
        // при ошибке тренировка остаётся открытой и таймеры должны продолжить работу
        setStartSeconds.clear();
        context.navigator().showMain();
    }

    @FXML
    private void onBack() {
        // Черновик без единой записи удаляем; завершённую тренировку — никогда (C1).
        Long currentId = context.state().workoutId();
        if (currentId != null
                && context.state().isDraft()
                && context.workoutService().deleteIfEmpty(currentId)) {
            stopTimers();
            context.state().clearWorkout();
            context.navigator().showMain();
            return;
        }

        // B5: пока пользователь не подтвердил выход, таймеры продолжают идти —
        // «Отмена» не должна останавливать тренировку
        if (Alerts.confirm("Выйти из тренировки",
                "Тренировка останется незавершённой в истории. Выйти?")) {
            stopTimers();
            context.navigator().showMain();
        }
    }

    /** Останавливает оба таймера, звук и забывает отметки старта подходов. */
    private void stopTimers() {
        timeline.stop();
        if (restTimeline != null) {
            restTimeline.stop();
        }
        stopRestSound();
        setStartSeconds.clear();
    }

    // ------------------------------------------------------------------ таблица

    private void bindTableColumns() {
        colExercise.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().exerciseName()));
        colSetNumber.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().setNumber()));
        colReps.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().reps()));
        colWeight.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().weightKg()));
        colTime.setCellValueFactory(cd -> {
            // B: в колонке «Время» — показание секундомера тренировки (общее время
            // от старта), а не длительность подхода. Для старых записей, где метки
            // нет, показываем длительность.
            Integer time = cd.getValue().timeMarkerSec() != null
                    ? cd.getValue().timeMarkerSec()
                    : cd.getValue().durationSec();
            return new ReadOnlyStringWrapper(time == null ? "" : TimeMask.format(time));
        });
        colEquipment.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().equipment()));

        // двойной клик по ячейке открывает окно с галочками (несколько предметов сразу)
        colEquipment.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? "" : value);

                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2
                            && !isEmpty()
                            && getTableRow() != null
                            && getTableRow().getItem() != null) {
                        openEquipmentDialog(getTableRow().getItem());
                    }
                });
            }
        });
        colNote.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                cd.getValue().note() == null ? "" : cd.getValue().note()));

        // ---------- повторы (пусто = стереть, мусор = предупреждение, M4) ----------
        colReps.setCellFactory(TextFieldTableCell.forTableColumn(REPS_CONVERTER));
        colReps.setOnEditCommit(event -> {
            long setId = event.getRowValue().setId();
            String raw = REPS_CONVERTER.lastText();

            try {
                if (raw == null || raw.isBlank()) {
                    context.workoutService().clearReps(setId);
                } else if (event.getNewValue() == null) {
                    Alerts.warn("Повторы",
                            "«" + raw.trim() + "» — это не число. Введите целое число, например 12.");
                    refreshTable();
                    return;
                } else {
                    context.workoutService().updateSet(setId,
                            event.getNewValue(), null, null, null, null);
                }
            } catch (RuntimeException e) {
                Alerts.error("Ошибка сохранения повторов", e.getMessage());
            }
            refreshTable();
        });

        // ---------- вес (принимает «72,5» и «72.5»; пусто = стереть, M4) ----------
        colWeight.setCellFactory(TextFieldTableCell.forTableColumn(WEIGHT_CONVERTER));
        colWeight.setOnEditCommit(event -> {
            long setId = event.getRowValue().setId();
            String raw = WEIGHT_CONVERTER.lastText();

            try {
                if (raw == null || raw.isBlank()) {
                    context.workoutService().clearWeight(setId);
                } else if (event.getNewValue() == null) {
                    Alerts.warn("Вес",
                            "«" + raw.trim() + "» — это не число. Введите вес, например 72,5.");
                    refreshTable();
                    return;
                } else {
                    context.workoutService().updateSet(setId,
                            null, event.getNewValue(), null, null, null);
                }
            } catch (RuntimeException e) {
                Alerts.error("Ошибка сохранения веса", e.getMessage());
            }
            refreshTable();
        });

        // ---------- время (чч:мм:сс или мм:сс; пусто = стереть) ----------
        colTime.setCellFactory(TextFieldTableCell.forTableColumn());
        colTime.setOnEditCommit(event -> {
            String text = event.getNewValue();
            long setId = event.getRowValue().setId();

            // B1: время поправили вручную — отметка старта подхода больше не актуальна
            setStartSeconds.remove(setId);

            if (text == null || text.isBlank()) {
                try {
                    context.workoutService().clearDuration(setId);
                } catch (RuntimeException e) {
                    Alerts.error("Ошибка очистки времени", e.getMessage());
                }
                refreshTable();
                return;
            }

            int newSeconds = TimeMask.toSeconds(text);
            if (newSeconds <= 0) {
                Alerts.warn("Время", "Введите время как чч:мм:сс, например 00:01:30.");
                refreshTable();
                return;
            }

            try {
                // B3: колонка показывает метку секундомера, если она есть —
                // правим только её и не переписываем длительность подхода.
                // Для старых записей (метки нет) правим длительность.
                if (event.getRowValue().timeMarkerSec() != null) {
                    context.workoutService().updateTimeMarker(setId, newSeconds);
                } else {
                    context.workoutService().updateDuration(setId, newSeconds);
                }
            } catch (RuntimeException e) {
                Alerts.error("Ошибка сохранения времени", e.getMessage());
            }
            refreshTable();
        });

        // ---------- заметка ----------
        colNote.setCellFactory(TextFieldTableCell.forTableColumn());
        colNote.setOnEditCommit(event -> {
            try {
                context.workoutService().updateSet(event.getRowValue().setId(),
                        null, null, null, null, event.getNewValue());
            } catch (RuntimeException e) {
                Alerts.error("Ошибка сохранения заметки", e.getMessage());
            }
            refreshTable();
        });
    }

    private void loadExercises() {
        exerciseBox.getItems().setAll(context.catalogService().exercises());

        exerciseBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Exercise exercise) {
                return exercise == null ? "" : exercise.getName();
            }

            @Override
            public Exercise fromString(String string) {
                return null; // ввод вручную не нужен
            }
        });

        exerciseBox.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Exercise exercise, boolean empty) {
                super.updateItem(exercise, empty);
                setText(empty || exercise == null ? null : exercise.getName());
            }
        });
    }

    // ---------------------------------------------------------- карточка справа

    /** Минимальная высота текстового поля карточки (одна-две строки текста). */
    private static final double MIN_DESCRIPTION_HEIGHT = 46;

    /** Запас на внутренние отступы TextArea, чтобы не появилась полоса прокрутки. */
    private static final double DESCRIPTION_PADDING = 14;

    /**
     * Ширина вертикальной полосы прокрутки.
     *
     * Резервируем её заранее: иначе высота считается «без прокрутки», полоса
     * появляется, ширина текста уменьшается, высота снова растёт — и макет
     * начинает дёргаться. С резервом расчёт устойчив в обе стороны.
     */
    private static final double SCROLLBAR_RESERVE = 18;

    /**
     * Готовит текстовые поля карточки упражнения.
     *
     * Задача: убрать внутренние полосы прокрутки и дать полям занять высоту
     * карточки. Для этого высота каждого TextArea привязывается к высоте его
     * текста: поле всегда ровно по содержимому (плюс отступы), поэтому скролл
     * внутри не нужен. Если текста больше, чем влезает в карточку, прокрутка
     * будет одна — внешняя, у ScrollPane вокруг карточки.
     *
     * Свободное место по вертикали поля делят между собой (VBox.vgrow="ALWAYS"
     * в FXML + fitToHeight у ScrollPane).
     */
    private void configureDescriptionFields() {
        for (TextArea area : List.of(setupLabel, movementLabel, musclesLabel, avoidLabel)) {
            area.setWrapText(true);
            area.setMaxHeight(Double.MAX_VALUE);
            bindHeightToProbe(area);
        }
    }

    /**
     * Высота поля = высота его текста (+ отступы), поэтому внутренняя полоса
     * прокрутки не появляется: полю всегда хватает места по содержимому.
     *
     * Привязываем и prefHeight, и minHeight: VBox при нехватке места сжимает
     * детей до minHeight, и если оставить minHeight маленьким, поле сожмётся
     * и снова покажет внутренний скролл. С привязкой minHeight карточка честно
     * становится выше окна, а прокрутка остаётся одна — внешняя, у ScrollPane.
     *
     * Ширину переноса считаем от ширины поля минус отступы и минус полоса
     * прокрутки (см. {@link #SCROLLBAR_RESERVE}) — так расчёт не «прыгает».
     */
    private static void bindHeightToProbe(TextArea area) {
        Text probe = new Text();
        probe.fontProperty().bind(area.fontProperty());
        probe.textProperty().bind(area.textProperty());
        probe.wrappingWidthProperty().bind(Bindings.max(
                area.widthProperty().subtract(DESCRIPTION_PADDING * 2 + SCROLLBAR_RESERVE), 60));

        DoubleBinding height = Bindings.createDoubleBinding(
                () -> Math.max(MIN_DESCRIPTION_HEIGHT,
                        probe.getLayoutBounds().getHeight() + DESCRIPTION_PADDING * 2),
                probe.layoutBoundsProperty());

        area.minHeightProperty().bind(height);
        area.prefHeightProperty().bind(height);
    }

    private void showExerciseInfo(SetRow row) {
        if (row == null) {
            exerciseNameLabel.setText("—");
            setupLabel.setText("—");
            movementLabel.setText("—");
            musclesLabel.setText("—");
            avoidLabel.setText("—");
            descriptionScroll.setVvalue(0);
            return;
        }

        exerciseNameLabel.setText(row.exerciseName());
        setupLabel.setText(cleanSetup(row.setup()));
        movementLabel.setText(valueOrDash(row.movement()));
        musclesLabel.setText(valueOrDash(row.muscles()));
        avoidLabel.setText(valueOrDash(row.avoid()));

        // новое упражнение читаем сверху, а не с середины прошлого текста
        descriptionScroll.setVvalue(0);
    }

    /** Префикс блока: буквы и пробелы до первой цифры («Гантели 1. …» -> «Гантели»). */
    private static final Pattern BLOCK_PREFIX =
            Pattern.compile("^([А-Яа-яЁёA-Za-z\\s]+?)(?=\\s*\\d)");

    /**
     * Повторы: пусто или мусор -> null (сам разбор «пусто/мусор» делает
     * {@code onEditCommit} через {@link #lastText()}, см. BUG M4).
     */
    private static final class RepsConverter extends StringConverter<Integer> {

        /** Сырой текст последнего ввода: null/blank — «стереть», иначе — значение. */
        private String lastText;

        @Override
        public String toString(Integer value) {
            return value == null || value == 0 ? "" : value.toString();
        }

        @Override
        public Integer fromString(String text) {
            lastText = text;
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String lastText() {
            return lastText;
        }
    }

    private static final RepsConverter REPS_CONVERTER = new RepsConverter();

    /** Вес: «72,5» и «72.5» -> 72.5; пусто или мусор -> null. */
    private static final class WeightConverter extends StringConverter<BigDecimal> {

        /** Сырой текст последнего ввода: null/blank — «стереть», иначе — значение. */
        private String lastText;

        @Override
        public String toString(BigDecimal value) {
            return value == null || value.signum() == 0 ? "" : value.stripTrailingZeros().toPlainString();
        }

        @Override
        public BigDecimal fromString(String text) {
            lastText = text;
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(text.trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String lastText() {
            return lastText;
        }
    }

    private static final WeightConverter WEIGHT_CONVERTER = new WeightConverter();

    /** Префикс ИП в разных написаниях: «ИП:», «ИП », «и.п.», «И. П.» (регистр не важен). */
    private static final Pattern SETUP_PREFIX =
            Pattern.compile("(?iu)^(?:и\\.?\\s?п\\.?)\\s*:?\\s*\\b");

    /**
     * В БД описание часто начинается с «ИП: ...» — в интерфейсе заголовок
     * уже есть, поэтому префикс убираем, чтобы не было «Исходное положение: ИП: ...».
     */
    private static String cleanSetup(String text) {
        if (text == null) {
            return "—";
        }
        String clean = SETUP_PREFIX.matcher(text.trim()).replaceFirst("").trim();
        return clean.isBlank() ? "—" : clean;
    }

    private static String valueOrDash(String value) {
        return (value == null || value.isBlank()) ? "—" : value;
    }
    /** BUG-17: при уходе с экрана останавливаем таймеры и звук. */
    @Override
    public void dispose() {
        if (timeline != null) {
            timeline.stop();
        }
        if (restTimeline != null) {
            restTimeline.stop();
        }
        stopRestSound();
        setStartSeconds.clear();
    }
}
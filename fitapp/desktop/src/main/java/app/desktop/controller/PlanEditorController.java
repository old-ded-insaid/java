package app.desktop.controller;

import app.desktop.AppContext;
import app.desktop.ui.Alerts;
import app.desktop.ui.EquipmentRow;
import app.desktop.ui.PlanExerciseRow;
import app.model.Equipment;
import app.model.Exercise;
import app.model.PlanExercise;
import app.model.WorkoutPlan;
import app.service.ValidationException;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Конструктор шаблона тренировки.
 *
 * Экран ничего не знает про SQL и EntityManager: все действия идут через
 * PlanService. Логика экрана — только «что показать» и «что отправить в сервис».
 *
 * Сценарий:
 *   1. Ввести название (и описание) шаблона.
 *   2. «Добавить упражнение» → выбрать из справочника ИЛИ создать новое.
 *   3. Указать подходы/повторы → «Добавить в шаблон».
 *   4. «Сохранить шаблон».
 *
 * Если нажать «Добавить в шаблон» до сохранения, шаблон создастся сам
 * (ensurePlanSaved) — чтобы не заставлять пользователя делать лишний шаг.
 */
public class PlanEditorController {

    private final AppContext context;

    // ---------------------------------------------------------- левая колонка
    @FXML private ComboBox<WorkoutPlan> planBox;
    @FXML private TextField nameField;
    @FXML private TextArea descriptionArea;
    @FXML private Label statusLabel;

    // -------------------------------------------------------- таблица состава
    @FXML private TableView<PlanExerciseRow> exerciseTable;
    @FXML private TableColumn<PlanExerciseRow, Integer> colIndex;
    @FXML private TableColumn<PlanExerciseRow, String> colExercise;
    @FXML private TableColumn<PlanExerciseRow, Integer> colSets;
    @FXML private TableColumn<PlanExerciseRow, Integer> colReps;
    @FXML private TableColumn<PlanExerciseRow, BigDecimal> colWeight;
    @FXML private TableColumn<PlanExerciseRow, String> colEquipment;

    // ------------------------------------------- панель добавления упражнения
    @FXML private ScrollPane addPanelScroll;
    @FXML private ComboBox<Exercise> exerciseBox;
    @FXML private CheckBox newExerciseCheck;
    @FXML private TextField newNameField;
    @FXML private TextArea setupArea;
    @FXML private TextArea movementArea;
    @FXML private TextArea musclesArea;
    @FXML private TextArea avoidArea;
    @FXML private ComboBox<Equipment> equipmentBox;
    @FXML private ListView<EquipmentRow> equipmentList;
    @FXML private Button equipmentAttachButton;
    @FXML private Button equipmentDetachButton;
    @FXML private Spinner<Integer> quantitySpinner;
    @FXML private Spinner<Integer> setsSpinner;
    @FXML private Spinner<Integer> repsSpinner;
    @FXML private TextField targetWeightField;

    // ------------------------------------------------ импорт шаблона из JSON
    @FXML private StackPane importOverlay;
    @FXML private TextArea importJsonArea;

    /** null — шаблон ещё не сохранён в БД. */
    private Long planId;

    /** Пока true, слушатели не реагируют на программное заполнение полей. */
    private boolean loading;

    /** Инвентарь для ещё не созданного упражнения: прикрепим при сохранении. */
    private final List<EquipmentRow> pendingEquipment = new ArrayList<>();

    /** Полный справочник упражнений: нужен для фильтрации при вводе. */
    private final List<Exercise> allExercises = new ArrayList<>();

    /** true, пока текст в поле упражнения меняется программно (выбор из списка). */
    private boolean updatingExerciseText;

    public PlanEditorController(AppContext context) {
        this.context = context;
    }

    // ========================================================================
    //  Инициализация
    // ========================================================================

    @FXML
    private void initialize() {
        configureSpinners();

        configureCombo(planBox, WorkoutPlan::getName);
        configureCombo(exerciseBox, Exercise::getName);

        // поиск по названию: ComboBox редактируемый, начинаем печатать — список фильтруется
        exerciseBox.setEditable(true);
        exerciseBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Exercise exercise) {
                return exercise == null ? "" : exercise.getName();
            }

            @Override
            public Exercise fromString(String text) {
                if (text == null || text.isBlank()) {
                    return null;
                }
                String query = text.trim();
                return allExercises.stream()
                        .filter(exercise -> exercise.getName().equalsIgnoreCase(query))
                        .findFirst()
                        .orElse(null);
            }
        });

        exerciseBox.getEditor().textProperty().addListener((observable, oldText, newText) -> {
            if (updatingExerciseText) {
                return; // текст выставлен выбором из списка — не фильтруем
            }
            filterExercises(newText);
        });
        configureCombo(equipmentBox, Equipment::getName);

        configureTable();

        planBox.valueProperty().addListener((observable, oldPlan, newPlan) -> {
            if (!loading && newPlan != null) {
                openPlan(newPlan);
            }
        });

        newExerciseCheck.selectedProperty().addListener(
                (observable, oldValue, isNew) -> applyNewMode(isNew));

        exerciseBox.valueProperty().addListener((observable, oldExercise, newExercise) -> {
            // текст в поле ставим сами, чтобы не запускать фильтр повторно
            if (newExercise != null) {
                updatingExerciseText = true;
                exerciseBox.getEditor().setText(newExercise.getName());
                updatingExerciseText = false;
            }

            if (!newExerciseCheck.isSelected()) {
                fillDetails(newExercise);
                refreshEquipmentList();
            }
            updateEquipmentState();
        });

        loadPlans();
        loadExercises();
        equipmentBox.getItems().setAll(context.planService().getAllEquipment());

        hideAddPanel();
        applyNewMode(false);
        updateEquipmentState();
        statusLabel.setText("Введите название шаблона и добавьте упражнения.");
    }

    private void configureSpinners() {
        quantitySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 1));
        setsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 50, 3));
        repsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, 12));
    }

    /** Одинаковый вид ComboBox: и в поле, и в выпадающем списке — имя, а не toString(). */
    private static <T> void configureCombo(ComboBox<T> combo, Function<T, String> nameOf) {
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : nameOf.apply(value);
            }

            @Override
            public T fromString(String string) {
                return null; // ручной ввод не нужен
            }
        });

        combo.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : nameOf.apply(item));
            }
        });
    }

    private void configureTable() {
        colIndex.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().orderIndex()));
        colExercise.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().exerciseName()));
        colSets.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().targetSets()));
        colReps.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().targetReps()));
        colWeight.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().targetWeightKg()));
        colEquipment.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().equipment()));

        // вес без лишних нулей; не задан — пусто
        colWeight.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null || value.signum() == 0
                        ? ""
                        : value.stripTrailingZeros().toPlainString());
            }
        });

        // двойной клик открывает описание упражнения в боковой панели
        exerciseTable.setRowFactory(view -> {
            TableRow<PlanExerciseRow> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    showExerciseDetails(row.getItem());
                }
            });
            return row;
        });
    }

    // ========================================================================
    //  Шаблон: загрузка, создание, сохранение
    // ========================================================================

    private void loadPlans() {
        List<WorkoutPlan> plans = context.planService().plans(context.userId());

        loading = true;
        planBox.getItems().setAll(plans);
        if (planId != null) {
            plans.stream()
                    .filter(plan -> plan.getId().equals(planId))
                    .findFirst()
                    .ifPresent(planBox::setValue);
        }
        loading = false;
    }

    private void openPlan(WorkoutPlan plan) {
        planId = plan.getId();
        nameField.setText(plan.getName());
        descriptionArea.setText(plan.getDescription() == null ? "" : plan.getDescription());
        refreshExercises();
        statusLabel.setText("Редактируется: " + plan.getName());
    }

    @FXML
    private void onNewPlan() {
        planId = null;
        nameField.clear();
        descriptionArea.clear();
        exerciseTable.getItems().clear();

        loading = true;
        planBox.getSelectionModel().clearSelection();
        loading = false;

        statusLabel.setText("Новый шаблон: введите название и добавьте упражнения.");
    }

    @FXML
    private void onSavePlan() {
        try {
            WorkoutPlan saved = (planId == null)
                    ? context.planService().createPlan(context.userId(),
                            nameField.getText(), descriptionArea.getText())
                    : context.planService().updatePlan(planId,
                            nameField.getText(), descriptionArea.getText());

            planId = saved.getId();
            loadPlans();
            refreshExercises();
            statusLabel.setText("Шаблон сохранён: " + saved.getName());
        } catch (RuntimeException e) {
            Alerts.error("Не удалось сохранить шаблон", e.getMessage());
        }
    }

    /** Возвращает id шаблона, создавая его при необходимости. */
    private long ensurePlanSaved() {
        if (planId != null) {
            context.planService().updatePlan(planId, nameField.getText(), descriptionArea.getText());
            return planId;
        }

        WorkoutPlan created = context.planService()
                .createPlan(context.userId(), nameField.getText(), descriptionArea.getText());
        planId = created.getId();
        loadPlans();
        return planId;
    }

    // ---------------------------------------------------------- импорт JSON

    @FXML
    private void onShowImport() {
        importJsonArea.clear();
        importOverlay.setVisible(true);
        importOverlay.setManaged(true);
        importJsonArea.requestFocus();
        statusLabel.setText("Вставьте JSON и нажмите «Импортировать».");
    }

    @FXML
    private void onCancelImport() {
        importOverlay.setVisible(false);
        importOverlay.setManaged(false);
        statusLabel.setText("Импорт отменён.");
    }

    @FXML
    private void onImportJson() {
        // B6: импорт создаёт упражнения и шаблон — не запускаем его параллельно
        // с другим экспортом/импортом и не держим UI на время разбора
        if (!context.tryStartJob()) {
            Alerts.warn("Операция уже идёт",
                    "Дождитесь завершения текущего экспорта или импорта.");
            return;
        }

        String json = importJsonArea.getText();

        Task<WorkoutPlan> importTask = new Task<>() {
            @Override
            protected WorkoutPlan call() {
                return context.planService().importPlanFromJson(json);
            }
        };

        importTask.setOnSucceeded(event -> {
            context.finishJob();
            WorkoutPlan imported = importTask.getValue();

            importOverlay.setVisible(false);
            importOverlay.setManaged(false);
            importJsonArea.clear();

            // показываем импортированный шаблон сразу
            planId = imported.getId();
            loadPlans();
            openPlan(imported);

            statusLabel.setText("Импортирован шаблон «" + imported.getName() + "»: упражнений — "
                    + exerciseTable.getItems().size());
        });

        importTask.setOnFailed(event -> {
            context.finishJob();
            // сюда приходят понятные сообщения сервиса:
            // пустой JSON, битый JSON, нет name, пустой exercises, подходы <= 0
            Throwable error = importTask.getException();
            Alerts.error("Импорт не удался",
                    error == null ? "Неизвестная ошибка" : error.getMessage());
        });

        Thread worker = new Thread(importTask, "fitapp-import-plan");
        worker.setDaemon(true);
        worker.start();
    }

    // ========================================================================
    //  Состав шаблона
    // ========================================================================

    private void refreshExercises() {
        if (planId == null) {
            exerciseTable.getItems().clear();
            return;
        }

        List<PlanExerciseRow> rows = context.planService().planExercises(planId).stream()
                .map(link -> new PlanExerciseRow(
                        link.getId(),
                        link.getOrderIndex(),
                        link.getExercise().getId(),
                        link.getExercise().getName(),
                        link.getTargetSets(),
                        link.getTargetReps(),
                        link.getTargetWeightKg(),
                        equipmentText(link.getExercise())))
                .toList();

        exerciseTable.getItems().setAll(rows);
    }

    @FXML
    private void onRemoveExercise() {
        PlanExerciseRow row = exerciseTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            Alerts.warn("Упражнение не выбрано", "Выберите строку в таблице.");
            return;
        }

        boolean confirmed = Alerts.confirm("Удалить из шаблона",
                "Убрать «" + row.exerciseName() + "» из шаблона? "
                        + "Само упражнение останется в справочнике.");
        if (!confirmed) {
            return;
        }

        try {
            context.planService().removeFromPlan(row.planExerciseId());
            refreshExercises();
            statusLabel.setText("Удалено из шаблона: " + row.exerciseName());
        } catch (RuntimeException e) {
            Alerts.error("Не удалось удалить упражнение", e.getMessage());
        }
    }

    // ========================================================================
    //  Боковая панель: добавить упражнение / описание
    // ========================================================================

    @FXML
    private void onShowAddPanel() {
        showAddPanel();
        statusLabel.setText(planId == null
                ? "Новое упражнение будет добавлено в новый шаблон."
                : "Добавление упражнения в «" + nameField.getText() + "».");
    }

    @FXML
    private void onConfirmAdd() {
        try {
            long id = ensurePlanSaved();
            Exercise exercise = resolveExercise();

            PlanExercise link = context.planService().addExerciseToPlan(id, exercise.getId(), 0);
            context.planService().setTargets(link.getId(),
                    setsSpinner.getValue(), repsSpinner.getValue());
            context.planService().setTargetWeight(link.getId(),
                    parseWeight(targetWeightField.getText()));

            loadExercises();       // новое упражнение должно появиться в справочнике
            refreshExercises();
            hideAddPanel();

            // сбрасываем панель в режим «выбрать из справочника»
            newExerciseCheck.setSelected(false);
            statusLabel.setText("Добавлено в шаблон: " + exercise.getName());
        } catch (RuntimeException e) {
            Alerts.error("Не удалось добавить упражнение", e.getMessage());
        }
    }

    /** Берёт выбранное упражнение или создаёт новое из заполненных полей. */
    private Exercise resolveExercise() {
        if (!newExerciseCheck.isSelected()) {
            Exercise selected = exerciseBox.getValue();
            if (selected == null) {
                throw new ValidationException(
                        "Выберите упражнение из списка или отметьте «Создать новое»");
            }
            return selected;
        }

        Exercise created = context.planService().createExercise(
                newNameField.getText(),
                setupArea.getText(),
                movementArea.getText(),
                musclesArea.getText(),
                avoidArea.getText());

        // инвентарь, накопленный в списке, привязываем к новому упражнению
        for (EquipmentRow row : pendingEquipment) {
            context.planService().addEquipment(created.getId(), row.equipmentId(), row.quantity());
        }
        pendingEquipment.clear();

        return created;
    }

    @FXML
    private void onSaveDetails() {
        PlanExerciseRow row = exerciseTable.getSelectionModel().getSelectedItem();

        if (row == null || newExerciseCheck.isSelected() || exerciseBox.getValue() == null) {
            Alerts.warn("Упражнение не выбрано",
                    "Двойной клик по строке таблицы — затем правьте описание и параметры.");
            return;
        }

        try {
            Exercise saved = context.planService().saveExerciseDetails(
                    exerciseBox.getValue().getId(),
                    setupArea.getText(),
                    movementArea.getText(),
                    musclesArea.getText(),
                    avoidArea.getText());

            // заодно сохраняем подходы/повторы/вес для этой строки шаблона
            context.planService().setTargets(row.planExerciseId(),
                    setsSpinner.getValue(), repsSpinner.getValue());
            context.planService().setTargetWeight(row.planExerciseId(),
                    parseWeight(targetWeightField.getText()));

            long keepId = row.planExerciseId();
            refreshExercises();
            exerciseTable.getItems().stream()
                    .filter(item -> item.planExerciseId() == keepId)
                    .findFirst()
                    .ifPresent(item -> exerciseTable.getSelectionModel().select(item));

            statusLabel.setText("Сохранено: " + saved.getName());
        } catch (RuntimeException e) {
            Alerts.error("Не удалось сохранить упражнение", e.getMessage());
        }
    }

    @FXML
    private void onMoveUp() {
        moveSelected(-1);
    }

    @FXML
    private void onMoveDown() {
        moveSelected(1);
    }

    /** Перемещение строки шаблона и повторное выделение, чтобы не потерять её. */
    private void moveSelected(int direction) {
        PlanExerciseRow row = exerciseTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            Alerts.warn("Упражнение не выбрано", "Выберите строку в таблице.");
            return;
        }

        try {
            context.planService().movePlanExercise(row.planExerciseId(), direction);
        } catch (RuntimeException e) {
            Alerts.error("Не удалось переместить упражнение", e.getMessage());
            return;
        }

        long keepId = row.planExerciseId();
        refreshExercises();

        exerciseTable.getItems().stream()
                .filter(item -> item.planExerciseId() == keepId)
                .findFirst()
                .ifPresent(item -> {
                    exerciseTable.getSelectionModel().select(item);
                    exerciseTable.scrollTo(item);
                });

        statusLabel.setText("Порядок обновлён: «" + row.exerciseName() + "»");
    }

    // ------------------------------------------------------------ инвентарь

    private void refreshEquipmentList() {
        if (newExerciseCheck.isSelected()) {
            equipmentList.getItems().setAll(pendingEquipment);
            updateEquipmentState();
            return;
        }

        Exercise exercise = exerciseBox.getValue();
        if (exercise == null) {
            equipmentList.getItems().clear();
            updateEquipmentState();
            return;
        }

        List<EquipmentRow> rows = context.planService().getEquipmentForExercise(exercise.getId()).stream()
                .map(link -> new EquipmentRow(
                        link.getEquipment().getId(),
                        equipmentText(link.getEquipment().getName(), link.getQuantity()),
                        link.getQuantity()))
                .toList();

        equipmentList.getItems().setAll(rows);
        updateEquipmentState();
    }

    @FXML
    private void onAttachEquipment() {
        Equipment equipment = equipmentBox.getValue();
        if (equipment == null) {
            Alerts.warn("Инвентарь", "Выберите инвентарь в списке.");
            return;
        }

        int quantity = quantitySpinner.getValue() == null ? 1 : quantitySpinner.getValue();
        EquipmentRow row = new EquipmentRow(equipment.getId(),
                equipmentText(equipment.getName(), quantity), quantity);

        // новое упражнение ещё не в БД — запомним и прикрепим при создании
        if (newExerciseCheck.isSelected()) {
            pendingEquipment.removeIf(item -> item.equipmentId() == equipment.getId());
            pendingEquipment.add(row);
            refreshEquipmentList();
            statusLabel.setText("Инвентарь будет прикреплён после создания упражнения");
            return;
        }

        Exercise exercise = exerciseBox.getValue();
        if (exercise == null) {
            Alerts.warn("Инвентарь", "Сначала выберите упражнение.");
            return;
        }

        try {
            context.planService().addEquipment(exercise.getId(), equipment.getId(), quantity);
            refreshEquipmentList();
            statusLabel.setText("Инвентарь прикреплён: " + equipment.getName());
        } catch (RuntimeException e) {
            Alerts.error("Не удалось прикрепить инвентарь", e.getMessage());
        }
    }

    @FXML
    private void onDetachEquipment() {
        EquipmentRow row = equipmentList.getSelectionModel().getSelectedItem();
        if (row == null) {
            Alerts.warn("Инвентарь", "Выберите строку в списке инвентаря.");
            return;
        }

        if (newExerciseCheck.isSelected()) {
            pendingEquipment.removeIf(item -> item.equipmentId() == row.equipmentId());
            refreshEquipmentList();
            return;
        }

        Exercise exercise = exerciseBox.getValue();
        if (exercise == null) {
            return;
        }

        try {
            context.planService().removeEquipment(exercise.getId(), row.equipmentId());
            refreshEquipmentList();
            statusLabel.setText("Инвентарь убран");
        } catch (RuntimeException e) {
            Alerts.error("Не удалось убрать инвентарь", e.getMessage());
        }
    }

    private void updateEquipmentState() {
        boolean canAttach = newExerciseCheck.isSelected() || exerciseBox.getValue() != null;
        equipmentAttachButton.setDisable(!canAttach);
        equipmentDetachButton.setDisable(equipmentList.getItems().isEmpty());
    }

    private static String equipmentText(String name, int quantity) {
        return quantity > 1 ? name + " (" + quantity + " шт)" : name;
    }

    /** «8», «8,5», пусто или мусор -> null (вес не задан). */
    private static BigDecimal parseWeight(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Инвентарь упражнения одной строкой для колонки таблицы. */
    private static String equipmentText(Exercise exercise) {
        if (exercise.getEquipmentLinks() == null || exercise.getEquipmentLinks().isEmpty()) {
            return "—";
        }
        return exercise.getEquipmentLinks().stream()
                .map(link -> equipmentText(link.getEquipment().getName(), link.getQuantity()))
                .collect(Collectors.joining(", "));
    }

    @FXML
    private void onCancelPanel() {
        hideAddPanel();        statusLabel.setText("Панель закрыта.");
    }

    private void showExerciseDetails(PlanExerciseRow row) {
        showAddPanel();

        // режим «из справочника», чтобы поля показывали описание выбранного упражнения
        newExerciseCheck.setSelected(false);

        Exercise exercise = exerciseBox.getItems().stream()
                .filter(item -> item.getId().equals(row.exerciseId()))
                .findFirst()
                .orElse(null);

        exerciseBox.setValue(exercise);
        fillDetails(exercise);

        // подходы/повторы/вес этого упражнения в шаблоне
        setsSpinner.getValueFactory().setValue(row.targetSets() == null ? 1 : row.targetSets());
        repsSpinner.getValueFactory().setValue(row.targetReps() == null ? 1 : row.targetReps());
        targetWeightField.setText(row.targetWeightKg() == null || row.targetWeightKg().signum() == 0
                ? ""
                : row.targetWeightKg().stripTrailingZeros().toPlainString());

        statusLabel.setText("Редактируется: " + row.exerciseName());
    }

    private void showAddPanel() {
        addPanelScroll.setVisible(true);
        addPanelScroll.setManaged(true);
    }

    private void hideAddPanel() {
        addPanelScroll.setVisible(false);
        addPanelScroll.setManaged(false);
    }

    // ========================================================================
    //  Режимы и заполнение полей
    // ========================================================================

    private void applyNewMode(boolean isNew) {
        exerciseBox.setDisable(isNew);
        newNameField.setDisable(!isNew);
        // инвентарь нужен в обоих режимах: у существующего — привязка, у нового — список в ожидании
        equipmentBox.setDisable(false);
        quantitySpinner.setDisable(false);

        if (isNew) {
            updatingExerciseText = true;
            exerciseBox.getSelectionModel().clearSelection();
            exerciseBox.getEditor().clear();
            updatingExerciseText = false;
            newNameField.clear();
            clearDetails();
        } else {
            fillDetails(exerciseBox.getValue());
        }

        refreshEquipmentList();
        updateEquipmentState();
    }

    private void fillDetails(Exercise exercise) {
        if (exercise == null) {
            clearDetails();
            return;
        }
        setupArea.setText(nullSafe(exercise.getSetup()));
        movementArea.setText(nullSafe(exercise.getMovement()));
        musclesArea.setText(nullSafe(exercise.getMuscles()));
        avoidArea.setText(nullSafe(exercise.getAvoid()));
    }

    private void clearDetails() {
        setupArea.clear();
        movementArea.clear();
        musclesArea.clear();
        avoidArea.clear();
    }

    private void loadExercises() {
        Long keepId = exerciseBox.getValue() == null ? null : exerciseBox.getValue().getId();

        allExercises.clear();
        allExercises.addAll(context.planService().getAllExercises());

        updatingExerciseText = true;
        exerciseBox.setItems(FXCollections.observableArrayList(allExercises));
        exerciseBox.getSelectionModel().clearSelection();
        exerciseBox.getEditor().clear();
        updatingExerciseText = false;

        if (keepId != null) {
            allExercises.stream()
                    .filter(exercise -> exercise.getId().equals(keepId))
                    .findFirst()
                    .ifPresent(exerciseBox::setValue);
        }
    }

    /**
     * Фильтр «на лету»: печатаем «жим» — остаются только упражнения,
     * в названии которых есть «жим» (регистр не важен).
     */
    private void filterExercises(String text) {
        if (allExercises.isEmpty()) {
            return;
        }

        List<Exercise> filtered;
        if (text == null || text.isBlank()) {
            filtered = new ArrayList<>(allExercises);
        } else {
            String query = text.trim().toLowerCase();
            filtered = allExercises.stream()
                    .filter(exercise -> exercise.getName().toLowerCase().contains(query))
                    .toList();
        }

        exerciseBox.setItems(FXCollections.observableArrayList(filtered));

        // список открываем, только если пользователь реально печатает в поле
        if (exerciseBox.getEditor().isFocused() && !filtered.isEmpty()) {
            exerciseBox.show();
        }
    }

    // ========================================================================

    @FXML
    private void onBack() {
        context.navigator().showMain();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

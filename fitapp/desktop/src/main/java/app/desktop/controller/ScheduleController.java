package app.desktop.controller;

import app.desktop.AppContext;
import app.desktop.ui.Alerts;
import app.desktop.ui.PlanChoice;
import app.model.Schedule;
import app.model.WorkoutPlan;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Расписание: создание, редактирование и удаление правил.
 *
 * Форма работает в двух режимах:
 *   создание  — editingId == null, кнопка «Создать»;
 *   изменение — editingId != null, кнопка «Сохранить изменения».
 *
 * «Начинать с тренировки» показывает шаблоны, отмеченные галочками,
 * и определяет, с какого места начинается ротация (schedule.start_plan_id).
 */
public class ScheduleController {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final AppContext context;

    // --------------------------------------------------------- форма правила
    @FXML private TextField nameField;
    @FXML private DatePicker startDateField;
    @FXML private Spinner<Integer> intervalSpinner;
    @FXML private ListView<PlanChoice> planList;
    @FXML private ComboBox<WorkoutPlan> startPlanBox;
    @FXML private Label statusLabel;
    @FXML private Button saveButton;
    @FXML private Button cancelButton;

    // --------------------------------------------------------- список правил
    @FXML private ListView<Schedule> scheduleList;
    @FXML private Label todayLabel;
    @FXML private Label nextLabel;
    @FXML private Button editButton;
    @FXML private Button deleteButton;

    /** null — режим создания. Иначе id редактируемого правила. */
    private Long editingId;

    /** Порядок шаблонов в правиле: сохраняем его при редактировании. */
    private List<Long> editingPlanOrder = new ArrayList<>();

    /** Пока true — программные изменения не запускают обработчики. */
    private boolean loading;

    public ScheduleController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        startDateField.setValue(LocalDate.now());
        intervalSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 365, 2));

        // чекбоксы в списке шаблонов
        planList.setCellFactory(view -> new CheckBoxListCell<PlanChoice>(PlanChoice::selectedProperty));

        // «Начинать с тренировки» — только названия, без toString() сущности
        configureCombo(startPlanBox, WorkoutPlan::getName);

        scheduleList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Schedule schedule, boolean empty) {
                super.updateItem(schedule, empty);
                if (empty || schedule == null) {
                    setText(null);
                    return;
                }
                setText(schedule.getName()
                        + "   ·   каждые " + schedule.getIntervalDays() + " дн."
                        + "   ·   старт " + schedule.getStartDate());
            }
        });

        scheduleList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldSchedule, newSchedule) -> {
                    if (loading || newSchedule == null) {
                        return;
                    }
                    showPreview(newSchedule);
                    loadIntoForm(newSchedule);
                });

        loadPlans();
        loadSchedules(null);
    }

    /** Одинаковый вид ComboBox: имя и в поле, и в выпадающем списке. */
    private static <T> void configureCombo(ComboBox<T> combo, Function<T, String> nameOf) {
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : nameOf.apply(value);
            }

            @Override
            public T fromString(String string) {
                return null;
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

    // ------------------------------------------------------------- шаблоны

    private void loadPlans() {
        List<PlanChoice> choices = context.catalogService().activePlans(context.userId())
                .stream()
                .map(PlanChoice::new)
                .toList();
        planList.getItems().setAll(choices);

        // галочка изменилась -> пересобираем список «Начинать с тренировки»
        for (PlanChoice choice : planList.getItems()) {
            choice.selectedProperty().addListener(
                    (observable, oldValue, selected) -> refreshStartPlanOptions());
        }

        refreshStartPlanOptions();
    }

    /** В «Начинать с тренировки» попадают только отмеченные шаблоны. */
    private void refreshStartPlanOptions() {
        if (loading) {
            return;
        }

        Long keepId = startPlanBox.getValue() == null ? null : startPlanBox.getValue().getId();

        List<WorkoutPlan> selected = planList.getItems().stream()
                .filter(PlanChoice::isSelected)
                .map(PlanChoice::plan)
                .toList();

        loading = true;
        startPlanBox.getItems().setAll(selected);

        WorkoutPlan restored = selected.stream()
                .filter(plan -> keepId != null && plan.getId().equals(keepId))
                .findFirst()
                .orElse(selected.isEmpty() ? null : selected.get(0));

        startPlanBox.setValue(restored);
        loading = false;
    }

    // --------------------------------------------------------- список правил

    /** @param selectId какое правило выделить (null — первое попавшееся) */
    private void loadSchedules(Long selectId) {
        List<Schedule> schedules = context.scheduleAdminService().schedules(context.userId());

        Schedule toSelect = null;
        if (selectId != null) {
            toSelect = schedules.stream()
                    .filter(schedule -> schedule.getId().equals(selectId))
                    .findFirst()
                    .orElse(null);
        }
        if (toSelect == null && !schedules.isEmpty()) {
            toSelect = schedules.get(0);
        }

        loading = true;
        scheduleList.getItems().setAll(schedules);
        scheduleList.getSelectionModel().clearSelection();
        if (toSelect != null) {
            scheduleList.getSelectionModel().select(toSelect);
        }
        loading = false;

        if (toSelect != null) {
            showPreview(toSelect);
            loadIntoForm(toSelect);
        } else {
            resetForm();
            todayLabel.setText("Сегодня: —");
            nextLabel.setText("Следующая: —");
        }
        updateMode();
    }

    // ---------------------------------------------------------------- форма

    private void loadIntoForm(Schedule schedule) {
        editingId = schedule.getId();
        editingPlanOrder = new ArrayList<>(
                context.scheduleAdminService().planIdsOf(schedule.getId()));

        nameField.setText(schedule.getName());
        startDateField.setValue(schedule.getStartDate());
        intervalSpinner.getValueFactory().setValue(schedule.getIntervalDays());

        loading = true;
        Set<Long> marked = new HashSet<>(editingPlanOrder);
        for (PlanChoice choice : planList.getItems()) {
            choice.selectedProperty().set(marked.contains(choice.plan().getId()));
        }
        loading = false;

        refreshStartPlanOptions();

        // start_plan_id == null значит «с первого шаблона правила»
        Long startPlanId = context.scheduleAdminService().startPlanIdOf(schedule.getId());
        if (startPlanId == null && !editingPlanOrder.isEmpty()) {
            startPlanId = editingPlanOrder.get(0);
        }
        if (startPlanId != null) {
            Long idToSelect = startPlanId;
            startPlanBox.getItems().stream()
                    .filter(plan -> plan.getId().equals(idToSelect))
                    .findFirst()
                    .ifPresent(startPlanBox::setValue);
        }

        statusLabel.setText("Редактируется: " + schedule.getName());
        updateMode();
    }

    private void resetForm() {
        editingId = null;
        editingPlanOrder = new ArrayList<>();

        nameField.clear();
        startDateField.setValue(LocalDate.now());
        intervalSpinner.getValueFactory().setValue(2);

        for (PlanChoice choice : planList.getItems()) {
            choice.selectedProperty().set(false);
        }

        refreshStartPlanOptions();
        updateMode();
    }

    /** Текст главной кнопки и доступность кнопок зависят от режима. */
    private void updateMode() {
        boolean editing = editingId != null;
        saveButton.setText(editing ? "Сохранить изменения" : "Создать");
        cancelButton.setDisable(!editing);

        boolean hasSelection = scheduleList.getSelectionModel().getSelectedItem() != null;
        editButton.setDisable(!hasSelection);
        deleteButton.setDisable(!hasSelection);
    }

    @FXML
    private void onCancel() {
        loading = true;
        scheduleList.getSelectionModel().clearSelection();
        loading = false;

        resetForm();
        todayLabel.setText("Сегодня: —");
        nextLabel.setText("Следующая: —");
        statusLabel.setText("Новое правило: заполните поля и нажмите «Создать».");
        updateMode();
    }

    @FXML
    private void onEdit() {
        Schedule selected = scheduleList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Alerts.warn("Правило не выбрано", "Выберите расписание в списке справа.");
            return;
        }
        showPreview(selected);
        loadIntoForm(selected);
    }

    @FXML
    private void onSave() {
        List<Long> planIds = collectPlanIds();
        LocalDate startDate = startDateField.getValue();
        Integer intervalValue = intervalSpinner.getValue();
        if (intervalValue == null) {
            Alerts.warn("Интервал", "Укажите интервал в днях.");
            return;
        }
        int intervalDays = intervalValue;
        Long startPlanId = startPlanBox.getValue() == null ? null : startPlanBox.getValue().getId();

        try {
            if (editingId == null) {
                Schedule created = context.scheduleAdminService().createSchedule(
                        context.userId(), nameField.getText(), startDate, intervalDays,
                        planIds, startPlanId);

                loadSchedules(created.getId());
                Alerts.info("Расписание создано", "Правило «" + created.getName() + "» сохранено.");
            } else {
                Schedule updated = context.scheduleAdminService().updateSchedule(
                        editingId, nameField.getText(), startDate, intervalDays,
                        planIds, startPlanId);

                loadSchedules(updated.getId());
                Alerts.info("Изменения сохранены", "Правило «" + updated.getName() + "» обновлено.");
            }
        } catch (RuntimeException e) {
            Alerts.error("Не удалось сохранить расписание", e.getMessage());
        }
    }

    @FXML
    private void onDelete() {
        Schedule selected = scheduleList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Alerts.warn("Правило не выбрано", "Выберите расписание в списке справа.");
            return;
        }

        boolean confirmed = Alerts.confirm("Удалить правило",
                "Удалить «" + selected.getName() + "»? Пункты расписания удалятся вместе с ним.");
        if (!confirmed) {
            return;
        }

        try {
            context.scheduleAdminService().deleteSchedule(selected.getId());
            loadSchedules(null);

            if (scheduleList.getItems().isEmpty()) {
                onCancel();
            }
            Alerts.info("Удалено", "Правило «" + selected.getName() + "» удалено.");
        } catch (RuntimeException e) {
            Alerts.error("Не удалось удалить правило", e.getMessage());
        }
    }

    // ------------------------------------------------------------- состав

    /**
     * Порядок: сначала прежний порядок правила (A, C, B останется A, C, B),
     * потом новые галочки в порядке списка.
     */
    private List<Long> collectPlanIds() {
        List<Long> planIds = new ArrayList<>();

        for (Long planId : editingPlanOrder) {
            if (isChecked(planId)) {
                planIds.add(planId);
            }
        }

        for (PlanChoice choice : planList.getItems()) {
            Long planId = choice.plan().getId();
            if (choice.isSelected() && !planIds.contains(planId)) {
                planIds.add(planId);
            }
        }

        return planIds;
    }

    private boolean isChecked(long planId) {
        return planList.getItems().stream()
                .anyMatch(choice -> choice.plan().getId().equals(planId) && choice.isSelected());
    }

    // ------------------------------------------------------------ просмотр

    /**
     * «Сегодня» — только если сегодня тренировочный день (кратно интервалу).
     * «Следующая» — ближайшая тренировка после сегодняшнего дня.
     */
    private void showPreview(Schedule schedule) {
        if (schedule == null) {
            todayLabel.setText("Сегодня: —");
            nextLabel.setText("Следующая: —");
            return;
        }

        LocalDate today = LocalDate.now();

        todayLabel.setText(context.scheduleService()
                .planForDate(schedule.getId(), today)
                .map(plan -> "Сегодня: " + plan.getName())
                .orElse("Сегодня: нет тренировки"));

        nextLabel.setText(context.scheduleService()
                .nextWorkout(schedule.getId(), today.plusDays(1))
                .map(workout -> "Следующая: " + DATE_FORMAT.format(workout.date())
                        + " — " + workout.plan().getName())
                .orElse("Следующая: —"));
    }

    @FXML
    private void onBack() {
        context.navigator().showMain();
    }
}

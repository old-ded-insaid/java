package app.desktop.controller;

import app.desktop.AppContext;
import app.desktop.ui.Alerts;
import app.desktop.ui.EquipmentRow;
import app.model.Equipment;
import app.model.Exercise;
import app.service.PlanService;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Справочник упражнений: поиск, фильтр по инвентарю, правка описания
 * и привязка инвентаря.
 *
 * Данные приходят из PlanService.searchExercises — упражнения уже с
 * подгруженным инвентарём (JOIN FETCH), поэтому читать связи после
 * транзакции безопасно.
 */
public class ExerciseCatalogController {

    private final AppContext context;

    // ---------------------------------------------------------- фильтры
    @FXML private TextField searchField;
    @FXML private ComboBox<FilterOption> equipmentFilterBox;
    @FXML private Label countLabel;

    // ----------------------------------------------------------- таблица
    @FXML private TableView<Exercise> exerciseTable;
    @FXML private TableColumn<Exercise, String> colName;
    @FXML private TableColumn<Exercise, String> colMuscles;
    @FXML private TableColumn<Exercise, String> colEquipment;

    // ----------------------------------------------------- редактор справа
    @FXML private Label selectedNameLabel;
    @FXML private TextArea setupArea;
    @FXML private TextArea movementArea;
    @FXML private TextArea musclesArea;
    @FXML private TextArea avoidArea;

    @FXML private ListView<EquipmentRow> equipmentList;
    @FXML private ComboBox<Equipment> equipmentBox;
    @FXML private Spinner<Integer> quantitySpinner;
    @FXML private Button attachButton;
    @FXML private Button detachButton;
    @FXML private Button saveButton;
    @FXML private Label statusLabel;

    /** Пока true — слушатели не перезагружают таблицу. */
    private boolean loading;

    public ExerciseCatalogController(AppContext context) {
        this.context = context;
    }

    /** Пункт фильтра: «Все» (null), «Без инвентаря» (-1) или конкретный инвентарь. */
    public record FilterOption(Long equipmentId, String title) {
        @Override
        public String toString() {
            return title;
        }
    }

    @FXML
    private void initialize() {
        configureTable();
        configureFilters();
        configureInventory();
        configureDetailsAreas();

        searchField.textProperty().addListener((observable, oldValue, newValue) -> reload());

        equipmentFilterBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!loading) {
                reload();
            }
        });

        exerciseTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldExercise, newExercise) -> showExercise(newExercise));

        showExercise(null);
        reload();
    }

    // ------------------------------------------------------------ таблица

    private void configureTable() {
        colName.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().getName()));
        colMuscles.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                nullSafe(cd.getValue().getMuscles())));
        colEquipment.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                equipmentText(cd.getValue())));
    }

    private void reload() {
        FilterOption filter = equipmentFilterBox.getValue();
        Long equipmentId = filter == null ? null : filter.equipmentId();

        Exercise selected = exerciseTable.getSelectionModel().getSelectedItem();
        Long keepId = selected == null ? null : selected.getId();

        List<Exercise> found = context.planService()
                .searchExercises(searchField.getText(), equipmentId);

        exerciseTable.getItems().setAll(found);
        countLabel.setText("Найдено: " + found.size());

        if (keepId != null) {
            found.stream()
                    .filter(item -> item.getId().equals(keepId))
                    .findFirst()
                    .ifPresent(item -> exerciseTable.getSelectionModel().select(item));
        }
    }

    private void configureFilters() {
        loading = true;

        equipmentFilterBox.getItems().add(new FilterOption(null, "Все"));
        equipmentFilterBox.getItems().add(new FilterOption(PlanService.EQUIPMENT_NONE, "Без инвентаря"));
        for (Equipment equipment : context.planService().getAllEquipment()) {
            equipmentFilterBox.getItems().add(new FilterOption(equipment.getId(), equipment.getName()));
        }
        equipmentFilterBox.getSelectionModel().selectFirst();

        loading = false;
    }

    // ---------------------------------------------------- редактор справа

    /** Минимальная высота текстового поля редактора (одна-две строки). */
    private static final double MIN_TEXT_HEIGHT = 46;

    /** Запас на внутренние отступы TextArea, чтобы не появилась полоса прокрутки. */
    private static final double TEXT_PADDING = 14;

    /**
     * Полоса прокрутки внутри TextArea резервируется заранее: иначе высота
     * считается «без прокрутки», полоса появляется, ширина текста уменьшается,
     * высота снова растёт — и макет дёргается.
     */
    private static final double SCROLLBAR_RESERVE = 18;

    /**
     * Готовит поля описания: перенос строк и высота «по тексту».
     *
     * Внутренние полосы прокрутки у TextArea не нужны: поле растягивается
     * по содержимому, а если текста больше, чем помещается в панель —
     * прокрутка будет одна, внешняя (ScrollPane вокруг карточки).
     */
    private void configureDetailsAreas() {
        for (TextArea area : List.of(setupArea, movementArea, musclesArea, avoidArea)) {
            area.setWrapText(true);
            area.setMaxHeight(Double.MAX_VALUE);
            bindHeightToProbe(area);
        }
    }

    /**
     * Высота поля = высота его текста (+ отступы).
     *
     * Считаем «пробным» Text с тем же шрифтом и той же шириной переноса:
     * скин TextArea создаётся только на первом CSS-проходе, поэтому узел
     * .text в initialize() ещё недоступен.
     *
     * Привязываем и prefHeight, и minHeight: VBox при нехватке места сжимает
     * детей до minHeight, и с маленьким minHeight поле снова показало бы
     * внутренний скролл.
     */
    private static void bindHeightToProbe(TextArea area) {
        Text probe = new Text();
        probe.fontProperty().bind(area.fontProperty());
        probe.textProperty().bind(area.textProperty());
        probe.wrappingWidthProperty().bind(Bindings.max(
                area.widthProperty().subtract(TEXT_PADDING * 2 + SCROLLBAR_RESERVE), 60));

        DoubleBinding height = Bindings.createDoubleBinding(
                () -> Math.max(MIN_TEXT_HEIGHT,
                        probe.getLayoutBounds().getHeight() + TEXT_PADDING * 2),
                probe.layoutBoundsProperty());

        area.minHeightProperty().bind(height);
        area.prefHeightProperty().bind(height);
    }

    private void showExercise(Exercise exercise) {
        boolean has = exercise != null;
        saveButton.setDisable(!has);
        attachButton.setDisable(!has);

        if (!has) {
            selectedNameLabel.setText("Выберите упражнение");
            setupArea.clear();
            movementArea.clear();
            musclesArea.clear();
            avoidArea.clear();
            equipmentList.getItems().clear();
            detachButton.setDisable(true);
            return;
        }

        selectedNameLabel.setText(exercise.getName());
        // «ИП:» — служебный префикс в данных, в поле он только мешает
        setupArea.setText(cleanSetup(exercise.getSetup()));
        movementArea.setText(nullSafe(exercise.getMovement()));
        musclesArea.setText(nullSafe(exercise.getMuscles()));
        avoidArea.setText(nullSafe(exercise.getAvoid()));

        refreshInventory(exercise);
    }

    private void refreshInventory(Exercise exercise) {
        List<EquipmentRow> rows = exercise.getEquipmentLinks().stream()
                .map(link -> new EquipmentRow(
                        link.getEquipment().getId(),
                        equipmentText(link.getEquipment().getName(), link.getQuantity()),
                        link.getQuantity()))
                .toList();

        equipmentList.getItems().setAll(rows);
        detachButton.setDisable(rows.isEmpty());
    }

    @FXML
    private void onSave() {
        Exercise selected = exerciseTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Alerts.warn("Упражнение не выбрано", "Выберите строку в таблице.");
            return;
        }

        try {
            context.planService().saveExerciseDetails(
                    selected.getId(),
                    setupArea.getText(),
                    movementArea.getText(),
                    musclesArea.getText(),
                    avoidArea.getText());

            reload();   // «Мышцы» в таблице могли измениться
            statusLabel.setText("Сохранено: " + selected.getName());
        } catch (RuntimeException e) {
            Alerts.error("Не удалось сохранить упражнение", e.getMessage());
        }
    }

    // ---------------------------------------------------------- инвентарь

    private void configureInventory() {
        quantitySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 1));
        equipmentBox.getItems().setAll(context.planService().getAllEquipment());

        equipmentBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Equipment equipment) {
                return equipment == null ? "" : equipment.getName();
            }

            @Override
            public Equipment fromString(String text) {
                return null;
            }
        });

        equipmentBox.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Equipment item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
    }

    @FXML
    private void onAttach() {
        Exercise exercise = exerciseTable.getSelectionModel().getSelectedItem();
        Equipment equipment = equipmentBox.getValue();

        if (exercise == null) {
            Alerts.warn("Инвентарь", "Выберите упражнение в таблице.");
            return;
        }
        if (equipment == null) {
            Alerts.warn("Инвентарь", "Выберите инвентарь в списке.");
            return;
        }

        int quantity = quantitySpinner.getValue() == null ? 1 : quantitySpinner.getValue();

        try {
            context.planService().addEquipment(exercise.getId(), equipment.getId(), quantity);
            reload();
            statusLabel.setText("Прикреплено: " + equipment.getName());
        } catch (RuntimeException e) {
            Alerts.error("Не удалось прикрепить инвентарь", e.getMessage());
        }
    }

    @FXML
    private void onDetach() {
        Exercise exercise = exerciseTable.getSelectionModel().getSelectedItem();
        EquipmentRow row = equipmentList.getSelectionModel().getSelectedItem();

        if (exercise == null || row == null) {
            Alerts.warn("Инвентарь", "Выберите строку в списке инвентаря.");
            return;
        }

        try {
            context.planService().removeEquipment(exercise.getId(), row.equipmentId());
            reload();
            statusLabel.setText("Инвентарь убран");
        } catch (RuntimeException e) {
            Alerts.error("Не удалось убрать инвентарь", e.getMessage());
        }
    }

    // ------------------------------------------------------------ служебное

    @FXML
    private void onBack() {
        context.navigator().showMain();
    }

    /** «Гантель 8 кг, Скамья (2 шт)» или «Без инвентаря». */
    private static String equipmentText(Exercise exercise) {
        if (exercise.getEquipmentLinks() == null || exercise.getEquipmentLinks().isEmpty()) {
            return "Без инвентаря";
        }
        return exercise.getEquipmentLinks().stream()
                .map(link -> equipmentText(link.getEquipment().getName(), link.getQuantity()))
                .reduce((first, second) -> first + ", " + second)
                .orElse("Без инвентаря");
    }

    private static String equipmentText(String name, int quantity) {
        return quantity > 1 ? name + " (" + quantity + " шт)" : name;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Префикс ИП в разных написаниях: «ИП:», «ИП », «и.п.», «И. П.» (регистр не важен).
     *
     * Тот же шаблон, что на экране тренировки: заголовок «Исходное положение»
     * уже стоит над полем, и «ИП:» в тексте — дубль.
     */
    private static final Pattern SETUP_PREFIX =
            Pattern.compile("(?iu)^(?:и\\.?\\s?п\\.?)\\s*:?\\s*\\b");

    /**
     * Убирает «ИП:» из начала описания исходного положения.
     *
     * Понимает «ИП:», «ИП », «и.п.», «И. П.» и не трогает середину строки.
     * Пустой результат (в данных было только «ИП:») возвращается пустой строкой.
     */
    private static String cleanSetup(String text) {
        if (text == null) {
            return "";
        }
        return SETUP_PREFIX.matcher(text.trim()).replaceFirst("").trim();
    }
}

package app.desktop.controller;

import app.desktop.AppContext;
import app.desktop.ui.Alerts;
import app.desktop.ui.MeasurementKind;
import app.desktop.ui.MeasurementRow;
import app.model.Measurement;
import app.service.MeasurementService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import javafx.stage.Stage;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Замеры тела и подвижности + картинка к замеру + график динамики.
 *
 * Файл копируется в media-папку только в момент сохранения (MediaService),
 * в БД пишется относительный путь и строка media, связанная с замером.
 */
public class MeasurementsController {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Подпись на оси X и в подсказке: день и месяц (год не нужен). */
    private static final DateTimeFormatter CHART_DATE = DateTimeFormatter.ofPattern("dd.MM");

    /** Подпись в списке показателей -> значение kind в БД (порядок = порядок списка). */
    private static final Map<String, String> CHART_KINDS = new LinkedHashMap<>();

    static {
        CHART_KINDS.put("Вес тела", MeasurementService.KIND_BODY_WEIGHT);
        CHART_KINDS.put("Пульс покоя", MeasurementService.KIND_PULSE_REST);
        CHART_KINDS.put("Подвижность", MeasurementService.KIND_MOBILITY);
    }

    private final AppContext context;

    // ------------------------------------------------------------- таблица
    @FXML private TableView<MeasurementRow> table;
    @FXML private TableColumn<MeasurementRow, String> colDate;
    @FXML private TableColumn<MeasurementRow, String> colKind;
    @FXML private TableColumn<MeasurementRow, String> colValue;
    @FXML private TableColumn<MeasurementRow, String> colNote;
    @FXML private TableColumn<MeasurementRow, String> colPhoto;

    // --------------------------------------------------------------- форма
    @FXML private ComboBox<MeasurementKind> kindBox;
    @FXML private TextField nameField;
    @FXML private TextField valueField;
    @FXML private TextField unitField;
    @FXML private TextArea noteArea;
    @FXML private ImageView previewImage;
    @FXML private Label imageNameLabel;

    // -------------------------------------------------------------- график
    @FXML private TabPane measurementsTabs;
    @FXML private ComboBox<String> chartTypeBox;
    @FXML private DatePicker chartFromPicker;
    @FXML private DatePicker chartToPicker;
    @FXML private Button buildChartButton;
    @FXML private LineChart<Number, Number> measurementChart;
    @FXML private NumberAxis chartXAxis;
    @FXML private NumberAxis chartYAxis;
    @FXML private Label chartInfoLabel;

    /** Сколько секунд в сутках: ось X считаем в днях (дробное число). */
    private static final double SECONDS_PER_DAY = 24 * 60 * 60;

    /** Больше 500 точек — прореживаем: JavaFX рисует их слишком долго. */
    private static final int MAX_CHART_POINTS = 500;

    /** Часовой пояс пользователя: в БД время лежит в UTC. */
    private static final ZoneId USER_ZONE = ZoneId.systemDefault();

    /** Выбранный файл: копируется в media-папку только при сохранении. */
    private Path pendingImage;

    public MeasurementsController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        kindBox.getItems().setAll(MeasurementKind.values());
        kindBox.getSelectionModel().selectFirst();
        installKindHints();

        colDate.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().dateText()));
        colKind.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().kindTitle()));
        colValue.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().valueText()));
        colNote.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().note()));
        colPhoto.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                cd.getValue().imagePath() == null ? "" : "фото"));

        configurePhotoColumn();
        configureRowClicks();
        configureChart();

        clearImage();
        refresh();
    }

    // -------------------------------------------------------------- график

    /**
     * Готовит вкладку графика: список показателей, период по умолчанию.
     *
     * Период по умолчанию — месяц: за этот срок видно тренд, но точки
     * не сливаются в одну линию.
     */
    private void configureChart() {
        chartTypeBox.getItems().setAll(CHART_KINDS.keySet());
        chartTypeBox.getSelectionModel().selectFirst();

        LocalDate today = LocalDate.now();
        chartToPicker.setValue(today);
        chartFromPicker.setValue(today.minusMonths(1));

        // B7: ось X — числовая (дни от 1970-01-01), чтобы расстояние между
        // датами было пропорционально: 1 день = 1 единица, а не «одна категория».
        chartXAxis.setLabel("Дата");
        chartXAxis.setForceZeroInRange(false);
        chartXAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number value) {
                if (value == null) {
                    return "";
                }
                long days = (long) Math.floor(value.doubleValue());
                return LocalDate.ofEpochDay(days).format(CHART_DATE);
            }

            @Override
            public Number fromString(String text) {
                return null;    // ввод в ось не нужен
            }
        });

        chartYAxis.setLabel("Значение");
        // вес 72.4 кг при оси «от нуля» выглядит прямой линией — пусть масштаб считает сам
        chartYAxis.setForceZeroInRange(false);

        measurementChart.setTitle("Динамика замеров");
    }

    /** Кнопка «Построить график». */
    @FXML
    private void onBuildChart() {
        buildChartData(true);
    }

    /**
     * Строит линию по замерам за выбранный период.
     *
     * Данные читаются в фоновом потоке (B2): за несколько лет замеров один
     * запрос к БД может занять заметное время, а окно замирать не должно.
     *
     * @param warnIfEmpty показывать предупреждение, если данных нет
     *                    (при авто-обновлении после добавления оно не нужно)
     */
    private void buildChartData(boolean warnIfEmpty) {
        String title = chartTypeBox.getValue();
        String kind = CHART_KINDS.get(title);
        if (kind == null) {
            Alerts.warn("График", "Выберите показатель.");
            return;
        }

        LocalDate to = chartToPicker.getValue() == null ? LocalDate.now() : chartToPicker.getValue();
        LocalDate from = chartFromPicker.getValue() == null ? to.minusMonths(1) : chartFromPicker.getValue();
        if (from.isAfter(to)) {
            // даты ввели «наоборот» — читаем период правильно и показываем как есть
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        chartFromPicker.setValue(from);
        chartToPicker.setValue(to);

        LocalDate fromDate = from;
        LocalDate toDate = to;
        long userId = context.userId();

        Task<List<Measurement>> task = new Task<>() {
            @Override
            protected List<Measurement> call() {
                return context.measurementService()
                        .getMeasurementsForChart(userId, kind, fromDate, toDate);
            }
        };

        // пока данные грузятся — кнопку выключаем, чтобы не плодить задачи
        if (buildChartButton != null) {
            buildChartButton.setDisable(true);
        }
        chartInfoLabel.setText("Загрузка…");

        task.setOnSucceeded(event -> {
            if (buildChartButton != null) {
                buildChartButton.setDisable(false);
            }
            renderChart(task.getValue(), title, fromDate, toDate, warnIfEmpty);
        });

        task.setOnFailed(event -> {
            if (buildChartButton != null) {
                buildChartButton.setDisable(false);
            }
            chartInfoLabel.setText("");
            Throwable error = task.getException();
            Alerts.error("Не удалось построить график",
                    error == null ? "Неизвестная ошибка" : error.getMessage());
        });

        Thread worker = new Thread(task, "fitapp-chart");
        worker.setDaemon(true);
        worker.start();
    }

    /** Рисует график по уже загруженным данным (вызывается на FX-потоке). */
    private void renderChart(List<Measurement> measurements,
                             String title,
                             LocalDate from,
                             LocalDate to,
                             boolean warnIfEmpty) {

        measurementChart.getData().clear();

        if (measurements == null || measurements.isEmpty()) {
            chartInfoLabel.setText("");
            if (warnIfEmpty) {
                Alerts.warn("Нет данных за выбранный период",
                        "Замеров «" + title + "» с " + from.format(CHART_DATE)
                                + " по " + to.format(CHART_DATE) + " нет.");
            }
            return;
        }

        List<Measurement> points = thin(measurements);

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(title);

        for (Measurement measurement : points) {
            ZonedDateTime local = measurement.getMeasuredAt()
                    .atZone(ZoneOffset.UTC)
                    .withZoneSameInstant(USER_ZONE);

            // дробные дни: два замера в один день не сольются в одну точку
            double days = local.toEpochSecond() / SECONDS_PER_DAY;
            series.getData().add(new XYChart.Data<>(days, measurement.getValueNum().doubleValue()));
        }

        measurementChart.getData().add(series);

        String unit = measurements.get(measurements.size() - 1).getValueUnit();
        String shown = points.size() == measurements.size()
                ? "Точек: " + points.size()
                : "Точек: " + points.size() + " из " + measurements.size();
        chartInfoLabel.setText(shown + (unit == null || unit.isBlank() ? "" : " · " + unit));
    }

    /**
     * Прореживание длинного ряда: оставляем каждую N-ю точку и последнюю.
     *
     * Нужно, чтобы график на несколько тысяч замеров не подвешивал отрисовку.
     */
    private static List<Measurement> thin(List<Measurement> measurements) {
        if (measurements.size() <= MAX_CHART_POINTS) {
            return measurements;
        }

        int step = (int) Math.ceil((double) measurements.size() / MAX_CHART_POINTS);
        List<Measurement> result = new ArrayList<>(MAX_CHART_POINTS + 1);

        for (int i = 0; i < measurements.size(); i += step) {
            result.add(measurements.get(i));
        }

        Measurement last = measurements.get(measurements.size() - 1);
        if (!result.get(result.size() - 1).equals(last)) {
            result.add(last);   // последняя точка важна: текущее значение
        }
        return result;
    }

    /** Если график уже построен — обновляем его после добавления/удаления замера. */
    private void refreshChartIfShown() {
        if (!measurementChart.getData().isEmpty()) {
            buildChartData(false);
        }
    }

    // ----------------------------------------------------------- подсказки

    private void installKindHints() {
        kindBox.setCellFactory(view -> kindCell());
        kindBox.setButtonCell(kindCell());
    }

    /** Ячейка ComboBox с подсказкой для каждого типа замера. */
    private ListCell<MeasurementKind> kindCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(MeasurementKind kind, boolean empty) {
                super.updateItem(kind, empty);
                if (empty || kind == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(kind.title());
                setTooltip(new Tooltip(hintFor(kind)));
            }
        };
    }

    private static String hintFor(MeasurementKind kind) {
        return switch (kind) {
            case MOBILITY -> "Например: наклон вперёд, ротация плеча (в см или градусах)";
            case PULSE_REST -> "Замер утром, лежа (уд/мин)";
            case BODY_WEIGHT -> "Вес тела утром натощак (кг)";
            case OTHER -> "Вес тела, давление и т.д.";
        };
    }

    // ------------------------------------------------------------ таблица

    private void configurePhotoColumn() {
        colPhoto.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);

                boolean hasPhoto = !empty && value != null && !value.isEmpty();
                setText(hasPhoto ? "фото" : "");
                setStyle(hasPhoto
                        ? "-fx-text-fill: -fx-accent; -fx-underline: true; -fx-cursor: hand;"
                        : "");

                setOnMouseClicked(event -> {
                    if (hasPhoto && getTableRow() != null && getTableRow().getItem() != null) {
                        openImage(getTableRow().getItem());
                    }
                });
            }
        });
    }

    private void configureRowClicks() {
        table.setRowFactory(view -> {
            TableRow<MeasurementRow> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    openImage(row.getItem());
                }
            });
            return row;
        });
    }

    private void refresh() {
        var rows = context.measurementService().list(context.userId()).stream()
                .map(measurement -> new MeasurementRow(
                        measurement.getId(),
                        DATE_FORMAT.format(measurement.getMeasuredAt()),
                        MeasurementKind.titleOf(measurement.getKind()),
                        formatValue(measurement),
                        measurement.getNote() == null ? "" : measurement.getNote(),
                        measurement.getMedia() == null ? null : measurement.getMedia().getFilePath()))
                .toList();

        table.getItems().setAll(rows);
    }

    // -------------------------------------------------------------- форма

    @FXML
    private void onPickImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Картинка к замеру");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Картинки (*.png, *.jpg, *.jpeg)", "*.png", "*.jpg", "*.jpeg"));

        File file = chooser.showOpenDialog(table.getScene() == null ? null : table.getScene().getWindow());
        if (file == null) {
            return;
        }

        pendingImage = file.toPath();
        previewImage.setImage(new Image(pendingImage.toUri().toString(), 200, 200, true, true));
        imageNameLabel.setText(file.getName());
    }

    @FXML
    private void onClearImage() {
        clearImage();
    }

    private void clearImage() {
        pendingImage = null;
        previewImage.setImage(null);
        imageNameLabel.setText("картинка не выбрана");
    }

    @FXML
    private void onAdd() {
        MeasurementKind kind = kindBox.getValue();
        if (kind == null) {
            Alerts.warn("Тип замера", "Выберите тип замера.");
            return;
        }

        BigDecimal value;
        try {
            value = parseValue(valueField.getText());
        } catch (NumberFormatException e) {
            Alerts.warn("Значение", "Введите число, например 72.5 (можно с точкой или запятой).");
            return;
        }

        String relativePath = null;
        try {
            if (pendingImage != null) {
                // копируем файл и получаем путь, который пойдёт в БД
                relativePath = context.mediaService().importImage(pendingImage);
            }

            context.measurementService().add(
                    context.userId(),
                    kind.dbValue(),
                    nameField.getText(),
                    value,
                    unitField.getText(),
                    noteArea.getText(),
                    relativePath);

            nameField.clear();
            valueField.clear();
            noteArea.clear();
            clearImage();
            refresh();
            refreshChartIfShown();
        } catch (RuntimeException e) {
            // файл уже скопирован, а запись не сохранилась — не оставляем мусор
            context.mediaService().deleteQuietly(relativePath);
            Alerts.error("Не удалось сохранить замер", e.getMessage());
        }
    }

    @FXML
    private void onDelete() {
        MeasurementRow row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            Alerts.warn("Замер не выбран", "Выберите строку в таблице.");
            return;
        }
        if (!Alerts.confirm("Удалить замер", "Удалить «" + row.kindTitle() + "» без возможности отмены?")) {
            return;
        }

        try {
            context.measurementService().delete(row.id());   // media-строка уходит каскадом
            context.mediaService().deleteQuietly(row.imagePath());
            refresh();
            refreshChartIfShown();
        } catch (RuntimeException e) {
            Alerts.error("Не удалось удалить замер", e.getMessage());
        }
    }

    @FXML
    private void onBack() {
        context.navigator().showMain();
    }

    // ------------------------------------------------------------ картинка

    /** Открывает картинку в отдельном окне (тема берётся у главного окна). */
    private void openImage(MeasurementRow row) {
        if (row == null || row.imagePath() == null) {
            Alerts.info("Фото", "К этому замеру картинка не прикреплена.");
            return;
        }

        Path file = context.mediaService().resolve(row.imagePath());
        if (!Files.isReadable(file)) {
            Alerts.warn("Файл не найден", "Ожидался файл:\n" + file);
            return;
        }

        Image image = new Image(file.toUri().toString(), 1000, 800, true, true);
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitWidth(Math.min(image.getWidth(), 1000));
        view.setFitHeight(Math.min(image.getHeight(), 800));

        StackPane root = new StackPane(view);
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root);
        if (table.getScene() != null) {
            // те же CSS, что у главного окна — тёмная тема сохраняется
            scene.getStylesheets().addAll(table.getScene().getStylesheets());
        }

        Stage stage = new Stage();
        stage.setTitle(row.kindTitle() + " · " + row.dateText());
        if (table.getScene() != null) {
            stage.initOwner(table.getScene().getWindow());
        }
        stage.setScene(scene);
        stage.show();
    }

    // ------------------------------------------------------------ значения

    /** "72,5" -> 72.5; пустая строка -> null (замер без числа, например фото). */
    private static BigDecimal parseValue(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return new BigDecimal(text.trim().replace(',', '.'));
    }

    private static String formatValue(Measurement measurement) {
        if (measurement.getValueNum() == null) {
            return "—";
        }
        String number = measurement.getValueNum().stripTrailingZeros().toPlainString();
        return measurement.getValueUnit() == null ? number : number + " " + measurement.getValueUnit();
    }
}

package app.desktop.ui;

import app.model.Equipment;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * Строка списка инвентаря с галочкой.
 *
 * Нужна для CheckBoxListCell: чтобы можно было отметить сразу несколько
 * предметов (например, «2 чёрные + жёлтая» резинки).
 */
public final class EquipmentChoice {

    private final Equipment equipment;
    private final BooleanProperty selected = new SimpleBooleanProperty(false);

    public EquipmentChoice(Equipment equipment) {
        this.equipment = equipment;
    }

    public Equipment equipment() {
        return equipment;
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    @Override
    public String toString() {
        return equipment.getName();
    }
}

package app.desktop.ui;

import app.model.WorkoutPlan;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * Обёртка для списка с чекбоксами (CheckBoxListCell).
 * CheckBoxListCell умеет показывать чекбокс, если дать ему ObservableValue<Boolean>.
 */
public final class PlanChoice {

    private final WorkoutPlan plan;
    private final BooleanProperty selected = new SimpleBooleanProperty(false);

    public PlanChoice(WorkoutPlan plan) {
        this.plan = plan;
    }

    public WorkoutPlan plan() {
        return plan;
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    /** Так ListView показывает имя шаблона, а не «PlanChoice@1a2b». */
    @Override
    public String toString() {
        return plan.getName();
    }
}

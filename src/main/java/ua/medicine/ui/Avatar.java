package ua.medicine.ui;

import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import ua.medicine.domain.Person;

/** Coloured circle with the person's initial. */
final class Avatar extends StackPane {

    Avatar(Person person, double radius) {
        var circle = new Circle(radius, Color.web(person.color()));
        var initial = new Label(person.name().isBlank() ? "?" : person.name().substring(0, 1).toUpperCase());
        initial.getStyleClass().add("avatar-initial");
        initial.setStyle("-fx-font-size: " + Math.round(radius) + "px;");
        getChildren().addAll(circle, initial);
    }
}

package ua.medicine.ui;

import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/** Row of preset colour swatches; one is selected. */
final class ColorPicker extends HBox {

    private String selected;

    ColorPicker(String initial) {
        super(8);
        selected = initial != null ? initial : Palette.COLORS.getFirst();
        for (String color : Palette.COLORS) {
            var swatch = new Circle(11, Color.web(color));
            swatch.getStyleClass().add("color-swatch");
            swatch.setOnMouseClicked(e -> {
                selected = color;
                render();
            });
            swatch.setUserData(color);
            getChildren().add(swatch);
        }
        render();
    }

    String selected() {
        return selected;
    }

    private void render() {
        getChildren().forEach(n -> {
            var circle = (Circle) n;
            circle.getStyleClass().remove("selected");
            if (circle.getUserData().equals(selected)) {
                circle.getStyleClass().add("selected");
            }
        });
    }
}

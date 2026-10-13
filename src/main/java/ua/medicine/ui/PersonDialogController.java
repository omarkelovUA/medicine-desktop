package ua.medicine.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import ua.medicine.AppContext;
import ua.medicine.domain.Person;
import ua.medicine.i18n.Messages;
import ua.medicine.service.PersonHasMedicinesException;

import java.util.Optional;

/** Add / edit person dialog. Google linking lives on the dashboard's person bar. */
public final class PersonDialogController {

    private PersonDialogController() {
    }

    /** Returns the saved person, or empty if cancelled or deleted. */
    public static Optional<Person> show(Window owner, AppContext ctx, Person existing) {
        var dialog = new Dialog<Person>();
        dialog.initOwner(owner);
        dialog.setTitle(Messages.get(existing == null ? "dialog.person.new" : "dialog.person.edit"));
        dialog.setHeaderText(null);
        var pane = dialog.getDialogPane();
        var save = new ButtonType(Messages.get("btn.save"), ButtonBar.ButtonData.OK_DONE);
        var cancel = new ButtonType(Messages.get("btn.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        var delete = new ButtonType(Messages.get("btn.delete"), ButtonBar.ButtonData.LEFT);
        pane.getButtonTypes().addAll(save, cancel);
        if (existing != null) {
            pane.getButtonTypes().add(delete);
        }

        var name = new TextField(existing == null ? "" : existing.name());
        var error = new Label();
        error.getStyleClass().add("error-label");
        error.setManaged(false);
        var color = new ColorPicker(existing == null ? null : existing.color());
        var nameLabel = new Label(Messages.get("field.personName").toUpperCase(Messages.LOCALE));
        nameLabel.getStyleClass().add("form-label");
        var colorLabel = new Label(Messages.get("field.color").toUpperCase(Messages.LOCALE));
        colorLabel.getStyleClass().add("form-label");
        var box = new VBox(6, nameLabel, name, error, colorLabel, color);
        box.setPadding(new Insets(8));
        box.setPrefWidth(340);
        pane.setContent(box);

        Person[] result = new Person[1];
        pane.lookupButton(save).addEventFilter(ActionEvent.ACTION, e -> {
            String text = name.getText() == null ? "" : name.getText().strip();
            String problem = null;
            if (text.isEmpty()) {
                problem = Messages.get("error.nameRequired");
            } else {
                try {
                    result[0] = existing == null
                            ? ctx.personService().create(text, color.selected())
                            : ctx.personService().update(new Person(existing.id(), text, color.selected(),
                            existing.googleEmail(), existing.googleCalendarId()));
                } catch (RuntimeException ex) {
                    problem = isUniqueViolation(ex) ? Messages.get("error.nameTaken") : null;
                    if (problem == null) {
                        Dialogs.dbError(pane.getScene().getWindow(), ex);
                        e.consume();
                        return;
                    }
                }
            }
            if (problem != null) {
                error.setText(problem);
                error.setManaged(true);
                e.consume();
            }
        });
        if (existing != null) {
            pane.lookupButton(delete).addEventFilter(ActionEvent.ACTION, e -> {
                if (!Dialogs.confirm(pane.getScene().getWindow(),
                        Messages.get("confirm.deletePerson", existing.name()))) {
                    e.consume();
                    return;
                }
                try {
                    // token first: SQLite may reuse the id for the next new person
                    ctx.googleAuth().forget(existing.id());
                    ctx.personService().delete(existing.id());
                } catch (PersonHasMedicinesException ex) {
                    Dialogs.error(pane.getScene().getWindow(), Messages.get("error.personHasMedicines"));
                    e.consume();
                } catch (RuntimeException ex) {
                    Dialogs.dbError(pane.getScene().getWindow(), ex);
                    e.consume();
                }
            });
        }
        dialog.setResultConverter(button -> button == save ? result[0] : null);
        return dialog.showAndWait();
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("UNIQUE")) {
                return true;
            }
        }
        return false;
    }
}

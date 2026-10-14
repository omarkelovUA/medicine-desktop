package ua.medicine.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.medicine.i18n.Messages;

import java.util.Optional;

/** Small helpers for message boxes. */
public final class Dialogs {

    private static final Logger log = LoggerFactory.getLogger(Dialogs.class);

    private Dialogs() {
    }

    public static void error(Window owner, String message) {
        var alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setTitle(Messages.get("app.title"));
        alert.showAndWait();
    }

    /** Logs the exception and shows it as a database error. */
    public static void dbError(Window owner, Exception e) {
        log.error("Operation failed", e);
        error(owner, Messages.get("error.db", e.getMessage()));
    }

    /** «Так» → true, «Ні» → false; «Скасувати», Esc or closing the window → empty. */
    public static Optional<Boolean> yesNoCancel(Window owner, String message) {
        var yes = new ButtonType(Messages.get("btn.yes"), ButtonBar.ButtonData.YES);
        var no = new ButtonType(Messages.get("btn.no"), ButtonBar.ButtonData.NO);
        var cancel = new ButtonType(Messages.get("btn.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        var alert = new Alert(Alert.AlertType.CONFIRMATION, message, yes, no, cancel);
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setTitle(Messages.get("app.title"));
        return alert.showAndWait().filter(b -> b != cancel).map(yes::equals);
    }

    public static boolean confirm(Window owner, String message) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setTitle(Messages.get("app.title"));
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }
}

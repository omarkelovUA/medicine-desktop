package ua.medicine.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.medicine.i18n.Messages;

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

    public static boolean confirm(Window owner, String message) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setTitle(Messages.get("app.title"));
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }
}

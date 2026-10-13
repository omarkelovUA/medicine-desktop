package ua.medicine.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import ua.medicine.AppContext;
import ua.medicine.i18n.Messages;
import ua.medicine.sync.GoogleAuthService;

import java.io.File;
import java.nio.file.Path;

/** Explains how to get the Google OAuth client file and imports it. */
final class GoogleSetupDialog {

    private static final String CONSOLE_URL = "https://console.cloud.google.com/apis/credentials";

    private GoogleSetupDialog() {
    }

    /** Returns true once a valid client file has been imported. */
    static boolean show(Window owner, AppContext ctx) {
        var dialog = new Dialog<Boolean>();
        dialog.initOwner(owner);
        dialog.setTitle(Messages.get("google.setup.title"));
        dialog.setHeaderText(null);
        var pane = dialog.getDialogPane();
        var choose = new ButtonType(Messages.get("google.setup.chooseFile"), ButtonBar.ButtonData.OK_DONE);
        var cancel = new ButtonType(Messages.get("btn.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().addAll(choose, cancel);

        var text = new Label(Messages.get("google.setup.text"));
        text.setWrapText(true);
        var link = new Hyperlink(Messages.get("google.setup.openConsole"));
        link.setOnAction(e -> ctx.io().submit(() -> GoogleAuthService.openUrl(CONSOLE_URL)));
        var error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        error.setManaged(false);
        var box = new VBox(10, text, link, error);
        box.setPadding(new Insets(8));
        box.setPrefWidth(520);
        pane.setContent(box);

        pane.lookupButton(choose).addEventFilter(ActionEvent.ACTION, e -> {
            var chooser = new FileChooser();
            chooser.setTitle(Messages.get("google.setup.chooseFile"));
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(Messages.get("google.setup.fileFilter"), "*.json"));
            File downloads = Path.of(System.getProperty("user.home"), "Downloads").toFile();
            if (downloads.isDirectory()) {
                chooser.setInitialDirectory(downloads);
            }
            File file = chooser.showOpenDialog(pane.getScene().getWindow());
            if (file == null) {
                e.consume();
                return;
            }
            try {
                ctx.googleAuth().importClientSecret(file.toPath());
            } catch (IllegalArgumentException ex) {
                error.setText(Messages.get("google.setup.invalidFile"));
                error.setManaged(true);
                pane.getScene().getWindow().sizeToScene();
                e.consume();
            } catch (RuntimeException ex) {
                Dialogs.dbError(pane.getScene().getWindow(), ex);
                e.consume();
            }
        });
        dialog.setResultConverter(button -> button == choose);
        return dialog.showAndWait().orElse(false);
    }
}

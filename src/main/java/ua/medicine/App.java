package ua.medicine;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ua.medicine.i18n.Messages;
import ua.medicine.ui.DashboardController;

import java.io.IOException;
import java.util.Locale;

public class App extends Application {

    private AppContext context;
    private DashboardController dashboard;
    private AppJobs jobs;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws IOException {
        Locale.setDefault(Messages.LOCALE);
        context = new AppContext(AppPaths.defaults());
        context.medicineService().extendWindows();
        context.doseService().resolveMissed();

        var loader = new FXMLLoader(DashboardController.class.getResource("dashboard.fxml"), Messages.bundle());
        loader.setControllerFactory(type -> new DashboardController(context));
        Parent root = loader.load();
        dashboard = loader.getController();

        var scene = new Scene(root, 1100, 750);
        scene.getStylesheets().add(DashboardController.class.getResource("theme.css").toExternalForm());
        stage.setTitle(Messages.get("app.title"));
        stage.setScene(scene);
        stage.show();

        dashboard.start(stage);
        jobs = new AppJobs(context, dashboard, dashboard.notifications());
        jobs.start();
        context.sync().requestSync();
    }

    public DashboardController dashboard() {
        return dashboard;
    }

    @Override
    public void stop() {
        if (jobs != null) {
            jobs.stop();
        }
        if (dashboard != null) {
            dashboard.stop();
        }
        if (context != null) {
            context.close();
        }
    }
}

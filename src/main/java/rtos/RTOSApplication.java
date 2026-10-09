package rtos;

import java.net.URL;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

public final class RTOSApplication extends Application {

    @Override
    public void start(Stage stage) {
        WebView webView = new WebView();
        webView.setContextMenuEnabled(false);
        WebEngine engine = webView.getEngine();
        URL report = RTOSApplication.class.getResource("index.html");
        if (report == null) {
            throw new IllegalStateException("No se encontró rtos/index.html en los recursos");
        }

        engine.getLoadWorker().stateProperty().addListener((observable, previous, current) -> {
            if (current == Worker.State.SUCCEEDED) {
                runScenarios(engine);
            }
        });

        stage.setTitle("RTOS | Informe de simulación");
        stage.setMinWidth(760);
        stage.setMinHeight(560);
        stage.setScene(new Scene(webView, 1360, 900));
        stage.show();
        engine.load(report.toExternalForm());
    }

    private void runScenarios(WebEngine engine) {
        Thread simulation = new Thread(() -> {
            try {
                List<String> reports = Main.captureDemos();
                String json = toJson(reports);
                Platform.runLater(() -> engine.executeScript("renderReports(" + json + ")"));
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String message = exception.getMessage() == null
                        ? "No se pudieron generar los reportes."
                        : exception.getMessage();
                Platform.runLater(() -> engine.executeScript("showError(" + quote(message) + ")"));
            }
        }, "rtos-report-simulation");
        simulation.setDaemon(true);
        simulation.start();
    }

    private static String toJson(List<String> reports) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < reports.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(quote(reports.get(index)));
        }
        return json.append(']').toString();
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
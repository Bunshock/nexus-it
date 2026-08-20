package com.bunshock.note_app_for_it_frontend.utils.core;

import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public final class DialogChrome {

    private DialogChrome() {
    }

    public static Stage buildDialogStage() {
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.initModality(Modality.APPLICATION_MODAL);
        return stage;
    }

    public static void centerOnContent(Stage stage, Region anchor) {
        stage.setOpacity(0);
        stage.setOnShown(e -> {
            Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
            if (b != null) {
                stage.setX(b.getMinX() + (b.getWidth()  - stage.getWidth())  / 2);
                stage.setY(b.getMinY() + (b.getHeight() - stage.getHeight()) / 2);
            }
            stage.setOpacity(1);
        });
    }

    public static VBox buildDialogRoot(double prefWidth, String accentColor) {
        VBox root = new VBox(14);
        root.setPrefWidth(prefWidth);
        root.setStyle("""
            -fx-background-color: %s, white;
            -fx-background-radius: 12, 10;
            -fx-background-insets: 0, 2;
            -fx-padding: 24;
            -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 5);
            """.formatted(accentColor));
        return root;
    }

    public static Scene buildDialogScene(VBox content) {
        StackPane wrapper = new StackPane(content);
        wrapper.setStyle("-fx-background-color: transparent; -fx-padding: 20;");
        Scene scene = new Scene(wrapper);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(DialogChrome.class.getResource(
            "/com/bunshock/note_app_for_it_frontend/css/styles.css").toExternalForm());
        return scene;
    }
}

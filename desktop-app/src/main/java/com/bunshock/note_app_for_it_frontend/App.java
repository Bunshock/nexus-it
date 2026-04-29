package com.bunshock.note_app_for_it_frontend;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class App extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(getClass().getResource("views/MainView.fxml"));
        
        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        double height = visualBounds.getHeight() * 0.95;
        double width = 1275.0;
        
        Scene scene = new Scene(root, width, height);
        stage.setScene(scene);
        stage.setTitle("Siglo 21 - Generador de Notas IT");
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}
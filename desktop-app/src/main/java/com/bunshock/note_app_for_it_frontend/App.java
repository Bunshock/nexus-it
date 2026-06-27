package com.bunshock.note_app_for_it_frontend;

import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;


public class App extends Application {

    @Override
    public void init() throws Exception {
        ConfigService.getInstance().load();
        DatabaseService.getInstance().initialize();
        ServiceLocator.getInstance().initialize(ConfigService.getInstance().getConfig());
    }

    @Override
    public void start(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(getClass().getResource("views/MainView.fxml"));

        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        double width  = visualBounds.getWidth()  * 0.85;
        double height = visualBounds.getHeight() * 0.95;

        Scene scene = new Scene(root, width, height);
        stage.setScene(scene);

        stage.getIcons().add(new Image(getClass().getResourceAsStream("images/favicon.png")));
        
        stage.setTitle("Universidad Siglo 21 - Soporte IT - Registro de Movimientos y Generación de Notas");

        stage.centerOnScreen();
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}

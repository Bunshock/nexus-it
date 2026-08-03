package com.bunshock.note_app_for_it_frontend;

import com.bunshock.note_app_for_it_frontend.services.ConfigService;
import com.bunshock.note_app_for_it_frontend.services.DatabaseService;
import com.bunshock.note_app_for_it_frontend.services.ServiceLocator;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;


public class App extends Application {

    // Must match MainView.fxml's <top> titleBar HBox's prefHeight. Added on top of the window
    // height budget below (not carved out of it) — see the height calculation comment for why.
    // Reduced from 40 to 32 to close the Generar Nota shortfall (see WINDOW_SHADOW_MARGIN's
    // comment), then nudged back up to 36 — user missed the taller bar and asked for a middle
    // ground; 36 costs ~4 more px of shortfall than 32 did, still far below the original 28px
    // regression on a typical screen.
    private static final double TITLE_BAR_HEIGHT = 36;

    // Must match MainController.WINDOW_SHADOW_MARGIN — the padding reserved around rootPane for
    // its drop shadow to render into (StageStyle.TRANSPARENT). Added on top of the window height/
    // width budget for the same reason as TITLE_BAR_HEIGHT: it isn't visible app content, so it
    // must not shrink what NoteGeneratorView etc. actually get to lay out in.
    //
    // Reduced from 20 (title bar 40->32 too): the height clamp below is capped at visualBounds
    // (screen minus taskbar) to guarantee the window never covers the taskbar, but visualBounds
    // only ever has ~5% headroom above the existing 0.95 factor — and TITLE_BAR_HEIGHT +
    // 2*WINDOW_SHADOW_MARGIN both get added on top of that budget, so whatever they total is
    // exactly how much of NoteGeneratorView's needed height silently gets clamped away on an
    // ordinary screen. Padding a fixed height bump on top doesn't fix this — it either still
    // gets clamped (no-op) or, if the clamp is loosened enough to let it through, starts
    // covering the taskbar again (tried, reverted). Shrinking the chrome overhead itself is the
    // only screen-size-independent fix: smaller overhead means less of it needs to be clamped
    // away, regardless of what any particular monitor's headroom happens to be.
    private static final double WINDOW_SHADOW_MARGIN = 12;

    private static App instance;
    private Stage primaryStage;

    @Override
    public void init() throws Exception {
        ConfigService.getInstance().load();
        DatabaseService.getInstance().initialize();
        ServiceLocator.getInstance().initialize(ConfigService.getInstance().getConfig());
    }

    @Override
    public void start(Stage stage) throws Exception {
        instance = this;
        primaryStage = stage;
        stage.getIcons().add(new Image(getClass().getResourceAsStream("images/favicon.png")));
        stage.setTitle("Universidad Siglo 21 - Soporte IT - Registro de Movimientos y Generación de Notas");
        stage.initStyle(StageStyle.TRANSPARENT);
        showLoginScreen(stage);
    }

    public static App getInstance() { return instance; }

    /**
     * Returns to the login screen from an active session — MainController's "Cerrar sesión"
     * action. Reuses showLoginScreen() itself (same screen, same success callback back into
     * showMainApp()) rather than duplicating it, so a logout behaves exactly like the very
     * first login.
     */
    public void showLoginAgain() {
        try {
            showLoginScreen(primaryStage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to return to login screen after logout", e);
        }
    }

    /**
     * Shown alone, before MainView (and its startup connectivity overlay) is constructed at
     * all — only a successful login proceeds to showMainApp(). LoginController itself never
     * touches the Stage; it just reports success/failure back through the callback.
     */
    private void showLoginScreen(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("views/LoginView.fxml"));
        Parent loginRoot = loader.load();
        com.bunshock.note_app_for_it_frontend.controllers.LoginController controller = loader.getController();

        // Reset whatever MainView's window chrome may have applied (maximized state, min size) —
        // a no-op on the very first call from start(), where none of this has been set yet; needed
        // when this is reached again via showLoginAgain() after logout, since MainView's own
        // 900x600 min size would otherwise block the login screen's fixed 420x560 size.
        stage.setMaximized(false);
        stage.setMinWidth(0);
        stage.setMinHeight(0);

        Scene loginScene = new Scene(loginRoot, 420, 560);
        loginScene.setFill(Color.TRANSPARENT);
        stage.setScene(loginScene);
        stage.setResizable(false);
        // centerOnScreen() must run after show(), not before — a Stage's actual width/height
        // aren't reliably finalized until its peer is created at show time, so calling it earlier
        // uses a stale/estimated size and skews the result (visibly, on a small fixed-size window
        // like this one — it read as sitting above true center).
        //
        // Platform.runLater(), not setOnShown(): onShown only fires on the hidden->shown
        // transition, so it worked for the very first login but silently never fired again on
        // showLoginAgain() after logout, since the Stage is already showing at that point and
        // show() is then a no-op — the window kept whatever position it happened to have from
        // MainView instead of re-centering to its own (smaller) login size. runLater() defers
        // until after the current pulse either way, so it centers correctly on both the initial
        // show and a same-Stage scene swap.
        stage.show();
        javafx.application.Platform.runLater(stage::centerOnScreen);

        controller.setOnLoginSuccess(() -> {
            try {
                showMainApp(stage);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load MainView after login", e);
            }
        });
    }

    private void showMainApp(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(getClass().getResource("views/MainView.fxml"));

        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        double width = visualBounds.getWidth() * 0.85 + 2 * WINDOW_SHADOW_MARGIN;
        // BorderPane's left (sidebar) and center rows share whatever height is left after the
        // top title bar, so the 85%/95%-of-screen budget this app was already tuned around
        // (NoteGeneratorView's left column sits right at that edge) needs the title bar's
        // height and the shadow margin added on top, not carved out of it — otherwise center's
        // min-height can exceed the shrunk row and force the whole row (sidebar included)
        // taller than the visible window. Clamped to visualBounds (screen minus taskbar, never
        // the full screen — see WINDOW_SHADOW_MARGIN's comment for why that clamp choice
        // matters and why the chrome overhead above was shrunk instead of just adding more
        // height on top of it).
        double height = Math.min(
            visualBounds.getHeight() * 0.95 + TITLE_BAR_HEIGHT + 2 * WINDOW_SHADOW_MARGIN,
            visualBounds.getHeight());

        // rootPane (MainView.fxml's BorderPane) is wrapped in a transparent, padded StackPane —
        // same "shadow needs room outside the visible content" technique every dialog in this
        // app already uses (see SettingsController.buildDialogScene, etc.). MainController reads
        // this wrapper back via rootPane.getParent() to toggle the padding/shadow/rounded-corner
        // clip off when maximized (see MainController.setupWindowChrome()).
        StackPane windowWrapper = new StackPane(root);
        windowWrapper.setStyle("-fx-background-color: transparent;");
        windowWrapper.setPadding(new Insets(WINDOW_SHADOW_MARGIN));

        Scene scene = new Scene(windowWrapper, width, height);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);

        // Native OS chrome is intentionally replaced by MainView.fxml's own title bar
        // (MainController.setupWindowChrome() wires drag-to-move, edge resize, the
        // minimize/maximize/close buttons, and the shadow/rounded-corner window frame). Icon,
        // title, and StageStyle.TRANSPARENT were already set once in start() — a Stage's style
        // can only be initialized before its first show(), which already happened for the login
        // screen, so it must not be set again here.
        stage.setResizable(true);
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        stage.centerOnScreen();
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}

package com.bunshock.note_app_for_it_frontend.controllers.envio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.util.Duration;

// Tab-bar shell for "Remito de Envío" — mirrors NoteGeneratorController's shape exactly (see its
// header comment for why this is a ToggleButton strip, not a real TabPane).
public class RemitoNoteController {

    @FXML private HBox tabBarRow;
    @FXML private HBox pageTabsBox;
    @FXML private StackPane pageStack;
    @FXML private Button btnAddTab;
    @FXML private Label lblGenerationStatus;

    // Explicit user-requested cap — prevents the page strip from growing unbounded.
    private static final int MAX_NOTE_TABS = 5;
    private final Tooltip maxTabsTooltip = new Tooltip("Se alcanzó el máximo de " + MAX_NOTE_TABS + " remitos.");

    // Right-click "Cerrar" kept alongside the inline "×" close button below — both call closePage().
    private static final String CLOSE_MENU_TEXT = "Cerrar";

    // Caps the name label so long note-type names still ellipsize — page-tab-button's own
    // -fx-max-width (160) minus padding/border/close-button/spacing, tuned by eye.
    private static final double TAB_NAME_MAX_WIDTH = 126;

    private record RemitoPage(ToggleButton toggle, Node content, RemitoTabController controller, Label nameLabel) {}

    private final ToggleGroup pageToggleGroup = new ToggleGroup();
    private final List<RemitoPage> pages = new ArrayList<>();

    public void initialize() {
        // Must be attached before the first page is created/selected below, or its own selection
        // fires with nobody listening and its content stays hidden.
        pageToggleGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val == null) {
                // ToggleButtons can deselect on a second click — exactly one must always stay
                // on. Guarded to skip a toggle already removed from the group (closePage()).
                if (old != null && old.getToggleGroup() == pageToggleGroup) old.setSelected(true);
                return;
            }
            for (RemitoPage p : pages) {
                boolean selected = p.toggle() == val;
                p.content().setVisible(selected);
                p.content().setManaged(selected);
            }
        });

        selectPage(createRemitoPage());

        btnAddTab.setOnAction(e -> handleAddTab());

        refreshTabChrome();
    }

    private void handleAddTab() {
        if (pages.size() >= MAX_NOTE_TABS) return;
        selectPage(createRemitoPage());
        refreshTabChrome();
    }

    private RemitoPage createRemitoPage() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                "/com/bunshock/note_app_for_it_frontend/views/RemitoTabContentView.fxml"));
            Parent content = loader.load();
            RemitoTabController ctrl = loader.getController();
            content.setVisible(false);
            content.setManaged(false);

            ToggleButton toggle = new ToggleButton();
            toggle.getStyleClass().add("page-tab-button");
            toggle.setToggleGroup(pageToggleGroup);
            toggle.setUserData(ctrl);

            Label nameLabel = new Label();
            nameLabel.setMaxWidth(TAB_NAME_MAX_WIDTH);
            nameLabel.getStyleClass().add("page-tab-name-label");

            Button closeButton = new Button("✕");
            closeButton.getStyleClass().add("page-tab-close-button");

            HBox graphic = new HBox(6, nameLabel, closeButton);
            graphic.setAlignment(Pos.CENTER_LEFT);
            toggle.setGraphic(graphic);
            toggle.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            RemitoPage page = new RemitoPage(toggle, content, ctrl, nameLabel);
            closeButton.setOnAction(e -> closePage(page));

            ContextMenu menu = new ContextMenu();
            MenuItem closeItem = new MenuItem(CLOSE_MENU_TEXT);
            closeItem.setOnAction(e -> closePage(page));
            menu.getItems().add(closeItem);
            toggle.setContextMenu(menu);

            ctrl.setCloseTabRequest(() -> closePage(page));
            ctrl.setTabNameChangeListener(this::refreshTabChrome);
            ctrl.setStatusMessageListener(this::showGenerationSuccess);

            pages.add(page);
            pageTabsBox.getChildren().add(toggle);
            pageStack.getChildren().add(content);
            return page;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load RemitoTabContentView.fxml", e);
        }
    }

    private void selectPage(RemitoPage page) {
        page.toggle().setSelected(true);
    }

    // At least 1 page must always stay open — replaces it with a fresh one if that was the last.
    private void closePage(RemitoPage page) {
        boolean wasSelected = page.toggle().isSelected();
        pages.remove(page);
        pageTabsBox.getChildren().remove(page.toggle());
        pageStack.getChildren().remove(page.content());
        page.toggle().setToggleGroup(null);

        if (pages.isEmpty()) {
            selectPage(createRemitoPage());
        } else if (wasSelected) {
            selectPage(pages.get(pages.size() - 1));
        }
        refreshTabChrome();
    }

    private void refreshTabChrome() {
        boolean multiple = pages.size() > 1;
        for (RemitoPage p : pages) {
            String name = p.controller().getTabDisplayName();
            String display = (name != null && !name.isBlank()) ? name : "Remito";
            p.nameLabel().setText(display);
            p.toggle().setText(display); // not rendered (GRAPHIC_ONLY) — kept for accessibility tools
            p.toggle().getContextMenu().getItems().get(0).setDisable(!multiple);
        }
        updateAddTabAvailability();
        // Label width isn't final until after this pulse's layout — same reasoning as
        // repositionTabConnector().
        Platform.runLater(() -> {
            for (RemitoPage p : pages) updateTabTooltip(p);
        });
    }

    // Scratch node reused for text measurement — never attached to the scene graph.
    private final Text tabTextMeasurer = new Text();

    private static final Duration TAB_TOOLTIP_SHOW_DELAY = Duration.millis(350);

    // Shows the tab's full name as a tooltip only when its own text is actually ellipsized
    // (nameLabel's fixed max-width truncates long note-type names) — no tooltip at all when the
    // full name already fits.
    private void updateTabTooltip(RemitoPage page) {
        String text = page.nameLabel().getText();
        Label nameLabel = page.nameLabel();
        if (text == null || text.isEmpty()) {
            page.toggle().setTooltip(null);
            return;
        }
        tabTextMeasurer.setText(text);
        tabTextMeasurer.setFont(nameLabel.getFont());
        double textWidth = tabTextMeasurer.getLayoutBounds().getWidth();
        // Real laid-out width, not the configured cap — the label can end up narrower than its
        // own max-width if the tab strip is squeezed, and comparing against the cap alone missed
        // that case (reported as the tooltip not showing at all).
        double available = nameLabel.getWidth() > 0 ? nameLabel.getWidth() : nameLabel.getMaxWidth();
        boolean overflowed = textWidth > available;
        if (overflowed) {
            Tooltip tooltip = new Tooltip(text);
            tooltip.setShowDelay(TAB_TOOLTIP_SHOW_DELAY);
            page.toggle().setTooltip(tooltip);
        } else {
            page.toggle().setTooltip(null);
        }
    }

    private void updateAddTabAvailability() {
        boolean atLimit = pages.size() >= MAX_NOTE_TABS;
        btnAddTab.setDisable(atLimit);
        btnAddTab.setTooltip(atLimit ? maxTabsTooltip : null);
    }

    private RemitoPage activePage() {
        Toggle selected = pageToggleGroup.getSelectedToggle();
        for (RemitoPage p : pages) {
            if (p.toggle() == selected) return p;
        }
        return null;
    }

    @FXML
    private void handleGenerarRemito() {
        RemitoPage active = activePage();
        if (active != null) active.controller().generarRemito();
    }

    @FXML
    private void handleClearForm() {
        RemitoPage active = activePage();
        if (active != null) active.controller().clearForm();
    }

    private static final Duration GENERATION_SUCCESS_HOLD = Duration.millis(2000);
    private static final Duration GENERATION_SUCCESS_FADE = Duration.millis(650);

    private void showGenerationSuccess(String message) {
        lblGenerationStatus.setText(message);
        lblGenerationStatus.setOpacity(1.0);
        FadeTransition fade = new FadeTransition(GENERATION_SUCCESS_FADE, lblGenerationStatus);
        fade.setDelay(GENERATION_SUCCESS_HOLD);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> lblGenerationStatus.setText(""));
        fade.play();
    }

    // Called by EnviosController on every navigation into this section — delegates to whichever
    // page is currently selected, since stock shortages are now a per-page concern.
    public void refreshStockWarning() {
        RemitoPage active = activePage();
        if (active != null) active.controller().refreshStockWarning();
    }
}

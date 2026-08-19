package com.bunshock.note_app_for_it_frontend.controllers.history;

import javafx.scene.control.Label;

// Small styled-Label builders shared by NoteDetailController and PrestamoDetailController's
// item/status cards.
final class DetailCardLabels {
    private DetailCardLabels() {}

    static Label smallLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: #475569; -fx-wrap-text: true;");
        return l;
    }

    static Label statusBadge(String text, String color) {
        Label l = new Label(text);
        l.setStyle(String.format(
            "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: %s; -fx-wrap-text: true;", color));
        return l;
    }

    static Label prefixLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        return l;
    }
}

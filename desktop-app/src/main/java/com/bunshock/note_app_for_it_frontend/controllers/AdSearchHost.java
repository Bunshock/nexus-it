package com.bunshock.note_app_for_it_frontend.controllers;

import com.bunshock.note_app_for_it_frontend.models.ADUser;

// Lets ADUserSelectionController (the shared multi-result AD search popup) report back to
// whichever screen opened it, without being hard-typed to a single controller class.
public interface AdSearchHost {
    void fillUserData(ADUser user);
    void onAdSelectionDialogClosed();
    void highlightFields(String hexColor);
    void triggerFeedback(String message, String hexColor);
}

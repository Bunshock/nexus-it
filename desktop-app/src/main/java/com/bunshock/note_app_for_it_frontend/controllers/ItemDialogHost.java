package com.bunshock.note_app_for_it_frontend.controllers;

import com.bunshock.note_app_for_it_frontend.models.AssetItem;
import com.bunshock.note_app_for_it_frontend.models.CountableItem;

// Lets ItemDialogController (the shared Add/Edit Equipo popup) report a new item back to
// whichever screen opened it, without being hard-typed to a single controller class.
public interface ItemDialogHost {
    void addAsset(AssetItem item);
    void addCountable(CountableItem item);
}

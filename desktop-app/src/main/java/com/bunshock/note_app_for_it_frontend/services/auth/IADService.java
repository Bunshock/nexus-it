package com.bunshock.note_app_for_it_frontend.services.auth;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.auth.ADUser;

public interface IADService {
    List<ADUser> search(String dni, String name, String username);
}

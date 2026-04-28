package com.bunshock.note_app_for_it_frontend.services;

import java.util.List;

import com.bunshock.note_app_for_it_frontend.models.ADUser;

public interface IADService {
    List<ADUser> search(String dni, String name, String username);
}

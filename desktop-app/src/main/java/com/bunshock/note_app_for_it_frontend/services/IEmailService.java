package com.bunshock.note_app_for_it_frontend.services;

import java.io.File;

public interface IEmailService {

    void sendNote(String toAddress, String subject, String body, File attachment) throws Exception;

    boolean isConfigured();
}

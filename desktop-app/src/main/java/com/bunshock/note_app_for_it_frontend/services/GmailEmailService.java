package com.bunshock.note_app_for_it_frontend.services;

import java.io.File;
import java.util.Properties;

import com.bunshock.note_app_for_it_frontend.models.core.AppConfig;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

public class GmailEmailService implements IEmailService {

    private final AppConfig.SmtpConfig smtpConfig;
    private final String senderAddress;
    private final String encryptedPassword;

    public GmailEmailService(AppConfig.SmtpConfig smtpConfig, String encryptedPassword) {
        this.smtpConfig = smtpConfig;
        this.senderAddress = smtpConfig.senderAddress;
        this.encryptedPassword = encryptedPassword;
    }

    @Override
    public boolean isConfigured() {
        return senderAddress != null && !senderAddress.isBlank()
            && encryptedPassword != null && !encryptedPassword.isBlank();
    }

    @Override
    public void sendNote(String toAddress, String subject, String body, File attachment) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("Email service is not configured");
        }

        String password = AppKeyEncryptionService.getInstance().decrypt(encryptedPassword);

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", smtpConfig.host);
        props.put("mail.smtp.port", String.valueOf(smtpConfig.port));

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(senderAddress, password);
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(senderAddress));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress));
        message.setSubject(subject);

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body);

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);

        if (attachment != null && attachment.exists()) {
            MimeBodyPart filePart = new MimeBodyPart();
            DataSource source = new FileDataSource(attachment);
            filePart.setDataHandler(new DataHandler(source));
            filePart.setFileName(attachment.getName());
            multipart.addBodyPart(filePart);
        }

        message.setContent(multipart);
        Transport.send(message);
    }
}

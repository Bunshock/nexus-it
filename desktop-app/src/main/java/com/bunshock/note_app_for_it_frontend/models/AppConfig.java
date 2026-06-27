package com.bunshock.note_app_for_it_frontend.models;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

    public AfFormat afFormat;
    public Map<String, List<String>> motivoOptions;
    public SmtpConfig smtp;
    public ApiEndpoint adApi;
    public ApiEndpoint glpiApi;
    public ApiEndpoint database;
    public int noteItemLimit;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AfFormat {
        public String prefix;
        public String separator;
        public int length;
        public String filler;
        public String inputPattern = "\\d";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SmtpConfig {
        public String host;
        public int port;
        public String senderAddress;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiEndpoint {
        public String baseUrl;
    }
}

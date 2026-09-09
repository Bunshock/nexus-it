package com.bunshock.note_app_for_it;

import com.bunshock.note_app_for_it.auth.IdpProperties;
import com.bunshock.note_app_for_it.directory.DirectoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({IdpProperties.class, DirectoryProperties.class})
@EnableScheduling // SessionStore.scheduledSweep — evict expired in-memory sessions
public class NoteAppForItApplication {

	public static void main(String[] args) {
		SpringApplication.run(NoteAppForItApplication.class, args);
	}

}

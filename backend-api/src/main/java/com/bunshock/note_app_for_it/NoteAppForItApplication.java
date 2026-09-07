package com.bunshock.note_app_for_it;

import com.bunshock.note_app_for_it.auth.IdpProperties;
import com.bunshock.note_app_for_it.directory.DirectoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({IdpProperties.class, DirectoryProperties.class})
public class NoteAppForItApplication {

	public static void main(String[] args) {
		SpringApplication.run(NoteAppForItApplication.class, args);
	}

}

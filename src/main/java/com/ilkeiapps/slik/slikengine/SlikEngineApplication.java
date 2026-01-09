package com.ilkeiapps.slik.slikengine;

import nu.pattern.OpenCV;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SlikEngineApplication {

	public static void main(String[] args) {
		OpenCV.loadLocally();
		SpringApplication.run(SlikEngineApplication.class, args);
	}

	@Bean
	public SpringApplicationRunListener runListener() {
		return new MyRunListener();
	}


	public static class MyRunListener implements SpringApplicationRunListener {

		@Override
		public void contextLoaded(ConfigurableApplicationContext context) {
			// Do nothing
		}

		@Override
		public void contextPrepared(ConfigurableApplicationContext context) {
			// Do nothing
		}


		@Override
		public void failed(ConfigurableApplicationContext context, Throwable exception) {
			// Log the error
			System.err.println("Application run failed xxx: " + exception.getMessage());
			// Shutdown the application
			SpringApplication.exit(context, () -> 1);
		}
	}

}

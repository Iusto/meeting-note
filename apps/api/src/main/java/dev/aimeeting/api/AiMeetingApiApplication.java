package dev.aimeeting.api;

import dev.aimeeting.api.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class AiMeetingApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiMeetingApiApplication.class, args);
    }
}

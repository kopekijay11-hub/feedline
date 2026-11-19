package ca.feedline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FeedlineApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeedlineApplication.class, args);
    }
}

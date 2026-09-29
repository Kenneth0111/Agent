package com.example.creator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CreatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(CreatorApplication.class, args);
    }
}

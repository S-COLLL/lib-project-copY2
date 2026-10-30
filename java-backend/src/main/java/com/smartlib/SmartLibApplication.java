package com.smartlib;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SmartLib library management system.
 * Spring Boot REST API (/api/**) over MySQL, and the HTML/CSS/JS frontend served as static content.
 */
@SpringBootApplication
@EnableScheduling
public class SmartLibApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartLibApplication.class, args);
    }
}

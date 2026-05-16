package com.sei.nexus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ZevraApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZevraApplication.class, args);
    }
}

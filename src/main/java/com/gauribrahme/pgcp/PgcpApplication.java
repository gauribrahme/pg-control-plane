package com.gauribrahme.pgcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PgcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(PgcpApplication.class, args);
    }
}

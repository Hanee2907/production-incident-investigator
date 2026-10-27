package com.production.incidents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IncidentInvestigatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentInvestigatorApplication.class, args);
    }
}

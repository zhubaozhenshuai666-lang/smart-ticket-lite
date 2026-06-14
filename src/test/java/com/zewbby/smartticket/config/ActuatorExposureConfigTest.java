package com.zewbby.smartticket.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorExposureConfigTest {

    @Test
    void actuatorOnlyExposesHealthInfoAndMetrics() throws Exception {
        String pomXml = Files.readString(Path.of("pom.xml"));
        String applicationYml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(pomXml).contains("spring-boot-starter-actuator");
        assertThat(applicationYml).contains("include: health,info,metrics");
        assertThat(applicationYml).doesNotContain("include: *");
        assertThat(applicationYml).doesNotContain("env,beans");
    }
}

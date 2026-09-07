package com.zenalyst.housing.platform;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests: a real PostgreSQL 16, real Flyway migrations, real HTTP.
 *
 * <p>The container is a manually started singleton rather than a {@code @Container} field.
 * A JUnit-managed container is torn down and recreated per test class, which at eight phases
 * of integration tests turns a fast suite into a slow one; started once here, every test
 * class shares it.
 *
 * <p>It is configured with {@code --locale=C} to match {@code docker-compose.yml}. Text
 * ordering under a locale-sensitive collation varies by host, and a system whose defence is
 * "you can reproduce our results" cannot have its ordering depend on where it runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("housing")
                    .withUsername("housing")
                    .withPassword("housing")
                    .withEnv("POSTGRES_INITDB_ARGS", "--locale=C --encoding=UTF8");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}

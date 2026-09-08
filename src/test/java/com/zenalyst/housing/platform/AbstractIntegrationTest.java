package com.zenalyst.housing.platform;

import com.zenalyst.housing.platform.security.Role;
import com.zenalyst.housing.platform.security.TokenService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
    private TestRestTemplate anonymous;

    @Autowired
    private TokenService tokens;

    /**
     * A client carrying an administrator token.
     *
     * <p>Most tests are about what the system does, not about who may ask it to, so they use this
     * and say nothing about authentication. {@code SecurityIT} is where the rules themselves are
     * tested, and it uses {@link #anonymous()} and tokens of its own.
     */
    protected TestRestTemplate rest;

    @BeforeEach
    void authenticateAsAdministrator() {
        rest = withRoles("registrar", Role.ADMIN);
    }

    /** A client with no credentials at all — for the endpoints that must work without any. */
    protected TestRestTemplate anonymous() {
        return anonymous;
    }

    protected TestRestTemplate withRoles(String subject, Role... roles) {
        return withHeader("Authorization", "Bearer " + tokens.issue(subject, List.of(roles)));
    }

    /**
     * A client that adds one header to every request.
     *
     * <p>Built by copying the injected template's URI handler — which knows the random port the
     * test server is on — and adding an interceptor. A fresh {@code TestRestTemplate} would not
     * know where to send anything.
     */
    protected TestRestTemplate withHeader(String name, String value) {
        TestRestTemplate client = new TestRestTemplate();
        client.getRestTemplate().setUriTemplateHandler(
                anonymous.getRestTemplate().getUriTemplateHandler());
        client.getRestTemplate().getInterceptors().add((request, body, execution) -> {
            request.getHeaders().add(name, value);
            return execution.execute(request, body);
        });
        return client;
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // JobRunr's dashboard binds a fixed port, so a running application — or a second test JVM —
        // makes every context here fail to start. Tests have no use for it.
        registry.add("jobrunr.dashboard.enabled", () -> false);

        // Jobs are enqueued but not executed in the background. Tests that care about the enqueue
        // path assert on the 202 and the status transition; tests that care about the result drive
        // DrawExecutionService directly. Leaving the poller running would add five seconds of
        // waiting per draw and a race between it and the test.
        registry.add("jobrunr.background-job-server.enabled", () -> false);
    }
}

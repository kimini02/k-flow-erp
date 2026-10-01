package com.kflow.erp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired Environment environment;
    @Value("${local.server.port}") int port;

    @Test
    void bootsOnPostgresWithFlywayAndNoBusinessSchema() throws Exception {
        assertThat(postgres.isRunning()).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(16);
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("select count(*) from information_schema.tables "
                         + "where table_schema = 'public' and table_type = 'BASE TABLE' "
                         + "and table_name <> 'flyway_schema_history'")) {
                result.next();
                assertThat(result.getInt(1)).isZero();
            }
        }
        assertThat(flyway.info().applied()).isEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getMetamodel().getEntities()).isEmpty();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
    }

    @Test
    void servesOpenApiAndSwaggerWithoutFakeBusinessEndpoints() throws Exception {
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        var spec = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(spec.statusCode()).isEqualTo(200);
        assertThat(spec.body()).contains("\"openapi\"").contains("\"paths\":{}");
        var ui = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");
    }
}

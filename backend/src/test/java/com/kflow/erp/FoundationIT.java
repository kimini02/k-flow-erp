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
    void bootsOnPostgresWithOnlyApprovedOrganizationHrAndIamSchema() throws Exception {
        assertThat(postgres.isRunning()).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(16);
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("select table_name from information_schema.tables "
                         + "where table_schema = 'public' and table_type = 'BASE TABLE' "
                         + "and table_name <> 'flyway_schema_history'")) {
                var tables = new java.util.ArrayList<String>();
                while (result.next()) tables.add(result.getString(1));
                assertThat(tables).containsExactlyInAnyOrder("organization_company", "organization_site", "organization_department", "organization_position", "hr_employee", "hr_employee_assignment", "iam_user", "iam_role", "iam_user_role", "iam_password_credential", "iam_credential_enrollment", "iam_bootstrap_record", "spring_session", "spring_session_attributes");
            }
        }
        assertThat(flyway.info().applied()).hasSize(11);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getMetamodel().getEntities()).extracting(jakarta.persistence.metamodel.EntityType::getName)
                .containsExactlyInAnyOrder("Company", "Site", "Department", "Position", "Employee", "EmployeeAssignment", "IamUser", "IamRole", "IamUserRole", "PasswordCredential", "CredentialEnrollment", "BootstrapRecord");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
    }

    @Test
    void servesOpenApiAndSwaggerWithOnlyApprovedCredentialCapability() throws Exception {
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        var spec = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(spec.statusCode()).isEqualTo(200);
        assertThat(spec.body()).contains("\"openapi\"");
        var paths=tools.jackson.databind.json.JsonMapper.builder().build().readTree(spec.body()).get("paths");
        assertThat(paths.size()).isEqualTo(6);
        assertThat(paths.get("/api/organization/sites/{siteId}").propertyNames()).containsExactly("get");
        assertThat(paths.get("/api/credential-enrollments/consume").propertyNames()).containsExactly("post");
        assertThat(paths.get("/api/auth/csrf").propertyNames()).containsExactly("get");
        assertThat(paths.get("/api/auth/login").propertyNames()).containsExactly("post");
        assertThat(paths.get("/api/auth/logout").propertyNames()).containsExactly("post");
        assertThat(paths.get("/api/auth/session").propertyNames()).containsExactly("get");
        assertThat(environment.getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
        var ui = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");
    }
}

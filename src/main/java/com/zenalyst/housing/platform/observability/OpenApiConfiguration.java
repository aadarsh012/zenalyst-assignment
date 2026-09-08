package com.zenalyst.housing.platform.observability;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API's own description, served at {@code /swagger-ui.html}.
 *
 * <p>The description is written for somebody arriving without context, because that is who reads
 * it: the endpoints that verify a draw look strange until you know that being checkable by a
 * stranger is the entire design goal.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI housingAllocationApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Housing Allocation")
                        .version("v1")
                        .description("""
                                Allocation of a public housing scheme: applications arrive online and \
                                on paper, duplicates are resolved, eligibility is assessed, the \
                                register is frozen, and flats are drawn by published rules.

                                The organising constraint is that every published output must be \
                                independently re-derivable from published inputs by somebody who does \
                                not trust the authority. That is why the verification endpoints — \
                                `POST /draws/{id}/verify`, `GET /audit/verify`, the registry and \
                                results exports — need no credentials: a check only the authority can \
                                run proves nothing.

                                Endpoints that can influence an outcome require a bearer token. \
                                `/applications/{no}/explain` is authorised per application: an \
                                applicant's token carries their application number as its subject and \
                                grants access to that file alone.
                                """)
                        .license(new License().name("Assignment submission")))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Obtain one from POST /api/v1/dev/token under the dev profile.")));
    }
}

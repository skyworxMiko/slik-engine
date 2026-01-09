package com.ilkeiapps.slik.slikengine.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@SecurityScheme(
        name = "Bearer Authentication",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer"
)
public class OpenAPIConfig {

    @Bean
    @Profile("default")
    public OpenAPI devApi() {
        return new OpenAPI()
                .info(new Info().title("API Robot CBAS")
                        .description("API Description for Robot CBAS")
                        .version("1.0"));
    }
}

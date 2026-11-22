package com.defigestuel.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.defigestuel.challenge.ChallengeProperties;

/** Le frontend Angular (par défaut http://localhost:4200) peut appeler l'API depuis le navigateur. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final ChallengeProperties properties;

    public CorsConfig(ChallengeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}

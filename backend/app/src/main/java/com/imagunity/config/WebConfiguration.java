package com.imagunity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

/** Réglages web communs. */
@Configuration(proxyBeanMethods = false)
class WebConfiguration {

    /**
     * Seuls les formulaires ({@code multipart/form-data}) sont décodés par Spring : le corps
     * {@code multipart/related} d'un dépôt STOW-RS doit arriver intact à la box, qui le lit en flux.
     */
    @Bean
    MultipartResolver multipartResolver() {
        StandardServletMultipartResolver resolver = new StandardServletMultipartResolver();
        resolver.setStrictServletCompliance(true);
        return resolver;
    }
}

package com.imagunity.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identité de la box, lue depuis la configuration ({@code imagunity.box.*}).
 *
 * @param name nom lisible de la box, affiché dans la console
 */
@ConfigurationProperties("imagunity.box")
public record BoxProperties(String name) {
}

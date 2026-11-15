package com.imagunity.scenarios;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * La box, telle que {@code com.imagunity.ImagUnityApplication} l'assemble, sans les composants du relais, du service
 * d'annuaire, du lanceur ni des scénarios, présents eux aussi sur le classpath des scénarios : la classe de la box
 * analyse tout {@code com.imagunity}, elle les ramasserait. Elle est elle-même écartée (sinon, configuration elle
 * aussi, elle relancerait l'analyse sans filtre).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.imagunity", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.imagunity\\.(relay|directoryserver|release|scenarios)\\..*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.imagunity\\.ImagUnityApplication")})
@ConfigurationPropertiesScan(basePackages = {"com.imagunity.api", "com.imagunity.config"})
class BoxApplication {
}

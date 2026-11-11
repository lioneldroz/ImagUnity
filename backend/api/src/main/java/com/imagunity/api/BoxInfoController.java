package com.imagunity.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Informations générales sur la box, pour la console. */
@RestController
@RequestMapping("/api/v1/box")
public class BoxInfoController {

    /** Réponse de {@code GET /api/v1/box}. */
    public record BoxInfo(String name, String version) {
    }

    private final BoxProperties properties;
    private final String version;

    @Autowired
    public BoxInfoController(BoxProperties properties) {
        this(properties, BoxInfoController.class.getPackage().getImplementationVersion());
    }

    /**
     * @param implementationVersion version lue dans le manifeste du jar, ou {@code null}
     *                              hors d'un jar (développement, tests)
     */
    BoxInfoController(BoxProperties properties, String implementationVersion) {
        this.properties = properties;
        this.version = implementationVersion != null ? implementationVersion : "dev";
    }

    @GetMapping
    public BoxInfo info() {
        return new BoxInfo(properties.name(), version);
    }
}

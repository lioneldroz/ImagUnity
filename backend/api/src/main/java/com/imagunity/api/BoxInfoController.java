package com.imagunity.api;

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

    public BoxInfoController(BoxProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public BoxInfo info() {
        String version = BoxInfoController.class.getPackage().getImplementationVersion();
        return new BoxInfo(properties.name(), version != null ? version : "dev");
    }
}

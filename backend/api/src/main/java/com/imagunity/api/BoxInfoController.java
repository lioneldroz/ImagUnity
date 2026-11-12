package com.imagunity.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Informations générales sur la box, pour la console. */
@RestController
@RequestMapping("/api/v1/box")
public class BoxInfoController {

    /**
     * Réponse de {@code GET /api/v1/box}.
     *
     * @param releaseSequence numéro de la version signée vérifiée au démarrage par le lanceur ({@code null} : version
     *                        non vérifiée)
     */
    public record BoxInfo(String name, String version, Long releaseSequence) {
    }

    private final BoxProperties properties;
    private final String version;
    private final Long releaseSequence;

    /**
     * @param releaseVersion  version signée retenue par le lanceur ({@code imagunity.release.version})
     * @param releaseSequence son numéro ({@code imagunity.release.sequence}, 0 : non vérifiée)
     */
    @Autowired
    public BoxInfoController(BoxProperties properties,
                             @Value("${imagunity.release.version:}") String releaseVersion,
                             @Value("${imagunity.release.sequence:0}") long releaseSequence) {
        this(properties, BoxInfoController.class.getPackage().getImplementationVersion(), releaseVersion,
                releaseSequence);
    }

    /**
     * @param implementationVersion version lue dans le manifeste du jar, ou {@code null}
     *                              hors d'un jar (développement, tests)
     */
    BoxInfoController(BoxProperties properties, String implementationVersion) {
        this(properties, implementationVersion, "", 0);
    }

    BoxInfoController(BoxProperties properties, String implementationVersion, String releaseVersion,
                      long releaseSequence) {
        this.properties = properties;
        this.releaseSequence = releaseSequence > 0 ? releaseSequence : null;
        if (this.releaseSequence != null && !releaseVersion.isBlank()) {
            this.version = releaseVersion;
        } else {
            this.version = implementationVersion != null ? implementationVersion : "dev";
        }
    }

    @GetMapping
    public BoxInfo info() {
        return new BoxInfo(properties.name(), version, releaseSequence);
    }
}

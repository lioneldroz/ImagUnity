package com.imagunity.scenarios;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Ressources propres à un module, recopiées hors du classpath.
 *
 * <p>La box, le relais et le service d'annuaire partagent ici un même classpath, où chacun publie son
 * {@code application.yml} et ses migrations Flyway sous les mêmes chemins ({@code db/migration/V1__…}) : lues par le
 * classpath, la première trouvée l'emporterait pour les trois. Chaque application reçoit donc les siennes, recopiées
 * depuis le dossier de classes ou le jar de son module, et désignées par {@code spring.config.location} et
 * {@code spring.flyway.locations}.
 */
final class AppFiles {

    private AppFiles() {
    }

    /**
     * Recopie la ressource {@code resource} (fichier ou dossier) du module qui contient {@code anchor} dans
     * {@code target}, et rend le chemin de la copie.
     */
    static Path copy(Class<?> anchor, String resource, Path target) {
        try {
            Path location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path destination = target.resolve(resource);
            if (Files.isDirectory(location)) {
                copyTree(location.resolve(resource), destination);
            } else {
                try (FileSystem jar = FileSystems.newFileSystem(location)) {
                    copyTree(jar.getPath(resource), destination);
                }
            }
            return destination;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException("Ressource absente : " + source.toUri());
        }
        if (!Files.isDirectory(source)) {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path copy = destination.resolve(source.relativize(file).toString());
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Classe chargée par son nom (classes de la box : présentes à l'exécution seulement). */
    static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Classe absente du classpath des scénarios : " + name
                    + " (compiler d'abord le module app : mvn -pl scenarios -am test)", e);
        }
    }
}

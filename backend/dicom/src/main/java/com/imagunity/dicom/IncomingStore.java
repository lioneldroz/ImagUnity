package com.imagunity.dicom;

import com.imagunity.domain.exam.ReceivedInstance;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;

/**
 * Dossier de réception des instances.
 *
 * <pre>
 * &lt;racine&gt;/.tmp/&lt;aléatoire&gt;.part                          réception en cours
 * &lt;racine&gt;/&lt;AET appelé&gt;/&lt;partenaire&gt;/&lt;Study UID&gt;/&lt;Series UID&gt;/&lt;SOP UID&gt;.dcm   instance reçue
 * </pre>
 *
 * Cette organisation suffit à reconstituer les études en attente après un redémarrage. Une instance destinée à
 * plusieurs partenaires (routage par règles) y est en un exemplaire par partenaire : chaque transfert supprime les
 * siens. Les instances rangées sans partenaire (versions antérieures) sont encore reprises.
 */
final class IncomingStore {

    private static final System.Logger LOG = System.getLogger(IncomingStore.class.getName());
    private static final String TEMP_DIR = ".tmp";
    private static final String SUFFIX = ".dcm";

    /** Instance retrouvée sur le disque au démarrage. */
    /** @param destination partenaire, ou {@code null} pour une instance rangée par une version antérieure */
    record Recovered(String calledAet, String destination, String studyInstanceUid, ReceivedInstance instance) {
    }

    private final Path root;

    IncomingStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    /** Crée les dossiers et supprime les réceptions interrompues. */
    void initialize() throws IOException {
        Path temp = root.resolve(TEMP_DIR);
        Files.createDirectories(temp);
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(temp)) {
            for (Path part : parts) {
                Files.deleteIfExists(part);
            }
        }
    }

    Path newTempFile() {
        return root.resolve(TEMP_DIR).resolve(UUID.randomUUID() + ".part");
    }

    /** Emplacement définitif ; les UID doivent avoir été validés par {@link com.imagunity.domain.exam.DicomUids}. */
    Path finalPath(String calledAet, String destination, String studyUid, String seriesUid, String sopInstanceUid) {
        Path path = root.resolve(calledAet).resolve(destination).resolve(studyUid).resolve(seriesUid)
                .resolve(sopInstanceUid + SUFFIX).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Chemin hors du dossier de réception");
        }
        return path;
    }

    /** Copie une instance reçue vers l'emplacement d'un autre partenaire (lien physique si possible). */
    void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);
        try {
            Files.createLink(target, source);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(source, target);
        }
    }

    /** Déplace atomiquement une réception terminée à son emplacement définitif. */
    void commit(Path temp, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Retrouve les instances reçues mais pas encore prises en charge. */
    List<Recovered> recover() throws IOException {
        List<Recovered> recovered = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root, 5)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(SUFFIX))::iterator) {
                Path relative = root.relativize(file);
                int depth = relative.getNameCount();
                if ((depth != 4 && depth != 5) || relative.getName(0).toString().equals(TEMP_DIR)) {
                    continue;
                }
                try {
                    String destination = depth == 5 ? relative.getName(1).toString() : null;
                    recovered.add(read(relative.getName(0).toString(), destination,
                            relative.getName(depth - 3).toString(), relative.getName(depth - 2).toString(), file));
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Instance illisible ignorée à la reprise : " + relative, e);
                }
            }
        }
        return recovered;
    }

    private static Recovered read(String calledAet, String destination, String studyUid, String seriesUid, Path file)
            throws IOException {
        String fileName = file.getFileName().toString();
        String sopInstanceUid = fileName.substring(0, fileName.length() - SUFFIX.length());
        Attributes fmi;
        try (DicomInputStream in = new DicomInputStream(file.toFile())) {
            fmi = in.readFileMetaInformation();
        }
        if (fmi == null) {
            throw new IOException("Pas de méta-informations DICOM");
        }
        ReceivedInstance instance = new ReceivedInstance(
                fmi.getString(Tag.MediaStorageSOPClassUID), sopInstanceUid, seriesUid,
                fmi.getString(Tag.TransferSyntaxUID), file, Files.size(file));
        return new Recovered(calledAet, destination, studyUid, instance);
    }
}

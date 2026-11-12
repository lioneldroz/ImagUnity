package com.imagunity.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Secret scellé dans le TPM de la box, par les outils {@code tpm2-tools} : seul ce TPM peut le desceller, et
 * seulement si l'état de démarrage mesuré (registres PCR choisis) est celui du scellement.
 *
 * <p>Le TPM ne sait pas conserver de clé ML-DSA ni ML-KEM : il garde le secret qui chiffre les fichiers de clés de la
 * box. Le secret scellé ({@code <base>.tpm.pub}, {@code <base>.tpm.priv}) est chiffré par la clé de stockage du TPM,
 * recréée à l'identique à chaque usage (clé primaire du propriétaire) : copié ailleurs, il ne s'ouvre pas.
 *
 * <p>Le TPM est désigné par la variable {@code TPM2TOOLS_TCTI} (par défaut {@code /dev/tpmrm0}). Les objets
 * transitoires sont vidés entre deux commandes, pour fonctionner aussi sans gestionnaire de ressources.
 *
 * <p>Clé primaire et secret scellé sont exclus de la protection contre les attaques par dictionnaire
 * ({@code noda}) : sans cela, une box sans surveillance passerait en verrouillage après quelques arrêts non propres
 * (coupures de courant), que le TPM compte comme des tentatives suspectes, et ne démarrerait plus pendant des heures.
 * Ces objets n'ont pas de mot de passe à deviner ; le secret est protégé par le TPM lui-même et, si voulu, par la
 * politique PCR, qui reste obligatoire ({@code userwithauth} absent). Un secret scellé par une version antérieure
 * (sans {@code noda}) est descellé avec l'ancien modèle, puis scellé de nouveau.
 */
final class TpmSeal {

    /** Exécute une commande ; renvoie sa sortie standard, ou échoue avec son message d'erreur. */
    @FunctionalInterface
    interface Runner {
        byte[] run(List<String> command, byte[] input) throws IOException;
    }

    /** Modèle de la clé primaire de stockage (attributs par défaut, plus {@code noda}). */
    static final String PRIMARY_ATTRIBUTES =
            "restricted|decrypt|fixedtpm|fixedparent|sensitivedataorigin|userwithauth|noda";
    private static final String SEALED_ATTRIBUTES = "fixedtpm|fixedparent|noda";

    private final Path publicPart;
    private final Path privatePart;
    private final String pcrs;
    private final Runner runner;

    /**
     * @param base fichier de référence (identité de la box) : le secret scellé est à côté
     * @param pcrs registres PCR auxquels lier le secret, par exemple {@code sha256:0,7} ({@code null} ou vide : aucun)
     */
    TpmSeal(Path base, String pcrs, Runner runner) {
        this.publicPart = Path.of(base + ".tpm.pub");
        this.privatePart = Path.of(base + ".tpm.priv");
        this.pcrs = pcrs == null || pcrs.isBlank() ? null : pcrs.strip();
        this.runner = runner;
    }

    TpmSeal(Path base, String pcrs) {
        this(base, pcrs, TpmSeal::exec);
    }

    boolean exists() {
        return Files.exists(publicPart) && Files.exists(privatePart);
    }

    /** Scelle ce secret (le secret scellé précédent est remplacé d'un coup). */
    void seal(byte[] secret) throws IOException {
        Path work = workDir();
        try {
            Path primary = work.resolve("primaire.ctx");
            flush();
            createPrimary(primary, false);
            flush();
            List<String> create = new ArrayList<>(List.of("tpm2_create", "-Q", "-C", primary.toString(), "-i", "-",
                    "-u", work.resolve("scelle.pub").toString(), "-r", work.resolve("scelle.priv").toString(),
                    // Avec une politique PCR, pas de « userwithauth » : la politique est alors obligatoire.
                    "-a", pcrs != null ? SEALED_ATTRIBUTES : SEALED_ATTRIBUTES + "|userwithauth"));
            if (pcrs != null) {
                Path values = work.resolve("pcr.bin");
                Path policy = work.resolve("politique");
                run("tpm2_pcrread", "-Q", "-o", values.toString(), pcrs);
                run("tpm2_createpolicy", "-Q", "--policy-pcr", "-l", pcrs, "-f", values.toString(), "-L",
                        policy.toString());
                flush();
                create.addAll(List.of("-L", policy.toString()));
            }
            runner.run(create, secret);
            flush();
            for (Path part : List.of(work.resolve("scelle.pub"), work.resolve("scelle.priv"))) {
                if (part.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(part, PosixFilePermissions.fromString("rw-------"));
                }
            }
            Files.move(work.resolve("scelle.pub"), publicPart, StandardCopyOption.REPLACE_EXISTING);
            Files.move(work.resolve("scelle.priv"), privatePart, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * Descelle le secret ; un secret scellé par une version antérieure est scellé de nouveau, au modèle actuel.
     *
     * @throws IOException si le TPM refuse (autre TPM, état de démarrage différent) ou ne répond pas
     */
    byte[] unseal() throws IOException {
        IOException current;
        try {
            return unseal(false);
        } catch (IOException e) {
            current = e;
        }
        byte[] secret;
        try {
            secret = unseal(true);
        } catch (IOException legacy) {
            throw current;
        }
        seal(secret);
        System.getLogger(TpmSeal.class.getName()).log(System.Logger.Level.INFO,
                "Secret scellé par une version antérieure : scellé de nouveau, exclu du verrouillage anti-force brute");
        return secret;
    }

    private byte[] unseal(boolean legacyPrimary) throws IOException {
        Path work = workDir();
        try {
            Path primary = work.resolve("primaire.ctx");
            Path sealed = work.resolve("scelle.ctx");
            flush();
            createPrimary(primary, legacyPrimary);
            flush();
            run("tpm2_load", "-Q", "-C", primary.toString(), "-u", publicPart.toString(), "-r",
                    privatePart.toString(), "-c", sealed.toString());
            flush();
            List<String> unseal = new ArrayList<>(List.of("tpm2_unseal", "-c", sealed.toString()));
            if (pcrs != null) {
                unseal.addAll(List.of("-p", "pcr:" + pcrs));
            }
            byte[] secret = runner.run(unseal, null);
            flush();
            return secret;
        } finally {
            deleteRecursively(work);
        }
    }

    /** Clé primaire de stockage, recréée à l'identique à chaque usage ; modèle antérieur : sans {@code noda}. */
    private void createPrimary(Path context, boolean legacy) throws IOException {
        List<String> command = new ArrayList<>(List.of("tpm2_createprimary", "-Q", "-C", "o", "-g", "sha256", "-G",
                "ecc", "-c", context.toString()));
        if (!legacy) {
            command.addAll(List.of("-a", PRIMARY_ATTRIBUTES));
        }
        runner.run(command, null);
    }

    private void run(String... command) throws IOException {
        runner.run(List.of(command), null);
    }

    /** Vide les objets et sessions transitoires ; sans effet notable avec un gestionnaire de ressources. */
    private void flush() {
        for (String kind : List.of("-t", "-s")) {
            try {
                runner.run(List.of("tpm2_flushcontext", kind), null);
            } catch (IOException e) {
                // rien à vider, ou TPM injoignable : la commande suivante le dira
            }
        }
    }

    private Path workDir() throws IOException {
        Path parent = publicPart.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, ".tpm-");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Lance une commande tpm2-tools (60 s au plus). */
    static byte[] exec(List<String> command, byte[] input) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.PIPE).start();
        } catch (IOException e) {
            throw new IOException("Outils TPM absents (" + command.getFirst() + ") : installer tpm2-tools", e);
        }
        try (OutputStream in = process.getOutputStream()) {
            if (input != null) {
                in.write(input);
            }
        }
        byte[] output;
        byte[] errors;
        try (InputStream out = process.getInputStream(); InputStream err = process.getErrorStream()) {
            // Lues en parallèle : un flux plein bloquerait l'autre.
            java.util.concurrent.CompletableFuture<byte[]> stderr = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return err.readAllBytes();
                        } catch (IOException e) {
                            return new byte[0];
                        }
                    });
            output = out.readAllBytes();
            errors = stderr.join();
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.getFirst() + " : pas de réponse du TPM");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(command.getFirst() + " interrompu", e);
        }
        if (process.exitValue() != 0) {
            String message = new String(errors, java.nio.charset.StandardCharsets.UTF_8).strip();
            String[] lines = message.split("\n");
            // La ligne qui porte le code du TPM dit la cause ; la dernière n'est souvent que « Unable to run ».
            String cause = java.util.Arrays.stream(lines).filter(l -> l.contains("tpm:")).findFirst()
                    .orElse(lines[lines.length - 1]).strip();
            if (message.contains("lockout")) {
                cause += " (TPM en verrouillage anti-force brute : voir docs/cles-tpm.md)";
            }
            throw new IOException(command.getFirst() + " a échoué : " + cause);
        }
        return output;
    }
}

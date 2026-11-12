package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.imagunity.directory.KeyFiles;
import com.imagunity.orchestration.BoxIdentity;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TpmSealTest {

    @TempDir
    Path dir;

    /**
     * TPM simulé : le « chiffré » est le secret préfixé du nom du TPM, que seul ce TPM accepte de recharger ; les
     * registres PCR valent {@code pcr}.
     */
    static final class FakeTpm implements TpmSeal.Runner {
        final String name;
        String pcr = "0";
        boolean broken;
        final List<String> commands = new ArrayList<>();

        FakeTpm(String name) {
            this.name = name;
        }

        @Override
        public byte[] run(List<String> command, byte[] input) throws IOException {
            commands.add(String.join(" ", command));
            if (broken) {
                throw new IOException(command.getFirst() + " a échoué : TPM injoignable");
            }
            switch (command.getFirst()) {
                case "tpm2_flushcontext" -> {
                    if (command.get(1).equals("-s")) {
                        throw new IOException("rien à vider");
                    }
                }
                case "tpm2_createprimary" -> Files.writeString(after(command, "-c"), "primaire " + name);
                case "tpm2_pcrread" -> Files.writeString(after(command, "-o"), pcr);
                case "tpm2_createpolicy" -> Files.writeString(after(command, "-L"),
                        "pcr=" + Files.readString(after(command, "-f")));
                case "tpm2_create" -> {
                    String policy = command.contains("-L") ? Files.readString(after(command, "-L")) : "";
                    Files.writeString(after(command, "-u"), policy);
                    Files.write(after(command, "-r"), (name + ":" + new String(input, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8));
                }
                case "tpm2_load" -> {
                    String priv = Files.readString(after(command, "-r"));
                    if (!priv.startsWith(name + ":")) {
                        throw new IOException("tpm2_load a échoué : integrity check failed");
                    }
                    Files.writeString(after(command, "-c"), Files.readString(after(command, "-u")) + "|"
                            + priv.substring(name.length() + 1));
                }
                case "tpm2_unseal" -> {
                    String[] ctx = Files.readString(after(command, "-c")).split("\\|", 2);
                    if (!ctx[0].isEmpty() && !ctx[0].equals("pcr=" + pcr)) {
                        throw new IOException("tpm2_unseal a échoué : policy check failed");
                    }
                    return ctx[1].getBytes(StandardCharsets.UTF_8);
                }
                default -> throw new IOException("commande inattendue " + command);
            }
            return new byte[0];
        }

        private static Path after(List<String> command, String option) {
            return Path.of(command.get(command.indexOf(option) + 1));
        }
    }

    @Test
    void secretIsOnlyRecoveredByTheSameTpmInTheSameBootState() throws IOException {
        FakeTpm tpm = new FakeTpm("tpm-b");
        TpmSeal seal = new TpmSeal(dir.resolve("identite.json"), " sha256:0,7 ", tpm);
        assertThat(seal.exists()).isFalse();

        seal.seal("secret".getBytes(StandardCharsets.UTF_8));

        assertThat(seal.exists()).isTrue();
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(
                dir.resolve("identite.json.tpm.priv")))).isEqualTo("rw-------");
        assertThat(new String(seal.unseal(), StandardCharsets.UTF_8)).isEqualTo("secret");
        assertThat(tpm.commands).anyMatch(c -> c.startsWith("tpm2_unseal") && c.endsWith("-p pcr:sha256:0,7"));
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("identite.json.tpm.pub", "identite.json.tpm.priv");
        }

        tpm.pcr = "1";
        assertThatThrownBy(seal::unseal).hasMessageContaining("policy check failed");
        assertThatThrownBy(() -> new TpmSeal(dir.resolve("identite.json"), "sha256:7", new FakeTpm("autre")).unseal())
                .hasMessageContaining("integrity check failed");
        TpmSeal unbound = new TpmSeal(dir.resolve("libre.json"), null, tpm);
        unbound.seal("libre".getBytes(StandardCharsets.UTF_8));
        tpm.pcr = "2";
        assertThat(new String(unbound.unseal(), StandardCharsets.UTF_8)).isEqualTo("libre");
    }

    @Test
    void identityIsCreatedMigratedAndReopenedThroughTheTpm() throws IOException {
        FakeTpm tpm = new FakeTpm("tpm-b");
        char[] phrase = "phrase de la box de test".toCharArray();

        // Box neuve : secret tiré, scellé ; clés créées sous ce secret.
        Path fresh = dir.resolve("neuve/identite.json");
        BoxIdentity created = DirectoryConfiguration.loadOrCreateIdentity(fresh, null, new TpmSeal(fresh, "", tpm));
        assertThat(KeyFiles.isProtected(fresh)).isTrue();
        assertThat(KeyFiles.isProtected(DirectoryConfiguration.encryptionFileOf(fresh))).isTrue();
        assertThat(DirectoryConfiguration.loadOrCreateIdentity(fresh, null, new TpmSeal(fresh, "", tpm)).boxId())
                .isEqualTo(created.boxId());

        // Box existante, clés sous phrase de passe : migrées, puis la phrase ne sert plus.
        Path older = dir.resolve("ancienne/identite.json");
        BoxIdentity before = DirectoryConfiguration.loadOrCreateIdentity(older, phrase);
        assertThatThrownBy(() -> DirectoryConfiguration.loadOrCreateIdentity(older, null, new TpmSeal(older, "", tpm)))
                .hasMessageContaining("la fournir une dernière fois");
        BoxIdentity migrated = DirectoryConfiguration.loadOrCreateIdentity(older, phrase, new TpmSeal(older, "", tpm));
        assertThat(migrated.boxId()).isEqualTo(before.boxId());
        assertThat(migrated.encryption().publicKey().fingerprint())
                .isEqualTo(before.encryption().publicKey().fingerprint());
        assertThatThrownBy(() -> KeyFiles.readPrivate(older, phrase)).hasMessageContaining("incorrecte");
        assertThat(DirectoryConfiguration.loadOrCreateIdentity(older, null, new TpmSeal(older, "", tpm)).boxId())
                .isEqualTo(before.boxId());

        // Box existante en clair.
        Path clear = dir.resolve("claire/identite.json");
        DirectoryConfiguration.loadOrCreateIdentity(clear);
        DirectoryConfiguration.loadOrCreateIdentity(clear, null, new TpmSeal(clear, "", tpm));
        assertThat(KeyFiles.isProtected(clear)).isTrue();

        // Fichiers de clés copiés sur une autre box : son TPM refuse.
        assertThatThrownBy(() -> DirectoryConfiguration.loadOrCreateIdentity(older, phrase,
                new TpmSeal(older, "", new FakeTpm("tpm-c")))).hasMessageContaining("Le TPM refuse de desceller");
        FakeTpm broken = new FakeTpm("tpm-d");
        broken.broken = true;
        assertThatThrownBy(() -> DirectoryConfiguration.loadOrCreateIdentity(dir.resolve("x/identite.json"), null,
                new TpmSeal(dir.resolve("x/identite.json"), "", broken))).hasMessageContaining("TPM injoignable");
    }

    @Test
    void commandsAreRunWithTheirInputAndErrors() throws IOException {
        assertThat(new String(TpmSeal.exec(List.of("cat"), "entrée".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)).isEqualTo("entrée");
        assertThat(TpmSeal.exec(List.of("true"), null)).isEmpty();
        assertThatThrownBy(() -> TpmSeal.exec(List.of("sh", "-c", "echo détail >&2; echo cause >&2; exit 3"), null))
                .hasMessage("sh a échoué : cause");
        assertThatThrownBy(() -> TpmSeal.exec(List.of("commande-tpm-inexistante"), null))
                .hasMessageContaining("installer tpm2-tools");
    }

    @Test
    void tpmModeIsSelectedByConfiguration() throws IOException {
        Path keys = dir.resolve("config/identite.json");
        DirectoryProperties properties = new DirectoryProperties(true, null, null, null, keys,
                java.time.Duration.ofMinutes(5), null, null, true, true, "");
        // Selon la machine, un TPM est joignable ou non : dans les deux cas, c'est bien le TPM qui protège les clés,
        // et sans TPM la box ne démarre pas (jamais de repli silencieux sur des clés en clair).
        try {
            new DirectoryConfiguration().boxIdentity(properties);
            assertThat(Path.of(keys + ".tpm.priv")).exists();
            assertThat(KeyFiles.isProtected(keys)).isTrue();
        } catch (IOException e) {
            assertThat(e).hasMessageContaining("tpm2_");
            assertThat(keys).doesNotExist();
        }
    }

    /** Vrai TPM logiciel (swtpm), si installé sur la machine : le test est sauté sinon (CI). */
    @Test
    void withARealSoftwareTpm() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/swtpm")) && Files.isExecutable(Path.of("/usr/bin/tpm2_unseal")),
                "swtpm et tpm2-tools absents");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path state = Files.createDirectories(dir.resolve("etat-tpm"));
        Process swtpm = new ProcessBuilder("swtpm", "socket", "--tpmstate", "dir=" + state, "--tpm2", "--server",
                "type=tcp,port=" + port, "--ctrl", "type=tcp,port=" + (port + 1), "--flags",
                "not-need-init,startup-clear").redirectErrorStream(true).start();
        try {
            Thread.sleep(500);
            String tcti = "swtpm:host=127.0.0.1,port=" + port;
            TpmSeal.Runner runner = (command, input) -> {
                List<String> withTcti = new ArrayList<>(List.of("env", "TPM2TOOLS_TCTI=" + tcti));
                withTcti.addAll(command);
                return TpmSeal.exec(withTcti, input);
            };
            Path keys = dir.resolve("reel/identite.json");
            BoxIdentity created = DirectoryConfiguration.loadOrCreateIdentity(keys, null,
                    new TpmSeal(keys, "sha256:7", runner));
            assertThat(DirectoryConfiguration.loadOrCreateIdentity(keys, null, new TpmSeal(keys, "sha256:7", runner))
                    .boxId()).isEqualTo(created.boxId());
            // État de démarrage modifié (PCR 7 étendu) : le TPM refuse de desceller.
            runner.run(List.of("tpm2_pcrextend", "7:sha256=" + "00".repeat(31) + "01"), null);
            assertThatThrownBy(() -> DirectoryConfiguration.loadOrCreateIdentity(keys, null,
                    new TpmSeal(keys, "sha256:7", runner))).hasMessageContaining("Le TPM refuse de desceller");
        } finally {
            swtpm.destroy();
        }
    }
}

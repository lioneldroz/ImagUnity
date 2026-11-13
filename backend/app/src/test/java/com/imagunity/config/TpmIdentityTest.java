package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.TpmSeal;
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

class TpmIdentityTest {

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
                case "tpm2_createprimary" -> Files.writeString(after(command, "-c"), name + "|"
                        + (command.contains("-a") ? command.get(command.indexOf("-a") + 1) : "ancien"));
                case "tpm2_pcrread" -> Files.writeString(after(command, "-o"), pcr);
                case "tpm2_createpolicy" -> Files.writeString(after(command, "-L"),
                        "pcr=" + Files.readString(after(command, "-f")));
                case "tpm2_create" -> {
                    String policy = command.contains("-L") ? Files.readString(after(command, "-L")) : "";
                    Files.writeString(after(command, "-u"), policy);
                    Files.write(after(command, "-r"), (Files.readString(after(command, "-C")) + "#"
                            + new String(input, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                }
                case "tpm2_load" -> {
                    String priv = Files.readString(after(command, "-r"));
                    String primary = Files.readString(after(command, "-C")) + "#";
                    if (!priv.startsWith(primary)) {
                        throw new IOException("tpm2_load a échoué : integrity check failed");
                    }
                    Files.writeString(after(command, "-c"), Files.readString(after(command, "-u")) + "|"
                            + priv.substring(primary.length()));
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
    void tpmModeIsSelectedByConfiguration() throws IOException {
        Path keys = dir.resolve("config/identite.json");
        DirectoryProperties properties = new DirectoryProperties(true, null, null, null, keys,
                java.time.Duration.ofMinutes(5), null, null, true, "");
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
            // TPM en verrouillage anti-force brute (un seul essai admis, puis un mauvais mot de passe) : la box
            // rouvre quand même son identité, ses objets étant exclus de cette protection.
            Path work = Files.createDirectories(dir.resolve("verrouillage"));
            runner.run(List.of("tpm2_dictionarylockout", "-Q", "-s", "-n", "1", "-t", "3600", "-l", "3600"), null);
            runner.run(List.of("tpm2_flushcontext", "-t"), null);
            runner.run(List.of("tpm2_createprimary", "-Q", "-C", "o", "-p", "protege", "-c",
                    work.resolve("p.ctx").toString()), null);
            runner.run(List.of("tpm2_flushcontext", "-t"), null);
            for (int i = 0; i < 2; i++) {
                try {
                    runner.run(List.of("tpm2_create", "-Q", "-C", work.resolve("p.ctx").toString(), "-P", "faux",
                            "-i", "-", "-u", work.resolve("x.pub").toString(), "-r",
                            work.resolve("x.priv").toString()), "x".getBytes(StandardCharsets.UTF_8));
                } catch (IOException expected) {
                    if (i == 1) {
                        assertThat(expected).hasMessageContaining("verrouillage anti-force brute");
                    }
                }
            }
            assertThat(DirectoryConfiguration.loadOrCreateIdentity(keys, null, new TpmSeal(keys, "sha256:7", runner))
                    .boxId()).as("malgré le verrouillage").isEqualTo(created.boxId());
            // État de démarrage modifié (PCR 7 étendu) : le TPM refuse de desceller.
            runner.run(List.of("tpm2_pcrextend", "7:sha256=" + "00".repeat(31) + "01"), null);
            assertThatThrownBy(() -> DirectoryConfiguration.loadOrCreateIdentity(keys, null,
                    new TpmSeal(keys, "sha256:7", runner))).hasMessageContaining("Le TPM refuse de desceller");
        } finally {
            swtpm.destroy();
        }
    }

}

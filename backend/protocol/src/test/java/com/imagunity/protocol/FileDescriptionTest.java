package com.imagunity.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileDescriptionTest {

    private static final String SHA = "a".repeat(64);
    private static final String TRANSFER = "AAECAwQFBgcICQoLDA0ODw";
    private static final String RECIPIENT = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    private static final Instant EXPIRES = Instant.parse("2026-11-15T17:00:00Z");

    private static String key() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return Codec.base64Url(pair.getPrivate().getEncoded());
    }

    private static FileDescription.Replica replica(String relay) throws Exception {
        return new FileDescription.Replica(relay, RECIPIENT, key());
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        FileDescription.Replica replica = replica("https://relais-1.org.example");
        FileDescription description = new FileDescription("IU1", TRANSFER, FileDescription.COMPLETE,
                "groupe-1-of-2.tar.zst.iu1", ChunkLayout.MIB, 2, ChunkLayout.MIB + 5, SHA,
                List.of(new FileDescription.Chunk(0, SHA, List.of(replica)),
                        new FileDescription.Chunk(1, SHA, List.of(replica, replica("http://relais-2:8443")))),
                EXPIRES);

        FileDescription back = FileDescription.fromJson(description.toJson());

        assertThat(back).isEqualTo(description);
        assertThat(back.layout().payload(1)).isEqualTo(5);
        assertThat(back.chunks().get(0).replicas().get(0).privateRetrievalKey().getAlgorithm()).isIn("Ed25519", "EdDSA");
        assertThat(description.toJson()).doesNotContain("fileKey");
    }

    @Test
    void invalidDescriptionsAreRefused() throws Exception {
        FileDescription.Chunk chunk = new FileDescription.Chunk(0, SHA, List.of(replica("https://r.example")));
        List<FileDescription.Chunk> one = List.of(chunk);
        assertThatThrownBy(() -> new FileDescription("IU2", TRANSFER, "complete", "f.iu1", ChunkLayout.MIB, 1, 10, SHA,
                one, EXPIRES)).hasMessageContaining("Suite");
        assertThatThrownBy(() -> new FileDescription("IU1", "x", "complete", "f.iu1", ChunkLayout.MIB, 1, 10, SHA, one,
                EXPIRES)).hasMessageContaining("identifiant de transfert");
        assertThatThrownBy(() -> new FileDescription("IU1", TRANSFER, "early", "f.iu1", ChunkLayout.MIB, 1, 10, SHA,
                one, EXPIRES)).hasMessageContaining("mode");
        assertThatThrownBy(() -> new FileDescription("IU1", TRANSFER, "complete", "../f", ChunkLayout.MIB, 1, 10, SHA,
                one, EXPIRES)).hasMessageContaining("nom");
        assertThatThrownBy(() -> new FileDescription("IU1", TRANSFER, "complete", "a..b", ChunkLayout.MIB, 1, 10, SHA,
                one, EXPIRES)).hasMessageContaining("nom");
        assertThatThrownBy(() -> new FileDescription("IU1", TRANSFER, "complete", "f.iu1", ChunkLayout.MIB, 1, 10, "x",
                one, EXPIRES)).hasMessageContaining("empreinte du paquet");
        assertThatThrownBy(() -> new FileDescription("IU1", TRANSFER, "complete", "f.iu1", ChunkLayout.MIB, 1, 10, SHA,
                List.of(), EXPIRES)).hasMessageContaining("nombre de morceaux");
        assertThatThrownBy(() -> new FileDescription("IU1", TRANSFER, "complete", "f.iu1", ChunkLayout.MIB, 1, 10, SHA,
                null, EXPIRES)).hasMessageContaining("nombre de morceaux");
        assertThatThrownBy(() -> new FileDescription("IU1", TRANSFER, "complete", "f.iu1", ChunkLayout.MIB, 1, 10, SHA,
                List.of(new FileDescription.Chunk(3, SHA, chunk.replicas())), EXPIRES)).hasMessageContaining("ordre");
        assertThatThrownBy(() -> new FileDescription.Chunk(0, "x", chunk.replicas())).hasMessageContaining("empreinte");
        assertThatThrownBy(() -> new FileDescription.Chunk(0, SHA, List.of())).hasMessageContaining("sans relais");
        assertThatThrownBy(() -> new FileDescription.Chunk(0, SHA, null)).hasMessageContaining("sans relais");
        assertThatThrownBy(() -> replica("ftp://r.example")).hasMessageContaining("adresse");
        assertThatThrownBy(() -> replica("https://r.example/?a=1")).hasMessageContaining("adresse");
        assertThatThrownBy(() -> replica("https://r.example/#x")).hasMessageContaining("adresse");
        assertThatThrownBy(() -> replica("https:///chemin")).hasMessageContaining("adresse");
        assertThatThrownBy(() -> replica("pas une url")).hasMessageContaining("adresse");
        assertThatThrownBy(() -> replica(null)).hasMessageContaining("adresse");
        assertThatThrownBy(() -> new FileDescription.Replica("https://r.example", "court", key()))
                .hasMessageContaining("identifiant de retrait");
        assertThatThrownBy(() -> new FileDescription.Replica("https://r.example", RECIPIENT, "AAAA"))
                .hasMessageContaining("clé de retrait");
        assertThatThrownBy(() -> new FileDescription.Replica("https://r.example", RECIPIENT, "%%%"))
                .hasMessageContaining("clé de retrait");
        assertThatThrownBy(() -> FileDescription.fromJson("{\"inconnu\":1}")).hasMessageContaining("illisible");
        assertThatThrownBy(() -> FileDescription.fromJson("null")).hasMessageContaining("vide");
    }
}

package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MultipartRelatedReaderTest {

    private static final String B = "frontiere-42";

    static byte[] body(String preamble, List<byte[]> parts, String type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(preamble.getBytes(StandardCharsets.ISO_8859_1));
        for (byte[] part : parts) {
            out.write(("--" + B + "\r\n" + (type == null ? "" : "Content-Type: " + type + "\r\n") + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(part);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        out.write(("--" + B + "--\r\népilogue ignoré").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** Lecture par petits morceaux : les délimiteurs tombent à cheval sur les lectures. */
    private static InputStream trickle(byte[] data) {
        return new ByteArrayInputStream(data) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 7));
            }
        };
    }

    private static List<byte[]> readAll(InputStream in) throws IOException {
        MultipartRelatedReader reader = new MultipartRelatedReader(in, B);
        List<byte[]> parts = new ArrayList<>();
        while (reader.next()) {
            assertThat(reader.headers()).containsEntry("content-type", "application/dicom");
            parts.add(reader.body().readAllBytes());
            assertThat(reader.body().read()).as("partie terminée").isEqualTo(-1);
        }
        assertThat(reader.next()).isFalse();
        return parts;
    }

    @Test
    void partsAreReadExactlyEvenWithBoundaryLikeContent() throws IOException {
        byte[] large = new byte[300_000];
        new Random(1).nextBytes(large);
        byte[] tricky = ("début\r\n--" + B.substring(0, 5) + " presque\r\n-- fin\r\n").getBytes(StandardCharsets.UTF_8);
        List<byte[]> parts = List.of(large, tricky, new byte[0]);

        assertThat(readAll(new ByteArrayInputStream(body("préambule ignoré\r\n", parts, "application/dicom"))))
                .containsExactlyElementsOf(parts);
        assertThat(readAll(trickle(body("", parts, "application/dicom")))).containsExactlyElementsOf(parts);
    }

    @Test
    void unreadPartsAreSkippedAndPaddingAccepted() throws IOException {
        byte[] data = ("--" + B + "  \r\nContent-Type: application/dicom\r\nX-Autre:  valeur \r\n\r\nun\r\n--" + B
                + "\r\nContent-Type: application/dicom\r\n\r\ndeux\r\n--" + B + "--").getBytes(StandardCharsets.UTF_8);
        MultipartRelatedReader reader = new MultipartRelatedReader(new ByteArrayInputStream(data), B);

        assertThat(reader.next()).isTrue();
        assertThat(reader.headers()).containsEntry("x-autre", "valeur");
        assertThat(reader.next()).as("partie non lue, ignorée").isTrue();
        assertThat(new String(reader.body().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("deux");
        assertThat(reader.next()).isFalse();
        assertThat(reader.body().read(new byte[4], 0, 4)).isEqualTo(-1);
    }

    @Test
    void malformedBodiesAreRefused() {
        assertThatThrownBy(() -> readAll(new ByteArrayInputStream("pas de délimiteur".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("sans délimiteur");
        assertThatThrownBy(() -> readAll(new ByteArrayInputStream(("--" + B).getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("tronqué après un délimiteur");
        assertThatThrownBy(() -> readAll(new ByteArrayInputStream(("--" + B + "x\r\n").getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("mal formé");
        assertThatThrownBy(() -> readAll(new ByteArrayInputStream(("--" + B + "\r\nContent-Type: application/dicom\r\n")
                .getBytes(StandardCharsets.UTF_8)))).hasMessageContaining("tronqué dans les en-têtes");
        assertThatThrownBy(() -> readAll(new ByteArrayInputStream(("--" + B + "\r\nSans deux-points\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8)))).hasMessageContaining("En-tête de partie mal formé");
        assertThatThrownBy(() -> readAll(new ByteArrayInputStream(("--" + B + "\r\nA: b\rc\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8)))).hasMessageContaining("Fin de ligne invalide");
        assertThatThrownBy(() -> readAll(new ByteArrayInputStream(("--" + B
                + "\r\nContent-Type: application/dicom\r\n\r\ncontenu sans fin").getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("délimiteur de fin absent");
        String longHeader = "--" + B + "\r\nX: " + "a".repeat(20_000) + "\r\n\r\n";
        assertThatThrownBy(() -> readAll(new ByteArrayInputStream(longHeader.getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("trop longs");
        String manyHeaders = "--" + B + "\r\n" + "X: aaaaaaaaaa\r\n".repeat(2_000) + "\r\n";
        assertThatThrownBy(() -> readAll(new ByteArrayInputStream(manyHeaders.getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("trop longs");
        assertThatThrownBy(() -> new MultipartRelatedReader(InputStream.nullInputStream(), ""))
                .hasMessageContaining("Délimiteur");
        assertThatThrownBy(() -> new MultipartRelatedReader(InputStream.nullInputStream(),
                "x".repeat(MultipartRelatedReader.MAX_BOUNDARY + 1))).hasMessageContaining("Délimiteur");
        assertThatThrownBy(() -> new MultipartRelatedReader(InputStream.nullInputStream(), null))
                .hasMessageContaining("Délimiteur");
    }

    @Test
    void longBoundariesOfRealPacsAreAccepted() throws IOException {
        String orthanc = "d3af3f70-833b-4fb4-a39e-c6e9073bc4c4-d3af3f70-833b-4fb4-a39e-c6e9073bc4c4"; // 73 caractères
        byte[] data = ("--" + orthanc + "\r\nContent-Type: application/dicom\r\n\r\nx\r\n--" + orthanc + "--")
                .getBytes(StandardCharsets.US_ASCII);
        MultipartRelatedReader reader = new MultipartRelatedReader(new ByteArrayInputStream(data), orthanc);
        assertThat(reader.next()).isTrue();
        assertThat(reader.body().readAllBytes()).containsExactly('x');
    }

    @Test
    void singleByteReadsWork() throws IOException {
        MultipartRelatedReader reader = new MultipartRelatedReader(new ByteArrayInputStream(
                body("", List.of(new byte[] {(byte) 0xff, 1}), "application/dicom")), B);
        assertThat(reader.next()).isTrue();
        InputStream part = reader.body();
        assertThat(part.read()).isEqualTo(0xff);
        assertThat(part.read()).isEqualTo(1);
        assertThat(part.read()).isEqualTo(-1);
        assertThat(part.read(new byte[1], 0, 0)).isZero();
    }

    @Test
    void contentTypeParameters() {
        String type = "multipart/related; type=\"application/dicom\"; boundary=abc";
        assertThat(MultipartRelatedReader.parameter(type, "TYPE")).isEqualTo("application/dicom");
        assertThat(MultipartRelatedReader.parameter(type, "boundary")).isEqualTo("abc");
        assertThat(MultipartRelatedReader.parameter(type, "start")).isNull();
        assertThat(MultipartRelatedReader.parameter(null, "boundary")).isNull();
        assertThat(MultipartRelatedReader.parameter("a; b=\"", "b")).isEqualTo("\"");
    }
}

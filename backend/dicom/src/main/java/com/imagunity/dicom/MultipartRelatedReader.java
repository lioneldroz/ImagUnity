package com.imagunity.dicom;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lecture en flux d'un corps {@code multipart/related} (RFC 2387, RFC 2046) : chaque partie est lue au fil de l'eau,
 * sans être chargée en mémoire, ce qui permet des instances de plusieurs centaines de Mo.
 *
 * <pre>
 * while (reader.next()) {
 *     reader.headers();   // en-têtes de la partie, noms en minuscules
 *     reader.body();      // contenu de la partie, jusqu'au délimiteur suivant
 * }
 * </pre>
 */
final class MultipartRelatedReader {

    private static final int BUFFER = 64 * 1024;
    private static final int MAX_HEADERS = 16 * 1024;
    static final int MAX_BOUNDARY = 200;

    private final InputStream in;
    /** {@code CRLF--boundary} : un délimiteur est toujours précédé d'une fin de ligne. */
    private final byte[] delimiter;
    private byte[] buf = new byte[BUFFER];
    private int pos;
    private int lim;
    private boolean eof;
    private boolean finished;
    private boolean inBody;
    /** Vrai juste après un délimiteur (partie terminée) : le suivant n'est pas à chercher. */
    private boolean afterDelimiter;
    private Map<String, String> headers = Map.of();

    /**
     * @param boundary délimiteur ; la RFC 2046 le limite à 70 caractères, mais des PACS en emploient de plus longs
     *                 (Orthanc : 73), d'où une limite plus large
     */
    MultipartRelatedReader(InputStream in, String boundary) {
        if (boundary == null || boundary.isEmpty() || boundary.length() > MAX_BOUNDARY) {
            throw new IllegalArgumentException("Délimiteur multipart invalide");
        }
        this.in = in;
        this.delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        // Le premier délimiteur peut ouvrir le corps : une fin de ligne fictive le rend semblable aux suivants.
        buf[0] = '\r';
        buf[1] = '\n';
        lim = 2;
    }

    /**
     * Passe à la partie suivante (le reste de la partie en cours est ignoré).
     *
     * @return faux s'il n'y a plus de partie
     * @throws IOException si le corps est mal formé ou tronqué
     */
    boolean next() throws IOException {
        if (finished) {
            return false;
        }
        if (inBody) {
            body().transferTo(java.io.OutputStream.nullOutputStream());
        } else if (!afterDelimiter) {
            skipToDelimiter();
        }
        afterDelimiter = false;
        // Après le délimiteur : « -- » (fin) ou une fin de ligne (une partie suit), avec d'éventuels blancs.
        if (!ensure(2)) {
            throw new IOException("Corps multipart tronqué après un délimiteur");
        }
        if (buf[pos] == '-' && buf[pos + 1] == '-') {
            finished = true;
            return false;
        }
        while (ensure(1) && (buf[pos] == ' ' || buf[pos] == '\t')) {
            pos++;
        }
        expectCrlf();
        headers = readHeaders();
        inBody = true;
        return true;
    }

    /** En-têtes de la partie en cours, noms en minuscules. */
    Map<String, String> headers() {
        return headers;
    }

    /** Contenu de la partie en cours, jusqu'au délimiteur suivant. */
    InputStream body() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n == -1 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return readBody(b, off, len);
            }
        };
    }

    private int readBody(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!inBody) {
            return -1;
        }
        while (true) {
            ensure(delimiter.length);
            int found = indexOf(pos, lim);
            if (found == pos) {
                pos += delimiter.length;
                inBody = false;
                afterDelimiter = true;
                return -1;
            }
            // Octets qui ne peuvent pas commencer un délimiteur : rendus tels quels.
            int safe = found >= 0 ? found : (eof ? lim : Math.max(pos, lim - delimiter.length + 1));
            if (safe > pos) {
                int n = Math.min(len, safe - pos);
                System.arraycopy(buf, pos, b, off, n);
                pos += n;
                return n;
            }
            if (eof) {
                throw new IOException("Corps multipart tronqué : délimiteur de fin absent");
            }
            fill();
        }
    }

    /** Ignore le préambule jusqu'au premier délimiteur. */
    private void skipToDelimiter() throws IOException {
        while (true) {
            ensure(delimiter.length);
            int found = indexOf(pos, lim);
            if (found >= 0) {
                pos = found + delimiter.length;
                return;
            }
            if (eof) {
                throw new IOException("Corps multipart sans délimiteur « --" + new String(delimiter, 4,
                        delimiter.length - 4, StandardCharsets.ISO_8859_1) + " »");
            }
            pos = Math.max(pos, lim - delimiter.length + 1);
            fill();
        }
    }

    private Map<String, String> readHeaders() throws IOException {
        Map<String, String> parsed = new LinkedHashMap<>();
        int total = 0;
        while (true) {
            String line = readLine();
            total += line.length() + 2;
            if (total > MAX_HEADERS) {
                throw new IOException("En-têtes de partie trop longs");
            }
            if (line.isEmpty()) {
                return parsed;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("En-tête de partie mal formé");
            }
            parsed.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
        }
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            if (!ensure(1)) {
                throw new IOException("Corps multipart tronqué dans les en-têtes d'une partie");
            }
            byte c = buf[pos++];
            if (c == '\r') {
                if (!ensure(1) || buf[pos] != '\n') {
                    throw new IOException("Fin de ligne invalide dans les en-têtes d'une partie");
                }
                pos++;
                return line.toString();
            }
            line.append((char) (c & 0xff));
            if (line.length() > MAX_HEADERS) {
                throw new IOException("En-têtes de partie trop longs");
            }
        }
    }

    private void expectCrlf() throws IOException {
        if (!ensure(2) || buf[pos] != '\r' || buf[pos + 1] != '\n') {
            throw new IOException("Délimiteur multipart mal formé");
        }
        pos += 2;
    }

    private int indexOf(int from, int to) {
        outer:
        for (int i = from; i <= to - delimiter.length; i++) {
            for (int j = 0; j < delimiter.length; j++) {
                if (buf[i + j] != delimiter[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Vrai si au moins {@code n} octets sont disponibles (lit au besoin) ; faux en fin de flux. */
    private boolean ensure(int n) throws IOException {
        while (lim - pos < n && !eof) {
            fill();
        }
        return lim - pos >= n;
    }

    private void fill() throws IOException {
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, lim - pos);
            lim -= pos;
            pos = 0;
        }
        if (lim == buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        int n = in.read(buf, lim, buf.length - lim);
        if (n < 0) {
            eof = true;
        } else {
            lim += n;
        }
    }

    /** Paramètre d'un type de contenu, par exemple {@code boundary} ; guillemets retirés. */
    static String parameter(String contentType, String name) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).strip().equalsIgnoreCase(name)) {
                String value = part.substring(eq + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }
}

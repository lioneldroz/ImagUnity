package com.imagunity.relay;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.OptionalLong;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Contenu des morceaux sur un service compatible S3 (MinIO, SeaweedFS, Garage, AWS…), partagé par toutes les
 * instances du relais : un morceau téléversé sur une instance est retiré par une autre. Objets
 * {@code <préfixe><senderId>}, déjà chiffrés par les box ; le relais n'y ajoute rien.
 */
public final class S3ChunkContents implements ChunkContents, AutoCloseable {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3ChunkContents(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix;
    }

    /** Client S3 : adresse du service, région, compte ; adressage par chemin (services autres qu'AWS). */
    public static S3Client client(URI endpoint, String region, String accessKey, String secretKey, boolean pathStyle) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(pathStyle)
                // Corps envoyé tel quel (empreinte calculée d'avance), sans encodage « aws-chunked » : pris en charge
                // par tous les services compatibles.
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .chunkedEncodingEnabled(false).build())
                // Sommes de contrôle seulement si le service les exige : beaucoup de services compatibles ne prennent
                // pas en charge les envois « aws-chunked ». L'intégrité est vérifiée par le relais (SHA-256).
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.create());
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }

    /** Vérifie que le compartiment existe, et le crée sinon (premier démarrage). */
    public void ensureBucket() throws IOException {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            create();
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw new IOException("Stockage S3 « " + bucket + " » inaccessible : " + e.getMessage(), e);
            }
            create();
        } catch (SdkException e) {
            throw new IOException("Stockage S3 injoignable : " + e.getMessage(), e);
        }
    }

    private void create() throws IOException {
        try {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (SdkException e) {
            throw new IOException("Création du compartiment S3 « " + bucket + " » impossible : " + e.getMessage(), e);
        }
    }

    String key(String senderId) {
        return prefix + senderId;
    }

    @Override
    public void commit(ChunkFiles.Received received, String senderId) throws IOException {
        try {
            s3.putObject(b -> b.bucket(bucket).key(key(senderId)).contentLength(received.size())
                    .contentType("application/octet-stream"), RequestBody.fromFile(received.temp()));
        } catch (SdkException e) {
            throw new IOException("Dépôt sur le stockage S3 impossible : " + e.getMessage(), e);
        }
    }

    @Override
    public Resource open(String senderId, long size) {
        return new AbstractResource() {
            @Override
            public String getDescription() {
                return "morceau " + senderId + " (S3)";
            }

            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public long contentLength() {
                return size;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                try {
                    return s3.getObject(b -> b.bucket(bucket).key(key(senderId)));
                } catch (SdkException e) {
                    throw new IOException("Lecture sur le stockage S3 impossible : " + e.getMessage(), e);
                }
            }
        };
    }

    @Override
    public void delete(String senderId) throws IOException {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key(senderId)));
        } catch (SdkException e) {
            throw new IOException("Suppression sur le stockage S3 impossible : " + e.getMessage(), e);
        }
    }

    @Override
    public OptionalLong usableSpace() {
        return OptionalLong.empty();
    }

    @Override
    public void close() {
        s3.close();
    }

    /** Pour les journaux. */
    @Override
    public String toString() {
        return "S3 " + bucket + "/" + prefix;
    }
}

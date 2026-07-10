package com.hivemem.attachment;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;

@Component
public class SeaweedFsClient {

    private final AttachmentProperties props;
    private S3Client s3;

    public SeaweedFsClient(AttachmentProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        if (!props.isEnabled()) return;
        var credentials = props.getS3AccessKey().isBlank()
                ? AnonymousCredentialsProvider.create()
                : StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getS3AccessKey(), props.getS3SecretKey()));
        s3 = S3Client.builder()
                .endpointOverride(URI.create(props.getS3Endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                // SeaweedFS does not decode SigV4 streaming (`aws-chunked`) request
                // bodies, so the AWS SDK's default chunked signing leaves the
                // `<size>;chunk-signature=…` framing baked into the stored object —
                // corrupting every thumbnail/original (JPEG/PDF won't decode).
                // Disabling chunked encoding sends a single, non-streamed body.
                .serviceConfiguration(S3Configuration.builder()
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            s3.headBucket(r -> r.bucket(props.getS3Bucket()));
        } catch (NoSuchBucketException e) {
            s3.createBucket(r -> r.bucket(props.getS3Bucket()));
        }
    }

    public void upload(String key, Path file, String contentType) {
        requireS3().putObject(
                r -> r.bucket(props.getS3Bucket()).key(key).contentType(contentType),
                RequestBody.fromFile(file));
    }

    public void uploadBytes(String key, byte[] bytes, String contentType) {
        requireS3().putObject(
                r -> r.bucket(props.getS3Bucket()).key(key).contentType(contentType),
                RequestBody.fromBytes(bytes));
    }

    public InputStream download(String key) {
        return requireS3().getObject(
                r -> r.bucket(props.getS3Bucket()).key(key),
                ResponseTransformer.toInputStream());
    }

    /** Ranged download (HTTP Range semantics, end inclusive) for partial-content serving. */
    public InputStream downloadRange(String key, long start, long endInclusive) {
        return requireS3().getObject(
                r -> r.bucket(props.getS3Bucket()).key(key)
                        .range("bytes=" + start + "-" + endInclusive),
                ResponseTransformer.toInputStream());
    }

    public byte[] downloadBytes(String key) {
        return requireS3().getObjectAsBytes(
                r -> r.bucket(props.getS3Bucket()).key(key)).asByteArray();
    }

    public void delete(String key) {
        requireS3().deleteObject(r -> r.bucket(props.getS3Bucket()).key(key));
    }

    /** Exposed for the backup module which needs raw S3 list/get/put access. */
    public S3Client s3Client() {
        return requireS3();
    }

    /** Fails fast with a clear message instead of an NPE when storage was never enabled. */
    private S3Client requireS3() {
        if (s3 == null) {
            throw new IllegalStateException(
                    "SeaweedFS S3 client not initialized — set hivemem.attachment.enabled=true; "
                    + "features that read/write attachment storage (OCR, vision, backup) require it");
        }
        return s3;
    }
}

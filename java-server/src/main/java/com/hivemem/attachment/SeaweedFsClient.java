package com.hivemem.attachment;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
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
        s3.putObject(
                r -> r.bucket(props.getS3Bucket()).key(key).contentType(contentType),
                RequestBody.fromFile(file));
    }

    public void uploadBytes(String key, byte[] bytes, String contentType) {
        s3.putObject(
                r -> r.bucket(props.getS3Bucket()).key(key).contentType(contentType),
                RequestBody.fromBytes(bytes));
    }

    public InputStream download(String key) {
        return s3.getObject(
                r -> r.bucket(props.getS3Bucket()).key(key),
                ResponseTransformer.toInputStream());
    }

    public byte[] downloadBytes(String key) {
        return s3.getObjectAsBytes(
                r -> r.bucket(props.getS3Bucket()).key(key)).asByteArray();
    }

    public void delete(String key) {
        s3.deleteObject(r -> r.bucket(props.getS3Bucket()).key(key));
    }

    /** Exposed for the backup module which needs raw S3 list/get/put access. */
    public S3Client s3Client() {
        return s3;
    }
}

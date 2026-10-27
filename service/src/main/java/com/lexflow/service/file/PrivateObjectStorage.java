package com.lexflow.service.file;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 私有对象存储适配器；业务层完成资源授权后才可读取，并负责关闭返回的输入流。 */
@Component
public class PrivateObjectStorage {

    private final MinioClient client;
    private final String bucket;

    public PrivateObjectStorage(
        @Value("${lexflow.storage.endpoint:http://127.0.0.1:19000}") String endpoint,
        @Value("${lexflow.storage.access-key:lexflow}") String accessKey,
        @Value("${lexflow.storage.secret-key:lexflow_minio_dev_only}") String secretKey,
        @Value("${lexflow.storage.bucket:lexflow-files}") String bucket
    ) {
        this.client = MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build();
        this.client.setTimeout(5000, 10000, 10000);
        this.bucket = bucket;
    }

    public synchronized void ensureBucket() throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            try {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            } catch (ErrorResponseException e) {
                if (!e.errorResponse().code().equals("BucketAlreadyOwnedByYou")) {
                    throw e;
                }
            }
        }
    }

    public void put(String key, Path file, String contentType) throws Exception {
        ensureBucket();
        try (var in = Files.newInputStream(file)) {
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, Files.size(file), -1)
                    .contentType(contentType)
                    .build()
            );
        }
    }

    public InputStream open(String key) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    }

    public void remove(String key) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }
}

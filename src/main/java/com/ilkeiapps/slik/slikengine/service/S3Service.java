package com.ilkeiapps.slik.slikengine.service;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    @Value("${eranyacloud.endpoint}")
    private String url;

    @Value("${eranyacloud.access.key}")
    private String accessKey;

    @Value("${eranyacloud.secret.key}")
    private String secretKey;

    @Value("${eranyacloud.bucket}")
    private String bucket;

    public void upload(File file) {
        var credentials = new BasicAWSCredentials(accessKey, secretKey);
        var endpoint = new AwsClientBuilder.EndpointConfiguration(url, null);
        var s3Client = AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withEndpointConfiguration(endpoint)
                .build();

        var res = s3Client.putObject(new PutObjectRequest(bucket, "cbas/avatar/" + file.getName(), file));

        res.getMetadata().getArchiveStatus();
    }
}

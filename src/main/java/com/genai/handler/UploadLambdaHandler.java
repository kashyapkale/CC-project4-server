package com.genai.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import com.genai.util.DBUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

public class UploadLambdaHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String BUCKET_NAME = System.getenv("DEST_BUCKET");
    private static final String SNS_TOPIC_ARN = System.getenv("SNS_TOPIC_ARN");

    private static final S3Client s3 = S3Client.builder()
            .region(Region.US_EAST_2)
            .build();
    private static final SnsClient sns = SnsClient.builder()
            .region(Region.US_EAST_2)
            .build();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("UploadLambdaHandler :: start\n");
        long start = System.currentTimeMillis();

        // 1. Get plain text from body (not Base64)
        String body = (String) event.get("body");
        if (body == null || body.isEmpty()) {
            throw new RuntimeException("No file content in request body.");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String transcriptText = body;

        // 2. Generate IDs and S3 Key
        String lectureId = UUID.randomUUID().toString();
        String s3Key = "transcripts/" + lectureId + ".txt";

        // 3. Upload to S3
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(s3Key)
                        .build(),
                RequestBody.fromBytes(bytes)
        );
        context.getLogger().log("Transcript uploaded to S3 in " + (System.currentTimeMillis() - start) + "ms\n");

        // 4. Get query parameter (lectureTitle)
        String lectureTitle = Optional.ofNullable((Map<String, String>) event.get("queryStringParameters"))
                .map(q -> q.getOrDefault("lectureTitle", "Untitled Lecture"))
                .orElse("Untitled Lecture");

        // 5. Insert into MySQL
        try (Connection conn = DBUtil.get()) {
            String sql = "INSERT INTO LectureMetadata " +
                    "(lecture_id, transcript_s3_uri, transcript_upload_time, transcript_status, title, " +
                    "notes_s3_uri, notes_upload_time, notes_status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, lectureId);
                ps.setString(2, "s3://" + BUCKET_NAME + "/" + s3Key);
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.setString(4, "uploaded");
                ps.setString(5, lectureTitle);

                // notes columns are initially null
                ps.setNull(6, java.sql.Types.VARCHAR);    // notes_s3_uri
                ps.setNull(7, java.sql.Types.TIMESTAMP);  // notes_upload_time
                ps.setNull(8, java.sql.Types.VARCHAR);    // notes_status

                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB insert failed", e);
        }

        // 6. Send SNS message to trigger notes generation Lambda
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> payload = new HashMap<>();
            payload.put("lectureId", lectureId);
            payload.put("s3Key", s3Key);
            payload.put("bucket", BUCKET_NAME);
            payload.put("lectureTitle", lectureTitle);

            String message = mapper.writeValueAsString(payload);

            sns.publish(PublishRequest.builder()
                    .topicArn(SNS_TOPIC_ARN)
                    .message(message)
                    .build());

            context.getLogger().log("SNS notification sent for lectureId: " + lectureId + "\n");
        } catch (Exception e) {
            throw new RuntimeException("SNS publish failed", e);
        }

        // 7. Build response
        Map<String, Object> response = new HashMap<>();
        response.put("lecture_id", lectureId);
        response.put("transcript_uri", "s3://" + BUCKET_NAME + "/" + s3Key);
        response.put("message", "Transcript uploaded and processing triggered.");
        return response;
    }
}

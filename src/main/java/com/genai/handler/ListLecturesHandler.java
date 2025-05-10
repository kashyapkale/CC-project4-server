package com.genai.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.genai.util.DBUtil;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URI;
import java.sql.*;
import java.time.Duration;
import java.util.*;

public class ListLecturesHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    // build once per container
    private static final Region S3_REGION = Region.US_EAST_2;
    private static final S3Presigner PRESIGNER = S3Presigner.builder()
            .region(S3_REGION)
            .build();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        List<Map<String, Object>> lectures = new ArrayList<>();

        String sql = "SELECT lecture_id, title, notes_s3_uri FROM LectureMetadata";

        // 1) Query the database
        try (Connection conn = DBUtil.get();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String lectureId   = rs.getString("lecture_id");
                String title       = rs.getString("title");
                String notesS3Uri  = rs.getString("notes_s3_uri");  // may be null

                String presignedUrl = null;
                if (notesS3Uri != null && !notesS3Uri.isEmpty()) {
                    // parse out bucket/key from "s3://bucket/key..."
                    String withoutScheme = notesS3Uri.replaceFirst("^s3://", "");
                    int slash = withoutScheme.indexOf('/');
                    String bucket = withoutScheme.substring(0, slash);
                    String key    = withoutScheme.substring(slash + 1);

                    // build the GET request
                    GetObjectRequest getReq = GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build();

                    // presign for 60 minutes
                    GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofMinutes(60))
                            .getObjectRequest(getReq)
                            .build();

                    presignedUrl = PRESIGNER.presignGetObject(presignReq)
                            .url()
                            .toString();
                }

                // assemble one lecture record
                Map<String, Object> rec = new HashMap<>();
                rec.put("lecture_id", lectureId);
                rec.put("title",       title);
                rec.put("notes",       presignedUrl);

                lectures.add(rec);
            }

        } catch (SQLException e) {
            context.getLogger().log("DB error: " + e.getMessage());
            throw new RuntimeException("Failed to read LectureMetadata", e);
        } catch (SdkException e) {
            context.getLogger().log("Presign error: " + e.getMessage());
            throw new RuntimeException("Failed to presign S3 URL", e);
        }

        // 2) Return as { "lectures": [ … ] }
        Map<String, Object> response = new HashMap<>();
        response.put("lectures", lectures);
        return response;
    }
}

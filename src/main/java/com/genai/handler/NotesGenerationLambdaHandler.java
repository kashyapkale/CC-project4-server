package com.genai.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genai.util.DBUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

public class NotesGenerationLambdaHandler implements RequestHandler<SNSEvent, String> {

    private static final Region REGION = Region.US_EAST_2;
    private static final S3Client s3 = S3Client.builder().region(REGION).build();
    private static final BedrockRuntimeClient bedrock = BedrockRuntimeClient.builder().region(REGION).build();
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String handleRequest(SNSEvent event, Context context) {
        try {
            for (SNSEvent.SNSRecord record : event.getRecords()) {
                String message = record.getSNS().getMessage();
                context.getLogger().log("Received SNS message: " + message + "\n");

                JsonNode json = mapper.readTree(message);
                String lectureId = json.get("lectureId").asText();
                String s3Key = json.get("s3Key").asText();
                String bucket = json.get("bucket").asText();
                String lectureTitle = json.get("lectureTitle").asText();

                // Step 1: Read transcript from S3
                String transcriptText = readS3Text(bucket, s3Key);
                // Log the first 100 characters (or fewer if shorter)
                int transcriptPreviewLen = Math.min(transcriptText.length(), 100);
                context.getLogger().log("Transcript read from S3; preview: \""
                        + transcriptText.substring(0, transcriptPreviewLen).replace("\n", " ")
                        + (transcriptText.length() > transcriptPreviewLen ? "…\"" : "\"") + "\n");

                // Step 2: Generate notes from transcript using Bedrock
                String notesText = generateNotesFromTranscript(transcriptText, context);
                // Log the first 100 characters of the notes
                int notesPreviewLen = Math.min(notesText.length(), 100);
                context.getLogger().log("Notes generated from Bedrock; preview: \""
                        + notesText.substring(0, notesPreviewLen).replace("\n", " ")
                        + (notesText.length() > notesPreviewLen ? "…\"" : "\"") + "\n");

                // Step 3: Upload notes to S3
                String notesKey = "notes/" + lectureId + ".txt";
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(notesKey)
                                .build(),
                        RequestBody.fromString(notesText, StandardCharsets.UTF_8)
                );
                context.getLogger().log("Notes uploaded to S3\n");

                // Step 4: Update MySQL row
                try (Connection conn = DBUtil.get()) {
                    String sql = "UPDATE LectureMetadata SET notes_s3_uri = ?, notes_upload_time = ?, notes_status = ? WHERE lecture_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, "s3://" + bucket + "/" + notesKey);
                        ps.setTimestamp(2, Timestamp.from(Instant.now()));
                        ps.setString(3, "completed");
                        ps.setString(4, lectureId);
                        ps.executeUpdate();
                    }
                }
                context.getLogger().log("MySQL metadata updated\n");
            }

            return "Success";
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage() + "\n");
            throw new RuntimeException("Failed to process SNS event", e);
        }
    }

    private String readS3Text(String bucket, String key) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()), StandardCharsets.UTF_8));
        return reader.lines().collect(Collectors.joining("\n"));
    }

    private String generateNotesFromTranscript(String transcriptText, Context context) {
        try {
            String prompt = "Please convert the following lecture transcript into concise notes in bullet points,  "
                    + "Make sure to include any instruction from professor related to assignments, "
                    + "exam or anything else in the notes (Only if professors mentions about anything in the class) \n\n"
                    +" Also, Explain important concepts described in very brief. :: Transcript starts"
                    + transcriptText;

            ObjectNode payload = mapper.createObjectNode();
            payload.put("prompt", prompt);
            payload.put("max_gen_len", 900);
            payload.put("temperature", 0.7);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(System.getenv("BEDROCK_MODEL_ID")) // Free-tier eligible
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(payload.toString()))
                    .build();

            InvokeModelResponse response = bedrock.invokeModel(request);
            JsonNode result = mapper.readTree(response.body().asUtf8String());

            return result.has("generation") ? result.get("generation").asText() : "No notes generated.";
        } catch (Exception e) {
            context.getLogger().log("Bedrock error: " + e.getMessage());
            throw new RuntimeException("Error during notes generation", e);
        }
    }
}

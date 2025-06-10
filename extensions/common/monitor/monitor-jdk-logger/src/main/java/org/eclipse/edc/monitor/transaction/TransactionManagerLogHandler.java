package org.eclipse.edc.monitor.transaction;

import com.fasterxml.jackson.databind.ObjectMapper; // Added
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map; // Added
import java.util.logging.Handler;
import java.util.logging.LogRecord;

public class TransactionManagerLogHandler extends Handler {

    private static final String TRANSACTION_MANAGER_API_ENDPOINT_CONFIG_KEY = "edc.transaction.manager.api.endpoint";
    private static final String DEFAULT_TRANSACTION_MANAGER_API_ENDPOINT = "http://your-transaction-manager.com/api/log";
    private final HttpClient httpClient;
    private final URI apiUri;
    private final DateTimeFormatter isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);
    private final ObjectMapper objectMapper; // Added


    public TransactionManagerLogHandler() {
        this.httpClient = HttpClient.newBuilder().build();
        String apiEndpoint = System.getProperty(TRANSACTION_MANAGER_API_ENDPOINT_CONFIG_KEY, DEFAULT_TRANSACTION_MANAGER_API_ENDPOINT);
        this.apiUri = URI.create(apiEndpoint);
        this.objectMapper = new ObjectMapper(); // Added
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getMessage() == null) {
            return;
        }

        String message = record.getMessage();
        if (message.contains("[Provider]") || message.contains("[Consumer]")) {
            try {
                String timestamp = isoFormatter.format(Instant.ofEpochMilli(record.getMillis()));

                // Use ObjectMapper to create JSON payload
                Map<String, String> payloadMap = Map.of(
                        "message", message,
                        "timestamp", timestamp
                );
                String jsonPayload = objectMapper.writeValueAsString(payloadMap); // Updated

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(apiUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                // Asynchronous send
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenAccept(response -> {
                            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                System.err.println("Error sending log to transaction manager: " + response.statusCode() + " - " + response.body());
                            }
                        })
                        .exceptionally(ex -> {
                            System.err.println("Exception sending log to transaction manager: " + ex.getMessage());
                            return null;
                        });

            } catch (Exception e) {
                System.err.println("Failed to send log to transaction manager: " + e.getMessage());
                if (getFormatter() != null) {
                    reportError("Failed to send log to transaction manager", e, java.util.logging.ErrorManager.WRITE_FAILURE);
                }
            }
        }
    }

    // escapeJson method removed

    @Override
    public void flush() {
        // No buffering, so nothing to flush
    }

    @Override
    public void close() throws SecurityException {
        // No resources to close
    }
}

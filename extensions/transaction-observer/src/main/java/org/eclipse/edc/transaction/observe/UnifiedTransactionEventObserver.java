package org.eclipse.edc.transaction.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.connector.contract.spi.event.contractnegotiation.ContractNegotiationFinalized;
import org.eclipse.edc.connector.transfer.spi.event.TransferProcessCompleted;
import org.eclipse.edc.connector.transfer.spi.event.TransferProcessInitiated;
import org.eclipse.edc.connector.transfer.spi.event.TransferProcessTerminated;
import org.eclipse.edc.connector.transfer.spi.store.TransferProcessStore;
import org.eclipse.edc.connector.transfer.spi.types.TransferProcess;
import org.eclipse.edc.spi.asset.AssetIndex;
import org.eclipse.edc.spi.event.Event;
import org.eclipse.edc.spi.event.EventEnvelope;
import org.eclipse.edc.spi.event.EventSubscriber;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.domain.agreement.ContractAgreement;
import org.eclipse.edc.spi.types.domain.asset.Asset;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class UnifiedTransactionEventObserver implements EventSubscriber {

    private static final String TRANSACTION_MANAGER_API_ENDPOINT_CONFIG_KEY = "edc.transaction.manager.api.endpoint";
    private static final String DEFAULT_TRANSACTION_MANAGER_API_ENDPOINT = "http://your-transaction-manager.com/api/transaction-event";

    private final HttpClient httpClient;
    private final URI apiUri;
    private final ObjectMapper objectMapper;
    private final TransferProcessStore transferProcessStore;
    private final AssetIndex assetIndex;
    private final Monitor monitor; // For logging
    private final DateTimeFormatter isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    public UnifiedTransactionEventObserver(ObjectMapper objectMapper,
                                           TransferProcessStore transferProcessStore,
                                           AssetIndex assetIndex,
                                           Monitor monitor) {
        this.httpClient = HttpClient.newBuilder().build();
        String apiEndpoint = System.getProperty(TRANSACTION_MANAGER_API_ENDPOINT_CONFIG_KEY, DEFAULT_TRANSACTION_MANAGER_API_ENDPOINT);
        this.apiUri = URI.create(apiEndpoint);
        this.objectMapper = objectMapper;
        this.transferProcessStore = transferProcessStore;
        this.assetIndex = assetIndex;
        this.monitor = monitor;
    }

    // Constructor for explicit HTTP client configuration (e.g. for tests)
    public UnifiedTransactionEventObserver(HttpClient httpClient,
                                           URI apiUri,
                                           ObjectMapper objectMapper,
                                           TransferProcessStore transferProcessStore,
                                           AssetIndex assetIndex,
                                           Monitor monitor) {
        this.httpClient = httpClient;
        this.apiUri = apiUri;
        this.objectMapper = objectMapper;
        this.transferProcessStore = transferProcessStore;
        this.assetIndex = assetIndex;
        this.monitor = monitor;
    }


    @Override
    public <E extends Event> void on(EventEnvelope<E> eventEnvelope) {
        Object eventPayload = eventEnvelope.getPayload();
        Map<String, Object> eventData = new HashMap<>();
        String eventType = null;
        String primaryId = null; // e.g., agreementId or transferProcessId

        eventData.put("eventId", eventEnvelope.getId());
        eventData.put("eventTimestampIso", isoFormatter.format(Instant.ofEpochMilli(eventEnvelope.getAt())));

        if (eventPayload instanceof ContractNegotiationFinalized) {
            eventType = "contractAgreementFinalized";
            ContractNegotiationFinalized finalizedEvent = (ContractNegotiationFinalized) eventPayload;
            ContractAgreement agreement = finalizedEvent.getContractAgreement();
            if (agreement == null) {
                monitor.warning("UnifiedTransactionEventObserver: ContractNegotiationFinalized event did not contain a ContractAgreement. Skipping.");
                return;
            }
            primaryId = agreement.getId();
            eventData.put("agreementId", agreement.getId());
            eventData.put("providerId", agreement.getProviderId());
            eventData.put("consumerId", agreement.getConsumerId());
            eventData.put("assetId", agreement.getAssetId()); // Asset ID from agreement
            eventData.put("contractSigningDateEpochSeconds", agreement.getContractSigningDate());
            eventData.put("contractSigningDateIso", isoFormatter.format(Instant.ofEpochSecond(agreement.getContractSigningDate())));
            eventData.put("policy", agreement.getPolicy()); // Policy might need custom serialization

        } else if (eventPayload instanceof TransferProcessInitiated) {
            eventType = "transferProcessInitiated";
            TransferProcessInitiated initiatedEvent = (TransferProcessInitiated) eventPayload;
            primaryId = initiatedEvent.getTransferProcessId();
            eventData.put("transferProcessId", primaryId);
            enrichWithTransferProcessData(primaryId, eventData);

        } else if (eventPayload instanceof TransferProcessCompleted) {
            eventType = "transferProcessCompleted";
            TransferProcessCompleted completedEvent = (TransferProcessCompleted) eventPayload;
            primaryId = completedEvent.getTransferProcessId();
            eventData.put("transferProcessId", primaryId);
            enrichWithTransferProcessData(primaryId, eventData);

        } else if (eventPayload instanceof TransferProcessTerminated) {
            eventType = "transferProcessTerminated";
            TransferProcessTerminated terminatedEvent = (TransferProcessTerminated) eventPayload;
            primaryId = terminatedEvent.getTransferProcessId();
            eventData.put("transferProcessId", primaryId);
            eventData.put("reason", terminatedEvent.getReason());
            enrichWithTransferProcessData(primaryId, eventData);
        } else {
            // Not an event this observer is interested in
            return;
        }

        eventData.put("eventType", eventType);

        try {
            String jsonPayload = objectMapper.writeValueAsString(eventData);
            sendNotification(jsonPayload, eventType, primaryId);
        } catch (Exception e) {
            monitor.severe(String.format("UnifiedTransactionEventObserver: Failed to serialize or send %s event for ID %s", eventType, primaryId), e);
        }
    }

    private void enrichWithTransferProcessData(String transferProcessId, Map<String, Object> eventData) {
        if (transferProcessId == null) {
            monitor.debug("UnifiedTransactionEventObserver: TransferProcessId is null, cannot enrich data.");
            return;
        }

        TransferProcess process = transferProcessStore.find(transferProcessId);
        if (process == null) {
            monitor.warning("UnifiedTransactionEventObserver: TransferProcess not found for ID: " + transferProcessId);
            return; // Or send partial data? For now, we return.
        }

        // Add any direct properties from TransferProcess if needed
        // eventData.put("transferProcessType", process.getType().toString());
        // eventData.put("transferProcessState", process.stateAsString());


        String assetId = process.getAssetId(); // Convenience method from TransferProcess
        if (assetId == null && process.getDataRequest() != null) { // Fallback if not directly on TP
            assetId = process.getDataRequest().getAssetId();
        }

        if (assetId != null) {
            eventData.put("assetId", assetId);
            Asset asset = assetIndex.findById(assetId);
            if (asset != null) {
                eventData.put("assetName", asset.getName());
                // Add all asset properties, or specific ones as per user request
                // Be mindful of PII or sensitive data in properties.
                eventData.put("assetProperties", asset.getProperties());
            } else {
                monitor.warning("UnifiedTransactionEventObserver: Asset not found for ID: " + assetId + " (referenced by TransferProcess: " + transferProcessId + ")");
            }
        } else {
            monitor.warning("UnifiedTransactionEventObserver: AssetId not found for TransferProcess: " + transferProcessId);
        }

        if (process.getContractId() != null) {
            eventData.put("contractId", process.getContractId());
        }
    }

    private void sendNotification(String jsonPayload, String eventType, String primaryId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(apiUri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        final String logContext = String.format("%s event for ID %s", eventType, primaryId);
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        monitor.warning(String.format("UnifiedTransactionEventObserver: Error sending %s to transaction manager: %s - %s", logContext, response.statusCode(), response.body()));
                    } else {
                        monitor.info(String.format("UnifiedTransactionEventObserver: Successfully sent %s to transaction manager.", logContext));
                    }
                })
                .exceptionally(ex -> {
                    monitor.severe(String.format("UnifiedTransactionEventObserver: Exception sending %s to transaction manager", logContext), ex);
                    return null;
                });
    }
}

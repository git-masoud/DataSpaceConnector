package org.eclipse.edc.extension.common.events.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.eclipse.edc.connector.asset.spi.observe.AssetListener;
import org.eclipse.edc.connector.asset.spi.store.AssetStore;
import org.eclipse.edc.connector.contract.spi.definition.observe.ContractDefinitionListener;
import org.eclipse.edc.connector.contract.spi.offer.ContractDefinitionResolver;
import org.eclipse.edc.connector.policy.spi.observe.PolicyDefinitionListener;
import org.eclipse.edc.connector.policy.spi.store.PolicyDefinitionStore;
import org.eclipse.edc.spi.types.domain.asset.Asset;
import org.eclipse.edc.spi.types.domain.contract.offer.ContractDefinition;
import org.eclipse.edc.policy.model.PolicyDefinition;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.connector.contract.spi.store.ContractDefinitionStore; // Added for querying all definitions

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;


public class OffersObserver implements AssetListener, PolicyDefinitionListener, ContractDefinitionListener {

    private static final Logger LOGGER = Logger.getLogger(OffersObserver.class.getName());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ContractDefinitionResolver contractDefinitionResolver; // Will be used as intended, or might need ContractDefinitionStore
    private final ContractDefinitionStore contractDefinitionStore; // Added
    private final AssetStore assetStore;
    private final PolicyDefinitionStore policyDefinitionStore;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiEndpointUrl;

    public OffersObserver(ContractDefinitionResolver contractDefinitionResolver,
                           ContractDefinitionStore contractDefinitionStore, // Added
                           AssetStore assetStore,
                           PolicyDefinitionStore policyDefinitionStore,
                           OkHttpClient httpClient,
                           ObjectMapper objectMapper,
                           String apiEndpointUrl) {
        this.contractDefinitionResolver = contractDefinitionResolver;
        this.contractDefinitionStore = contractDefinitionStore; // Added
        this.assetStore = assetStore;
        this.policyDefinitionStore = policyDefinitionStore;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiEndpointUrl = apiEndpointUrl;
    }

    // AssetListener methods
    @Override
    public void created(Asset asset) {
        // TODO: Implement
    }

    @Override
    public void deleted(Asset asset) {
        // TODO: Implement
    }

    @Override
    public void updated(Asset newAsset, Asset oldAsset) {
        LOGGER.info("Asset updated event received for asset ID: " + newAsset.getId() + ". Old asset ID: " + (oldAsset != null ? oldAsset.getId() : "null"));

        // Use ContractDefinitionStore to get all contract definitions
        List<ContractDefinition> contractDefinitions = contractDefinitionStore.findAll(QuerySpec.max()).toList();
        LOGGER.info("Found " + contractDefinitions.size() + " contract definitions to evaluate.");

        for (ContractDefinition contractDefinition : contractDefinitions) {
            if (matchesAsset(contractDefinition.getAssetsSelector(), newAsset)) {
                LOGGER.info("Asset " + newAsset.getId() + " matches selector for ContractDefinition " + contractDefinition.getId());
                try {
                    PolicyDefinition policyDefinition = policyDefinitionStore.findById(contractDefinition.getContractPolicyId());
                    if (policyDefinition == null) {
                        LOGGER.warning("PolicyDefinition not found for ID: " + contractDefinition.getContractPolicyId() + " referenced by ContractDefinition " + contractDefinition.getId());
                        continue;
                    }

                    Map<String, Object> eventPayload = constructEventPayload(newAsset, contractDefinition, policyDefinition);
                    String jsonPayload = objectMapper.writeValueAsString(eventPayload);

                    sendEvent(jsonPayload);

                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error processing asset update for ContractDefinition " + contractDefinition.getId() + " and Asset " + newAsset.getId(), e);
                }
            } else {
                LOGGER.fine("Asset " + newAsset.getId() + " does not match selector for ContractDefinition " + contractDefinition.getId());
            }
        }
    }

    private boolean matchesAsset(List<Criterion> criteria, Asset asset) {
        if (criteria == null || criteria.isEmpty()) {
            // No criteria means it's a "match all" or "match none" depending on interpretation.
            // For asset linkage, it often means it doesn't select any asset unless other mechanisms are used.
            // Let's assume for now that empty criteria do not match any specific asset directly.
            return false;
        }
        for (Criterion criterion : criteria) {
            Object assetValue = asset.getProperty(criterion.getOperandLeft().toString());
            if (assetValue == null) {
                return false; // Property not found on asset
            }

            // Simplified comparison for "=" operator and String properties
            if ("=".equals(criterion.getOperator())) {
                if (criterion.getOperandRight() instanceof String) {
                    if (!Objects.equals(assetValue.toString(), criterion.getOperandRight().toString())) {
                        return false; // Value does not match
                    }
                } else {
                    // TODO: Handle other types or log a warning for unsupported types
                    LOGGER.warning("Unsupported operandRight type for '=' operator: " + criterion.getOperandRight().getClass().getName());
                    return false;
                }
            } else {
                // TODO: Implement other operators like IN, LIKE, etc.
                LOGGER.warning("Unsupported operator: " + criterion.getOperator());
                return false;
            }
        }
        return true; // All criteria matched
    }

    private Map<String, Object> constructEventPayload(Asset asset, ContractDefinition contractDefinition, PolicyDefinition policyDefinition) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("@context", "https.w3id.org/edc/v0.0.1/ns/"); // Assuming a default context
        payload.put("@id", asset.getId());
        payload.put("edc:name", asset.getProperty("edc:name")); // Or asset.getName() if available and preferred
        payload.put("edc:description", asset.getProperty("edc:description")); // Or asset.getDescription()
        payload.put("edc:id", asset.getId()); // EDC's own ID for the asset
        payload.put("edc:contenttype", asset.getProperty("edc:contenttype")); // Or asset.getContentType()

        // Construct dcat:distribution if applicable
        // This is a simplified representation. Real distribution might be more complex.
        List<Map<String, Object>> distributions = new ArrayList<>();
        Map<String, Object> distribution = new HashMap<>();
        distribution.put("@type", "dcat:Distribution");
        distribution.put("dct:format", asset.getProperty("dct:format")); // Example, might come from asset properties
        distribution.put("dcat:accessService", asset.getDataAddress().getType()); // Example
        distributions.add(distribution);
        payload.put("dcat:distribution", distributions);


        // Construct odrl:hasPolicy
        Map<String, Object> odrlPolicy = new HashMap<>();
        String odrlPolicyId = String.format("%s:%s:%s", contractDefinition.getId(), asset.getId(), policyDefinition.getUid());
        odrlPolicy.put("@id", odrlPolicyId);
        odrlPolicy.put("@type", "odrl:Offer"); // Or Set, depending on policy structure
        odrlPolicy.put("odrl:target", asset.getId()); // Policy targets this asset

        // Embed policy details (simplified)
        // This part can be complex depending on how deep the policy structure needs to be represented.
        // For now, referencing the policy by its ID. A fuller representation might inline rules.
        odrlPolicy.put("odrl:permission", policyDefinition.getPermissions());
        odrlPolicy.put("odrl:prohibition", policyDefinition.getProhibitions());
        odrlPolicy.put("odrl:obligation", policyDefinition.getObligations());

        payload.put("odrl:hasPolicy", odrlPolicy);

        return payload;
    }

    private void sendEvent(String jsonPayload) {
        RequestBody body = RequestBody.create(jsonPayload, JSON);
        Request request = new Request.Builder()
                .url(apiEndpointUrl)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                LOGGER.info("Event successfully sent to " + apiEndpointUrl + ". Response: " + response.body().string());
            } else {
                LOGGER.warning("Failed to send event to " + apiEndpointUrl + ". Code: " + response.code() + " Body: " + response.body().string());
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending event to " + apiEndpointUrl, e);
        }
    }


    @Override
    public void preCreate(Asset asset) {
        // TODO: Implement
    }

    @Override
    public void preDelete(Asset asset) {
        // TODO: Implement
    }

    @Override
    public void preUpdate(Asset newAsset, Asset oldAsset) {
        // TODO: Implement
    }

    // PolicyDefinitionListener methods
    @Override
    public void created(PolicyDefinition policyDefinition) {
        // TODO: Implement
    }

    @Override
    public void deleted(PolicyDefinition policyDefinition) {
        // TODO: Implement
    }

    @Override
    public void updated(PolicyDefinition newPolicyDefinition, PolicyDefinition oldPolicyDefinition) {
        LOGGER.info("PolicyDefinition updated event received for policy ID: " + newPolicyDefinition.getUid() + ". Old policy ID: " + (oldPolicyDefinition != null ? oldPolicyDefinition.getUid() : "null"));

        List<ContractDefinition> contractDefinitions = contractDefinitionStore.findAll(QuerySpec.max()).toList();
        LOGGER.fine("Found " + contractDefinitions.size() + " contract definitions to evaluate for policy update.");

        for (ContractDefinition contractDefinition : contractDefinitions) {
            // Check if the contract definition uses the updated policy
            if (Objects.equals(contractDefinition.getContractPolicyId(), newPolicyDefinition.getUid())) {
                LOGGER.info("ContractDefinition " + contractDefinition.getId() + " is affected by the update to PolicyDefinition " + newPolicyDefinition.getUid());

                // Now find the first asset that matches this contract definition's selector
                List<Asset> assets = assetStore.findAll(QuerySpec.max()).toList();
                LOGGER.fine("Evaluating " + assets.size() + " assets for ContractDefinition " + contractDefinition.getId());

                for (Asset asset : assets) {
                    if (matchesAsset(contractDefinition.getAssetsSelector(), asset)) {
                        LOGGER.info("Asset " + asset.getId() + " matches ContractDefinition " + contractDefinition.getId() + ". Triggering event for policy update.");
                        try {
                            // The newPolicyDefinition is the one that was updated and is relevant here
                            Map<String, Object> eventPayload = constructEventPayload(asset, contractDefinition, newPolicyDefinition);
                            String jsonPayload = objectMapper.writeValueAsString(eventPayload);
                            sendEvent(jsonPayload);
                            // Found an asset for this contract definition, break from asset search as per requirement ("first relevant Asset")
                            break;
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "Error processing policy update for Asset " + asset.getId() + ", ContractDefinition " + contractDefinition.getId() + ", PolicyDefinition " + newPolicyDefinition.getUid(), e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void preCreate(PolicyDefinition policyDefinition) {
        // TODO: Implement
    }

    @Override
    public void preDelete(PolicyDefinition policyDefinition) {
        // TODO: Implement
    }

    @Override
    public void preUpdate(PolicyDefinition newPolicyDefinition, PolicyDefinition oldPolicyDefinition) {
        // TODO: Implement
    }


    // ContractDefinitionListener methods
    @Override
    public void created(ContractDefinition contractDefinition) {
        LOGGER.info("ContractDefinition created event received for ID: " + contractDefinition.getId());

        try {
            // 1. Fetch the PolicyDefinition
            PolicyDefinition policyDefinition = policyDefinitionStore.findById(contractDefinition.getContractPolicyId());
            if (policyDefinition == null) {
                LOGGER.severe("PolicyDefinition not found for ID: " + contractDefinition.getContractPolicyId() +
                              " referenced by new ContractDefinition " + contractDefinition.getId() + ". Event cannot be sent.");
                return;
            }
            LOGGER.fine("Successfully fetched PolicyDefinition " + policyDefinition.getUid() + " for ContractDefinition " + contractDefinition.getId());

            // 2. Find the first relevant Asset
            List<Asset> assets = assetStore.findAll(QuerySpec.max()).toList();
            Asset matchingAsset = null;
            if (assets.isEmpty() && !contractDefinition.getAssetsSelector().isEmpty()) {
                 LOGGER.warning("No assets available in the store to match against ContractDefinition " + contractDefinition.getId());
            } else if (contractDefinition.getAssetsSelector().isEmpty()) {
                LOGGER.warning("ContractDefinition " + contractDefinition.getId() + " has an empty asset selector. No specific asset to match.");
                // Depending on requirements, an empty selector might mean "matches all" or "matches none".
                // If it means "matches all", we might pick the first asset in the store, or send a generic event.
                // For now, we proceed only if there's a selector and assets.
                // If an empty selector implies it should not be processed here, or has special handling, this logic would change.
                // Or, if it's a global contract not tied to specific assets, this event might not apply or need different handling.
                // Given the current `matchesAsset` behavior (returns false for empty criteria), no asset will match.
            }


            LOGGER.fine("Evaluating " + assets.size() + " assets for new ContractDefinition " + contractDefinition.getId());
            for (Asset asset : assets) {
                if (matchesAsset(contractDefinition.getAssetsSelector(), asset)) {
                    matchingAsset = asset;
                    LOGGER.info("Asset " + matchingAsset.getId() + " matches new ContractDefinition " + contractDefinition.getId());
                    break; // Found the first relevant asset
                }
            }

            if (matchingAsset == null) {
                // This log covers the case where selectors are present but no asset matches.
                LOGGER.warning("No matching asset found for new ContractDefinition " + contractDefinition.getId() +
                               " among " + assets.size() + " available assets. Event cannot be sent for a specific asset.");
                return;
            }

            // 3. Construct and send the event payload
            Map<String, Object> eventPayload = constructEventPayload(matchingAsset, contractDefinition, policyDefinition);
            String jsonPayload = objectMapper.writeValueAsString(eventPayload);
            sendEvent(jsonPayload);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing ContractDefinition created event for ID: " + contractDefinition.getId(), e);
        }
    }

    @Override
    public void deleted(ContractDefinition contractDefinition) {
        // TODO: Implement
    }

    @Override
    public void updated(ContractDefinition newContractDefinition, ContractDefinition oldContractDefinition) {
        // TODO: Implement
    }

    @Override
    public void preCreate(ContractDefinition contractDefinition) {
        // TODO: Implement
    }

    @Override
    public void preDelete(ContractDefinition contractDefinition) {
        // TODO: Implement
    }

    @Override
    public void preUpdate(ContractDefinition newContractDefinition, ContractDefinition oldContractDefinition) {
        // TODO: Implement
    }
}

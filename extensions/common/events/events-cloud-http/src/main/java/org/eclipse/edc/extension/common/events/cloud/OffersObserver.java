package org.eclipse.edc.extension.common.events.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.eclipse.edc.connector.asset.spi.observe.AssetListener;
import org.eclipse.edc.connector.asset.spi.index.AssetIndex; // Changed from AssetStore
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Added missing class declaration, assuming it was accidentally removed by previous diff
public class OffersObserver implements AssetListener, PolicyDefinitionListener, ContractDefinitionListener {
    private static final Logger LOGGER = Logger.getLogger(OffersObserver.class.getName());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ContractDefinitionResolver contractDefinitionResolver; // Will be used as intended, or might need ContractDefinitionStore
    private final ContractDefinitionStore contractDefinitionStore; // Added
    private final AssetIndex assetIndex; // Changed from AssetStore
    private final PolicyDefinitionStore policyDefinitionStore;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiEndpointUrl;

    public OffersObserver(ContractDefinitionResolver contractDefinitionResolver,
                           ContractDefinitionStore contractDefinitionStore, // Added
                           AssetIndex assetIndex, // Changed from AssetStore
                           PolicyDefinitionStore policyDefinitionStore,
                           OkHttpClient httpClient,
                           ObjectMapper objectMapper,
                           String apiEndpointUrl) {
        this.contractDefinitionResolver = contractDefinitionResolver;
        this.contractDefinitionStore = contractDefinitionStore; // Added
        this.assetIndex = assetIndex; // Changed from AssetStore
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
        List<ContractDefinition> contractDefinitions = contractDefinitionStore.findAll(QuerySpec.Builder.newInstance().build()).toList();
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
        LOGGER.info("PolicyDefinition updated event received for policy ID: " + newPolicyDefinition.getUid() +
                ". Old policy ID: " + (oldPolicyDefinition != null ? oldPolicyDefinition.getUid() : "null"));

        // 1. Fetch ContractDefinitions by Policy ID
        QuerySpec querySpecAccessPolicy = QuerySpec.Builder.newInstance()
                .filter(List.of(new Criterion("accessPolicyId", "=", newPolicyDefinition.getUid())))
                .build();
        Stream<ContractDefinition> cdByAccessPolicyStream = contractDefinitionStore.findAll(querySpecAccessPolicy);
        LOGGER.fine("Found contract definitions by accessPolicyId " + newPolicyDefinition.getUid());


        QuerySpec querySpecContractPolicy = QuerySpec.Builder.newInstance()
                .filter(List.of(new Criterion("contractPolicyId", "=", newPolicyDefinition.getUid())))
                .build();
        Stream<ContractDefinition> cdByContractPolicyStream = contractDefinitionStore.findAll(querySpecContractPolicy);
        LOGGER.fine("Found contract definitions by contractPolicyId " + newPolicyDefinition.getUid());

        List<ContractDefinition> uniqueContractDefinitions = Stream.concat(cdByAccessPolicyStream, cdByContractPolicyStream)
                .distinct()
                .collect(Collectors.toList());

        if (uniqueContractDefinitions.isEmpty()) {
            LOGGER.info("No contract definitions found referencing updated policy ID: " + newPolicyDefinition.getUid());
            return;
        }
        LOGGER.info("Total " + uniqueContractDefinitions.size() + " unique contract definitions are affected by the policy update.");

        // 2. Fetch Matching Asset for each ContractDefinition
        for (ContractDefinition contractDefinition : uniqueContractDefinitions) {
            List<Criterion> assetSelectorCriteria = contractDefinition.getAssetsSelector();

            // Use helper method to transform criteria
            List<Criterion> transformedCriteria = transformAssetSelectorCriteria(assetSelectorCriteria);

            if (transformedCriteria.isEmpty()) {
                 LOGGER.warning("ContractDefinition " + contractDefinition.getId() +
                                " has no valid asset selector criteria after transformation. Skipping asset search.");
                continue;
            }

            QuerySpec assetQuerySpec = QuerySpec.Builder.newInstance()
                    .filter(transformedCriteria)
                    .limit(1)
                    .build();

            LOGGER.fine("Querying for asset matching ContractDefinition " + contractDefinition.getId() +
                        " with criteria: " + transformedCriteria);

            try (Stream<Asset> assetsStream = assetIndex.queryAssets(assetQuerySpec)) {
                Asset matchingAsset = assetsStream.findFirst().orElse(null);

                if (matchingAsset != null) {
                    LOGGER.info("Asset " + matchingAsset.getId() + " found for ContractDefinition " +
                                contractDefinition.getId() + ". Constructing and sending event.");
                    Map<String, Object> eventPayload = constructEventPayload(matchingAsset, contractDefinition, newPolicyDefinition);
                    String jsonPayload = objectMapper.writeValueAsString(eventPayload);
                    sendEvent(jsonPayload);
                    // As per current understanding, we process one asset per affected contract definition.
                    // If only one event total for the policy update is needed, a break would go here.
                } else {
                    LOGGER.info("No matching asset found for ContractDefinition " + contractDefinition.getId() +
                                " with the given criteria.");
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error processing policy update for ContractDefinition " +
                                         contractDefinition.getId() + " and PolicyDefinition " + newPolicyDefinition.getUid(), e);
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
            // 1. Handle Asset Selector and Find Matching Asset
            List<Criterion> assetSelectorCriteria = contractDefinition.getAssetsSelector();
            if (assetSelectorCriteria == null || assetSelectorCriteria.isEmpty()) {
                LOGGER.warning("No asset selector criteria found for ContractDefinition " + contractDefinition.getId() + ". Event cannot be sent for a specific asset.");
                return;
            }

            List<Criterion> transformedCriteria = transformAssetSelectorCriteria(assetSelectorCriteria);
            if (transformedCriteria.isEmpty()) {
                LOGGER.warning("Asset selector criteria for ContractDefinition " + contractDefinition.getId() +
                               " resulted in empty transformed criteria. Skipping asset search.");
                return;
            }

            QuerySpec assetQuerySpec = QuerySpec.Builder.newInstance()
                    .filter(transformedCriteria)
                    .limit(1)
                    .build();

            LOGGER.fine("Querying for asset matching new ContractDefinition " + contractDefinition.getId() + " with criteria: " + transformedCriteria);
            Asset matchingAsset;
            try (Stream<Asset> assetsStream = assetIndex.queryAssets(assetQuerySpec)) {
                matchingAsset = assetsStream.findFirst().orElse(null);
            }

            if (matchingAsset == null) {
                LOGGER.warning("No matching asset found for new ContractDefinition " + contractDefinition.getId() + " based on its asset selector. Event cannot be sent.");
                return;
            }
            LOGGER.info("Asset " + matchingAsset.getId() + " found for new ContractDefinition " + contractDefinition.getId());

            // 2. Fetch the PolicyDefinition
            PolicyDefinition policyDefinition = policyDefinitionStore.findById(contractDefinition.getContractPolicyId());
            if (policyDefinition == null) {
                LOGGER.severe("PolicyDefinition not found for ID: " + contractDefinition.getContractPolicyId() +
                              " referenced by new ContractDefinition " + contractDefinition.getId() + ". Event cannot be sent.");
                return;
            }
            LOGGER.fine("Successfully fetched PolicyDefinition " + policyDefinition.getUid() + " for ContractDefinition " + contractDefinition.getId());

            // 3. Construct and send the event payload
            Map<String, Object> eventPayload = constructEventPayload(matchingAsset, contractDefinition, policyDefinition);
            String jsonPayload = objectMapper.writeValueAsString(eventPayload);
            sendEvent(jsonPayload);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing ContractDefinition created event for ID: " + contractDefinition.getId(), e);
        }
    }

    // Helper method to transform asset selector criteria
    private List<Criterion> transformAssetSelectorCriteria(List<Criterion> selectorCriteria) {
        if (selectorCriteria == null || selectorCriteria.isEmpty()) {
            return Collections.emptyList();
        }
        List<Criterion> assetQueryCriteria = new ArrayList<>();
        for (Criterion criterion : selectorCriteria) {
            Object operandLeftObj = criterion.getOperandLeft();
            if (operandLeftObj == null) { // Skip criteria with null operandLeft
                LOGGER.warning("Skipping criterion with null operandLeft: " + criterion);
                continue;
            }
            String originalOperandLeft = String.valueOf(operandLeftObj);
            if (originalOperandLeft.trim().isEmpty()) { // Skip criteria with empty operandLeft
                LOGGER.warning("Skipping criterion with empty operandLeft: " + criterion);
                continue;
            }
            assetQueryCriteria.add(new Criterion(
                    Asset.PROPERTIES + "." + originalOperandLeft,
                    criterion.getOperator(),
                    criterion.getOperandRight()
            ));
        }
        return assetQueryCriteria;
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

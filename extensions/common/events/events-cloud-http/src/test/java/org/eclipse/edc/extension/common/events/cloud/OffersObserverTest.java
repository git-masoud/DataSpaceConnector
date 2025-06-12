package org.eclipse.edc.extension.common.events.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.eclipse.edc.connector.asset.spi.store.AssetStore;
import org.eclipse.edc.connector.contract.spi.offer.ContractDefinitionResolver;
import org.eclipse.edc.connector.contract.spi.store.ContractDefinitionStore;
import org.eclipse.edc.connector.policy.spi.store.PolicyDefinitionStore;
import org.eclipse.edc.policy.model.PolicyDefinition;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.asset.Asset;
import org.eclipse.edc.spi.types.domain.contract.offer.ContractDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class OffersObserverTest {

    private static final String TEST_API_ENDPOINT = "http://localhost:8080/test";
    private static final String TEST_ASSET_ID = UUID.randomUUID().toString();
    private static final String TEST_CONTRACT_DEFINITION_ID = UUID.randomUUID().toString();
    private static final String TEST_POLICY_ID = UUID.randomUUID().toString();

    @Mock
    private ContractDefinitionResolver contractDefinitionResolver;
    @Mock
    private AssetStore assetStore;
    @Mock
    private PolicyDefinitionStore policyDefinitionStore;
    @Mock
    private ContractDefinitionStore contractDefinitionStore;
    @Mock
    private OkHttpClient okHttpClient;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper(); // Use a Spy to allow actual JSON conversion

    @Mock
    private Call mockCall;
    @Mock
    private Response mockResponse;

    @Captor
    private ArgumentCaptor<Request> requestCaptor;

    private OffersObserver offersObserver;

    @BeforeEach
    void setUp() {
        // objectMapper is a Spy, so it's already initialized.
        // If it were a @Mock, it would be: objectMapper = Mockito.mock(ObjectMapper.class);
        offersObserver = new OffersObserver(
                contractDefinitionResolver,
                contractDefinitionStore,
                assetStore,
                policyDefinitionStore,
                okHttpClient,
                objectMapper,
                TEST_API_ENDPOINT
        );
    }

    private Asset createMockAsset(String id, Map<String, Object> properties) {
        return Asset.Builder.newInstance().id(id).properties(properties).dataAddress(DataAddress.Builder.newInstance().type("test-type").build()).build();
    }

    private ContractDefinition createMockContractDefinition(String id, String accessPolicyId, String contractPolicyId, List<Criterion> assetSelector) {
        return ContractDefinition.Builder.newInstance()
                .id(id)
                .accessPolicyId(accessPolicyId)
                .contractPolicyId(contractPolicyId)
                .assetsSelector(assetSelector)
                .build();
    }

    private PolicyDefinition createMockPolicyDefinition(String id) {
        return PolicyDefinition.Builder.newInstance()
                .id(id)
                // .policy(Policy.Builder.newInstance().build()) // Policy structure might be needed for payload
                .build();
    }

    @Test
    void testAssetUpdated_matchingContract_sendsEvent() throws IOException {
        // Arrange
        Map<String, Object> assetProps = Map.of("asset:prop:id", TEST_ASSET_ID, "edc:name", "Test Asset");
        Asset mockAsset = createMockAsset(TEST_ASSET_ID, assetProps);

        List<Criterion> assetSelector = Collections.singletonList(new Criterion("asset:prop:id", "=", TEST_ASSET_ID));
        ContractDefinition mockContractDefinition = createMockContractDefinition(TEST_CONTRACT_DEFINITION_ID, "accessPolicyId", TEST_POLICY_ID, assetSelector);
        PolicyDefinition mockPolicyDefinition = createMockPolicyDefinition(TEST_POLICY_ID);

        when(contractDefinitionStore.findAll(any(QuerySpec.class))).thenReturn(List.of(mockContractDefinition).stream());
        when(policyDefinitionStore.findById(TEST_POLICY_ID)).thenReturn(mockPolicyDefinition);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(mockResponse);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.body()).thenReturn(ResponseBody.create("", MediaType.parse("application/json")));


        // Act
        offersObserver.updated(mockAsset, null); // AssetListener takes (Asset newAsset, Asset oldAsset)

        // Assert
        verify(contractDefinitionStore).findAll(any(QuerySpec.class));
        verify(policyDefinitionStore).findById(TEST_POLICY_ID);
        verify(objectMapper).writeValueAsString(any(Map.class));
        verify(okHttpClient).newCall(requestCaptor.capture());
        verify(mockCall).execute();

        Request capturedRequest = requestCaptor.getValue();
        assertEquals(TEST_API_ENDPOINT, capturedRequest.url().toString());
        assertEquals("POST", capturedRequest.method());
        assertNotNull(capturedRequest.body());
    }

    @Test
    void testAssetUpdated_noMatchingContract_doesNotSendEvent() throws IOException {
        // Arrange
        Asset mockAsset = createMockAsset(TEST_ASSET_ID, Map.of("asset:prop:id", "someOtherId"));
        List<Criterion> assetSelector = Collections.singletonList(new Criterion("asset:prop:id", "=", TEST_ASSET_ID));
        ContractDefinition mockContractDefinition = createMockContractDefinition(TEST_CONTRACT_DEFINITION_ID, "accessPolicyId", TEST_POLICY_ID, assetSelector);

        when(contractDefinitionStore.findAll(any(QuerySpec.class))).thenReturn(List.of(mockContractDefinition).stream());
        // No need to mock policyDefinitionStore, objectMapper, or okHttpClient if no match is expected

        // Act
        offersObserver.updated(mockAsset, null);

        // Assert
        verify(contractDefinitionStore).findAll(any(QuerySpec.class));
        verify(policyDefinitionStore, never()).findById(anyString());
        verify(objectMapper, never()).writeValueAsString(any(Map.class));
        verify(okHttpClient, never()).newCall(any(Request.class));
    }

    @Test
    void testPolicyDefinitionUpdated_matchingContractAndAsset_sendsEvent() throws IOException {
        // Arrange
        PolicyDefinition mockPolicyDefinition = createMockPolicyDefinition(TEST_POLICY_ID);
        Map<String, Object> assetProps = Map.of("asset:prop:id", TEST_ASSET_ID, "edc:name", "Test Asset Policy Update");
        Asset mockAsset = createMockAsset(TEST_ASSET_ID, assetProps);

        List<Criterion> assetSelector = Collections.singletonList(new Criterion("asset:prop:id", "=", TEST_ASSET_ID));
        ContractDefinition mockContractDefinition = createMockContractDefinition(TEST_CONTRACT_DEFINITION_ID, "accessPolicyId", TEST_POLICY_ID, assetSelector);

        when(contractDefinitionStore.findAll(any(QuerySpec.class))).thenReturn(List.of(mockContractDefinition).stream());
        when(assetStore.findAll(any(QuerySpec.class))).thenReturn(List.of(mockAsset).stream());
        // policyDefinitionStore.findById is not called in this path, as the updated policy is an input

        when(okHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(mockResponse);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.body()).thenReturn(ResponseBody.create("", MediaType.parse("application/json")));

        // Act
        offersObserver.updated(mockPolicyDefinition, null); // PolicyDefinitionListener takes (PolicyDefinition new, PolicyDefinition old)

        // Assert
        verify(contractDefinitionStore).findAll(any(QuerySpec.class));
        verify(assetStore).findAll(any(QuerySpec.class));
        verify(objectMapper).writeValueAsString(any(Map.class));
        verify(okHttpClient).newCall(requestCaptor.capture());
        verify(mockCall).execute();

        Request capturedRequest = requestCaptor.getValue();
        assertEquals(TEST_API_ENDPOINT, capturedRequest.url().toString());
    }

    @Test
    void testContractDefinitionCreated_matchingAssetAndPolicy_sendsEvent() throws IOException {
        // Arrange
        PolicyDefinition mockPolicyDefinition = createMockPolicyDefinition(TEST_POLICY_ID);
        Map<String, Object> assetProps = Map.of("asset:prop:id", TEST_ASSET_ID, "edc:name", "Test Asset CD Create");
        Asset mockAsset = createMockAsset(TEST_ASSET_ID, assetProps);

        List<Criterion> assetSelector = Collections.singletonList(new Criterion("asset:prop:id", "=", TEST_ASSET_ID));
        ContractDefinition mockContractDefinition = createMockContractDefinition(TEST_CONTRACT_DEFINITION_ID, "accessPolicyId", TEST_POLICY_ID, assetSelector);

        when(policyDefinitionStore.findById(TEST_POLICY_ID)).thenReturn(mockPolicyDefinition);
        when(assetStore.findAll(any(QuerySpec.class))).thenReturn(List.of(mockAsset).stream());

        when(okHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(mockResponse);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.body()).thenReturn(ResponseBody.create("", MediaType.parse("application/json")));

        // Act
        offersObserver.created(mockContractDefinition);

        // Assert
        verify(policyDefinitionStore).findById(TEST_POLICY_ID);
        verify(assetStore).findAll(any(QuerySpec.class));
        verify(objectMapper).writeValueAsString(any(Map.class));
        verify(okHttpClient).newCall(requestCaptor.capture());
        verify(mockCall).execute();

        Request capturedRequest = requestCaptor.getValue();
        assertEquals(TEST_API_ENDPOINT, capturedRequest.url().toString());
    }
}

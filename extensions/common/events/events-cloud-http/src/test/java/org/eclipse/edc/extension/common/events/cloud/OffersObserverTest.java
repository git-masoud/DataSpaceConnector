package org.eclipse.edc.extension.common.events.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.eclipse.edc.connector.asset.spi.index.AssetIndex; // Changed from AssetStore
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
    private AssetIndex assetIndex; // Changed from AssetStore
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
    @Captor
    private ArgumentCaptor<QuerySpec> querySpecCaptor;

    private OffersObserver offersObserver;

    @BeforeEach
    void setUp() {
        // objectMapper is a Spy, so it's already initialized.
        // If it were a @Mock, it would be: objectMapper = Mockito.mock(ObjectMapper.class);
        offersObserver = new OffersObserver(
                contractDefinitionResolver,
                contractDefinitionStore,
                assetIndex, // Changed from AssetStore
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
        ContractDefinition mockCdAccess = createMockContractDefinition("cdAccess1", TEST_POLICY_ID, "otherPolicyId", assetSelector);
        ContractDefinition mockCdContract = createMockContractDefinition("cdContract1", "otherAccessId", TEST_POLICY_ID, assetSelector);

        // Mocking contractDefinitionStore.findAll to return different streams based on the filter
        when(contractDefinitionStore.findAll(argThat(qs -> qs.getFilterExpression().stream()
                .anyMatch(c -> c.getOperandLeft().equals("accessPolicyId") && c.getOperandRight().equals(TEST_POLICY_ID)))))
                .thenReturn(Stream.of(mockCdAccess));
        when(contractDefinitionStore.findAll(argThat(qs -> qs.getFilterExpression().stream()
                .anyMatch(c -> c.getOperandLeft().equals("contractPolicyId") && c.getOperandRight().equals(TEST_POLICY_ID)))))
                .thenReturn(Stream.of(mockCdContract));

        when(assetIndex.queryAssets(any(QuerySpec.class))).thenReturn(Stream.of(mockAsset));

        when(okHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(mockResponse);
        when(mockResponse.isSuccessful()).thenReturn(true);
        // Return valid JSON to avoid issues with response.body().string() if called by logger
        when(mockResponse.body()).thenReturn(ResponseBody.create("{}", MediaType.parse("application/json")));


        // Act
        offersObserver.updated(mockPolicyDefinition, null);

        // Assert
        // Verify findAll is called twice (once for accessPolicyId, once for contractPolicyId)
        verify(contractDefinitionStore, times(2)).findAll(querySpecCaptor.capture());
        List<QuerySpec> capturedCdSpecs = querySpecCaptor.getAllValues();
        // More robust check for captured QuerySpecs
        assertTrue(capturedCdSpecs.stream().anyMatch(qs -> qs.getFilterExpression().stream()
                        .anyMatch(c -> c.getOperandLeft().equals("accessPolicyId") && c.getOperandRight().equals(TEST_POLICY_ID))),
                "QuerySpec for accessPolicyId not found or incorrect.");
        assertTrue(capturedCdSpecs.stream().anyMatch(qs -> qs.getFilterExpression().stream()
                        .anyMatch(c -> c.getOperandLeft().equals("contractPolicyId") && c.getOperandRight().equals(TEST_POLICY_ID))),
                "QuerySpec for contractPolicyId not found or incorrect.");


        // Verify queryAssets is called for each unique contract definition that has a selector.
        // In this setup, mockCdAccess and mockCdContract are distinct and both have selectors.
        verify(assetIndex, times(2)).queryAssets(querySpecCaptor.capture());
        List<QuerySpec> capturedAssetSpecs = querySpecCaptor.getAllValues().stream()
                                            .filter(qs -> qs.getFilterExpression().stream() // Check for asset property criteria
                                                .anyMatch(c -> c.getOperandLeft().toString().startsWith(Asset.PROPERTIES)))
                                            .collect(Collectors.toList());

        assertEquals(2, capturedAssetSpecs.size(), "Expected two asset queries with property selectors.");

        for(QuerySpec capturedAssetSpec : capturedAssetSpecs) {
            assertEquals(1, capturedAssetSpec.getLimit());
            assertTrue(capturedAssetSpec.getFilterExpression().get(0).getOperandLeft().toString().startsWith(Asset.PROPERTIES + "."));
            // Check if the specific criterion is correctly translated
             assertEquals(assetSelector.get(0).getOperandRight(), capturedAssetSpec.getFilterExpression().get(0).getOperandRight());
             assertEquals(assetSelector.get(0).getOperator(), capturedAssetSpec.getFilterExpression().get(0).getOperator());
        }

        verify(objectMapper, times(2)).writeValueAsString(any(Map.class));
        verify(okHttpClient, times(2)).newCall(requestCaptor.capture());
        verify(mockCall, times(2)).execute();

        List<Request> capturedRequests = requestCaptor.getAllValues();
        assertEquals(TEST_API_ENDPOINT, capturedRequests.get(0).url().toString());
        assertEquals(TEST_API_ENDPOINT, capturedRequests.get(1).url().toString());
    }

    @Test
    void testPolicyDefinitionUpdated_noMatchingContracts_doesNotSendEvent() throws IOException {
        // Arrange
        PolicyDefinition mockPolicyDefinition = createMockPolicyDefinition(TEST_POLICY_ID);
        // Mock contractDefinitionStore to return empty streams for both access and contract policy queries
        when(contractDefinitionStore.findAll(argThat(qs -> qs.getFilterExpression().stream()
                .anyMatch(c -> c.getOperandLeft().equals("accessPolicyId") && c.getOperandRight().equals(TEST_POLICY_ID)))))
                .thenReturn(Stream.empty());
        when(contractDefinitionStore.findAll(argThat(qs -> qs.getFilterExpression().stream()
                .anyMatch(c -> c.getOperandLeft().equals("contractPolicyId") && c.getOperandRight().equals(TEST_POLICY_ID)))))
                .thenReturn(Stream.empty());

        // Act
        offersObserver.updated(mockPolicyDefinition, null);

        // Assert
        verify(contractDefinitionStore, times(2)).findAll(any(QuerySpec.class));
        verify(assetIndex, never()).queryAssets(any(QuerySpec.class));
        verify(okHttpClient, never()).newCall(any(Request.class));
    }

    @Test
    void testPolicyDefinitionUpdated_contractFound_assetSelectorEmpty_doesNotSendEvent() throws IOException {
        // Arrange
        PolicyDefinition mockPolicyDefinition = createMockPolicyDefinition(TEST_POLICY_ID);
        ContractDefinition mockCdWithEmptySelector = createMockContractDefinition("cdEmptySelector", TEST_POLICY_ID, TEST_POLICY_ID, Collections.emptyList());

        when(contractDefinitionStore.findAll(argThat(qs -> qs.getFilterExpression().stream()
                .anyMatch(c -> c.getOperandLeft().equals("accessPolicyId") && c.getOperandRight().equals(TEST_POLICY_ID)))))
                .thenReturn(Stream.of(mockCdWithEmptySelector));
        when(contractDefinitionStore.findAll(argThat(qs -> qs.getFilterExpression().stream()
                .anyMatch(c -> c.getOperandLeft().equals("contractPolicyId") && c.getOperandRight().equals(TEST_POLICY_ID)))))
                .thenReturn(Stream.empty()); // Or also mockCdWithEmptySelector, distinct will handle it

        // Act
        offersObserver.updated(mockPolicyDefinition, null);

        // Assert
        verify(contractDefinitionStore, times(2)).findAll(any(QuerySpec.class));
        verify(assetIndex, never()).queryAssets(any(QuerySpec.class)); // Asset query should be skipped
        verify(okHttpClient, never()).newCall(any(Request.class));
    }

    @Test
    void testPolicyDefinitionUpdated_contractFound_noMatchingAsset_doesNotSendEvent() throws IOException {
        // Arrange
        PolicyDefinition mockPolicyDefinition = createMockPolicyDefinition(TEST_POLICY_ID);
        List<Criterion> assetSelector = Collections.singletonList(new Criterion("asset:prop:id", "=", TEST_ASSET_ID));
        ContractDefinition mockContractDefinition = createMockContractDefinition(TEST_CONTRACT_DEFINITION_ID, TEST_POLICY_ID, TEST_POLICY_ID, assetSelector);

        when(contractDefinitionStore.findAll(argThat(qs -> qs.getFilterExpression().stream()
                .anyMatch(c -> c.getOperandLeft().equals("accessPolicyId") && c.getOperandRight().equals(TEST_POLICY_ID)))))
                .thenReturn(Stream.of(mockContractDefinition));
        when(contractDefinitionStore.findAll(argThat(qs -> qs.getFilterExpression().stream()
                .anyMatch(c -> c.getOperandLeft().equals("contractPolicyId") && c.getOperandRight().equals(TEST_POLICY_ID)))))
                .thenReturn(Stream.empty());

        when(assetIndex.queryAssets(any(QuerySpec.class))).thenReturn(Stream.empty()); // No asset found

        // Act
        offersObserver.updated(mockPolicyDefinition, null);

        // Assert
        verify(contractDefinitionStore, times(2)).findAll(any(QuerySpec.class));
        verify(assetIndex).queryAssets(querySpecCaptor.capture());
        QuerySpec capturedAssetSpec = querySpecCaptor.getValue(); // Should be the one from the assetIndex call
        assertEquals(1, capturedAssetSpec.getLimit());
        assertTrue(capturedAssetSpec.getFilterExpression().get(0).getOperandLeft().toString().startsWith(Asset.PROPERTIES + "."));

        verify(okHttpClient, never()).newCall(any(Request.class));
    }

    @Test
    void testContractDefinitionCreated_matchingAssetAndPolicy_sendsEvent() throws IOException {
        // Arrange
        PolicyDefinition mockPolicyDefinition = createMockPolicyDefinition(TEST_POLICY_ID);
        Map<String, Object> assetProps = Map.of("asset:prop:id", TEST_ASSET_ID, "edc:name", "Test Asset CD Create");
        Asset mockAsset = createMockAsset(TEST_ASSET_ID, assetProps);

        String originalCriterionOperandLeft = "asset:prop:id";
        List<Criterion> assetSelector = Collections.singletonList(new Criterion(originalCriterionOperandLeft, "=", TEST_ASSET_ID));
        ContractDefinition mockContractDefinition = createMockContractDefinition(TEST_CONTRACT_DEFINITION_ID, "accessPolicyId", TEST_POLICY_ID, assetSelector);

        when(policyDefinitionStore.findById(TEST_POLICY_ID)).thenReturn(mockPolicyDefinition);
        when(assetIndex.queryAssets(any(QuerySpec.class))).thenReturn(Stream.of(mockAsset));

        when(okHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(mockResponse);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.body()).thenReturn(ResponseBody.create("{}", MediaType.parse("application/json")));

        // Act
        offersObserver.created(mockContractDefinition);

        // Assert
        verify(assetIndex).queryAssets(querySpecCaptor.capture());
        QuerySpec capturedAssetSpec = querySpecCaptor.getValue();
        assertEquals(1, capturedAssetSpec.getLimit());
        assertEquals(1, capturedAssetSpec.getFilterExpression().size());
        Criterion capturedCriterion = capturedAssetSpec.getFilterExpression().get(0);
        assertEquals(Asset.PROPERTIES + "." + originalCriterionOperandLeft, capturedCriterion.getOperandLeft());
        assertEquals("=", capturedCriterion.getOperator());
        assertEquals(TEST_ASSET_ID, capturedCriterion.getOperandRight());

        verify(policyDefinitionStore).findById(TEST_POLICY_ID);
        verify(objectMapper).writeValueAsString(any(Map.class));
        verify(okHttpClient).newCall(requestCaptor.capture());
        verify(mockCall).execute();

        Request capturedRequest = requestCaptor.getValue();
        assertEquals(TEST_API_ENDPOINT, capturedRequest.url().toString());
    }

    @Test
    void testContractDefinitionCreated_emptyAssetSelector_doesNotSendEvent() throws IOException {
        // Arrange
        ContractDefinition mockContractDefinition = createMockContractDefinition(
                TEST_CONTRACT_DEFINITION_ID, "accessPolicyId", TEST_POLICY_ID, Collections.emptyList()); // Empty selector

        // Act
        offersObserver.created(mockContractDefinition);

        // Assert
        verify(assetIndex, never()).queryAssets(any(QuerySpec.class));
        verify(policyDefinitionStore, never()).findById(anyString());
        verify(okHttpClient, never()).newCall(any(Request.class));
    }

    @Test
    void testContractDefinitionCreated_transformedCriteriaEmpty_doesNotSendEvent() throws IOException {
        // Arrange
        // Criterion with null operandLeft will be skipped by transformAssetSelectorCriteria
        List<Criterion> assetSelector = Collections.singletonList(new Criterion(null, "=", TEST_ASSET_ID));
        ContractDefinition mockContractDefinition = createMockContractDefinition(
                TEST_CONTRACT_DEFINITION_ID, "accessPolicyId", TEST_POLICY_ID, assetSelector);

        // Act
        offersObserver.created(mockContractDefinition);

        // Assert
        verify(assetIndex, never()).queryAssets(any(QuerySpec.class));
        verify(policyDefinitionStore, never()).findById(anyString());
        verify(okHttpClient, never()).newCall(any(Request.class));
    }


    @Test
    void testContractDefinitionCreated_noMatchingAsset_doesNotSendEvent() throws IOException {
        // Arrange
        List<Criterion> assetSelector = Collections.singletonList(new Criterion("asset:prop:id", "=", TEST_ASSET_ID));
        ContractDefinition mockContractDefinition = createMockContractDefinition(
                TEST_CONTRACT_DEFINITION_ID, "accessPolicyId", TEST_POLICY_ID, assetSelector);

        when(assetIndex.queryAssets(any(QuerySpec.class))).thenReturn(Stream.empty()); // No asset found

        // Act
        offersObserver.created(mockContractDefinition);

        // Assert
        verify(assetIndex).queryAssets(any(QuerySpec.class));
        verify(policyDefinitionStore, never()).findById(anyString());
        verify(okHttpClient, never()).newCall(any(Request.class));
    }

    @Test
    void testContractDefinitionCreated_policyNotFound_doesNotSendEvent() throws IOException {
        // Arrange
        Map<String, Object> assetProps = Map.of("asset:prop:id", TEST_ASSET_ID);
        Asset mockAsset = createMockAsset(TEST_ASSET_ID, assetProps);
        List<Criterion> assetSelector = Collections.singletonList(new Criterion("asset:prop:id", "=", TEST_ASSET_ID));
        ContractDefinition mockContractDefinition = createMockContractDefinition(
                TEST_CONTRACT_DEFINITION_ID, "accessPolicyId", TEST_POLICY_ID, assetSelector);

        when(assetIndex.queryAssets(any(QuerySpec.class))).thenReturn(Stream.of(mockAsset));
        when(policyDefinitionStore.findById(TEST_POLICY_ID)).thenReturn(null); // Policy not found

        // Act
        offersObserver.created(mockContractDefinition);

        // Assert
        verify(assetIndex).queryAssets(any(QuerySpec.class));
        verify(policyDefinitionStore).findById(TEST_POLICY_ID);
        verify(okHttpClient, never()).newCall(any(Request.class));
    }
}

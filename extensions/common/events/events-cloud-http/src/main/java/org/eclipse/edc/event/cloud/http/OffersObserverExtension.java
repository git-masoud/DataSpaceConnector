/*
 *  Copyright (c) 2022 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

package org.eclipse.edc.event.cloud.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.connector.asset.spi.observe.AssetObservable;
import org.eclipse.edc.connector.asset.spi.index.AssetIndex; // Changed from AssetStore
import org.eclipse.edc.connector.contract.spi.definition.observe.ContractDefinitionObservable;
import org.eclipse.edc.connector.contract.spi.offer.ContractDefinitionResolver;
import org.eclipse.edc.connector.contract.spi.store.ContractDefinitionStore;
import org.eclipse.edc.connector.policy.spi.observe.PolicyDefinitionObservable;
import org.eclipse.edc.connector.policy.spi.store.PolicyDefinitionStore;
import org.eclipse.edc.extension.common.events.cloud.OffersObserver; // Import for OffersObserver
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.event.Event;
import org.eclipse.edc.spi.event.EventRouter;
import org.eclipse.edc.spi.http.EdcHttpClient;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.Hostname;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.types.TypeManager;

import java.time.Clock;
import java.util.Objects;
import java.util.stream.Stream; // Added import for Stream

@Extension(value = "Offers Observer Extension") // Updated extension name
public class OffersObserverExtension implements ServiceExtension {

    @Setting(required = true)
    static final String EDC_EVENTS_CLOUDEVENTS_ENDPOINT = "edc.events.cloudevents.endpoint";

    @Setting(required = true, value = "API endpoint for OffersObserver") // Updated setting description
    static final String OFFERS_OBSERVER_API_ENDPOINT = "edc.events.offersobserver.endpoint"; // Updated setting key

    @Inject
    private EdcHttpClient edcHttpClient;

    @Inject
    private EventRouter eventRouter;

    @Inject
    private TypeManager typeManager;

    @Inject
    private Clock clock;

    @Inject
    private Hostname hostname;

    // Injections for OffersObserver
    @Inject(required = false)
    private AssetObservable assetObservable;

    @Inject(required = false)
    private PolicyDefinitionObservable policyDefinitionObservable;

    @Inject(required = false)
    private ContractDefinitionObservable contractDefinitionObservable;

    @Inject(required = false)
    private ContractDefinitionResolver contractDefinitionResolver;

    @Inject(required = false)
    private ContractDefinitionStore contractDefinitionStore;

    @Inject(required = false)
    private AssetIndex assetIndex; // Changed from AssetStore

    @Inject(required = false)
    private PolicyDefinitionStore policyDefinitionStore;


    @Override
    public void initialize(ServiceExtensionContext context) {
        Monitor monitor = context.getMonitor();

        // Initialize existing CloudEventsPublisher
        // Consider if this CloudEventsPublisher part is still relevant for OffersObserverExtension
        // For now, keeping it as it was. If this extension is solely for OffersObserver, this part could be removed.
        var cloudEventsEndpoint = context.getConfig().getString(EDC_EVENTS_CLOUDEVENTS_ENDPOINT, null);
        if (cloudEventsEndpoint != null) {
            eventRouter.register(Event.class, new CloudEventsPublisher(cloudEventsEndpoint, monitor, typeManager, edcHttpClient, clock, hostname));
            monitor.info("OffersObserverExtension: CloudEventsPublisher registered for endpoint: " + cloudEventsEndpoint);
        } else {
            monitor.info("OffersObserverExtension: CloudEventsPublisher not registered as endpoint is not configured.");
        }


        // Initialize OffersObserver
        String offersObserverApiEndpoint = context.getConfig().getString(OFFERS_OBSERVER_API_ENDPOINT, null);
        if (offersObserverApiEndpoint == null) {
            monitor.warning(String.format("OffersObserver not initialized: Missing configuration for '%s'", OFFERS_OBSERVER_API_ENDPOINT));
            return; // Do not proceed if the endpoint is not configured
        }

        // Check for required dependencies for OffersObserver
        if (Stream.of(assetObservable, policyDefinitionObservable, contractDefinitionObservable,
                     contractDefinitionResolver, contractDefinitionStore, assetIndex, policyDefinitionStore, edcHttpClient, typeManager) // Changed assetStore to assetIndex
                     .anyMatch(Objects::isNull)) {
            monitor.severe("OffersObserver not initialized due to missing one or more core dependencies. " +
                           "Please ensure AssetObservable, PolicyDefinitionObservable, ContractDefinitionObservable, " +
                           "ContractDefinitionResolver, ContractDefinitionStore, AssetIndex, PolicyDefinitionStore, " + // Changed AssetStore to AssetIndex
                           "EdcHttpClient, and TypeManager are available.");
            return;
        }

        ObjectMapper objectMapper = typeManager.getMapper(); // Get ObjectMapper from TypeManager

        OffersObserver offersObserver = new OffersObserver(
                contractDefinitionResolver,
                contractDefinitionStore,
                assetIndex, // Changed from assetStore
                policyDefinitionStore,
                edcHttpClient.getHttpClient(), // Pass the underlying OkHttpClient
                objectMapper,
                offersObserverApiEndpoint
        );

        assetObservable.register(offersObserver);
        policyDefinitionObservable.register(offersObserver);
        contractDefinitionObservable.register(offersObserver);

        monitor.info("OffersObserver registered. Sending events to: " + offersObserverApiEndpoint);
    }
}

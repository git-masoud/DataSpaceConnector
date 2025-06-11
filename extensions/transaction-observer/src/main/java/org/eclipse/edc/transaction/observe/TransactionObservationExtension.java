package org.eclipse.edc.transaction.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.connector.contract.spi.event.contractnegotiation.ContractNegotiationFinalized;
import org.eclipse.edc.connector.transfer.spi.event.TransferProcessCompleted;
import org.eclipse.edc.connector.transfer.spi.event.TransferProcessInitiated;
import org.eclipse.edc.connector.transfer.spi.event.TransferProcessTerminated;
import org.eclipse.edc.connector.transfer.spi.store.TransferProcessStore; // Added
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.asset.AssetIndex; // Added
import org.eclipse.edc.spi.event.EventRouter;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
// Removed individual observer imports if they were there

@Extension(value = "Transaction Observation Extension")
public class TransactionObservationExtension implements ServiceExtension {

    @Inject
    private EventRouter eventRouter;

    @Inject
    private TransferProcessStore transferProcessStore; // Added

    @Inject
    private AssetIndex assetIndex; // Added

    private UnifiedTransactionEventObserver unifiedObserver; // Changed

    @Override
    public String name() {
        return "Transaction Observation Extension";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        ObjectMapper objectMapper = new ObjectMapper(); // Single ObjectMapper

        // Instantiate the unified observer
        unifiedObserver = new UnifiedTransactionEventObserver(
                objectMapper,
                transferProcessStore,
                assetIndex,
                context.getMonitor() // Pass monitor to the observer
        );

        // Register for all relevant event types
        eventRouter.register(ContractNegotiationFinalized.class, unifiedObserver);
        eventRouter.register(TransferProcessInitiated.class, unifiedObserver);
        eventRouter.register(TransferProcessCompleted.class, unifiedObserver);
        eventRouter.register(TransferProcessTerminated.class, unifiedObserver);

        context.getMonitor().info("Initialized Transaction Observation Extension with UnifiedTransactionEventObserver.");
    }

    @Override
    public void shutdown() {
        if (eventRouter != null && unifiedObserver != null) {
            // Unregister for all event types it was registered for.
            // The EventRouter typically handles unregistering the subscriber instance
            // from all events it was registered for.
            eventRouter.unregister(unifiedObserver);
            context.getMonitor().info("Unregistered UnifiedTransactionEventObserver.");
        }
        ServiceExtension.super.shutdown();
    }
}

package org.eclipse.edc.monitor.transaction;

import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.util.logging.Logger;

@Extension(value = "Transaction Manager Log Extension")
public class TransactionManagerLogExtension implements ServiceExtension {

    @Override
    public String name() {
        return "Transaction Manager Log Extension";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        TransactionManagerLogHandler handler = new TransactionManagerLogHandler();

        // Optionally, configure the handler further using context settings if needed
        // For example, context.getConfig("edc.transaction.manager.api.endpoint");
        // However, the handler already reads from System.getProperty.

        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(handler);

        context.getMonitor().info("Initialized Transaction Manager Log Extension. Logs containing [Provider] or [Consumer] will be sent to the configured transaction manager.");
    }
}

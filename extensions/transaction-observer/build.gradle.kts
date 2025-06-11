plugins {
    `java-library`
}

// Ensure you have a group defined, e.g., in gradle.properties or set explicitly
// group = "org.eclipse.edc.extensions"

dependencies {
    api(project(":spi:common:core-spi"))
    api(project(":spi:control-plane:contract-spi")) // For ContractNegotiationFinalized event
    api(project(":spi:control-plane:transfer-spi")) // Added for TransferProcess events
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.0") // Or your project's version

    // Test dependencies (can be added later if creating unit tests)
    // testImplementation(project(":core:common:junit"))
    // testImplementation("org.mockito:mockito-core:4.11.0") // Or your project's version
}

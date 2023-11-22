/*
 *  Copyright (c) 2020, 2021 Microsoft Corporation
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Microsoft Corporation - initial API and implementation
 *
 */

plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core:control-plane:control-plane-core"))
    implementation(project(":data-protocols:dsp"))
    implementation(project(":extensions:common:auth:auth-tokenbased"))
    // implementation(project(":extensions:common:vault:vault-filesystem"))
     implementation(project(":extensions:common:vault:vault-hashicorp"))
    implementation(project(":extensions:common:configuration:configuration-filesystem"))
    implementation(project(":extensions:common:iam:iam-mock"))
//    implementation(project(":extensions:common:iam:oauth2:oauth2-daps"))
    implementation(project(":extensions:control-plane:api:management-api"))
    // Embedded DPF
    implementation(project(":extensions:control-plane:transfer:transfer-data-plane"))
    implementation(project(":extensions:data-plane-selector:data-plane-selector-client"))
    implementation(project(":spi:data-plane-selector:data-plane-selector-spi"))
    implementation(project(":extensions:control-plane:api:control-plane-api-client"))
    implementation(project(":core:control-plane:control-plane-aggregate-services"))
    implementation(project(":extensions:control-plane:api:control-plane-api"))
    implementation(project(":core:data-plane-selector:data-plane-selector-core"))
    implementation(project(":core:data-plane:data-plane-core"))
    implementation(project(":spi:control-plane:transfer-data-plane-spi"))
    //implementation(project(":core:data-plane:data-plane-core"))

    //implementation(project(":extensions:data-plane:data-plane-api"))
    implementation(project(":extensions:common:metrics:micrometer-core"))
    implementation(project(":extensions:data-plane:data-plane-http"))
    implementation("org.eclipse.edc:data-plane-aws-s3:0.4.1")
    //implementation("org.eclipse.edc:provision-aws-s3:0.3.1")
    // implementation(project(":extensions:data-plane:data-plane-kafka"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    //  implementation(project(":extensions:data-plane:data-plane-client"))
    implementation(project(":extensions:data-plane-selector:data-plane-selector-api"))

}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    exclude("**/pom.properties", "**/pom.xm")
    mergeServiceFiles()
    archiveFileName.set("data-plane-selector.jar")
}

edcBuild {
    publish.set(false)
}

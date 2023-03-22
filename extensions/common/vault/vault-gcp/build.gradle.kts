/*
 *  Copyright (c) 2022 Google LLC
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Google LCC - Initial implementation
 *
 */

plugins {
    `java-library`
}

dependencies {
    api(project(":spi:common:core-spi"))
    api(project(":core:common:util"))

    implementation(platform("com.google.cloud:libraries-bom:26.1.4"))

    implementation(libs.googlecloud.iam.admin)
    implementation(libs.googlecloud.iam.credentials)
    implementation("com.google.cloud:google-cloud-core")
    implementation("com.google.cloud:google-cloud-secretmanager")
    implementation("com.google.api:gax-grpc")
    implementation("com.google.auth:google-auth-library-oauth2-http")
    testImplementation(project(":core:common:junit"))
}

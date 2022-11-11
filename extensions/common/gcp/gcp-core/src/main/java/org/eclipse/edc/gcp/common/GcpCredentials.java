/*
 *  Copyright (c) 2022 T-Systems International GmbH
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       T-Systems International GmbH
 *
 */

package org.eclipse.edc.gcp.common;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.eclipse.edc.gcp.storage.GcsStoreSchema;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.spi.types.domain.DataAddress;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

public class GcpCredentials {

    public enum GcpCredentialType {
        DEFAULT_APPLICATION, GOOGLE_ACCESS_TOKEN, GOOGLE_SERVICE_KEY
    }

    private final Base64.Decoder b64Decoder;
    private final Vault vault;
    private final TypeManager typeManager;
    private final Monitor monitor;

    public GcpCredentials(Vault vault, TypeManager typeManager, Monitor monitor) {
        this.vault = vault;
        this.typeManager = typeManager;
        this.b64Decoder = Base64.getDecoder();
        this.monitor = monitor;
    }

    /**
     * Returns the Google Credentials which will be created based on the following order:
     * if keyName is provided in the dataAddress
     * - then Google Credentials should be retrieved from a token which is persisted in the vault
     * if ACCESS_TOKEN_VALUE is provided in the dataAddress
     * - then Google Credentials should be retrieved from a token which is provided in the ACCESS_TOKEN_VALUE in b64 format
     * if SERVICE_ACCOUNT_KEY_NAME is provided in the dataAddress
     * - then Google Credentials should be retrieved from a Credentials file which is persisted in the vault
     * if SERVICE_ACCOUNT_VALUE is provided in the dataAddress
     * - then Google Credentials should be retrieved from a Credentials file which is provided in the SERVICE_ACCOUNT_VALUE in b64 format
     * otherwise it will be created based on the Application Default Credentials
     *
     * @return GoogleCredentials
     */
    public GoogleCredentials resolveGoogleCredentialsFromDataAddress(DataAddress dataAddress) {
        if (dataAddress.getKeyName() != null && !dataAddress.getKeyName().isEmpty()) {
            var tokenContent = vault.resolveSecret(dataAddress.getKeyName());
            return createGoogleCredential(tokenContent, GcpCredentialType.GOOGLE_ACCESS_TOKEN);
        } else if (dataAddress.getProperty(GcsStoreSchema.SERVICE_ACCOUNT_KEY_NAME) != null && !dataAddress.getProperty(GcsStoreSchema.SERVICE_ACCOUNT_KEY_NAME).isEmpty()) {
            return createGoogleCredential(vault.resolveSecret(dataAddress.getProperty(GcsStoreSchema.SERVICE_ACCOUNT_KEY_NAME)), GcpCredentialType.GOOGLE_SERVICE_KEY);
        } else if (dataAddress.getProperty(GcsStoreSchema.SERVICE_ACCOUNT_KEY_VALUE) != null && !dataAddress.getProperty(GcsStoreSchema.SERVICE_ACCOUNT_KEY_VALUE).isEmpty()) {
            try {
                var serviceKeyContent = new String(b64Decoder.decode(dataAddress.getProperty(GcsStoreSchema.SERVICE_ACCOUNT_KEY_VALUE)));
                if (!serviceKeyContent.contains("service_account")) {
                    throw new GcpException("SERVICE_ACCOUNT_VALUE is not provided as a valid service account key file.");
                }
                return createGoogleCredential(serviceKeyContent, GcpCredentialType.GOOGLE_SERVICE_KEY);
            } catch (IllegalArgumentException ex) {
                throw new GcpException("SERVICE_ACCOUNT_VALUE is not provided in a valid base64 format.");
            }
        } else {
            return creatApplicationDefaultCredentials();
        }
    }

    /**
     * Returns the Google Credentials which will created based on the Application Default Credentials in the following approaches
     * - Credentials file pointed to by the GOOGLE_APPLICATION_CREDENTIALS environment variable
     * - Credentials provided by the Google Cloud SDK gcloud auth application-default login command
     * - Google App Engine built-in credentials
     * - Google Cloud Shell built-in credentials
     * - Google Compute Engine built-in credentials
     *
     * @return GoogleCredentials
     */
    public GoogleCredentials creatApplicationDefaultCredentials() {
        return createGoogleCredential("", GcpCredentialType.DEFAULT_APPLICATION);
    }


    public GoogleCredentials createGoogleCredential(String keyContent, GcpCredentialType gcpCredentialType) {
        GoogleCredentials googleCredentials;

        if (gcpCredentialType.equals(GcpCredentialType.GOOGLE_ACCESS_TOKEN)) {
            try {
                var gcpAccessToken = typeManager.readValue(keyContent, GcpAccessToken.class);
                monitor.info("Gcp: The provided token will be used to resolve the google credentials.");
                googleCredentials = GoogleCredentials.create(
                        new AccessToken(gcpAccessToken.getToken(),
                                new Date(gcpAccessToken.getExpiration())));
            } catch (EdcException ex) {
                throw new GcpException("ACCESS_TOKEN is not in a valid GcpAccessToken format.");
            } catch (Exception e) {
                throw new GcpException("Error while getting the default credentials.", e);
            }
        } else if (gcpCredentialType.equals(GcpCredentialType.GOOGLE_SERVICE_KEY)) {
            try {
                monitor.info("Gcp: The provided credentials file will be used to resolve the google credentials.");
                googleCredentials = GoogleCredentials.fromStream(new ByteArrayInputStream(keyContent.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new GcpException("Error while getting the credentials from the credentials file.", e);
            }

        } else {
            try {
                monitor.info("Gcp: The default Credentials will be used to resolve the google credentials.");
                googleCredentials = GoogleCredentials.getApplicationDefault();
            } catch (IOException e) {
                throw new GcpException("Error while getting the default credentials.", e);
            }
        }
        return googleCredentials;
    }
}
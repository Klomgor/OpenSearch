/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.common.settings;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateUpdateTask;
import org.opensearch.cluster.LocalNodeClusterManagerListener;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Priority;
import org.opensearch.common.UUIDs;
import org.opensearch.common.hash.MessageDigests;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Used to publish secure setting hashes in the cluster state and to validate those hashes against the local values of those same settings.
 * This is colloquially referred to as the secure setting consistency check. It will publish and verify hashes only for the collection
 * of settings passed in the constructor. The settings have to have the {@link Setting.Property#Consistent} property.
 *
 * @opensearch.internal
 */
public final class ConsistentSettingsService {
    private static final Logger logger = LogManager.getLogger(ConsistentSettingsService.class);

    private final Settings settings;
    private final ClusterService clusterService;
    private final Collection<Setting<?>> secureSettingsCollection;
    private final SecretKeyFactory pbkdf2KeyFactory;

    public ConsistentSettingsService(Settings settings, ClusterService clusterService, Collection<Setting<?>> secureSettingsCollection) {
        this.settings = settings;
        this.clusterService = clusterService;
        this.secureSettingsCollection = secureSettingsCollection;
        // this is used to compute the PBKDF2 hash (the published one)
        try {
            this.pbkdf2KeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("The \"PBKDF2WithHmacSHA512\" algorithm is required for consistent secure settings' hashes", e);
        }
    }

    /**
     * Returns a {@link LocalNodeClusterManagerListener} that will publish hashes of all the settings passed in the constructor. These hashes are
     * published by the cluster-manager node only. Note that this is not designed for {@link SecureSettings} implementations that are mutable.
     */
    public LocalNodeClusterManagerListener newHashPublisher() {
        // eagerly compute hashes to be published
        final Map<String, String> computedHashesOfConsistentSettings = computeHashesOfConsistentSecureSettings();
        return new HashesPublisher(computedHashesOfConsistentSettings, clusterService);
    }

    /**
     * Verifies that the hashes of consistent secure settings in the latest {@code ClusterState} verify for the values of those same
     * settings on the local node. The settings to be checked are passed in the constructor. Also, validates that a missing local
     * value is also missing in the published set, and vice-versa.
     */
    public boolean areAllConsistent() {
        final ClusterState state = clusterService.state();
        final Map<String, String> publishedHashesOfConsistentSettings = state.metadata().hashesOfConsistentSettings();
        final Set<String> publishedSettingKeysToVerify = new HashSet<>();
        publishedSettingKeysToVerify.addAll(publishedHashesOfConsistentSettings.keySet());
        final AtomicBoolean allConsistent = new AtomicBoolean(true);
        forEachConcreteSecureSettingDo(concreteSecureSetting -> {
            final String publishedSaltAndHash = publishedHashesOfConsistentSettings.get(concreteSecureSetting.getKey());
            final byte[] localHash = concreteSecureSetting.getSecretDigest(settings);
            if (publishedSaltAndHash == null && localHash == null) {
                // consistency of missing
                logger.debug(
                    "no published hash for the consistent secure setting [{}] but it also does NOT exist on the local node",
                    concreteSecureSetting.getKey()
                );
            } else if (publishedSaltAndHash == null && localHash != null) {
                // setting missing on cluster-manager but present locally
                logger.warn(
                    "no published hash for the consistent secure setting [{}] but it exists on the local node",
                    concreteSecureSetting.getKey()
                );
                if (state.nodes().isLocalNodeElectedClusterManager()) {
                    throw new IllegalStateException(
                        "Master node cannot validate consistent setting. No published hash for ["
                            + concreteSecureSetting.getKey()
                            + "] but setting exists."
                    );
                }
                allConsistent.set(false);
            } else if (publishedSaltAndHash != null && localHash == null) {
                // setting missing locally but present on master
                logger.warn(
                    "the consistent secure setting [{}] does not exist on the local node but there is a published hash for it",
                    concreteSecureSetting.getKey()
                );
                allConsistent.set(false);
            } else {
                assert publishedSaltAndHash != null;
                assert localHash != null;
                final String[] parts = publishedSaltAndHash.split(":");
                if (parts == null || parts.length != 2) {
                    throw new IllegalArgumentException(
                        "published hash ["
                            + publishedSaltAndHash
                            + " ] for secure setting ["
                            + concreteSecureSetting.getKey()
                            + "] is invalid"
                    );
                }
                final String publishedSalt = parts[0];
                final String publishedHash = parts[1];
                final byte[] computedSaltedHashBytes = computeSaltedPBKDF2Hash(localHash, publishedSalt.getBytes(StandardCharsets.UTF_8));
                final String computedSaltedHash = new String(Base64.getEncoder().encode(computedSaltedHashBytes), StandardCharsets.UTF_8);
                if (false == publishedHash.equals(computedSaltedHash)) {
                    logger.warn(
                        "the published hash [{}] of the consistent secure setting [{}] differs from the locally computed one [{}]",
                        publishedHash,
                        concreteSecureSetting.getKey(),
                        computedSaltedHash
                    );
                    if (state.nodes().isLocalNodeElectedClusterManager()) {
                        throw new IllegalStateException(
                            "Master node cannot validate consistent setting. The published hash ["
                                + publishedHash
                                + "] of the consistent secure setting ["
                                + concreteSecureSetting.getKey()
                                + "] differs from the locally computed one ["
                                + computedSaltedHash
                                + "]."
                        );
                    }
                    allConsistent.set(false);
                }
            }
            publishedSettingKeysToVerify.remove(concreteSecureSetting.getKey());
        });
        // another case of settings missing locally, when group settings have not expanded to all the keys published
        for (String publishedSettingKey : publishedSettingKeysToVerify) {
            for (Setting<?> setting : secureSettingsCollection) {
                if (setting.match(publishedSettingKey)) {
                    // setting missing locally but present on master
                    logger.warn(
                        "the consistent secure setting [{}] does not exist on the local node but there is a published hash for it",
                        publishedSettingKey
                    );
                    allConsistent.set(false);
                }
            }
        }
        return allConsistent.get();
    }

    /**
     * Iterate over the passed in secure settings, expanding {@link Setting.AffixSetting} to concrete settings, in the scope of the local
     * settings.
     */
    private void forEachConcreteSecureSettingDo(Consumer<SecureSetting<?>> secureSettingConsumer) {
        for (Setting<?> setting : secureSettingsCollection) {
            assert setting.isConsistent() : "[" + setting.getKey() + "] is not a consistent setting";
            if (setting instanceof Setting.AffixSetting<?>) {
                ((Setting.AffixSetting<?>) setting).getAllConcreteSettings(settings).forEach(concreteSetting -> {
                    assert concreteSetting instanceof SecureSetting<?> : "[" + concreteSetting.getKey() + "] is not a secure setting";
                    secureSettingConsumer.accept((SecureSetting<?>) concreteSetting);
                });
            } else if (setting instanceof SecureSetting<?>) {
                secureSettingConsumer.accept((SecureSetting<?>) setting);
            } else {
                assert false : "Unrecognized consistent secure setting [" + setting.getKey() + "]";
            }
        }
    }

    private Map<String, String> computeHashesOfConsistentSecureSettings() {
        final Map<String, String> hashesBySettingKey = new HashMap<>();
        forEachConcreteSecureSettingDo(concreteSecureSetting -> {
            final byte[] localHash = concreteSecureSetting.getSecretDigest(settings);
            if (localHash != null) {
                final String salt = UUIDs.randomBase64UUID();
                final byte[] publicHash = computeSaltedPBKDF2Hash(localHash, salt.getBytes(StandardCharsets.UTF_8));
                final String encodedPublicHash = new String(Base64.getEncoder().encode(publicHash), StandardCharsets.UTF_8);
                hashesBySettingKey.put(concreteSecureSetting.getKey(), salt + ":" + encodedPublicHash);
            }
        });
        return hashesBySettingKey;
    }

    private byte[] computeSaltedPBKDF2Hash(byte[] bytes, byte[] salt) {
        final int iterations = 5000;
        final int keyLength = 512;
        char[] value = null;
        try {
            value = MessageDigests.toHexCharArray(bytes);
            final PBEKeySpec spec = new PBEKeySpec(value, salt, iterations, keyLength);
            final SecretKey key = pbkdf2KeyFactory.generateSecret(spec);
            return key.getEncoded();
        } catch (InvalidKeySpecException e) {
            throw new RuntimeException("Unexpected exception when computing PBKDF2 hash", e);
        } finally {
            if (value != null) {
                Arrays.fill(value, '0');
            }
        }
    }

    static final class HashesPublisher implements LocalNodeClusterManagerListener {

        // eagerly compute hashes to be published
        final Map<String, String> computedHashesOfConsistentSettings;
        final ClusterService clusterService;

        HashesPublisher(Map<String, String> computedHashesOfConsistentSettings, ClusterService clusterService) {
            this.computedHashesOfConsistentSettings = Collections.unmodifiableMap(computedHashesOfConsistentSettings);
            this.clusterService = clusterService;
        }

        @Override
        public void onClusterManager() {
            clusterService.submitStateUpdateTask("publish-secure-settings-hashes", new ClusterStateUpdateTask(Priority.URGENT) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    final Map<String, String> publishedHashesOfConsistentSettings = currentState.metadata().hashesOfConsistentSettings();
                    if (computedHashesOfConsistentSettings.equals(publishedHashesOfConsistentSettings)) {
                        logger.debug("Nothing to publish. What is already published matches this node's view.");
                        return currentState;
                    } else {
                        return ClusterState.builder(currentState)
                            .metadata(
                                Metadata.builder(currentState.metadata()).hashesOfConsistentSettings(computedHashesOfConsistentSettings)
                            )
                            .build();
                    }
                }

                @Override
                public void onFailure(String source, Exception e) {
                    logger.error("unable to publish secure settings hashes", e);
                }

            });
        }

        @Override
        public void offClusterManager() {
            logger.trace("I am no longer master, nothing to do");
        }
    }

}

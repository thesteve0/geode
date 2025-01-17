/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.net;

import static org.apache.geode.internal.net.filewatch.FileWatchingX509ExtendedKeyManager.newFileWatchingKeyManager;
import static org.apache.geode.internal.net.filewatch.FileWatchingX509ExtendedTrustManager.newFileWatchingTrustManager;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.stream.Stream;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


public class SSLUtil {
  /**
   * This is a list of the algorithms that are tried, in order, when "any" is specified. Update
   * this list as new algorithms become available and are supported by Geode. Remove old,
   * no-longer trusted algorithms.
   */
  static final String[] DEFAULT_ALGORITHMS = {"TLSv1.3", "TLSv1.2"};

  public static @NotNull SSLContext getSSLContextInstance(final @NotNull SSLConfig sslConfig)
      throws NoSuchAlgorithmException {
    final String[] protocols = combineProtocols(sslConfig);
    return findSSLContextForProtocols(protocols, DEFAULT_ALGORITHMS);
  }

  static @NotNull String[] combineProtocols(final @NotNull SSLConfig sslConfig) {
    return Stream.concat(
        Stream.of(sslConfig.getServerProtocolsAsStringArray()),
        Stream.of(sslConfig.getClientProtocolsAsStringArray()))
        .distinct()
        .toArray(String[]::new);
  }

  /**
   * Search for a context supporting one of the given prioritized list of
   * protocols. The second argument is a list of protocols to try if the
   * first list contains "any". The second argument should also be in prioritized
   * order. If there are no matches for any of the protocols in the second
   * argument we will continue in the first argument list.
   * with a first argument of A, B, any, C
   * and a second argument of D, E
   * the search order would be A, B, D, E, C
   */
  static @NotNull SSLContext findSSLContextForProtocols(final @NotNull String[] protocols,
      final @NotNull String[] protocolsForAny)
      throws NoSuchAlgorithmException {
    for (String protocol : protocols) {
      if (protocol.equalsIgnoreCase("any")) {
        try {
          return findSSLContextForProtocols(protocolsForAny, new String[0]);
        } catch (NoSuchAlgorithmException e) {
          // none of the default algorithms is available - continue to see if there
          // are any others in the requested list
        }
      }
      try {
        return SSLContext.getInstance(protocol);
      } catch (NoSuchAlgorithmException e) {
        // continue
      }
    }
    throw new NoSuchAlgorithmException();
  }

  /** Splits an array of values from a string, whitespace or comma separated. */
  static @NotNull String[] split(final @Nullable String text) {
    if (StringUtils.isBlank(text)) {
      return new String[0];
    }

    return text.split("[\\s,]+");
  }

  public static @NotNull SSLContext createAndConfigureSSLContext(final @NotNull SSLConfig sslConfig,
      final boolean skipSslVerification) {
    try {
      if (sslConfig.useDefaultSSLContext()) {
        return SSLContext.getDefault();
      }

      final SSLContext ssl = getSSLContextInstance(sslConfig);

      final KeyManager[] keyManagers;
      if (sslConfig.getKeystore() != null) {
        keyManagers = new KeyManager[] {newFileWatchingKeyManager(sslConfig)};
      } else {
        keyManagers = null;
      }

      final TrustManager[] trustManagers;
      if (skipSslVerification) {
        trustManagers = new TrustManager[] {new X509TrustManager() {
          @Override
          public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return null;
          }

          @Override
          public void checkClientTrusted(X509Certificate[] certs, String authType) {}

          @Override
          public void checkServerTrusted(X509Certificate[] certs, String authType) {}

        }};
      } else if (sslConfig.getTruststore() != null) {
        trustManagers = new TrustManager[] {newFileWatchingTrustManager(sslConfig)};
      } else {
        trustManagers = null;
      }

      ssl.init(keyManagers, trustManagers, new SecureRandom());
      return ssl;
    } catch (Exception e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  static @NotNull KeyManagerFactory getDefaultKeyManagerFactory() throws NoSuchAlgorithmException {
    return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
  }

  static @NotNull TrustManagerFactory getDefaultTrustManagerFactory()
      throws NoSuchAlgorithmException {
    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
  }
}

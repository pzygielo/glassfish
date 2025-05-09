/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation.
 * Copyright (c) 1997, 2021 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.enterprise.server.pluggable;

import com.sun.enterprise.security.ssl.manager.UnifiedX509KeyManager;
import com.sun.enterprise.security.ssl.manager.UnifiedX509TrustManager;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Permission;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.PropertyPermission;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.glassfish.api.admin.ProcessEnvironment;
import org.glassfish.api.admin.ProcessEnvironment.ProcessType;
import org.glassfish.logging.annotation.LogMessageInfo;
import org.glassfish.logging.annotation.LogMessagesResourceBundle;
import org.glassfish.logging.annotation.LoggerInfo;
import org.glassfish.security.common.MasterPassword;
import org.jvnet.hk2.annotations.Service;


/**
 * SecuritySupport is part of PluggableFeature that provides access to internal services managed by application server.
 *
 * @author Shing Wai Chan
 */
@Service
@Singleton
public class SecuritySupport {

    public static final String KEY_STORE_PROP = "javax.net.ssl.keyStore";
    public static final String KEYSTORE_PASS_PROP = "javax.net.ssl.keyStorePassword";
    public static final String KEYSTORE_TYPE_PROP = "javax.net.ssl.keyStoreType";

    public static final String TRUST_STORE_PROP = "javax.net.ssl.trustStore";
    public static final String TRUSTSTORE_PASS_PROP = "javax.net.ssl.trustStorePassword";
    public static final String TRUSTSTORE_TYPE_PROP = "javax.net.ssl.trustStoreType";


    @LogMessagesResourceBundle
    private static final String SHARED_LOGMESSAGE_RESOURCE = "com.sun.enterprise.server.pluggable.LogMessages";

    @LoggerInfo(subsystem = "SECURITY - SSL", description = "Security - SSL", publish = true)
    private static final String SEC_SSL_LOGGER = "jakarta.enterprise.system.security.ssl";

    @LogMessageInfo(
        message = "The SSL certificate with alias {0} has expired: {1}",
        level = "SEVERE",
        cause = "Certificate expired.",
        action = "Check the expiration date of the certicate.")
    private static final String SSL_CERT_EXPIRED = "NCLS-SECURITY-05054";

    private static final Logger LOG = Logger.getLogger(SEC_SSL_LOGGER, SHARED_LOGMESSAGE_RESOURCE);

    private static final String DEFAULT_KEYSTORE_PASS = "changeit";
    private static final String DEFAULT_TRUSTSTORE_PASS = "changeit";

    private final List<KeyStore> keyStores = new ArrayList<>();
    private final List<KeyStore> trustStores = new ArrayList<>();
    private final List<char[]> keyStorePasswords = new ArrayList<>();
    private final List<String> tokenNames = new ArrayList<>();

    private final Date initDate = new Date();

    @Inject
    private ProcessEnvironment processEnvironment;

    @Inject
    private MasterPassword masterPassword;


    @PostConstruct
    private void init() {
        String keyStoreFileName = System.getProperty(KEY_STORE_PROP);
        String trustStoreFileName = System.getProperty(TRUST_STORE_PROP);
        char[] keyStorePass = masterPassword.getMasterPassword();
        char[] trustStorePass = keyStorePass;

        boolean isAcc = processEnvironment.getProcessType().equals(ProcessType.ACC);

        // If we don't have a keystore password yet check the properties.
        // Always do so for the app client case whether the passwords have been
        // found from master password helper or not.
        if (keyStorePass == null || isAcc) {
            final String keyStorePassOverride = System.getProperty(KEYSTORE_PASS_PROP, DEFAULT_KEYSTORE_PASS);
            if (keyStorePassOverride != null) {
                keyStorePass = keyStorePassOverride.toCharArray();
            }

            final String trustStorePassOverride = System.getProperty(TRUSTSTORE_PASS_PROP, DEFAULT_TRUSTSTORE_PASS);
            if (trustStorePassOverride != null) {
                trustStorePass = trustStorePassOverride.toCharArray();
            }
        }

        loadStores(
            null, null,
            keyStoreFileName, keyStorePass,
            System.getProperty(KEYSTORE_TYPE_PROP, KeyStore.getDefaultType()),
            trustStoreFileName, trustStorePass,
            System.getProperty(TRUSTSTORE_TYPE_PROP, KeyStore.getDefaultType()));
        Arrays.fill(keyStorePass, ' ');
        Arrays.fill(trustStorePass, ' ');
    }

    /**
     * @return an array of keystores containing keys and certificates.
     */
    public KeyStore[] getKeyStores() {
        return keyStores.toArray(new KeyStore[keyStores.size()]);
    }

    /**
     * @return an array of truststores containing certificates.
     */
    public KeyStore[] getTrustStores() {
        return trustStores.toArray(new KeyStore[trustStores.size()]);
    }

    /**
     * @param token
     * @return a keystore. If token is null, return the the first keystore.
     */
    public KeyStore getKeyStore(String token) {
        int idx = getTokenIndex(token);
        if (idx < 0) {
            return null;
        }
        return keyStores.get(idx);
    }

    /**
     * @param token
     * @return a truststore. If token is null, return the first truststore.
     */
    public KeyStore getTrustStore(String token) {
        int idx = getTokenIndex(token);
        if (idx < 0) {
            return null;
        }
        return trustStores.get(idx);
    }

    /**
     * @param type
     * @param index
     * @return load a null keystore of given type.
     * @throws KeyStoreException
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws CertificateException
     */
    public KeyStore loadNullStore(String type, int index)
        throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore keyStore = KeyStore.getInstance(type);
        keyStore.load(null, keyStorePasswords.get(index));
        return keyStore;
    }

    /**
     * @param masterPass
     * @return result whether the given master password is correct.
     */
    public boolean verifyMasterPassword(final char[] masterPass) {
        return Arrays.equals(masterPass, keyStorePasswords.get(0));
    }

    /**
     * @param algorithm
     * @return KeyManagers for the specified algorithm.
     * @throws IOException
     * @throws KeyStoreException
     * @throws NoSuchAlgorithmException
     * @throws UnrecoverableKeyException
     */
    public KeyManager[] getKeyManagers(String algorithm)
        throws IOException, KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
        KeyStore[] kstores = getKeyStores();
        ArrayList<KeyManager> keyManagers = new ArrayList<>();
        for (int i = 0; i < kstores.length; i++) {
            checkCertificateDates(kstores[i], initDate);
            KeyManagerFactory kmf = KeyManagerFactory
                .getInstance((algorithm != null) ? algorithm : KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(kstores[i], keyStorePasswords.get(i));
            KeyManager[] kmgrs = kmf.getKeyManagers();
            if (kmgrs != null) {
                keyManagers.addAll(Arrays.asList(kmgrs));
            }
        }

        KeyManager keyManager = new UnifiedX509KeyManager(keyManagers.toArray(new X509KeyManager[keyManagers.size()]),
            getTokenNames());
        return new KeyManager[] {keyManager};
    }

    /**
     * @param algorithm
     * @return TrustManagers for the specified algorithm.
     * @throws IOException
     * @throws KeyStoreException
     * @throws NoSuchAlgorithmException
     */
    public TrustManager[] getTrustManagers(String algorithm) throws IOException, KeyStoreException, NoSuchAlgorithmException {
        KeyStore[] tstores = getTrustStores();
        ArrayList<TrustManager> trustManagers = new ArrayList<>();
        for (KeyStore tstore : tstores) {
            checkCertificateDates(tstore, initDate);
            TrustManagerFactory tmf = TrustManagerFactory
                .getInstance((algorithm != null) ? algorithm : TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(tstore);
            TrustManager[] tmgrs = tmf.getTrustManagers();
            if (tmgrs != null) {
                trustManagers.addAll(Arrays.asList(tmgrs));
            }
        }
        TrustManager trustManager;
        if (trustManagers.size() == 1) {
            trustManager = trustManagers.get(0);
        } else {
            trustManager = new UnifiedX509TrustManager(trustManagers.toArray(new X509TrustManager[trustManagers.size()]));
        }

        return new TrustManager[] { trustManager };
    }

    /**
     * Gets the PrivateKey for specified alias from the corresponding keystore indicated by the index.
     *
     * @param alias Alias for which the PrivateKey is desired.
     * @param keystoreIndex Index of the keystore.
     * @return {@link PrivateKey} or null
     * @throws KeyStoreException
     * @throws NoSuchAlgorithmException
     * @throws UnrecoverableKeyException
     */
    public PrivateKey getPrivateKeyForAlias(String alias, int keystoreIndex) throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
        if (System.getProperty("java.vm.specification.version").compareTo("24") < 0 && processEnvironment.getProcessType().isStandaloneServer()) {
            checkPermission(KEYSTORE_PASS_PROP);
        }

        Key key = keyStores.get(keystoreIndex).getKey(alias, keyStorePasswords.get(keystoreIndex));
        if (key instanceof PrivateKey) {
            return (PrivateKey) key;
        }

        return null;
    }

    /**
     * @return an array of token names in order corresponding to array of keystores.
     */
    public String[] getTokenNames() {
        return tokenNames.toArray(new String[tokenNames.size()]);
    }

    /**
     * Check permission for the given key.
     *
     * @param key
     */
    private void checkPermission(String key) {
        try {
            // Checking a random permission to check if it is server.
            Permission perm = new RuntimePermission("SSLPassword");
            AccessController.checkPermission(perm);
        } catch (AccessControlException e) {
            String message = e.getMessage();
            Permission perm = new PropertyPermission(key, "read");
            if (message != null) {
                message = message.replace(e.getPermission().toString(), perm.toString());
            }
            throw new AccessControlException(message, perm);
        }
    }

    private int getTokenIndex(String token) {
        int idx = -1;
        if (token != null) {
            idx = tokenNames.indexOf(token);
            if (idx < 0) {
                LOG.log(Level.FINEST, "Token {0} not found", token);
            }
        }
        return idx;
    }


    private void loadStores(String tokenName, Provider provider, String keyStoreFile, char[] keyStorePass,
        String keyStoreType, String trustStoreFile, char[] trustStorePass, String trustStoreType) {

        try {
            KeyStore keyStore = loadKS(keyStoreType, provider, keyStoreFile, keyStorePass);
            KeyStore trustStore = loadKS(trustStoreType, provider, trustStoreFile, trustStorePass);
            keyStores.add(keyStore);
            trustStores.add(trustStore);
            keyStorePasswords.add(Arrays.copyOf(keyStorePass, keyStorePass.length));
            tokenNames.add(tokenName);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    /**
     * This method load keystore with given keystore file and keystore password for a given keystore
     * type and provider. It always return a non-null keystore.
     *
     * @param keyStoreType
     * @param provider
     * @param keyStoreFile
     * @param keyStorePass
     * @retun keystore loaded, never null.
     */
    private static KeyStore loadKS(String keyStoreType, Provider provider, String keyStoreFile, char[] keyStorePass) throws Exception {
        final KeyStore keyStore;
        if (provider == null) {
            keyStore = KeyStore.getInstance(keyStoreType);
        } else {
            keyStore = KeyStore.getInstance(keyStoreType, provider);
        }
        if (keyStoreFile == null) {
            keyStore.load(null, keyStorePass);
            return keyStore;
        }
        try (FileInputStream istream = new FileInputStream(keyStoreFile);
            BufferedInputStream bstream = new BufferedInputStream(istream)) {
            LOG.log(Level.FINE, "Loading keystoreFile = {0}, keystorePass is null = {1}",
                new Object[] {keyStoreFile, keyStorePass == null});
            keyStore.load(bstream, keyStorePass);
        }

        return keyStore;
    }

    /**
     * Check X509 certificates in a store for expiration.
     */
    private static void checkCertificateDates(KeyStore store, Date date) throws KeyStoreException {
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            var alias = aliases.nextElement();
            Certificate cert = store.getCertificate(alias);
            if (cert instanceof X509Certificate) {
                if (((X509Certificate) cert).getNotAfter().before(date)) {
                    LOG.log(Level.SEVERE, SSL_CERT_EXPIRED, new Object[] { alias, cert });
                }
            }
        }
    }
}

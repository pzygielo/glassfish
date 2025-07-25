/*
 * Copyright (c) 2021, 2025 Contributors to the Eclipse Foundation
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.admin.mbeanserver.ssl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CRL;
import java.security.cert.CRLException;
import java.security.cert.CertPathParameters;
import java.security.cert.CertStore;
import java.security.cert.CertStoreParameters;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.glassfish.admin.mbeanserver.Util;
import org.glassfish.logging.annotation.LogMessageInfo;

import static org.glassfish.embeddable.GlassFishVariable.KEYSTORE_FILE;

/**
 * This class is a utility class that would configure a client socket factory using
 * either the SSL defaults for GlassFish  or via params supplied.
 * This is a singleton class.
 * The initial use for this class is to configure the  SslRMIClientSocketFactory
 * for use with the JMX connector.
 *
 * @author prasads@dev.java.net
 */
public class SSLClientConfigurator {

    private SSLParams sslParams;
    private static volatile SSLClientConfigurator sslCC;
    private SSLContext sslContext;

    private static final Logger _logger = Util.getLogger();
    private String[] enabledProtocols;
    private String[] enabledCipherSuites;

    @LogMessageInfo(level="SEVERE", message="Error preparing SSL context", action="Please refer to the stack trace", cause="unknown")
    private final static String errorPreparingSSL = Util.LOG_PREFIX + "00014";

    @LogMessageInfo(level="WARNING", message="No Key store found for {0}")
    private final static String noKeyEntry = Util.LOG_PREFIX + "00015";

    @LogMessageInfo(level="WARNING", message="No keystores defined")
    private final static String noKeyStores = Util.LOG_PREFIX + "00016";

    @LogMessageInfo(level="WARNING", message="Bad maxCertLength: {0}")
    private final static String badMaxCertLength = Util.LOG_PREFIX + "00017";

    @LogMessageInfo(level="SEVERE", message="JSSE keystoreload failed for type = {0} path = {1} {2}", action="Please refer to the stack trace", cause="unknown")
    private final static String keystoreLoadFailed = Util.LOG_PREFIX + "00018";

    @LogMessageInfo(level="WARNING", message="All SSL protocol variants disabled for network-listener, using SSL implementation specific defaults")
    private final static String allVariantsDisabled = Util.LOG_PREFIX + "00019";

    @LogMessageInfo(level="WARNING", message="All SSL cipher suites disabled for network-listener(s).  Using SSL implementation specific defaults")
    private final static String allCipherSuitesDisabled = Util.LOG_PREFIX + "00020";

    @LogMessageInfo(level="WARNING", message="Unknown cipher error for cipher {0}")
    private final static String unkCipher = Util.LOG_PREFIX + "00021";

    // Private constructor
    private SSLClientConfigurator() {

    }

    public static SSLClientConfigurator getInstance() {
        if(sslCC == null ) {
            sslCC = new SSLClientConfigurator();
            return sslCC;
        } else {
            return sslCC;
        }
    }

    public void setSSLParams(SSLParams sslParams) {
        this.sslParams = sslParams;
    }

    /**
     * This method creates an SSLContext based on the default provider and then
     * created TrustManagers, KeyManagers and initializes the SSLContext with
     * the TrustManager, KeyManager
     *
     * @return SSLContext
     */
    public SSLContext configure(SSLParams sslParams) {
        this.sslParams = sslParams;

        // get the protocol and the SSLContext.
        String protocol = sslParams.getProtocol();
        try {
            sslContext = SSLContext.getInstance(protocol);
        } catch (NoSuchAlgorithmException ex) {

            _logger.log(Level.SEVERE, errorPreparingSSL, ex);
        }

        configureCiphersAndProtocols();

        // get the TrustManagers
        String trustAlgorithm = sslParams.getTrustAlgorithm();
        if (trustAlgorithm == null) {
            trustAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
        }

       // Certificate encoding algorithm (e.g., SunX509)
       String algorithm = sslParams.getKeyAlgorithm();
       if (algorithm == null) {
            algorithm = "SunX509";
       }

       String keyAlias = sslParams.getCertNickname();
       if (keyAlias == null) {
           keyAlias = "s1as";
       }

       // Initialize the SSLContext
        try {
            sslContext.init(getKeyManagers(algorithm, keyAlias), getTrustManagers(trustAlgorithm), new SecureRandom());
        } catch (Exception ex) {
            _logger.log(Level.SEVERE, errorPreparingSSL, ex);
        }

        return sslContext;
    }

    /**
     * Gets a list of Enabled Protocols
     * @return
     */
    public String[] getEnabledProtocols() {
        if(enabledProtocols == null ) {
            configureCiphersAndProtocols();
        }
        return enabledProtocols;
    }

    /**
     * Returns the list of Enabled Protocols as a comma separated String
     * @return
     */
    public String getEnabledProtocolsAsString() {
        if(getEnabledProtocols() != null && getEnabledProtocols().length >0) {
            return toCommaSeparatedString(getEnabledProtocols());
        } else {
            return null ;
        }
    }

    /**
     * gets a list of Enabled Cipher Suites
     * @return
     */
    public String[] getEnabledCipherSuites() {
        if(enabledCipherSuites == null) {
            configureCiphersAndProtocols();
        }
        return enabledCipherSuites;
    }

    /**
     * Returns a list of Enabled Cipher Suites as a String
     * @return
     */
    public String getEnabledCipherSuitesAsString() {
        if(getEnabledCipherSuites() != null && getEnabledCipherSuites().length > 0) {
            return toCommaSeparatedString(getEnabledCipherSuites());
        } else {
            return null;
        }
    }


    /**
     * Gets the initialized key managers.
     */
    protected KeyManager[] getKeyManagers(String algorithm, String keyAlias) throws Exception {
        if (System.getProperty(KEYSTORE_FILE.getSystemPropertyName()) == null) {
            _logger.log(Level.WARNING, noKeyStores);
            return null;
        }
        _logger.log(Level.FINE, "Algorithm: {0}", algorithm);
        _logger.log(Level.FINE, "Key Alias: {0}", keyAlias);
        _logger.log(Level.FINE, "KeyStore Type: {0}", sslParams.getKeyStoreType());

        String keystorePass = sslParams.getKeyStorePassword();
        KeyStore ks = getStore(sslParams.getKeyStoreType(), sslParams.getKeyStore().getPath(), keystorePass);
        if (keyAlias != null && !ks.isKeyEntry(keyAlias)) {
            _logger.log(Level.WARNING, noKeyEntry, keyAlias);
            return null;
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);
        kmf.init(ks, keystorePass.toCharArray());

        return kmf.getKeyManagers();
    }


    /**
     * @return the intialized trust managers or null
     */
    protected TrustManager[] getTrustManagers(String algorithm) throws Exception {
        String crlf = sslParams.getCrlFile();

        _logger.log(Level.FINE, "in getTrustManagers TrustManager type = {0} path = {1} password = {2}",
                new Object[]{sslParams.getTrustStoreType(),
                    sslParams.getTrustStore().getPath(),
                    sslParams.getTrustStorePassword()});

        KeyStore trustStore = getStore(sslParams.getTrustStoreType(), sslParams.getTrustStore().getPath(),
            sslParams.getTrustStorePassword());
        if (trustStore == null) {
            return null;
        }
        if (crlf == null) {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(algorithm);
            tmf.init(trustStore);
            return tmf.getTrustManagers();
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(algorithm);
        CertPathParameters params = getParameters(algorithm, crlf, trustStore);
        ManagerFactoryParameters mfp = new CertPathTrustManagerParameters(params);
        tmf.init(mfp);
        return tmf.getTrustManagers();
    }


    /**
     * Return the initialization parameters for the TrustManager.
     * Currently, only the default <code>PKIX</code> is supported.
     *
     * @param algorithm The algorithm to get parameters for.
     * @param crlf The path to the CRL file.
     * @param trustStore The configured TrustStore.
     * @return The parameters including the CRLs and TrustStore.
     */
    protected CertPathParameters getParameters(String algorithm,
                                               String crlf,
                                               KeyStore trustStore)
            throws Exception {

        CertPathParameters params = null;
        if ("PKIX".equalsIgnoreCase(algorithm)) {
            PKIXBuilderParameters xparams =
                new PKIXBuilderParameters(trustStore,
                                          new X509CertSelector());
            Collection crls = getCRLs(crlf);
            CertStoreParameters csp = new CollectionCertStoreParameters(crls);
            CertStore store = CertStore.getInstance("Collection", csp);
            xparams.addCertStore(store);
            xparams.setRevocationEnabled(true);
            String trustLength = sslParams.getTrustMaxCertLength();
            if (trustLength != null) {
                try {
                    xparams.setMaxPathLength(Integer.parseInt(trustLength));
                } catch(Exception ex) {

                    _logger.log(Level.WARNING, badMaxCertLength, trustLength);
                }
            }
            params = xparams;
        } else {
            throw new CRLException("CRLs not supported for type: "
                                   + algorithm);
        }
        return params;
    }


    /**
     * Load the collection of CRLs.
     */
    protected Collection<? extends CRL> getCRLs(String crlf)
            throws IOException, CRLException, CertificateException {

        File crlFile = new File(crlf);
        if (!crlFile.isAbsolute()) {
            crlFile = new File(System.getProperty("catalina.base"), crlf);
        }
        Collection<? extends CRL> crls = null;
        InputStream is = null;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            is = new FileInputStream(crlFile);
            crls = cf.generateCRLs(is);
        } catch(IOException iex) {
            throw iex;
        } catch(CRLException crle) {
            throw crle;
        } catch(CertificateException ce) {
            throw ce;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ex) {
                }
            }
        }

        return crls;
    }

   /*
     * Gets the key- or truststore with the specified type, path, and password.
     */
    private KeyStore getStore(String type, String path, String pass)
            throws IOException {

        KeyStore ks = null;
        InputStream istream = null;
        try {
            ks = KeyStore.getInstance(type);
            if (!"PKCS11".equalsIgnoreCase(type) && !"".equalsIgnoreCase(path)) {
                File keyStoreFile = new File(path);
                if (!keyStoreFile.isAbsolute()) {
                    keyStoreFile = new File(System.getProperty("catalina.base"), path);
                }
                istream = new FileInputStream(keyStoreFile);
            }
            ks.load(istream, pass.toCharArray());
        } catch (Exception ex) {
            _logger.log(Level.SEVERE, formatMessage(keystoreLoadFailed, type, path, ex.getMessage()), ex);
            throw new IOException(ex.getMessage(), ex);
        } finally {
            if (istream != null) {
                try {
                    istream.close();
                } catch (IOException ioe) {
                    // Do nothing
                }
            }
        }

        return ks;
    }

    private static String formatMessage(final String key, final Object... args) {
        final String format = Util.JMX_LOGGER.getResourceBundle().getString(key);
        return MessageFormat.format(format, args);
    }

    private void configureCiphersAndProtocols() {
        List<String> tmpSSLArtifactsList = new LinkedList<String>();
        // first configure the protocols
        System.out.println("SSLParams ="+ sslParams);
        if (sslParams.getSsl2Enabled()) {
            tmpSSLArtifactsList.add("SSLv2");
        }
        if (sslParams.getSsl3Enabled()) {
            tmpSSLArtifactsList.add("SSLv3");
        }
        if (sslParams.getTlsEnabled()) {
            tmpSSLArtifactsList.add("TLSv1");
        }
        if (sslParams.getTls11Enabled()) {
            tmpSSLArtifactsList.add("TLSv1.1");
        }
        if (sslParams.getTls12Enabled()) {
            tmpSSLArtifactsList.add("TLSv1.2");
        }
        if (sslParams.getTls13Enabled()) {
            tmpSSLArtifactsList.add("TLSv1.3");
        }
        if (sslParams.getSsl3Enabled() || sslParams.getTlsEnabled()) {
            tmpSSLArtifactsList.add("SSLv2Hello");
        }

        if (tmpSSLArtifactsList.isEmpty()) {
            _logger.log(Level.WARNING, allVariantsDisabled);
        } else {
            final String[] protocols = new String[tmpSSLArtifactsList.size()];
            tmpSSLArtifactsList.toArray(protocols);
            enabledProtocols = protocols;
        }

        tmpSSLArtifactsList.clear();

        // ssl3-tls-ciphers
        final String ssl3Ciphers = sslParams.getSsl3TlsCiphers();
        if (ssl3Ciphers != null && ssl3Ciphers.length() > 0) {
            final String[] ssl3CiphersArray = ssl3Ciphers.split(",");
            for (final String cipher : ssl3CiphersArray) {
                tmpSSLArtifactsList.add(cipher.trim());
            }
        }
        // ssl2-tls-ciphers
        final String ssl2Ciphers = sslParams.getSsl2Ciphers();
        if (ssl2Ciphers != null && ssl2Ciphers.length() > 0) {
            final String[] ssl2CiphersArray = ssl2Ciphers.split(",");
            for (final String cipher : ssl2CiphersArray) {
                tmpSSLArtifactsList.add(cipher.trim());
            }
        }

        final String[] ciphers = getJSSECiphers(tmpSSLArtifactsList);
        if (ciphers == null || ciphers.length == 0) {
            _logger.log(Level.WARNING, allCipherSuitesDisabled);
        } else {
            enabledCipherSuites = ciphers;
        }
    }


    /*
     * Evalutates the given List of cipher suite names, converts each cipher
     * suite that is enabled (i.e., not preceded by a '-') to the corresponding
     * JSSE cipher suite name, and returns a String[] of enabled cipher suites.
     *
     * @param sslCiphers List of SSL ciphers to evaluate.
     *
     * @return String[] of cipher suite names, or null if none of the cipher
     *  suites in the given List are enabled or can be mapped to corresponding
     *  JSSE cipher suite names
     */
    private String[] getJSSECiphers(final List<String> configuredCiphers) {
        Set<String> enabledCiphers = null;
        for (String cipher : configuredCiphers) {
            if (cipher.length() > 0 && cipher.charAt(0) != '-') {
                if (cipher.charAt(0) == '+') {
                    cipher = cipher.substring(1);
                }
                final String jsseCipher = getJSSECipher(cipher);
                if (jsseCipher == null) {
                    _logger.log(Level.WARNING, unkCipher, cipher);
                } else {
                    if (enabledCiphers == null) {
                        enabledCiphers = new HashSet<String>(configuredCiphers.size());
                    }
                    enabledCiphers.add(jsseCipher);
                }
            }
        }

        return ((enabledCiphers == null)
                ? null
                : enabledCiphers.toArray(new String[enabledCiphers.size()]));
    }


    /*
     * Converts the given cipher suite name to the corresponding JSSE cipher.
     *
     * @param cipher The cipher suite name to convert
     *
     * @return The corresponding JSSE cipher suite name, or null if the given
     * cipher suite name can not be mapped
     */
    private static String getJSSECipher(final String cipher) {

        final CipherInfo ci = CipherInfo.getCipherInfo(cipher);
        return ((ci != null) ? ci.getCipherName() : null);

    }

    private String toCommaSeparatedString(String[] strArray) {
        StringBuilder strBuf = new StringBuilder(strArray[0]);
        for(int i=1; i<strArray.length; i++) {
            strBuf.append(",");
            strBuf.append(strArray[i]);
        }
        return strBuf.toString();
    }


    // ---------------------------------------------------------- Nested Classes


    /**
     * This class represents the information associated with ciphers.
     * It also maintains a Map from configName to CipherInfo.
     */
    private static final class CipherInfo {
        private static final short SSL2 = 0x1;
        private static final short SSL3 = 0x2;
        private static final short TLS = 0x4;

        // The old names mapped to the standard names as existed
        private static final String[][] OLD_CIPHER_MAPPING = {
                // IWS 6.x or earlier
                {"rsa_null_md5", "SSL_RSA_WITH_NULL_MD5"},
                {"rsa_null_sha", "SSL_RSA_WITH_NULL_SHA"},
                {"rsa_rc4_40_md5", "SSL_RSA_EXPORT_WITH_RC4_40_MD5"},
                {"rsa_rc4_128_md5", "SSL_RSA_WITH_RC4_128_MD5"},
                {"rsa_rc4_128_sha", "SSL_RSA_WITH_RC4_128_SHA"},
                {"rsa_3des_sha", "SSL_RSA_WITH_3DES_EDE_CBC_SHA"},
                {"fips_des_sha", "SSL_RSA_WITH_DES_CBC_SHA"},
                {"rsa_des_sha", "SSL_RSA_WITH_DES_CBC_SHA"},

                // backward compatible with AS 9.0 or earlier
                {"SSL_RSA_WITH_NULL_MD5", "SSL_RSA_WITH_NULL_MD5"},
                {"SSL_RSA_WITH_NULL_SHA", "SSL_RSA_WITH_NULL_SHA"}
        };

        private static final Map<String,CipherInfo> ciphers =
                new HashMap<String,CipherInfo>();

        private final String cipherName;
        private final short protocolVersion;


        static {
            for (int i = 0, len = OLD_CIPHER_MAPPING.length; i < len; i++) {
                String nonStdName = OLD_CIPHER_MAPPING[i][0];
                String stdName = OLD_CIPHER_MAPPING[i][1];
                ciphers.put(nonStdName,
                        new CipherInfo(stdName, (short) (SSL3 | TLS)));
            }
        }

        /**
         * @param cipherName      name that may depends on backend
         * @param protocolVersion
         */
        private CipherInfo(final String cipherName,
                           final short protocolVersion) {
            this.cipherName = cipherName;
            this.protocolVersion = protocolVersion;
        }

        public static void updateCiphers(final SSLContext sslContext) {
            SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
            String[] supportedCiphers = factory.getDefaultCipherSuites();
            for (int i = 0, len = supportedCiphers.length; i < len; i++) {
                String s = supportedCiphers[i];
                ciphers.put(s, new CipherInfo(s, (short) (SSL3 | TLS)));
            }
        }
        public static CipherInfo getCipherInfo(final String configName) {
            return ciphers.get(configName);
        }

        public String getCipherName() {
            return cipherName;
        }

        @SuppressWarnings({"UnusedDeclaration"})
        public boolean isSSL2() {
            return (protocolVersion & SSL2) == SSL2;
        }

        @SuppressWarnings({"UnusedDeclaration"})
        public boolean isSSL3() {
            return (protocolVersion & SSL3) == SSL3;
        }

        @SuppressWarnings({"UnusedDeclaration"})
        public boolean isTLS() {
            return (protocolVersion & TLS) == TLS;
        }

    } // END CipherInfo


}

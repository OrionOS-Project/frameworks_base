/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.keystore;

import java.security.Provider;

/**
 * {@link Provider} of JCA crypto operations operating on Android KeyStore keys.
 *
 * <p>This provider was separated out of {@link AndroidKeyStoreProvider} to work around the issue
 * that Bouncy Castle provider incorrectly declares that it accepts arbitrary keys (incl. Android
 * KeyStore ones). This causes JCA to select the Bouncy Castle's implementation of JCA crypto
 * operations for Android KeyStore keys unless Android KeyStore's own implementations are installed
 * as higher-priority than Bouncy Castle ones. The purpose of this provider is to do just that: to
 * offer crypto operations operating on Android KeyStore keys and to be installed at higher priority
 * than the Bouncy Castle provider.
 *
 * <p>Once Bouncy Castle provider is fixed, this provider can be merged into the
 * {@code AndroidKeyStoreProvider}.
 *
 * @hide
 */
class AndroidKeyStoreBCWorkaroundProvider extends Provider {

    // IMPLEMENTATION NOTE: Class names are hard-coded in this provider to avoid loading these
    // classes when this provider is instantiated and installed early on during each app's
    // initialization process.

    private static final String PACKAGE_NAME = "android.security.keystore";
    private static final String KEYSTORE_SECRET_KEY_CLASS_NAME =
            PACKAGE_NAME + ".AndroidKeyStoreSecretKey";
    private static final String KEYSTORE_PRIVATE_KEY_CLASS_NAME =
            PACKAGE_NAME + ".AndroidKeyStorePrivateKey";
    private static final String KEYSTORE_PUBLIC_KEY_CLASS_NAME =
            PACKAGE_NAME + ".AndroidKeyStorePublicKey";

    AndroidKeyStoreBCWorkaroundProvider() {
        super("AndroidKeyStoreBCWorkaround",
                1.0,
                "Android KeyStore security provider to work around Bouncy Castle");

        // --------------------- javax.crypto.Mac
        putMacImpl("HmacSHA1", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA1");
        put("Alg.Alias.Mac.1.2.840.113549.2.7", "HmacSHA1");
        put("Alg.Alias.Mac.HMAC-SHA1", "HmacSHA1");
        put("Alg.Alias.Mac.HMAC/SHA1", "HmacSHA1");

        putMacImpl("HmacSHA224", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA224");
        put("Alg.Alias.Mac.1.2.840.113549.2.9", "HmacSHA224");
        put("Alg.Alias.Mac.HMAC-SHA224", "HmacSHA224");
        put("Alg.Alias.Mac.HMAC/SHA224", "HmacSHA224");

        putMacImpl("HmacSHA256", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA256");
        put("Alg.Alias.Mac.1.2.840.113549.2.9", "HmacSHA256");
        put("Alg.Alias.Mac.HMAC-SHA256", "HmacSHA256");
        put("Alg.Alias.Mac.HMAC/SHA256", "HmacSHA256");

        putMacImpl("HmacSHA384", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA384");
        put("Alg.Alias.Mac.1.2.840.113549.2.10", "HmacSHA384");
        put("Alg.Alias.Mac.HMAC-SHA384", "HmacSHA384");
        put("Alg.Alias.Mac.HMAC/SHA384", "HmacSHA384");

        putMacImpl("HmacSHA512", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA512");
        put("Alg.Alias.Mac.1.2.840.113549.2.11", "HmacSHA512");
        put("Alg.Alias.Mac.HMAC-SHA512", "HmacSHA512");
        put("Alg.Alias.Mac.HMAC/SHA512", "HmacSHA512");

        // --------------------- javax.crypto.Cipher
        putSymmetricCipherImpl("AES/ECB/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStoreUnauthenticatedAESCipherSpi$ECB$NoPadding");
        putSymmetricCipherImpl("AES/ECB/PKCS7Padding",
                PACKAGE_NAME + ".AndroidKeyStoreUnauthenticatedAESCipherSpi$ECB$PKCS7Padding");

        putSymmetricCipherImpl("AES/CBC/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStoreUnauthenticatedAESCipherSpi$CBC$NoPadding");
        putSymmetricCipherImpl("AES/CBC/PKCS7Padding",
                PACKAGE_NAME + ".AndroidKeyStoreUnauthenticatedAESCipherSpi$CBC$PKCS7Padding");

        putSymmetricCipherImpl("AES/CTR/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStoreUnauthenticatedAESCipherSpi$CTR$NoPadding");

        putAsymmetricCipherImpl("RSA/ECB/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$NoPadding");
        put("Alg.Alias.Cipher.RSA/None/NoPadding", "RSA/ECB/NoPadding");
        putAsymmetricCipherImpl("RSA/ECB/PKCS1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$PKCS1Padding");
        put("Alg.Alias.Cipher.RSA/None/PKCS1Padding", "RSA/ECB/PKCS1Padding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPPadding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA1AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPPadding", "RSA/ECB/OAEPPadding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPWithSHA-1AndMGF1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA1AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-1AndMGF1Padding",
                "RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPWithSHA-224AndMGF1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA224AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-224AndMGF1Padding",
                "RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA256AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-256AndMGF1Padding",
                "RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPWithSHA-384AndMGF1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA384AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-384AndMGF1Padding",
                "RSA/ECB/OAEPWithSHA-384AndMGF1Padding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPWithSHA-512AndMGF1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA512AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-512AndMGF1Padding",
                "RSA/ECB/OAEPWithSHA-512AndMGF1Padding");
    }

    private void putMacImpl(String algorithm, String implClass) {
        put("Mac." + algorithm, implClass);
        put("Mac." + algorithm + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
    }

    private void putSymmetricCipherImpl(String transformation, String implClass) {
        put("Cipher." + transformation, implClass);
        put("Cipher." + transformation + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
    }

    private void putAsymmetricCipherImpl(String transformation, String implClass) {
        put("Cipher." + transformation, implClass);
        put("Cipher." + transformation + " SupportedKeyClasses",
                KEYSTORE_PRIVATE_KEY_CLASS_NAME + "|" + KEYSTORE_PUBLIC_KEY_CLASS_NAME);
    }
}

/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.annotation.Nullable;
import android.security.Credentials;
import android.security.KeyPairGeneratorSpec;
import android.security.KeyStore;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;

import com.android.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.org.bouncycastle.asn1.ASN1InputStream;
import com.android.org.bouncycastle.asn1.ASN1Integer;
import com.android.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.org.bouncycastle.asn1.DERBitString;
import com.android.org.bouncycastle.asn1.DERInteger;
import com.android.org.bouncycastle.asn1.DERNull;
import com.android.org.bouncycastle.asn1.DERSequence;
import com.android.org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import com.android.org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import com.android.org.bouncycastle.asn1.x509.Certificate;
import com.android.org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import com.android.org.bouncycastle.asn1.x509.TBSCertificate;
import com.android.org.bouncycastle.asn1.x509.Time;
import com.android.org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import com.android.org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import com.android.org.bouncycastle.jce.X509Principal;
import com.android.org.bouncycastle.jce.provider.X509CertificateObject;
import com.android.org.bouncycastle.x509.X509V3CertificateGenerator;

import libcore.util.EmptyArray;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyPairGeneratorSpi;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provides a way to create instances of a KeyPair which will be placed in the
 * Android keystore service usable only by the application that called it. This
 * can be used in conjunction with
 * {@link java.security.KeyStore#getInstance(String)} using the
 * {@code "AndroidKeyStore"} type.
 * <p>
 * This class can not be directly instantiated and must instead be used via the
 * {@link KeyPairGenerator#getInstance(String)
 * KeyPairGenerator.getInstance("AndroidKeyStore")} API.
 *
 * @hide
 */
public abstract class AndroidKeyStoreKeyPairGeneratorSpi extends KeyPairGeneratorSpi {

    public static class RSA extends AndroidKeyStoreKeyPairGeneratorSpi {
        public RSA() {
            super(KeymasterDefs.KM_ALGORITHM_RSA);
        }
    }

    public static class EC extends AndroidKeyStoreKeyPairGeneratorSpi {
        public EC() {
            super(KeymasterDefs.KM_ALGORITHM_EC);
        }
    }

    /*
     * These must be kept in sync with system/security/keystore/defaults.h
     */

    /* EC */
    private static final int EC_DEFAULT_KEY_SIZE = 256;
    private static final int EC_MIN_KEY_SIZE = 192;
    private static final int EC_MAX_KEY_SIZE = 521;

    /* RSA */
    private static final int RSA_DEFAULT_KEY_SIZE = 2048;
    private static final int RSA_MIN_KEY_SIZE = 512;
    private static final int RSA_MAX_KEY_SIZE = 8192;

    private static final Map<String, Integer> SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE =
            new HashMap<String, Integer>();
    private static final List<String> SUPPORTED_EC_NIST_CURVE_NAMES = new ArrayList<String>();
    static {
        // Aliases for NIST P-192
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("p-192", 192);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("secp192r1", 192);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("prime192v1", 192);

        // Aliases for NIST P-224
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("p-224", 224);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("secp224r1", 224);

        // Aliases for NIST P-256
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("p-256", 256);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("secp256r1", 256);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("prime256v1", 256);

        // Aliases for NIST P-384
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("p-384", 384);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("secp384r1", 384);

        // Aliases for NIST P-521
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("p-521", 521);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("secp521r1", 521);

        SUPPORTED_EC_NIST_CURVE_NAMES.addAll(SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.keySet());
        Collections.sort(SUPPORTED_EC_NIST_CURVE_NAMES);
    }

    private final int mOriginalKeymasterAlgorithm;

    private KeyStore mKeyStore;

    private KeyGenParameterSpec mSpec;

    private String mEntryAlias;
    private boolean mEncryptionAtRestRequired;
    private @KeyProperties.KeyAlgorithmEnum String mJcaKeyAlgorithm;
    private int mKeymasterAlgorithm = -1;
    private int mKeySizeBits;
    private SecureRandom mRng;

    private int[] mKeymasterPurposes;
    private int[] mKeymasterBlockModes;
    private int[] mKeymasterEncryptionPaddings;
    private int[] mKeymasterSignaturePaddings;
    private int[] mKeymasterDigests;

    private long mRSAPublicExponent;

    protected AndroidKeyStoreKeyPairGeneratorSpi(int keymasterAlgorithm) {
        mOriginalKeymasterAlgorithm = keymasterAlgorithm;
    }

    @Override
    public void initialize(int keysize, SecureRandom random) {
        throw new IllegalArgumentException(
                KeyGenParameterSpec.class.getName() + " or " + KeyPairGeneratorSpec.class.getName()
                + " required to initialize this KeyPairGenerator");
    }

    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidAlgorithmParameterException {
        resetAll();

        boolean success = false;
        try {
            if (params == null) {
                throw new InvalidAlgorithmParameterException(
                        "Must supply params of type " + KeyGenParameterSpec.class.getName()
                        + " or " + KeyPairGeneratorSpec.class.getName());
            }

            KeyGenParameterSpec spec;
            boolean encryptionAtRestRequired = false;
            int keymasterAlgorithm = mOriginalKeymasterAlgorithm;
            if (params instanceof KeyGenParameterSpec) {
                spec = (KeyGenParameterSpec) params;
            } else if (params instanceof KeyPairGeneratorSpec) {
                // Legacy/deprecated spec
                KeyPairGeneratorSpec legacySpec = (KeyPairGeneratorSpec) params;
                try {
                    KeyGenParameterSpec.Builder specBuilder;
                    String specKeyAlgorithm = legacySpec.getKeyType();
                    if (specKeyAlgorithm != null) {
                        // Spec overrides the generator's default key algorithm
                        try {
                            keymasterAlgorithm =
                                    KeyProperties.KeyAlgorithm.toKeymasterAsymmetricKeyAlgorithm(
                                            specKeyAlgorithm);
                        } catch (IllegalArgumentException e) {
                            throw new InvalidAlgorithmParameterException(
                                    "Invalid key type in parameters", e);
                        }
                    }
                    switch (keymasterAlgorithm) {
                        case KeymasterDefs.KM_ALGORITHM_EC:
                            specBuilder = new KeyGenParameterSpec.Builder(
                                    legacySpec.getKeystoreAlias(),
                                    KeyProperties.PURPOSE_SIGN
                                    | KeyProperties.PURPOSE_VERIFY);
                            // Authorized to be used with any digest (including no digest).
                            specBuilder.setDigests(KeyProperties.DIGEST_NONE);
                            break;
                        case KeymasterDefs.KM_ALGORITHM_RSA:
                            specBuilder = new KeyGenParameterSpec.Builder(
                                    legacySpec.getKeystoreAlias(),
                                    KeyProperties.PURPOSE_ENCRYPT
                                    | KeyProperties.PURPOSE_DECRYPT
                                    | KeyProperties.PURPOSE_SIGN
                                    | KeyProperties.PURPOSE_VERIFY);
                            // Authorized to be used with any digest (including no digest).
                            specBuilder.setDigests(KeyProperties.DIGEST_NONE);
                            specBuilder.setSignaturePaddings(
                                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1);
                            // Authorized to be used with any padding (including no padding).
                            specBuilder.setEncryptionPaddings(
                                    KeyProperties.ENCRYPTION_PADDING_NONE);
                            // Disable randomized encryption requirement to support encryption
                            // padding NONE above.
                            specBuilder.setRandomizedEncryptionRequired(false);
                            break;
                        default:
                            throw new ProviderException(
                                    "Unsupported algorithm: " + mKeymasterAlgorithm);
                    }

                    if (legacySpec.getKeySize() != -1) {
                        specBuilder.setKeySize(legacySpec.getKeySize());
                    }
                    if (legacySpec.getAlgorithmParameterSpec() != null) {
                        specBuilder.setAlgorithmParameterSpec(
                                legacySpec.getAlgorithmParameterSpec());
                    }
                    specBuilder.setCertificateSubject(legacySpec.getSubjectDN());
                    specBuilder.setCertificateSerialNumber(legacySpec.getSerialNumber());
                    specBuilder.setCertificateNotBefore(legacySpec.getStartDate());
                    specBuilder.setCertificateNotAfter(legacySpec.getEndDate());
                    encryptionAtRestRequired = legacySpec.isEncryptionRequired();
                    specBuilder.setUserAuthenticationRequired(false);

                    spec = specBuilder.build();
                } catch (NullPointerException | IllegalArgumentException e) {
                    throw new InvalidAlgorithmParameterException(e);
                }
            } else {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported params class: " + params.getClass().getName()
                        + ". Supported: " + KeyGenParameterSpec.class.getName()
                        + ", " + KeyPairGeneratorSpec.class.getName());
            }

            mEntryAlias = spec.getKeystoreAlias();
            mSpec = spec;
            mKeymasterAlgorithm = keymasterAlgorithm;
            mEncryptionAtRestRequired = encryptionAtRestRequired;
            mKeySizeBits = spec.getKeySize();
            initAlgorithmSpecificParameters();
            if (mKeySizeBits == -1) {
                mKeySizeBits = getDefaultKeySize(keymasterAlgorithm);
            }
            checkValidKeySize(keymasterAlgorithm, mKeySizeBits);

            if (spec.getKeystoreAlias() == null) {
                throw new InvalidAlgorithmParameterException("KeyStore entry alias not provided");
            }

            String jcaKeyAlgorithm;
            try {
                jcaKeyAlgorithm = KeyProperties.KeyAlgorithm.fromKeymasterAsymmetricKeyAlgorithm(
                        keymasterAlgorithm);
                mKeymasterPurposes = KeyProperties.Purpose.allToKeymaster(spec.getPurposes());
                mKeymasterBlockModes = KeyProperties.BlockMode.allToKeymaster(spec.getBlockModes());
                mKeymasterEncryptionPaddings = KeyProperties.EncryptionPadding.allToKeymaster(
                        spec.getEncryptionPaddings());
                mKeymasterSignaturePaddings = KeyProperties.SignaturePadding.allToKeymaster(
                        spec.getSignaturePaddings());
                if (spec.isDigestsSpecified()) {
                    mKeymasterDigests = KeyProperties.Digest.allToKeymaster(spec.getDigests());
                } else {
                    mKeymasterDigests = EmptyArray.INT;
                }
            } catch (IllegalArgumentException e) {
                throw new InvalidAlgorithmParameterException(e);
            }

            mJcaKeyAlgorithm = jcaKeyAlgorithm;
            mRng = random;
            mKeyStore = KeyStore.getInstance();
            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    private void resetAll() {
        mEntryAlias = null;
        mJcaKeyAlgorithm = null;
        mKeymasterAlgorithm = -1;
        mKeymasterPurposes = null;
        mKeymasterBlockModes = null;
        mKeymasterEncryptionPaddings = null;
        mKeymasterSignaturePaddings = null;
        mKeymasterDigests = null;
        mKeySizeBits = 0;
        mSpec = null;
        mRSAPublicExponent = -1;
        mEncryptionAtRestRequired = false;
        mRng = null;
        mKeyStore = null;
    }

    private void initAlgorithmSpecificParameters() throws InvalidAlgorithmParameterException {
        AlgorithmParameterSpec algSpecificSpec = mSpec.getAlgorithmParameterSpec();
        switch (mKeymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_RSA:
            {
                BigInteger publicExponent = null;
                if (algSpecificSpec instanceof RSAKeyGenParameterSpec) {
                    RSAKeyGenParameterSpec rsaSpec = (RSAKeyGenParameterSpec) algSpecificSpec;
                    if (mKeySizeBits == -1) {
                        mKeySizeBits = rsaSpec.getKeysize();
                    } else if (mKeySizeBits != rsaSpec.getKeysize()) {
                        throw new InvalidAlgorithmParameterException("RSA key size must match "
                                + " between " + mSpec + " and " + algSpecificSpec
                                + ": " + mKeySizeBits + " vs " + rsaSpec.getKeysize());
                    }
                    publicExponent = rsaSpec.getPublicExponent();
                } else if (algSpecificSpec != null) {
                    throw new InvalidAlgorithmParameterException(
                        "RSA may only use RSAKeyGenParameterSpec");
                }
                if (publicExponent == null) {
                    publicExponent = RSAKeyGenParameterSpec.F4;
                }
                if (publicExponent.compareTo(BigInteger.ZERO) < 1) {
                    throw new InvalidAlgorithmParameterException(
                            "RSA public exponent must be positive: " + publicExponent);
                }
                if (publicExponent.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported RSA public exponent: " + publicExponent
                            + ". Only exponents <= " + Long.MAX_VALUE + " supported");
                }
                mRSAPublicExponent = publicExponent.longValue();
                break;
            }
            case KeymasterDefs.KM_ALGORITHM_EC:
                if (algSpecificSpec instanceof ECGenParameterSpec) {
                    ECGenParameterSpec ecSpec = (ECGenParameterSpec) algSpecificSpec;
                    String curveName = ecSpec.getName();
                    Integer ecSpecKeySizeBits = SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.get(
                            curveName.toLowerCase(Locale.US));
                    if (ecSpecKeySizeBits == null) {
                        throw new InvalidAlgorithmParameterException(
                                "Unsupported EC curve name: " + curveName
                                + ". Supported: " + SUPPORTED_EC_NIST_CURVE_NAMES);
                    }
                    if (mKeySizeBits == -1) {
                        mKeySizeBits = ecSpecKeySizeBits;
                    } else if (mKeySizeBits != ecSpecKeySizeBits) {
                        throw new InvalidAlgorithmParameterException("EC key size must match "
                                + " between " + mSpec + " and " + algSpecificSpec
                                + ": " + mKeySizeBits + " vs " + ecSpecKeySizeBits);
                    }
                } else if (algSpecificSpec != null) {
                    throw new InvalidAlgorithmParameterException(
                        "EC may only use ECGenParameterSpec");
                }
                break;
            default:
                throw new ProviderException("Unsupported algorithm: " + mKeymasterAlgorithm);
        }
    }

    @Override
    public KeyPair generateKeyPair() {
        if (mKeyStore == null || mSpec == null) {
            throw new IllegalStateException("Not initialized");
        }

        final int flags = (mEncryptionAtRestRequired) ? KeyStore.FLAG_ENCRYPTED : 0;
        if (((flags & KeyStore.FLAG_ENCRYPTED) != 0)
                && (mKeyStore.state() != KeyStore.State.UNLOCKED)) {
            throw new IllegalStateException(
                    "Encryption at rest using secure lock screen credential requested for key pair"
                    + ", but the user has not yet entered the credential");
        }

        KeymasterArguments args = new KeymasterArguments();
        args.addInt(KeymasterDefs.KM_TAG_KEY_SIZE, mKeySizeBits);
        args.addInt(KeymasterDefs.KM_TAG_ALGORITHM, mKeymasterAlgorithm);
        args.addInts(KeymasterDefs.KM_TAG_PURPOSE, mKeymasterPurposes);
        args.addInts(KeymasterDefs.KM_TAG_BLOCK_MODE, mKeymasterBlockModes);
        args.addInts(KeymasterDefs.KM_TAG_PADDING, mKeymasterEncryptionPaddings);
        args.addInts(KeymasterDefs.KM_TAG_PADDING, mKeymasterSignaturePaddings);
        args.addInts(KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigests);

        KeymasterUtils.addUserAuthArgs(args,
                mSpec.isUserAuthenticationRequired(),
                mSpec.getUserAuthenticationValidityDurationSeconds());
        args.addDate(KeymasterDefs.KM_TAG_ACTIVE_DATETIME,
                (mSpec.getKeyValidityStart() != null)
                ? mSpec.getKeyValidityStart() : new Date(0));
        args.addDate(KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                (mSpec.getKeyValidityForOriginationEnd() != null)
                ? mSpec.getKeyValidityForOriginationEnd() : new Date(Long.MAX_VALUE));
        args.addDate(KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                (mSpec.getKeyValidityForConsumptionEnd() != null)
                ? mSpec.getKeyValidityForConsumptionEnd() : new Date(Long.MAX_VALUE));
        addAlgorithmSpecificParameters(args);

        byte[] additionalEntropy =
                KeyStoreCryptoOperationUtils.getRandomBytesToMixIntoKeystoreRng(
                        mRng, (mKeySizeBits + 7) / 8);

        final String privateKeyAlias = Credentials.USER_PRIVATE_KEY + mEntryAlias;
        boolean success = false;
        try {
            Credentials.deleteAllTypesForAlias(mKeyStore, mEntryAlias);
            KeyCharacteristics resultingKeyCharacteristics = new KeyCharacteristics();
            int errorCode = mKeyStore.generateKey(
                    privateKeyAlias,
                    args,
                    additionalEntropy,
                    flags,
                    resultingKeyCharacteristics);
            if (errorCode != KeyStore.NO_ERROR) {
                throw new ProviderException(
                        "Failed to generate key pair", KeyStore.getKeyStoreException(errorCode));
            }

            KeyPair result;
            try {
                result = AndroidKeyStoreProvider.loadAndroidKeyStoreKeyPairFromKeystore(
                        mKeyStore, privateKeyAlias);
            } catch (UnrecoverableKeyException e) {
                throw new ProviderException("Failed to load generated key pair from keystore", e);
            }

            if (!mJcaKeyAlgorithm.equalsIgnoreCase(result.getPrivate().getAlgorithm())) {
                throw new ProviderException(
                        "Generated key pair algorithm does not match requested algorithm: "
                        + result.getPrivate().getAlgorithm() + " vs " + mJcaKeyAlgorithm);
            }

            final X509Certificate cert;
            try {
                cert = generateSelfSignedCertificate(result.getPrivate(), result.getPublic());
            } catch (Exception e) {
                throw new ProviderException("Failed to generate self-signed certificate", e);
            }

            byte[] certBytes;
            try {
                certBytes = cert.getEncoded();
            } catch (CertificateEncodingException e) {
                throw new ProviderException(
                        "Failed to obtain encoded form of self-signed certificate", e);
            }

            int insertErrorCode = mKeyStore.insert(
                    Credentials.USER_CERTIFICATE + mEntryAlias,
                    certBytes,
                    KeyStore.UID_SELF,
                    flags);
            if (insertErrorCode != KeyStore.NO_ERROR) {
                throw new ProviderException("Failed to store self-signed certificate",
                        KeyStore.getKeyStoreException(insertErrorCode));
            }

            success = true;
            return result;
        } finally {
            if (!success) {
                Credentials.deleteAllTypesForAlias(mKeyStore, mEntryAlias);
            }
        }
    }

    private void addAlgorithmSpecificParameters(KeymasterArguments keymasterArgs) {
        switch (mKeymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_RSA:
                keymasterArgs.addLong(KeymasterDefs.KM_TAG_RSA_PUBLIC_EXPONENT, mRSAPublicExponent);
                break;
            case KeymasterDefs.KM_ALGORITHM_EC:
                break;
            default:
                throw new ProviderException("Unsupported algorithm: " + mKeymasterAlgorithm);
        }
    }

    private X509Certificate generateSelfSignedCertificate(
            PrivateKey privateKey, PublicKey publicKey) throws Exception {
        String signatureAlgorithm =
                getCertificateSignatureAlgorithm(mKeymasterAlgorithm, mKeySizeBits, mSpec);
        if (signatureAlgorithm == null) {
            // Key cannot be used to sign a certificate
            return generateSelfSignedCertificateWithFakeSignature(publicKey);
        } else {
            // Key can be used to sign a certificate
            return generateSelfSignedCertificateWithValidSignature(
                    privateKey, publicKey, signatureAlgorithm);
        }
    }

    @SuppressWarnings("deprecation")
    private X509Certificate generateSelfSignedCertificateWithValidSignature(
            PrivateKey privateKey, PublicKey publicKey, String signatureAlgorithm)
                    throws Exception {
        final X509V3CertificateGenerator certGen = new X509V3CertificateGenerator();
        certGen.setPublicKey(publicKey);
        certGen.setSerialNumber(mSpec.getCertificateSerialNumber());
        certGen.setSubjectDN(mSpec.getCertificateSubject());
        certGen.setIssuerDN(mSpec.getCertificateSubject());
        certGen.setNotBefore(mSpec.getCertificateNotBefore());
        certGen.setNotAfter(mSpec.getCertificateNotAfter());
        certGen.setSignatureAlgorithm(signatureAlgorithm);
        return certGen.generate(privateKey);
    }

    @SuppressWarnings("deprecation")
    private X509Certificate generateSelfSignedCertificateWithFakeSignature(
            PublicKey publicKey) throws Exception {
        V3TBSCertificateGenerator tbsGenerator = new V3TBSCertificateGenerator();
        ASN1ObjectIdentifier sigAlgOid;
        AlgorithmIdentifier sigAlgId;
        byte[] signature;
        switch (mKeymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
                sigAlgOid = X9ObjectIdentifiers.ecdsa_with_SHA256;
                sigAlgId = new AlgorithmIdentifier(sigAlgOid);
                ASN1EncodableVector v = new ASN1EncodableVector();
                v.add(new DERInteger(0));
                v.add(new DERInteger(0));
                signature = new DERSequence().getEncoded();
                break;
            case KeymasterDefs.KM_ALGORITHM_RSA:
                sigAlgOid = PKCSObjectIdentifiers.sha256WithRSAEncryption;
                sigAlgId = new AlgorithmIdentifier(sigAlgOid, DERNull.INSTANCE);
                signature = new byte[1];
                break;
            default:
                throw new ProviderException("Unsupported key algorithm: " + mKeymasterAlgorithm);
        }

        try (ASN1InputStream publicKeyInfoIn = new ASN1InputStream(publicKey.getEncoded())) {
            tbsGenerator.setSubjectPublicKeyInfo(
                    SubjectPublicKeyInfo.getInstance(publicKeyInfoIn.readObject()));
        }
        tbsGenerator.setSerialNumber(new ASN1Integer(mSpec.getCertificateSerialNumber()));
        X509Principal subject =
                new X509Principal(mSpec.getCertificateSubject().getEncoded());
        tbsGenerator.setSubject(subject);
        tbsGenerator.setIssuer(subject);
        tbsGenerator.setStartDate(new Time(mSpec.getCertificateNotBefore()));
        tbsGenerator.setEndDate(new Time(mSpec.getCertificateNotAfter()));
        tbsGenerator.setSignature(sigAlgId);
        TBSCertificate tbsCertificate = tbsGenerator.generateTBSCertificate();

        ASN1EncodableVector result = new ASN1EncodableVector();
        result.add(tbsCertificate);
        result.add(sigAlgId);
        result.add(new DERBitString(signature));
        return new X509CertificateObject(Certificate.getInstance(new DERSequence(result)));
    }

    private static int getDefaultKeySize(int keymasterAlgorithm) {
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
                return EC_DEFAULT_KEY_SIZE;
            case KeymasterDefs.KM_ALGORITHM_RSA:
                return RSA_DEFAULT_KEY_SIZE;
            default:
                throw new ProviderException("Unsupported algorithm: " + keymasterAlgorithm);
        }
    }

    private static void checkValidKeySize(int keymasterAlgorithm, int keySize)
            throws InvalidAlgorithmParameterException {
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
                if (keySize < EC_MIN_KEY_SIZE || keySize > EC_MAX_KEY_SIZE) {
                    throw new InvalidAlgorithmParameterException("EC key size must be >= "
                            + EC_MIN_KEY_SIZE + " and <= " + EC_MAX_KEY_SIZE);
                }
                break;
            case KeymasterDefs.KM_ALGORITHM_RSA:
                if (keySize < RSA_MIN_KEY_SIZE || keySize > RSA_MAX_KEY_SIZE) {
                    throw new InvalidAlgorithmParameterException("RSA key size must be >= "
                            + RSA_MIN_KEY_SIZE + " and <= " + RSA_MAX_KEY_SIZE);
                }
                break;
            default:
                throw new ProviderException("Unsupported algorithm: " + keymasterAlgorithm);
        }
    }

    /**
     * Returns the {@code Signature} algorithm to be used for signing a certificate using the
     * specified key or {@code null} if the key cannot be used for signing a certificate.
     */
    @Nullable
    private static String getCertificateSignatureAlgorithm(
            int keymasterAlgorithm,
            int keySizeBits,
            KeyGenParameterSpec spec) {
        // Constraints:
        // 1. Key must be authorized for signing.
        // 2. Signature digest must be one of key's authorized digests.
        // 3. For RSA keys, the digest output size must not exceed modulus size minus space needed
        //    for RSA PKCS#1 signature padding (about 29 bytes: minimum 10 bytes of padding + 15--19
        //    bytes overhead for encoding digest OID and digest value in DER).
        // 4. For EC keys, the there is no point in using a digest whose output size is longer than
        //    key/field size because the digest will be truncated to that size.

        if ((spec.getPurposes() & KeyProperties.PURPOSE_SIGN) == 0) {
            // Key not authorized for signing
            return null;
        }
        if (!spec.isDigestsSpecified()) {
            // Key not authorized for any digests -- can't sign
            return null;
        }
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
            {
                Set<Integer> availableKeymasterDigests = getAvailableKeymasterSignatureDigests(
                        spec.getDigests(),
                        AndroidKeyStoreBCWorkaroundProvider.getSupportedEcdsaSignatureDigests());

                int bestKeymasterDigest = -1;
                int bestDigestOutputSizeBits = -1;
                for (int keymasterDigest : availableKeymasterDigests) {
                    int outputSizeBits = KeymasterUtils.getDigestOutputSizeBits(keymasterDigest);
                    if (outputSizeBits == keySizeBits) {
                        // Perfect match -- use this digest
                        bestKeymasterDigest = keymasterDigest;
                        bestDigestOutputSizeBits = outputSizeBits;
                        break;
                    }
                    // Not a perfect match -- check against the best digest so far
                    if (bestKeymasterDigest == -1) {
                        // First digest tested -- definitely the best so far
                        bestKeymasterDigest = keymasterDigest;
                        bestDigestOutputSizeBits = outputSizeBits;
                    } else {
                        // Prefer output size to be as close to key size as possible, with output
                        // sizes larger than key size preferred to those smaller than key size.
                        if (bestDigestOutputSizeBits < keySizeBits) {
                            // Output size of the best digest so far is smaller than key size.
                            // Anything larger is a win.
                            if (outputSizeBits > bestDigestOutputSizeBits) {
                                bestKeymasterDigest = keymasterDigest;
                                bestDigestOutputSizeBits = outputSizeBits;
                            }
                        } else {
                            // Output size of the best digest so far is larger than key size.
                            // Anything smaller is a win, as long as it's not smaller than key size.
                            if ((outputSizeBits < bestDigestOutputSizeBits)
                                    && (outputSizeBits >= keySizeBits)) {
                                bestKeymasterDigest = keymasterDigest;
                                bestDigestOutputSizeBits = outputSizeBits;
                            }
                        }
                    }
                }
                if (bestKeymasterDigest == -1) {
                    return null;
                }
                return KeyProperties.Digest.fromKeymasterToSignatureAlgorithmDigest(
                        bestKeymasterDigest) + "WithECDSA";
            }
            case KeymasterDefs.KM_ALGORITHM_RSA:
            {
                Set<Integer> availableKeymasterDigests = getAvailableKeymasterSignatureDigests(
                        spec.getDigests(),
                        AndroidKeyStoreBCWorkaroundProvider.getSupportedEcdsaSignatureDigests());

                // The amount of space available for the digest is less than modulus size because
                // padding must be at least 10 bytes long, and then there's also the 15--19
                // bytes overhead for encoding digest OID and digest value in DER.
                int maxDigestOutputSizeBits = keySizeBits - 29 * 8;
                int bestKeymasterDigest = -1;
                int bestDigestOutputSizeBits = -1;
                for (int keymasterDigest : availableKeymasterDigests) {
                    int outputSizeBits = KeymasterUtils.getDigestOutputSizeBits(keymasterDigest);
                    if (outputSizeBits > maxDigestOutputSizeBits) {
                        // Digest too long (signature generation will fail) -- skip
                        continue;
                    }
                    if (bestKeymasterDigest == -1) {
                        // First digest tested -- definitely the best so far
                        bestKeymasterDigest = keymasterDigest;
                        bestDigestOutputSizeBits = outputSizeBits;
                    } else {
                        // The longer the better
                        if (outputSizeBits > bestDigestOutputSizeBits) {
                            bestKeymasterDigest = keymasterDigest;
                            bestDigestOutputSizeBits = outputSizeBits;
                        }
                    }
                }
                if (bestKeymasterDigest == -1) {
                    return null;
                }
                return KeyProperties.Digest.fromKeymasterToSignatureAlgorithmDigest(
                        bestKeymasterDigest) + "WithRSA";
            }
            default:
                throw new ProviderException("Unsupported algorithm: " + keymasterAlgorithm);
        }
    }

    private static Set<Integer> getAvailableKeymasterSignatureDigests(
            @KeyProperties.DigestEnum String[] authorizedKeyDigests,
            @KeyProperties.DigestEnum String[] supportedSignatureDigests) {
        Set<Integer> authorizedKeymasterKeyDigests = new HashSet<Integer>();
        for (int keymasterDigest : KeyProperties.Digest.allToKeymaster(authorizedKeyDigests)) {
            authorizedKeymasterKeyDigests.add(keymasterDigest);
        }
        Set<Integer> supportedKeymasterSignatureDigests = new HashSet<Integer>();
        for (int keymasterDigest
                : KeyProperties.Digest.allToKeymaster(supportedSignatureDigests)) {
            supportedKeymasterSignatureDigests.add(keymasterDigest);
        }
        if (authorizedKeymasterKeyDigests.contains(KeymasterDefs.KM_DIGEST_NONE)) {
            // Key is authorized to be used with any digest
            return supportedKeymasterSignatureDigests;
        } else {
            // Key is authorized to be used only with specific digests.
            Set<Integer> result = new HashSet<Integer>(supportedKeymasterSignatureDigests);
            result.retainAll(authorizedKeymasterKeyDigests);
            return result;
        }
    }
}

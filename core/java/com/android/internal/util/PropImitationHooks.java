/*
 * Copyright (C) 2022 Paranoid Android
 *           (C) 2023 ArrowOS
 *           (C) 2023 The LibreMobileOS Foundation
 *           (C) 2024 The LeafOS Project
 *           (C) 2024 Kusuma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.app.ActivityTaskManager;
import android.app.Application;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Binder;
import android.os.Environment;
import android.os.SystemProperties;
import android.os.Process;
import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyEntryResponse;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.openssl.PEMKeyPair;
import com.android.internal.org.bouncycastle.openssl.PEMParser;
import com.android.internal.org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import com.android.internal.util.XMLParser;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public class PropImitationHooks {

    private static final String TAG = PropImitationHooks.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean USE_PROPS_SPOOF =
            SystemProperties.getBoolean("persist.sys.extra.use_props", true);
    private static final boolean USE_KEYS_SPOOF =
            SystemProperties.getBoolean("persist.sys.extra.use_keys", true);
    private static final String PROP_HOOKS_MAINLINE = "persist.sys.pihooks_mainline_";

    public static final String SPOOF_PIXEL_GPHOTOS = "persist.sys.pixelprops.gphotos";
    public static final String ENABLE_PIXEL_PROPS = "persist.sys.pixelprops.all";
    public static final String ENABLE_GAME_PROP_OPTIONS = "persist.sys.gameprops.enabled";
    public static final String SPOOF_PIXEL_GOOGLE_APPS = "persist.sys.pixelprops.google";

    private static final Map<String, Object> propsToChangeMainline;
    private static final Map<String, Object> propsToChangePixelXL;
    private static final Map<String, Object> propsToChangePixel5a;        

    private static final String sStockFp = SystemProperties.get("ro.build.fingerprint");

    private static final String PROPS_FILE = "props.json";
    private static final String KEYS_FILE = "keys.xml";

    private static final String PACKAGE_ARCORE = "com.google.ar.core";
    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";

    private static final ComponentName GMS_ADD_ACCOUNT_ACTIVITY = ComponentName.unflattenFromString(
            "com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity");

    private static volatile String sProcessName;
    private static volatile boolean sIsGms, sIsFinsky;

    private static final ASN1ObjectIdentifier OID = new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");
    private static final CertificateFactory certificateFactory;
    private static final Map<String, KeyBox> keyboxes = new HashMap<>();
    private static volatile String algo;

    static {
        try {
            certificateFactory = CertificateFactory.getInstance("X.509");
        } catch (Throwable t) {
            Log.e(TAG, t.toString());
            throw new RuntimeException(t);
        }
    }

    static {
        propsToChangeMainline = new HashMap<>();
        propsToChangeMainline.put("BRAND", "google");
        propsToChangeMainline.put("MANUFACTURER", "Google");
        propsToChangeMainline.put("DEVICE", "caiman");
        propsToChangeMainline.put("PRODUCT", "caiman");
        propsToChangeMainline.put("MODEL", "Pixel 9 Pro");
        propsToChangeMainline.put("FINGERPRINT", "google/caiman/caiman:14/AD1A.240530.047.U1/12150698:user/release-keys");
        propsToChangePixelXL = new HashMap<>();
        propsToChangePixelXL.put("BRAND", "google");
        propsToChangePixelXL.put("MANUFACTURER", "Google");
        propsToChangePixelXL.put("DEVICE", "marlin");
        propsToChangePixelXL.put("PRODUCT", "marlin");
        propsToChangePixelXL.put("MODEL", "Pixel XL");
        propsToChangePixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
        propsToChangePixel5a = new HashMap<>();
        propsToChangePixel5a.put("BRAND", "google");
        propsToChangePixel5a.put("MANUFACTURER", "Google");
        propsToChangePixel5a.put("DEVICE", "barbet");
        propsToChangePixel5a.put("PRODUCT", "barbet");
        propsToChangePixel5a.put("HARDWARE", "barbet");
        propsToChangePixel5a.put("MODEL", "Pixel 5a");
        propsToChangePixel5a.put("ID", "AP2A.240805.005");
        propsToChangePixel5a.put("FINGERPRINT", "google/barbet/barbet:14/AP2A.240805.005/12025142:user/release-keys");
    }

    public static void setProps(Context context) {
        final String packageName = context.getPackageName();
        final String processName = Application.getProcessName();

        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(processName)) {
            Log.e(TAG, "Null package or process name");
            return;
        }

        sProcessName = processName;
        sIsGms = packageName.equals(PACKAGE_GMS) && processName.equals(PROCESS_GMS_UNSTABLE);
        sIsFinsky = packageName.equals(PACKAGE_FINSKY);

        /* Set certified properties for GMSCore
         * Set stock fingerprint for ARCore
         */
        if (USE_PROPS_SPOOF) {
            if (packageName.equals(PACKAGE_GMS)) {
                dlog("Setting fresh build date for: " + packageName);
                setPropValue("TIME", String.valueOf(System.currentTimeMillis()));
                if (sIsGms) {
                    setCertifiedPropsForGms();
                }
            } else if (!sStockFp.isEmpty() && packageName.equals(PACKAGE_ARCORE)) {
                dlog("Setting stock fingerprint for: " + packageName);
                setPropValue("FINGERPRINT", sStockFp);
            }
        }

        setGameProps(packageName);

        if (!SystemProperties.getBoolean(ENABLE_PIXEL_PROPS, true)) {
            return;
        }

        boolean isPixelDevice = SystemProperties.get("ro.soc.manufacturer").equalsIgnoreCase("Google");
        String model = SystemProperties.get("ro.product.model");
        boolean isMainlineDevice = isPixelDevice && model.matches("Pixel [8-9][a-zA-Z ]*");
        boolean isTensorDevice = isPixelDevice && model.matches("Pixel [6-9][a-zA-Z ]*");

        Map<String, Object> propsToChange = new HashMap<>();

        boolean isExcludedProcess = processName != null && (processName.toLowerCase().contains("unstable"));

        String[] packagesToSpoofAsMainlineDevice = {
            "com.google.android.apps.aiwallpapers",
            "com.google.android.apps.bard",
            "com.google.android.apps.customization.pixel",
            "com.google.android.apps.emojiwallpaper",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.apps.privacy.wildlife",
            "com.google.android.apps.wallpaper",
            "com.google.android.apps.wallpaper.pixel",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "com.google.android.inputmethod.latin",
            "com.google.android.tts",
            "com.google.android.wallpaper.effects"
        };

        if (Arrays.asList(packagesToSpoofAsMainlineDevice).contains(packageName) && !isExcludedProcess) {
            if (SystemProperties.getBoolean(SPOOF_PIXEL_GOOGLE_APPS, true)) {
                if (!isMainlineDevice) {
                    propsToChange.putAll(propsToChangeMainline);
                }
            }
        }
        if (packageName.equals("com.google.android.apps.photos")) {
            if (SystemProperties.getBoolean(SPOOF_PIXEL_GPHOTOS, true)) {
                propsToChange.putAll(propsToChangePixelXL);
            } else {
                if (!isMainlineDevice) {
                    propsToChange.putAll(propsToChangePixel5a);
                }
            }
        }
        
        if (packageName.equals("com.snapchat.android")) {
            propsToChange.putAll(propsToChangePixelXL);
        }
        
        if (packageName.equals("com.google.android.settings.intelligence")) {
            setPixelPropValue("FINGERPRINT", "eng.nobody." + 
                new java.text.SimpleDateFormat("yyyyMMdd.HHmmss").format(new java.util.Date()));
        }

        if (!propsToChange.isEmpty()) {
            if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
            for (Map.Entry<String, Object> prop : propsToChange.entrySet()) {
                String key = prop.getKey();
                Object values = prop.getValue();
                if (DEBUG) Log.d(TAG, "Defining " + key + " prop for: " + packageName);
                setPixelPropValue(key, values);
            }
        }
    }

    private static void setPixelPropValue(String key, Object values) {
        try {
            Field field = getBuildClassField(key);
            if (field != null) {
                field.setAccessible(true);
                if (field.getType() == int.class) {
                    if (values instanceof String) {
                        field.set(null, Integer.parseInt((String) values));
                    } else if (values instanceof Integer) {
                        field.set(null, (Integer) values);
                    }
                } else if (field.getType() == long.class) {
                    if (values instanceof String) {
                        field.set(null, Long.parseLong((String) values));
                    } else if (values instanceof Long) {
                        field.set(null, (Long) values);
                    }
                } else {
                    field.set(null, values.toString());
                }
                field.setAccessible(false);
                dlog("Set prop " + key + " to " + values);
            } else {
                Log.e(TAG, "Field " + key + " not found in Build or Build.VERSION classes");
            }
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static Field getBuildClassField(String key) throws NoSuchFieldException {
        try {
            Field field = Build.class.getDeclaredField(key);
            dlog("Field " + key + " found in Build.class");
            return field;
        } catch (NoSuchFieldException e) {
            Field field = Build.VERSION.class.getDeclaredField(key);
            dlog("Field " + key + " found in Build.VERSION.class");
            return field;
        }
    }

    public static void setGameProps(String packageName) {
        if (!SystemProperties.getBoolean(ENABLE_GAME_PROP_OPTIONS, false)) {
            return;
        }
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        Map<String, String> gamePropsToChange = new HashMap<>();
        String[] keys = {"BRAND", "DEVICE", "MANUFACTURER", "MODEL", "FINGERPRINT", "PRODUCT"};
        for (String key : keys) {
            String systemPropertyKey = "persist.sys.gameprops." + packageName + "." + key;
            String values = SystemProperties.get(systemPropertyKey);
            if (values != null && !values.isEmpty()) {
                gamePropsToChange.put(key, values);
                if (DEBUG) Log.d(TAG, "Got system property: " + systemPropertyKey + " = " + values);
            }
        }
        if (!gamePropsToChange.isEmpty()) {
            if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
            for (Map.Entry<String, String> prop : gamePropsToChange.entrySet()) {
                String key = prop.getKey();
                String values = prop.getValue();
                if (DEBUG) Log.d(TAG, "Defining " + key + " prop for: " + packageName);
                setPixelPropValue(key, values);
            }
        }
    }

    private static byte[] getBootHashFromProp() {
        String bh = SystemProperties.get("ro.boot.vbmeta.digest", null);
        if (bh == null || bh.length() != 64) {
            return null;
        }
        return hexToByteArray(bh);
    }

    private static byte[] hexToByteArray(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                  + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static void setPropValue(String key, String value) {
        try {
            // Unlock
            Class clazz = Build.class;
            if (key.startsWith("VERSION:")) {
                clazz = Build.VERSION.class;
                key = key.substring(8);
            }
            Field field = clazz.getDeclaredField(key);
            field.setAccessible(true);

            // Edit
            if (field.getType().equals(Long.TYPE)) {
                field.set(null, Long.parseLong(value));
            } else if (field.getType().equals(Integer.TYPE)) {
                field.set(null, Integer.parseInt(value));
            } else {
                field.set(null, value);
            }

            // Lock
            field.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to spoof Build." + key, e);
        }
    }

    private static PrivateKey parsePrivateKey(String str, String algo) throws Exception {
        PEMParser pemParser = new PEMParser(new StringReader(str));
        Object object = pemParser.readObject();
        pemParser.close();
        PrivateKey privateKey;
        if (object instanceof PEMKeyPair) {
            // Handle PEMKeyPair (for ECDSA or RSA)
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            privateKey = converter.getPrivateKey(((PEMKeyPair) object).getPrivateKeyInfo());
        } else if (object instanceof PrivateKeyInfo) {
            // Handle PrivateKeyInfo directly (in case the key is already in PKCS#8 format)
            privateKey = new JcaPEMKeyConverter().getPrivateKey((PrivateKeyInfo) object);
        } else {
            throw new IllegalArgumentException("Unsupported key format.");
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
        return KeyFactory.getInstance(algo).generatePrivate(spec);
    }

    private static byte[] parseCert(String str) {
        String cleanPem = str.replaceAll("-----BEGIN [A-Z ]+-----", "")
                             .replaceAll("-----END [A-Z ]+-----", "")
                             .replaceAll("\\s", ""); // Remove all whitespace
        return Base64.getDecoder().decode(cleanPem);
    }

    private static void setCertifiedPropsForGms() {
        final boolean was = isGmsAddAccountActivityOnTop();
        final TaskStackListener taskStackListener = new TaskStackListener() {
            @Override
            public void onTaskStackChanged() {
                final boolean is = isGmsAddAccountActivityOnTop();
                if (is ^ was) {
                    dlog("GmsAddAccountActivityOnTop is:" + is + " was:" + was +
                            ", killing myself!"); // process will restart automatically later
                    Process.killProcess(Process.myPid());
                }
            }
        };
        if (!was) {
            File propsFile = new File(Environment.getDataSystemDirectory(), PROPS_FILE);
            String savedProps = readFromFile(propsFile);
            if (TextUtils.isEmpty(savedProps)) {
                Log.e(TAG, "No props found to spoof");
                return;
            }
            dlog("Found props");
            try {
                JSONObject parsedProps = new JSONObject(savedProps);
                Iterator<String> keys = parsedProps.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = parsedProps.getString(key);
                    dlog(key + ": " + value);
                    setPropValue(key, value);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing JSON data", e);
            }
        } else {
            dlog("Skip spoofing build for GMS, because GmsAddAccountActivityOnTop");
        }
        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskStackListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register task stack listener!", e);
        }
    }

    private static boolean isCallerSafetyNet() {
        return sIsGms && Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(elem -> elem.getClassName().contains("DroidGuard"));
    }

    private static boolean isGmsAddAccountActivityOnTop() {
        try {
            final ActivityTaskManager.RootTaskInfo focusedTask =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return focusedTask != null && focusedTask.topActivity != null
                    && focusedTask.topActivity.equals(GMS_ADD_ACCOUNT_ACTIVITY);
        } catch (Exception e) {
            Log.e(TAG, "Unable to get top activity!", e);
        }
        return false;
    }

    private static byte[] getCertificateChain(String algo) throws Exception {                       
        var keyBox = keyboxes.get(algo);
        if (keyBox != null) {
            return keyBox.certificates;
        }
        throw new Exception("Unsupported algorithm: " + algo);
    }

    private static byte[] modifyLeaf(byte[] bytes) throws Throwable {
        X509Certificate leaf = (X509Certificate) certificateFactory.generateCertificate(
                new ByteArrayInputStream(bytes));
        if (leaf.getExtensionValue(OID.getId()) == null) throw new Exception(
                "Could not obtain the expected value.");

        X509CertificateHolder leafHolder = new X509CertificateHolder(leaf.getEncoded());
        Extension ext = leafHolder.getExtension(OID);
        ASN1Sequence sequence = ASN1Sequence.getInstance(ext.getExtnValue().getOctets());
        ASN1Encodable[] encodables = sequence.toArray();
        ASN1Sequence teeEnforced = (ASN1Sequence) encodables[7];
        ASN1EncodableVector vector = new ASN1EncodableVector();
        ASN1Encodable rootOfTrust = null;
	    
        for (ASN1Encodable asn1Encodable : teeEnforced) {
            ASN1TaggedObject taggedObject = (ASN1TaggedObject) asn1Encodable;
            if (taggedObject.getTagNo() == 704) {
                rootOfTrust = (ASN1Sequence) taggedObject.getObject();
                continue;
            }	
            vector.add(taggedObject);
        }
        if (rootOfTrust == null) throw new Exception("Failed to retrieve root of trust");

        algo = leaf.getPublicKey().getAlgorithm();

        PrivateKey privateKey;
        byte[] firstCertificates;
        X509v3CertificateBuilder builder;
        X509CertificateHolder certHolder;
        ContentSigner signer;

        var keyBox = keyboxes.get(algo);
        if (keyBox == null) throw new Exception("Unsupported algorithm: " + algo);
        firstCertificates = keyBox.firstCertificates;
        certHolder = new X509CertificateHolder(firstCertificates);

        builder = new X509v3CertificateBuilder(
                certHolder.getSubject(),
                leafHolder.getSerialNumber(),
                leafHolder.getNotBefore(),
                leafHolder.getNotAfter(),
                leafHolder.getSubject(),
                leafHolder.getSubjectPublicKeyInfo()
        );
        privateKey = keyBox.privateKey;
        signer = new JcaContentSignerBuilder(leaf.getSigAlgName()).build(privateKey);

        byte[] verifiedBootKey = new byte[32];
        ThreadLocalRandom.current().nextBytes(verifiedBootKey);

        byte[] verifiedBootHash = null; // Initialize with a default value or null
        try {
            ASN1Sequence r = (ASN1Sequence) rootOfTrust;
            DEROctetString derOctetString = (DEROctetString) r.getObjectAt(3);
            verifiedBootHash = derOctetString.getOctets();
        } catch (ArrayIndexOutOfBoundsException | ClassCastException e) {
            verifiedBootHash = getBootHashFromProp();
        }
        if (verifiedBootHash == null) {
            verifiedBootHash = new byte[32];  
            ThreadLocalRandom.current().nextBytes(verifiedBootHash);
        }

        ASN1Encodable[] rootOfTrustEnc = {
                new DEROctetString(verifiedBootKey), 
                ASN1Boolean.TRUE, 
                new ASN1Enumerated(0), 
                new DEROctetString(verifiedBootHash)
        };

        ASN1Sequence rootOfTrustSeq = new DERSequence(rootOfTrustEnc);
        ASN1TaggedObject rootOfTrustTagObj = new DERTaggedObject(704, rootOfTrustSeq);
        vector.add(rootOfTrustTagObj);
	    
        ASN1Sequence hackEnforced = new DERSequence(vector);
        encodables[7] = hackEnforced;
        ASN1Sequence hackedSeq = new DERSequence(encodables);
	    
        ASN1OctetString hackedSeqOctets = new DEROctetString(hackedSeq);
        Extension hackedExt = new Extension(OID, false, hackedSeqOctets);
        builder.addExtension(hackedExt);
	    
        for (ASN1ObjectIdentifier extensionOID : leafHolder.getExtensions().getExtensionOIDs()) {
            if (OID.getId().equals(extensionOID.getId())) continue;
            builder.addExtension(leafHolder.getExtension(extensionOID));
        }
	    
        return builder.build(signer).getEncoded();
    }

    private static String readFromFile(File file) {
        StringBuilder content = new StringBuilder();
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append(System.lineSeparator());
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading from file", e);
            }
        }
        return content.toString();
    }

    private static void dlog(String message) {
        if (DEBUG) Log.d(TAG, message);
    }

    private static void readAndParseFromXml(String data) {
        keyboxes.clear();
        if (data == null) {
            dlog("Clear all keyboxes");
            return;
        }
        XMLParser xmlParser = new XMLParser(data);
        try {
            int numberOfKeyboxes = Integer.parseInt(Objects.requireNonNull(xmlParser.obtainPath(
                    "AndroidAttestation.NumberOfKeyboxes").get("text")));
            for (int i = 0; i < numberOfKeyboxes; i++) {
                String keyboxAlgorithm = xmlParser.obtainPath(
                        "AndroidAttestation.Keybox.Key[" + i + "]").get("algorithm");
                String privateKey = xmlParser.obtainPath(
                        "AndroidAttestation.Keybox.Key[" + i + "].PrivateKey").get("text");
                int numberOfCertificates = Integer.parseInt(Objects.requireNonNull(xmlParser.obtainPath(
                        "AndroidAttestation.Keybox.Key[" + i + "].CertificateChain.NumberOfCertificates").get("text")));
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                byte[] firstCertBytes = null;
                for (int j = 0; j < numberOfCertificates; j++) {
                    Map<String, String> certData = xmlParser.obtainPath(
                            "AndroidAttestation.Keybox.Key[" + i + "].CertificateChain.Certificate[" + j + "]");
                    byte[] certBytes = parseCert(certData.get("text"));
                    if (j == 0) {
                        firstCertBytes = certBytes;
                    }
                    stream.write(certBytes);
                }
                byte[] certificateChain = stream.toByteArray();
                String algo;
                if (keyboxAlgorithm.toLowerCase().equals("ecdsa")) {
                    algo = KeyProperties.KEY_ALGORITHM_EC;
                } else {
                    algo = KeyProperties.KEY_ALGORITHM_RSA;
                }
                PrivateKey privateKeyObj = parsePrivateKey(privateKey, algo);
                keyboxes.put(algo, new KeyBox(privateKeyObj, firstCertBytes, certificateChain));
            }
            dlog("Update " + numberOfKeyboxes + " keyboxes");
        } catch (Throwable t) {
            Log.e("Error loading xml file (keyboxes cleared): ", t.toString());
        }
    }


    public static boolean shouldBypassTaskPermission(Context context) {
        // GMS doesn't have MANAGE_ACTIVITY_TASKS permission
        final int callingUid = Binder.getCallingUid();
        final int gmsUid;
        try {
            gmsUid = context.getPackageManager().getApplicationInfo(PACKAGE_GMS, 0).uid;
            dlog("shouldBypassTaskPermission: gmsUid:" + gmsUid + " callingUid:" + callingUid);
        } catch (Exception e) {
            return false;
        }
        return gmsUid == callingUid;
    }

    public static void onEngineGetCertificateChain() {
        File keysFile = new File(Environment.getDataSystemDirectory(), KEYS_FILE);
        if (!USE_KEYS_SPOOF || !keysFile.exists() || !readFromFile(keysFile).contains("Keybox")) {
            if (USE_PROPS_SPOOF && (isCallerSafetyNet() || sIsFinsky)) {
                dlog("Blocked key attestation sIsGms=" + sIsGms + " sIsFinsky=" + sIsFinsky);
                throw new UnsupportedOperationException();
            }
        }
    }

    public static KeyEntryResponse onGetKeyEntry(KeyEntryResponse response) {
        if (response == null) return null;
        if (response.metadata == null) return response;
        File keysFile = new File(Environment.getDataSystemDirectory(), KEYS_FILE);
        if (USE_PROPS_SPOOF && USE_KEYS_SPOOF && keysFile.exists()) {
            String savedKeys = readFromFile(keysFile);
            if (savedKeys.contains("Keybox")) {
                algo = null;
                try {
                    readAndParseFromXml(savedKeys);
                    byte[] newLeaf = modifyLeaf(response.metadata.certificate);
                    response.metadata.certificateChain = getCertificateChain(algo);
                    response.metadata.certificate = newLeaf;
                } catch (Throwable t) {
                    Log.e(TAG, "onGetKeyEntry: ", t);
                }
            }
        }
        return response;
    }

    public static class KeyBox {
        private final PrivateKey privateKey;
        private final byte[] firstCertificates;
        private final byte[] certificates;

        public KeyBox(PrivateKey privateKey, byte[] firstCertificates, byte[] certificates) {
            this.privateKey = privateKey;
            this.firstCertificates = firstCertificates;
            this.certificates = certificates;
        }
    }
}

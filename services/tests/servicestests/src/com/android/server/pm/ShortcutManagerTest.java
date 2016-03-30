/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.pm;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ILauncherApps;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.InstrumentationTestCase;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.frameworks.servicestests.R;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.LauncherAppsService.LauncherAppsImpl;
import com.android.server.pm.ShortcutService.ConfigConstants;
import com.android.server.pm.ShortcutService.FileOutputStreamWithPath;
import com.android.server.pm.ShortcutUser.PackageWithUser;

import libcore.io.IoUtils;

import org.junit.Assert;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests for ShortcutService and ShortcutManager.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest \
 -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner

 * TODO: Add checks with assertAllNotHaveIcon()
 *
 * TODO: separate, detailed tests for ShortcutInfo (CTS?) *
 *
 * TODO: Cross-user test (do in CTS?)
 */
@SmallTest
public class ShortcutManagerTest extends InstrumentationTestCase {
    private static final String TAG = "ShortcutManagerTest";

    /**
     * Whether to enable dump or not.  Should be only true when debugging to avoid bugs where
     * dump affecting the behavior.
     */
    private static final boolean ENABLE_DUMP = false; // DO NOT SUBMIT WITH true

    // public for mockito
    public class BaseContext extends MockContext {
        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.USER_SERVICE:
                    return mMockUserManager;
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public String getSystemServiceName(Class<?> serviceClass) {
            return getTestContext().getSystemServiceName(serviceClass);
        }

        @Override
        public PackageManager getPackageManager() {
            return mMockPackageManager;
        }

        @Override
        public Resources getResources() {
            return getTestContext().getResources();
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            // ignore.
            return null;
        }
    }

    /** Context used in the client side */
    public class ClientContext extends BaseContext {
        @Override
        public String getPackageName() {
            return mInjectedClientPackage;
        }
    }

    /** Context used in the service side */
    public class ServiceContext extends BaseContext {
        long injectClearCallingIdentity() {
            final int prevCallingUid = mInjectedCallingUid;
            mInjectedCallingUid = Process.SYSTEM_UID;
            return prevCallingUid;
        }

        void injectRestoreCallingIdentity(long token) {
            mInjectedCallingUid = (int) token;
        }

        @Override
        public void startActivityAsUser(@RequiresPermission Intent intent, @Nullable Bundle options,
                UserHandle userId) {
        }
    }

    /** ShortcutService with injection override methods. */
    private final class ShortcutServiceTestable extends ShortcutService {
        final ServiceContext mContext;

        public ShortcutServiceTestable(ServiceContext context, Looper looper) {
            super(context, looper);
            mContext = context;
        }

        @Override
        String injectShortcutManagerConstants() {
            return ConfigConstants.KEY_RESET_INTERVAL_SEC + "=" + (INTERVAL / 1000) + ","
                    + ConfigConstants.KEY_MAX_SHORTCUTS + "=" + MAX_SHORTCUTS + ","
                    + ConfigConstants.KEY_MAX_DAILY_UPDATES + "=" + MAX_DAILY_UPDATES + ","
                    + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP + "=" + MAX_ICON_DIMENSION + ","
                    + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM + "="
                    + MAX_ICON_DIMENSION_LOWRAM + ","
                    + ConfigConstants.KEY_ICON_FORMAT + "=PNG,"
                    + ConfigConstants.KEY_ICON_QUALITY + "=100";
        }

        @Override
        long injectClearCallingIdentity() {
            return mContext.injectClearCallingIdentity();
        }

        @Override
        void injectRestoreCallingIdentity(long token) {
            mContext.injectRestoreCallingIdentity(token);
        }

        @Override
        int injectDipToPixel(int dip) {
            return dip;
        }

        @Override
        long injectCurrentTimeMillis() {
            return mInjectedCurrentTimeLillis;
        }

        @Override
        int injectBinderCallingUid() {
            return mInjectedCallingUid;
        }

        @Override
        int injectGetPackageUid(String packageName, int userId) {
            return getInjectedPackageInfo(packageName, userId, false).applicationInfo.uid;
        }

        @Override
        File injectSystemDataPath() {
            return new File(mInjectedFilePathRoot, "system");
        }

        @Override
        File injectUserDataPath(@UserIdInt int userId) {
            return new File(mInjectedFilePathRoot, "user-" + userId);
        }

        @Override
        void injectValidateIconResPackage(ShortcutInfo shortcut, Icon icon) {
            // Can't check
        }

        @Override
        boolean injectIsLowRamDevice() {
            return mInjectedIsLowRamDevice;
        }

        @Override
        PackageManagerInternal injectPackageManagerInternal() {
            return mMockPackageManagerInternal;
        }

        @Override
        boolean hasShortcutHostPermission(@NonNull String callingPackage, int userId) {
            // Sort of hack; do a simpler check.
            return LAUNCHER_1.equals(callingPackage) || LAUNCHER_2.equals(callingPackage);
        }

        @Override
        PackageInfo injectPackageInfo(String packageName, @UserIdInt int userId,
                boolean getSignatures) {
            return getInjectedPackageInfo(packageName, userId, getSignatures);
        }

        @Override
        ApplicationInfo injectApplicationInfo(String packageName, @UserIdInt int userId) {
            PackageInfo pi = injectPackageInfo(packageName, userId, /* getSignatures= */ false);
            return pi != null ? pi.applicationInfo : null;
        }

        @Override
        void postToHandler(Runnable r) {
            final long token = mContext.injectClearCallingIdentity();
            super.postToHandler(r);
            try {
                runTestOnUiThread(() -> {});
            } catch (Throwable e) {
                fail("runTestOnUiThread failed: " + e);
            }
            mContext.injectRestoreCallingIdentity(token);
        }

        @Override
        void wtf(String message, Exception e) {
            // During tests, WTF is fatal.
            fail(message + "  exception: " + e);
        }
    }

    /** ShortcutManager with injection override methods. */
    private class ShortcutManagerTestable extends ShortcutManager {
        public ShortcutManagerTestable(Context context, ShortcutServiceTestable service) {
            super(context, service);
        }

        @Override
        protected int injectMyUserId() {
            return UserHandle.getUserId(mInjectedCallingUid);
        }
    }

    private class LauncherAppImplTestable extends LauncherAppsImpl {
        final ServiceContext mContext;

        public LauncherAppImplTestable(ServiceContext context) {
            super(context);
            mContext = context;
        }

        @Override
        public void ensureInUserProfiles(UserHandle userToCheck, String message) {
            if (getCallingUserId() == userToCheck.getIdentifier()) {
                return; // okay
            }
            if (getCallingUserId() == USER_0 && userToCheck.getIdentifier() == USER_P0) {
                return; // profile, okay.
            }
            if (getCallingUserId() == USER_P0 && userToCheck.getIdentifier() == USER_0) {
                return; // profile, okay.
            }

            if (mInjectedCallingUid != Process.SYSTEM_UID) {
                throw new SecurityException("To access other users, you need to be SYSTEM" +
                        ", but current UID=" + mInjectedCallingUid);
            }
        }

        @Override
        public void verifyCallingPackage(String callingPackage) {
            // SKIP
        }

        @Override
        boolean isEnabledProfileOf(UserHandle user, UserHandle listeningUser, String debugMsg) {
            // This requires CROSS_USER
            assertEquals(Process.SYSTEM_UID, mInjectedCallingUid);
            return user.getIdentifier() == listeningUser.getIdentifier();
        }

        @Override
        void postToPackageMonitorHandler(Runnable r) {
            final long token = mContext.injectClearCallingIdentity();
            r.run();
            mContext.injectRestoreCallingIdentity(token);
        }

        @Override
        int injectBinderCallingUid() {
            return mInjectedCallingUid;
        }
    }

    private class LauncherAppsTestable extends LauncherApps {
        public LauncherAppsTestable(Context context, ILauncherApps service) {
            super(context, service);
        }
    }

    public static class ShortcutActivity extends Activity {
    }

    public static class ShortcutActivity2 extends Activity {
    }

    public static class ShortcutActivity3 extends Activity {
    }

    private ServiceContext mServiceContext;
    private ClientContext mClientContext;

    private ShortcutServiceTestable mService;
    private ShortcutManagerTestable mManager;
    private ShortcutServiceInternal mInternal;

    private LauncherAppImplTestable mLauncherAppImpl;
    private LauncherAppsTestable mLauncherApps;

    private File mInjectedFilePathRoot;

    private long mInjectedCurrentTimeLillis;

    private boolean mInjectedIsLowRamDevice;

    private int mInjectedCallingUid;
    private String mInjectedClientPackage;

    private Map<String, PackageInfo> mInjectedPackages;

    private ArrayList<PackageWithUser> mUninstalledPackages;

    private PackageManager mMockPackageManager;
    private PackageManagerInternal mMockPackageManagerInternal;
    private UserManager mMockUserManager;

    private static final String CALLING_PACKAGE_1 = "com.android.test.1";
    private static final int CALLING_UID_1 = 10001;

    private static final String CALLING_PACKAGE_2 = "com.android.test.2";
    private static final int CALLING_UID_2 = 10002;

    private static final String CALLING_PACKAGE_3 = "com.android.test.3";
    private static final int CALLING_UID_3 = 10003;

    private static final String LAUNCHER_1 = "com.android.launcher.1";
    private static final int LAUNCHER_UID_1 = 10011;

    private static final String LAUNCHER_2 = "com.android.launcher.2";
    private static final int LAUNCHER_UID_2 = 10012;

    private static final int USER_0 = UserHandle.USER_SYSTEM;
    private static final int USER_10 = 10;
    private static final int USER_11 = 11;
    private static final int USER_P0 = 20; // profile of user 0

    private static final UserHandle HANDLE_USER_0 = UserHandle.of(USER_0);
    private static final UserHandle HANDLE_USER_10 = UserHandle.of(USER_10);
    private static final UserHandle HANDLE_USER_11 = UserHandle.of(USER_11);
    private static final UserHandle HANDLE_USER_P0 = UserHandle.of(USER_P0);


    private static final long START_TIME = 1440000000101L;

    private static final long INTERVAL = 10000;

    private static final int MAX_SHORTCUTS = 10;

    private static final int MAX_DAILY_UPDATES = 3;

    private static final int MAX_ICON_DIMENSION = 128;

    private static final int MAX_ICON_DIMENSION_LOWRAM = 32;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mServiceContext = spy(new ServiceContext());
        mClientContext = new ClientContext();

        mMockPackageManager = mock(PackageManager.class);
        mMockPackageManagerInternal = mock(PackageManagerInternal.class);
        mMockUserManager = mock(UserManager.class);

        // Prepare injection values.

        mInjectedCurrentTimeLillis = START_TIME;

        mInjectedPackages = new HashMap<>();;
        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 1);
        addPackage(CALLING_PACKAGE_2, CALLING_UID_2, 2);
        addPackage(CALLING_PACKAGE_3, CALLING_UID_3, 3);
        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 4);
        addPackage(LAUNCHER_2, LAUNCHER_UID_2, 5);

        mUninstalledPackages = new ArrayList<>();

        mInjectedFilePathRoot = new File(getTestContext().getCacheDir(), "test-files");

        // Empty the data directory.
        if (mInjectedFilePathRoot.exists()) {
            Assert.assertTrue("failed to delete dir",
                    FileUtils.deleteContents(mInjectedFilePathRoot));
        }
        mInjectedFilePathRoot.mkdirs();

        initService();
        setCaller(CALLING_PACKAGE_1);
    }

    private Context getTestContext() {
        return getInstrumentation().getContext();
    }

    /** (Re-) init the manager and the service. */
    private void initService() {
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);

        // Instantiate targets.
        mService = new ShortcutServiceTestable(mServiceContext, Looper.getMainLooper());
        mManager = new ShortcutManagerTestable(mClientContext, mService);

        mInternal = LocalServices.getService(ShortcutServiceInternal.class);

        mLauncherAppImpl = new LauncherAppImplTestable(mServiceContext);
        mLauncherApps = new LauncherAppsTestable(mClientContext, mLauncherAppImpl);

        // Load the setting file.
        mService.onBootPhase(SystemService.PHASE_LOCK_SETTINGS_READY);
    }

    private void addPackage(String packageName, int uid, int version) {
        addPackage(packageName, uid, version, packageName);
    }

    private <T> List<T> list(T... array) {
        return Arrays.asList(array);
    }

    private Signature[] genSignatures(String... signatures) {
        final Signature[] sigs = new Signature[signatures.length];
        for (int i = 0; i < signatures.length; i++){
            sigs[i] = new Signature(signatures[i].getBytes());
        }
        return sigs;
    }

    private PackageInfo genPackage(String packageName, int uid, int version, String... signatures) {
        final PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = uid;
        pi.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED
                | ApplicationInfo.FLAG_ALLOW_BACKUP;
        pi.versionCode = version;
        pi.signatures = genSignatures(signatures);

        return pi;
    }

    private void addPackage(String packageName, int uid, int version, String... signatures) {
        mInjectedPackages.put(packageName, genPackage(packageName, uid, version, signatures));
    }

    private void uninstallPackage(int userId, String packageName) {
        mUninstalledPackages.add(PackageWithUser.of(userId, packageName));
    }

    PackageInfo getInjectedPackageInfo(String packageName, @UserIdInt int userId,
            boolean getSignatures) {
        final PackageInfo pi = mInjectedPackages.get(packageName);
        if (pi == null) return null;

        final PackageInfo ret = new PackageInfo();
        ret.packageName = pi.packageName;
        ret.versionCode = pi.versionCode;
        ret.applicationInfo = new ApplicationInfo(pi.applicationInfo);
        ret.applicationInfo.uid = UserHandle.getUid(userId, pi.applicationInfo.uid);
        if (mUninstalledPackages.contains(PackageWithUser.of(userId, packageName))) {
            ret.applicationInfo.flags &= ~ApplicationInfo.FLAG_INSTALLED;
        }

        if (getSignatures) {
            ret.signatures = pi.signatures;
        }

        return ret;
    }

    /** Replace the current calling package */
    private void setCaller(String packageName, int userId) {
        mInjectedClientPackage = packageName;
        mInjectedCallingUid =
                Preconditions.checkNotNull(getInjectedPackageInfo(packageName, userId, false),
                        "Unknown package").applicationInfo.uid;
    }

    private void setCaller(String packageName) {
        setCaller(packageName, UserHandle.USER_SYSTEM);
    }

    private String getCallingPackage() {
        return mInjectedClientPackage;
    }

    private void runWithCaller(String packageName, int userId, Runnable r) {
        final String previousPackage = mInjectedClientPackage;
        final int previousUserId = UserHandle.getUserId(mInjectedCallingUid);

        setCaller(packageName, userId);

        r.run();

        setCaller(previousPackage, previousUserId);
    }

    private int getCallingUserId() {
        return UserHandle.getUserId(mInjectedCallingUid);
    }

    private UserHandle getCallingUser() {
        return UserHandle.of(getCallingUserId());
    }

    /** For debugging */
    private void dumpsysOnLogcat() {
        if (!ENABLE_DUMP) return;

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintWriter pw = new PrintWriter(out);
        mService.dumpInner(pw);
        pw.close();

        Log.e(TAG, "Dumping ShortcutService:");
        for (String line : out.toString().split("\n")) {
            Log.e(TAG, line);
        }
    }

    /**
     * For debugging, dump arbitrary file on logcat.
     */
    private void dumpFileOnLogcat(String path) {
        if (!ENABLE_DUMP) return;

        Log.i(TAG, "Dumping file: " + path);
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                Log.i(TAG, line);
            }
        } catch (Exception e) {
            Log.e(TAG, "Couldn't read file", e);
            fail("Exception " + e);
        }
    }

    /**
     * For debugging, dump the main state file on logcat.
     */
    private void dumpBaseStateFile() {
        mService.saveDirtyInfo();
        dumpFileOnLogcat(mInjectedFilePathRoot.getAbsolutePath()
                + "/system/" + ShortcutService.FILENAME_BASE_STATE);
    }

    /**
     * For debugging, dump per-user state file on logcat.
     */
    private void dumpUserFile(int userId) {
        mService.saveDirtyInfo();
        dumpFileOnLogcat(mInjectedFilePathRoot.getAbsolutePath()
                + "/user-" + userId
                + "/" + ShortcutService.FILENAME_USER_PACKAGES);
    }

    private void waitOnMainThread() throws Throwable {
        runTestOnUiThread(() -> {});
    }

    public static Bundle makeBundle(Object... keysAndValues) {
        Preconditions.checkState((keysAndValues.length % 2) == 0);

        if (keysAndValues.length == 0) {
            return null;
        }
        final Bundle ret = new Bundle();

        for (int i = keysAndValues.length - 2; i >= 0; i -= 2) {
            final String key = keysAndValues[i].toString();
            final Object value = keysAndValues[i + 1];

            if (value == null) {
                ret.putString(key, null);
            } else if (value instanceof Integer) {
                ret.putInt(key, (Integer) value);
            } else if (value instanceof String) {
                ret.putString(key, (String) value);
            } else if (value instanceof Bundle) {
                ret.putBundle(key, (Bundle) value);
            } else {
                fail("Type not supported yet: " + value.getClass().getName());
            }
        }
        return ret;
    }

    /**
     * Make a shortcut with an ID.
     */
    private ShortcutInfo makeShortcut(String id) {
        return makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* weight =*/ 0);
    }

    /**
     * Make a shortcut with an ID and timestamp.
     */
    private ShortcutInfo makeShortcutWithTimestamp(String id, long timestamp) {
        final ShortcutInfo s = makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* weight =*/ 0);
        s.setTimestamp(timestamp);
        return s;
    }

    /**
     * Make a shortcut with an ID and icon.
     */
    private ShortcutInfo makeShortcutWithIcon(String id, Icon icon) {
        return makeShortcut(
                id, "Title-" + id, /* activity =*/ null, icon,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* weight =*/ 0);
    }

    private ShortcutInfo makePackageShortcut(String packageName, String id) {
        String origCaller = getCallingPackage();

        setCaller(packageName);
        ShortcutInfo s = makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* weight =*/ 0);
        setCaller(origCaller); // restore the caller

        return s;
    }

    /**
     * Make multiple shortcuts with IDs.
     */
    private List<ShortcutInfo> makeShortcuts(String... ids) {
        final ArrayList<ShortcutInfo> ret = new ArrayList();
        for (String id : ids) {
            ret.add(makeShortcut(id));
        }
        return ret;
    }

    private ShortcutInfo.Builder makeShortcutBuilder() {
        return new ShortcutInfo.Builder(mClientContext);
    }

    /**
     * Make a shortcut with details.
     */
    private ShortcutInfo makeShortcut(String id, String title, ComponentName activity,
            Icon icon, Intent intent, int weight) {
        final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext)
                .setId(id)
                .setTitle(title)
                .setWeight(weight)
                .setIntent(intent);
        if (icon != null) {
            b.setIcon(icon);
        }
        if (activity != null) {
            b.setActivityComponent(activity);
        }
        final ShortcutInfo s = b.build();

        s.setTimestamp(mInjectedCurrentTimeLillis); // HACK

        return s;
    }

    /**
     * Make an intent.
     */
    private Intent makeIntent(String action, Class<?> clazz, Object... bundleKeysAndValues) {
        final Intent intent = new Intent(action);
        intent.setComponent(makeComponent(clazz));
        intent.replaceExtras(makeBundle(bundleKeysAndValues));
        return intent;
    }

    /**
     * Make an component name, with the client context.
     */
    @NonNull
    private ComponentName makeComponent(Class<?> clazz) {
        return new ComponentName(mClientContext, clazz);
    }

    private <T> Set<T> makeSet(T... values) {
        final HashSet<T> ret = new HashSet<>();
        for (T s : values) {
            ret.add(s);
        }
        return ret;
    }

    @NonNull
    private ShortcutInfo findById(List<ShortcutInfo> list, String id) {
        for (ShortcutInfo s : list) {
            if (s.getId().equals(id)) {
                return s;
            }
        }
        fail("Shortcut with id " + id + " not found");
        return null;
    }

    private void assertResetTimes(long expectedLastResetTime, long expectedNextResetTime) {
        assertEquals(expectedLastResetTime, mService.getLastResetTimeLocked());
        assertEquals(expectedNextResetTime, mService.getNextResetTimeLocked());
    }

    @NonNull
    private List<ShortcutInfo> assertShortcutIds(@NonNull List<ShortcutInfo> actualShortcuts,
            String... expectedIds) {
        final HashSet<String> expected = new HashSet<>(list(expectedIds));
        final HashSet<String> actual = new HashSet<>();
        for (ShortcutInfo s : actualShortcuts) {
            actual.add(s.getId());
        }

        // Compare the sets.
        assertEquals(expected, actual);
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllHaveIntents(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNotNull("ID " + s.getId(), s.getIntent());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllNotHaveIntents(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNull("ID " + s.getId(), s.getIntent());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllHaveTitle(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNotNull("ID " + s.getId(), s.getTitle());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllNotHaveTitle(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNull("ID " + s.getId(), s.getTitle());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllNotHaveIcon(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNull("ID " + s.getId(), s.getIcon());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllHaveIconResId(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId() + " not have icon res ID", s.hasIconResource());
            assertFalse("ID " + s.getId() + " shouldn't have icon FD", s.hasIconFile());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllHaveIconFile(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertFalse("ID " + s.getId() + " shouldn't have icon res ID", s.hasIconResource());
            assertTrue("ID " + s.getId() + " not have icon FD", s.hasIconFile());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllHaveIcon(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.hasIconFile() || s.hasIconResource());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllHaveFlags(@NonNull List<ShortcutInfo> actualShortcuts,
            int shortcutFlags) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.hasFlags(shortcutFlags));
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllKeyFieldsOnly(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.hasKeyFieldsOnly());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllNotKeyFieldsOnly(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertFalse("ID " + s.getId(), s.hasKeyFieldsOnly());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllDynamic(@NonNull List<ShortcutInfo> actualShortcuts) {
        return assertAllHaveFlags(actualShortcuts, ShortcutInfo.FLAG_DYNAMIC);
    }

    @NonNull
    private List<ShortcutInfo> assertAllPinned(@NonNull List<ShortcutInfo> actualShortcuts) {
        return assertAllHaveFlags(actualShortcuts, ShortcutInfo.FLAG_PINNED);
    }

    @NonNull
    private List<ShortcutInfo> assertAllDynamicOrPinned(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.isDynamic() || s.isPinned());
        }
        return actualShortcuts;
    }

    private void assertDynamicOnly(ShortcutInfo si) {
        assertTrue(si.isDynamic());
        assertFalse(si.isPinned());
    }

    private void assertPinnedOnly(ShortcutInfo si) {
        assertFalse(si.isDynamic());
        assertTrue(si.isPinned());
    }

    private void assertDynamicAndPinned(ShortcutInfo si) {
        assertTrue(si.isDynamic());
        assertTrue(si.isPinned());
    }

    private void assertBitmapSize(int expectedWidth, int expectedHeight, @NonNull Bitmap bitmap) {
        assertEquals("width", expectedWidth, bitmap.getWidth());
        assertEquals("height", expectedHeight, bitmap.getHeight());
    }

    private <T> void assertAllUnique(Collection<T> list) {
        final Set<Object> set = new HashSet<>();
        for (T item : list) {
            if (set.contains(item)) {
                fail("Duplicate item found: " + item + " (in the list: " + list + ")");
            }
            set.add(item);
        }
    }

    @NonNull
    private Bitmap pfdToBitmap(@NonNull ParcelFileDescriptor pfd) {
        Preconditions.checkNotNull(pfd);
        try {
            return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
        } finally {
            IoUtils.closeQuietly(pfd);
        }
    }

    private void assertBundleEmpty(BaseBundle b) {
        assertTrue(b == null || b.size() == 0);
    }

    private ShortcutInfo getPackageShortcut(String packageName, String shortcutId, int userId) {
        return mService.getPackageShortcutForTest(packageName, shortcutId, userId);
    }

    private void assertShortcutExists(String packageName, String shortcutId, int userId) {
        assertTrue(getPackageShortcut(packageName, shortcutId, userId) != null);
    }

    private void assertShortcutNotExists(String packageName, String shortcutId, int userId) {
        assertTrue(getPackageShortcut(packageName, shortcutId, userId) == null);
    }

    private Intent launchShortcutAndGetIntent(
            @NonNull String packageName, @NonNull String shortcutId, int userId) {
        reset(mServiceContext);
        assertTrue(mLauncherApps.startShortcut(packageName, shortcutId, null, null,
                UserHandle.of(userId)));

        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mServiceContext).startActivityAsUser(
                intentCaptor.capture(),
                any(Bundle.class),
                eq(UserHandle.of(userId)));
        return intentCaptor.getValue();
    }

    private void assertShortcutLaunchable(@NonNull String packageName, @NonNull String shortcutId,
            int userId) {
        assertNotNull(launchShortcutAndGetIntent(packageName, shortcutId, userId));
    }

    private void assertShortcutNotLaunchable(@NonNull String packageName,
            @NonNull String shortcutId, int userId) {
        try {
            final boolean ok = mLauncherApps.startShortcut(packageName, shortcutId, null, null,
                    UserHandle.of(userId));
            if (!ok) {
                return; // didn't launch, okay.
            }
            fail();
        } catch (SecurityException expected) {
            // security exception is okay too.
        }
    }

    private ShortcutInfo getPackageShortcut(String packageName, String shortcutId) {
        return getPackageShortcut(packageName, shortcutId, getCallingUserId());
    }

    private ShortcutInfo getCallerShortcut(String shortcutId) {
        return getPackageShortcut(getCallingPackage(), shortcutId, getCallingUserId());
    }

    private List<ShortcutInfo> getLauncherShortcuts(String launcher, int userId, int queryFlags) {
        final List<ShortcutInfo>[] ret = new List[1];
        runWithCaller(launcher, userId, () -> {
            final ShortcutQuery q = new ShortcutQuery();
            q.setQueryFlags(queryFlags);
            ret[0] = mLauncherApps.getShortcuts(q, UserHandle.of(userId));
        });
        return ret[0];
    }

    private List<ShortcutInfo> getLauncherPinnedShortcuts(String launcher, int userId) {
        return getLauncherShortcuts(launcher, userId, ShortcutQuery.FLAG_GET_PINNED);
    }


    private Intent genPackageDeleteIntent(String pakcageName, int userId) {
        Intent i = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        i.setData(Uri.parse("package:" + pakcageName));
        i.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return i;
    }

    private Intent genPackageUpdateIntent(String pakcageName, int userId) {
        Intent i = new Intent(Intent.ACTION_PACKAGE_ADDED);
        i.setData(Uri.parse("package:" + pakcageName));
        i.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        i.putExtra(Intent.EXTRA_REPLACING, true);
        return i;
    }

    /**
     * Wrap a set in an ArraySet just to get a better toString.
     */
    private <T> Set<T> set(Set<T> in) {
        return new ArraySet<T>(in);
    }

    /**
     * Test for the first launch path, no settings file available.
     */
    public void testFirstInitialize() {
        assertResetTimes(START_TIME, START_TIME + INTERVAL);
    }

    /**
     * Test for {@link ShortcutService#getLastResetTimeLocked()} and
     * {@link ShortcutService#getNextResetTimeLocked()}.
     */
    public void testUpdateAndGetNextResetTimeLocked() {
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock.
        mInjectedCurrentTimeLillis += 100;

        // Shouldn't have changed.
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock, almost the reset time.
        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;

        // Shouldn't have changed.
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock.
        mInjectedCurrentTimeLillis += 1;

        assertResetTimes(START_TIME + INTERVAL, START_TIME + 2 * INTERVAL);

        // Advance further; 4 days since start.
        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL + 50;

        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);
    }

    /**
     * Test for the restoration from saved file.
     */
    public void testInitializeFromSavedFile() {

        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL + 50;
        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);

        mService.saveBaseStateLocked();

        dumpBaseStateFile();

        mService.saveDirtyInfo();

        // Restore.
        initService();

        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);
    }

    /**
     * Test for the restoration from restored file.
     */
    public void testLoadFromBrokenFile() {
        // TODO Add various broken cases.
    }

    public void testLoadConfig() {
        mService.updateConfigurationLocked(
                ConfigConstants.KEY_RESET_INTERVAL_SEC + "=123,"
                        + ConfigConstants.KEY_MAX_SHORTCUTS + "=4,"
                        + ConfigConstants.KEY_MAX_DAILY_UPDATES + "=5,"
                        + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP + "=100,"
                        + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM + "=50,"
                        + ConfigConstants.KEY_ICON_FORMAT + "=WEBP,"
                        + ConfigConstants.KEY_ICON_QUALITY + "=75");
        assertEquals(123000, mService.getResetIntervalForTest());
        assertEquals(4, mService.getMaxDynamicShortcutsForTest());
        assertEquals(5, mService.getMaxDailyUpdatesForTest());
        assertEquals(100, mService.getMaxIconDimensionForTest());
        assertEquals(CompressFormat.WEBP, mService.getIconPersistFormatForTest());
        assertEquals(75, mService.getIconPersistQualityForTest());

        mInjectedIsLowRamDevice = true;
        mService.updateConfigurationLocked(
                ConfigConstants.KEY_MAX_ICON_DIMENSION_DP + "=100,"
                        + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM + "=50,"
                        + ConfigConstants.KEY_ICON_FORMAT + "=JPEG");
        assertEquals(ShortcutService.DEFAULT_RESET_INTERVAL_SEC * 1000,
                mService.getResetIntervalForTest());

        assertEquals(ShortcutService.DEFAULT_MAX_SHORTCUTS_PER_APP,
                mService.getMaxDynamicShortcutsForTest());

        assertEquals(ShortcutService.DEFAULT_MAX_DAILY_UPDATES,
                mService.getMaxDailyUpdatesForTest());

        assertEquals(50, mService.getMaxIconDimensionForTest());

        assertEquals(CompressFormat.JPEG, mService.getIconPersistFormatForTest());

        assertEquals(ShortcutService.DEFAULT_ICON_PERSIST_QUALITY,
                mService.getIconPersistQualityForTest());
    }

    // === Test for app side APIs ===

    /** Test for {@link android.content.pm.ShortcutManager#getMaxDynamicShortcutCount()} */
    public void testGetMaxDynamicShortcutCount() {
        assertEquals(MAX_SHORTCUTS, mManager.getMaxDynamicShortcutCount());
    }

    /** Test for {@link android.content.pm.ShortcutManager#getRemainingCallCount()} */
    public void testGetRemainingCallCount() {
        assertEquals(MAX_DAILY_UPDATES, mManager.getRemainingCallCount());
    }

    /** Test for {@link android.content.pm.ShortcutManager#getRateLimitResetTime()} */
    public void testGetRateLimitResetTime() {
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL + 50;

        assertEquals(START_TIME + 5 * INTERVAL, mManager.getRateLimitResetTime());
    }

    public void testSetDynamicShortcuts() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.icon1);
        final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.icon2));

        final ShortcutInfo si1 = makeShortcut(
                "shortcut1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                icon1,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo si2 = makeShortcut(
                "shortcut2",
                "Title 2",
                /* activity */ null,
                icon2,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2");
        assertEquals(2, mManager.getRemainingCallCount());

        // TODO: Check fields

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1");
        assertEquals(1, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list()));
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getRemainingCallCount());

        dumpsysOnLogcat();

        mInjectedCurrentTimeLillis++; // Need to advance the clock for reset to work.
        mService.resetThrottlingInner(UserHandle.USER_SYSTEM);

        dumpsysOnLogcat();

        assertTrue(mManager.setDynamicShortcuts(list(si2, si3)));
        assertEquals(2, mManager.getDynamicShortcuts().size());

        // TODO Check max number

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))));
        });
    }

    public void testAddDynamicShortcuts() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1");

        assertTrue(mManager.addDynamicShortcut(si2));
        assertEquals(1, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2");

        // Add with the same ID
        assertTrue(mManager.addDynamicShortcut(makeShortcut("shortcut1")));
        assertEquals(0, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2");

        // TODO Check max number

        // TODO Check fields.

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.addDynamicShortcut(makeShortcut("s1")));
        });
    }

    public void testDeleteDynamicShortcut() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertTrue(mManager.setDynamicShortcuts(list(si1, si2, si3)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");

        assertEquals(2, mManager.getRemainingCallCount());

        mManager.deleteDynamicShortcut("shortcut1");
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut2", "shortcut3");

        mManager.deleteDynamicShortcut("shortcut1");
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut2", "shortcut3");

        mManager.deleteDynamicShortcut("shortcutXXX");
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut2", "shortcut3");

        mManager.deleteDynamicShortcut("shortcut2");
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut3");

        mManager.deleteDynamicShortcut("shortcut3");
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()));

        // Still 2 calls left.
        assertEquals(2, mManager.getRemainingCallCount());

        // TODO Make sure pinned shortcuts won't be deleted.
    }

    public void testDeleteAllDynamicShortcuts() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertTrue(mManager.setDynamicShortcuts(list(si1, si2, si3)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");

        assertEquals(2, mManager.getRemainingCallCount());

        mManager.deleteAllDynamicShortcuts();
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(2, mManager.getRemainingCallCount());

        // Note delete shouldn't affect throttling, so...
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getDynamicShortcuts().size());

        // This should still work.
        assertTrue(mManager.setDynamicShortcuts(list(si1, si2, si3)));
        assertEquals(3, mManager.getDynamicShortcuts().size());

        // Still 1 call left
        assertEquals(1, mManager.getRemainingCallCount());

        // TODO Make sure pinned shortcuts won't be deleted.
    }

    public void testThrottling() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Reached the max

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Still throttled
        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Now it should work.
        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1))); // fail
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        // 4 days later...
        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 5, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 5, mManager.getRateLimitResetTime());

        // Make sure getRemainingCallCount() itself gets reset without calling setDynamicShortcuts().
        mInjectedCurrentTimeLillis = START_TIME + 8 * INTERVAL;
        assertEquals(3, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 9, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 9, mManager.getRateLimitResetTime());
    }

    public void testThrottling_rewind() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis = 12345; // Clock reset!

        // Since the clock looks invalid, the counter shouldn't have reset.
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Forward again.  Still haven't reset yet.
        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Now rewind -- this will reset the counters.
        mInjectedCurrentTimeLillis = START_TIME - 100000;
        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        // Forward again, should be reset now.
        mInjectedCurrentTimeLillis += INTERVAL;
        assertEquals(3, mManager.getRemainingCallCount());
    }

    public void testThrottling_perPackage() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Reached the max

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));

        // Try from a different caller.
        mInjectedClientPackage = CALLING_PACKAGE_2;
        mInjectedCallingUid = CALLING_UID_2;

        // Need to create a new one wit the updated package name.
        final ShortcutInfo si2 = makeShortcut("shortcut1");

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertEquals(2, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertEquals(1, mManager.getRemainingCallCount());

        // Back to the original caller, still throttled.
        mInjectedClientPackage = CALLING_PACKAGE_1;
        mInjectedCallingUid = CALLING_UID_1;

        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;
        assertEquals(0, mManager.getRemainingCallCount());
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Now it should work.
        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertFalse(mManager.setDynamicShortcuts(list(si1)));

        mInjectedClientPackage = CALLING_PACKAGE_2;
        mInjectedCallingUid = CALLING_UID_2;

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertFalse(mManager.setDynamicShortcuts(list(si2)));
    }

    public void testIcons() {
        final Icon res32x32 = Icon.createWithResource(getTestContext(), R.drawable.black_32x32);
        final Icon res64x64 = Icon.createWithResource(getTestContext(), R.drawable.black_64x64);
        final Icon res512x512 = Icon.createWithResource(getTestContext(), R.drawable.black_512x512);

        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));
        final Icon bmp64x64 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_64x64));
        final Icon bmp512x512 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_512x512));

        // Set from package 1
        setCaller(CALLING_PACKAGE_1);
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIcon("res32x32", res32x32),
                makeShortcutWithIcon("res64x64", res64x64),
                makeShortcutWithIcon("bmp32x32", bmp32x32),
                makeShortcutWithIcon("bmp64x64", bmp64x64),
                makeShortcutWithIcon("bmp512x512", bmp512x512),
                makeShortcut("none")
        )));

        // getDynamicShortcuts() shouldn't return icons, thus assertAllNotHaveIcon().
        assertShortcutIds(assertAllNotHaveIcon(mManager.getDynamicShortcuts()),
                "res32x32",
                "res64x64",
                "bmp32x32",
                "bmp64x64",
                "bmp512x512",
                "none");

        // Call from another caller with the same ID, just to make sure storage is per-package.
        setCaller(CALLING_PACKAGE_2);
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIcon("res32x32", res512x512),
                makeShortcutWithIcon("res64x64", res512x512),
                makeShortcutWithIcon("none", res512x512)
        )));
        assertShortcutIds(assertAllNotHaveIcon(mManager.getDynamicShortcuts()),
                "res32x32",
                "res64x64",
                "none");

        // Re-initialize and load from the files.
        mService.saveDirtyInfo();
        initService();

        // Load from launcher.
        Bitmap bmp;

        setCaller(LAUNCHER_1);
        // Check hasIconResource()/hasIconFile().
        assertShortcutIds(assertAllHaveIconResId(mLauncherApps.getShortcutInfo(
                CALLING_PACKAGE_1, list("res32x32"),
                getCallingUser())), "res32x32");

        assertShortcutIds(assertAllHaveIconResId(mLauncherApps.getShortcutInfo(
                CALLING_PACKAGE_1, list("res64x64"), getCallingUser())),
                "res64x64");

        assertShortcutIds(assertAllHaveIconFile(mLauncherApps.getShortcutInfo(
                CALLING_PACKAGE_1, list("bmp32x32"), getCallingUser())),
                "bmp32x32");

        assertShortcutIds(assertAllHaveIconFile(mLauncherApps.getShortcutInfo(
                CALLING_PACKAGE_1, list("bmp64x64"), getCallingUser())),
                "bmp64x64");

        assertShortcutIds(assertAllHaveIconFile(mLauncherApps.getShortcutInfo(
                CALLING_PACKAGE_1, list("bmp512x512"), getCallingUser())),
                "bmp512x512");

        // Check
        assertEquals(
                R.drawable.black_32x32,
                mLauncherApps.getShortcutIconResId(
                        makePackageShortcut(CALLING_PACKAGE_1, "res32x32"), getCallingUser()));

        assertEquals(
                R.drawable.black_64x64,
                mLauncherApps.getShortcutIconResId(

                        makePackageShortcut(CALLING_PACKAGE_1, "res64x64"), getCallingUser()));

        assertEquals(
                0, // because it's not a resource
                mLauncherApps.getShortcutIconResId(
                        makePackageShortcut(CALLING_PACKAGE_1, "bmp32x32"), getCallingUser()));
        assertEquals(
                0, // because it's not a resource
                mLauncherApps.getShortcutIconResId(
                        makePackageShortcut(CALLING_PACKAGE_1, "bmp64x64"), getCallingUser()));
        assertEquals(
                0, // because it's not a resource
                mLauncherApps.getShortcutIconResId(
                        makePackageShortcut(CALLING_PACKAGE_1, "bmp512x512"), getCallingUser()));

        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                makePackageShortcut(CALLING_PACKAGE_1, "bmp32x32"), getCallingUser()));
        assertBitmapSize(32, 32, bmp);

        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                makePackageShortcut(CALLING_PACKAGE_1, "bmp64x64"), getCallingUser()));
        assertBitmapSize(64, 64, bmp);

        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                makePackageShortcut(CALLING_PACKAGE_1, "bmp512x512"), getCallingUser()));
        assertBitmapSize(128, 128, bmp);

        // TODO Test the content URI case too.
    }

    private void checkShrinkBitmap(int expectedWidth, int expectedHeight, int resId, int maxSize) {
        assertBitmapSize(expectedWidth, expectedHeight,
                ShortcutService.shrinkBitmap(BitmapFactory.decodeResource(
                        getTestContext().getResources(), resId),
                        maxSize));
    }

    public void testShrinkBitmap() {
        checkShrinkBitmap(32, 32, R.drawable.black_512x512, 32);
        checkShrinkBitmap(511, 511, R.drawable.black_512x512, 511);
        checkShrinkBitmap(512, 512, R.drawable.black_512x512, 512);

        checkShrinkBitmap(1024, 4096, R.drawable.black_1024x4096, 4096);
        checkShrinkBitmap(1024, 4096, R.drawable.black_1024x4096, 4100);
        checkShrinkBitmap(512, 2048, R.drawable.black_1024x4096, 2048);

        checkShrinkBitmap(4096, 1024, R.drawable.black_4096x1024, 4096);
        checkShrinkBitmap(4096, 1024, R.drawable.black_4096x1024, 4100);
        checkShrinkBitmap(2048, 512, R.drawable.black_4096x1024, 2048);
    }

    private File openIconFileForWriteAndGetPath(int userId, String packageName)
            throws IOException {
        // Shortcut IDs aren't used in the path, so just pass the same ID.
        final FileOutputStreamWithPath out =
                mService.openIconFileForWrite(userId, makePackageShortcut(packageName, "id"));
        out.close();
        return out.getFile();
    }

    public void testOpenIconFileForWrite() throws IOException {
        mInjectedCurrentTimeLillis = 1000;

        final File p10_1_1 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);
        final File p10_1_2 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);

        final File p10_2_1 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_2);
        final File p10_2_2 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_2);

        final File p11_1_1 = openIconFileForWriteAndGetPath(11, CALLING_PACKAGE_1);
        final File p11_1_2 = openIconFileForWriteAndGetPath(11, CALLING_PACKAGE_1);

        mInjectedCurrentTimeLillis++;

        final File p10_1_3 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);
        final File p10_1_4 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);
        final File p10_1_5 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);

        final File p10_2_3 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_2);
        final File p11_1_3 = openIconFileForWriteAndGetPath(11, CALLING_PACKAGE_1);

        // Make sure their paths are all unique
        assertAllUnique(list(
                p10_1_1,
                p10_1_2,
                p10_1_3,
                p10_1_4,
                p10_1_5,

                p10_2_1,
                p10_2_2,
                p10_2_3,

                p11_1_1,
                p11_1_2,
                p11_1_3
        ));

        // Check each set has the same parent.
        assertEquals(p10_1_1.getParent(), p10_1_2.getParent());
        assertEquals(p10_1_1.getParent(), p10_1_3.getParent());
        assertEquals(p10_1_1.getParent(), p10_1_4.getParent());
        assertEquals(p10_1_1.getParent(), p10_1_5.getParent());

        assertEquals(p10_2_1.getParent(), p10_2_2.getParent());
        assertEquals(p10_2_1.getParent(), p10_2_3.getParent());

        assertEquals(p11_1_1.getParent(), p11_1_2.getParent());
        assertEquals(p11_1_1.getParent(), p11_1_3.getParent());

        // Check the parents are still unique.
        assertAllUnique(list(
                p10_1_1.getParent(),
                p10_2_1.getParent(),
                p11_1_1.getParent()
        ));

        // All files created at the same time for the same package/user, expcet for the first ones,
        // will have "_" in the path.
        assertFalse(p10_1_1.getName().contains("_"));
        assertTrue(p10_1_2.getName().contains("_"));
        assertFalse(p10_1_3.getName().contains("_"));
        assertTrue(p10_1_4.getName().contains("_"));
        assertTrue(p10_1_5.getName().contains("_"));

        assertFalse(p10_2_1.getName().contains("_"));
        assertTrue(p10_2_2.getName().contains("_"));
        assertFalse(p10_2_3.getName().contains("_"));

        assertFalse(p11_1_1.getName().contains("_"));
        assertTrue(p11_1_2.getName().contains("_"));
        assertFalse(p11_1_3.getName().contains("_"));
    }

    public void testUpdateShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5")
            )));
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5")
            )));
        });
        runWithCaller(LAUNCHER_1, UserHandle.USER_SYSTEM, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s2", "s3"),
                    getCallingUser());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s4", "s5"),
                    getCallingUser());
        });
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            mManager.deleteDynamicShortcut("s1");
            mManager.deleteDynamicShortcut("s2");
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            mManager.deleteDynamicShortcut("s1");
            mManager.deleteDynamicShortcut("s3");
            mManager.deleteDynamicShortcut("s5");
        });
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s3", "s4", "s5");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2", "s3");
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s2", "s4");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s4", "s5");
        });

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            ShortcutInfo s2 = makeShortcutBuilder()
                    .setId("s2")
                    .setIcon(Icon.createWithResource(getTestContext(), R.drawable.black_32x32))
                    .build();

            ShortcutInfo s4 = makeShortcutBuilder()
                    .setId("s4")
                    .setTitle("new title")
                    .build();

            mManager.updateShortcuts(list(s2, s4));
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            ShortcutInfo s2 = makeShortcutBuilder()
                    .setId("s2")
                    .setIntent(makeIntent(Intent.ACTION_ANSWER, ShortcutActivity.class,
                            "key1", "val1"))
                    .build();

            ShortcutInfo s4 = makeShortcutBuilder()
                    .setId("s4")
                    .setIntent(new Intent(Intent.ACTION_ALL_APPS))
                    .build();

            mManager.updateShortcuts(list(s2, s4));
        });

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s3", "s4", "s5");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2", "s3");

            ShortcutInfo s = getCallerShortcut("s2");
            assertTrue(s.hasIconResource());
            assertEquals(R.drawable.black_32x32, s.getIconResourceId());
            assertEquals("Title-s2", s.getTitle());

            s = getCallerShortcut("s4");
            assertFalse(s.hasIconResource());
            assertEquals(0, s.getIconResourceId());
            assertEquals("new title", s.getTitle());
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s2", "s4");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s4", "s5");

            ShortcutInfo s = getCallerShortcut("s2");
            assertFalse(s.hasIconResource());
            assertEquals(0, s.getIconResourceId());
            assertEquals("Title-s2", s.getTitle());
            assertEquals(Intent.ACTION_ANSWER, s.getIntent().getAction());
            assertEquals(1, s.getIntent().getExtras().size());

            s = getCallerShortcut("s4");
            assertFalse(s.hasIconResource());
            assertEquals(0, s.getIconResourceId());
            assertEquals("Title-s4", s.getTitle());
            assertEquals(Intent.ACTION_ALL_APPS, s.getIntent().getAction());
            assertBundleEmpty(s.getIntent().getExtras());
        });
        // TODO Check with other fields too.

        // TODO Check bitmap removal too.

        runWithCaller(CALLING_PACKAGE_2, USER_11, () -> {
            mManager.updateShortcuts(list());
        });
    }

    // TODO: updateShortcuts()
    // TODO: getPinnedShortcuts()

    // === Test for launcher side APIs ===

    private static ShortcutQuery buildQuery(long changedSince,
            String packageName, ComponentName componentName,
            /* @ShortcutQuery.QueryFlags */ int flags) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setChangedSince(changedSince);
        q.setPackage(packageName);
        q.setActivity(componentName);
        q.setQueryFlags(flags);
        return q;
    }

    public void testGetShortcuts() {

        // Set up shortcuts.

        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcutWithTimestamp("s1", 5000);
        final ShortcutInfo s1_2 = makeShortcutWithTimestamp("s2", 1000);

        assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_2 = makeShortcutWithTimestamp("s2", 1500);
        final ShortcutInfo s2_3 = makeShortcutWithTimestamp("s3", 3000);
        final ShortcutInfo s2_4 = makeShortcutWithTimestamp("s4", 500);
        assertTrue(mManager.setDynamicShortcuts(list(s2_2, s2_3, s2_4)));

        setCaller(CALLING_PACKAGE_3);
        final ShortcutInfo s3_2 = makeShortcutWithTimestamp("s3", START_TIME + 5000);
        assertTrue(mManager.setDynamicShortcuts(list(s3_2)));

        setCaller(LAUNCHER_1);

        // Get dynamic
        assertAllDynamic(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllNotKeyFieldsOnly(
                mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                        /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                "s1", "s2"))));

        // Get pinned
        assertShortcutIds(
                mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED), getCallingUser())
                /* none */);

        // Get both, with timestamp
        assertAllDynamic(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllNotKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED | ShortcutQuery.FLAG_GET_DYNAMIC),
                        getCallingUser())),
                "s2", "s3"))));

        // FLAG_GET_KEY_FIELDS_ONLY
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s2", "s3"))));

        // Pin some shortcuts.
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                list("s3", "s4"), getCallingUser());

        // Pinned ones only
        assertAllPinned(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllNotKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED),
                        getCallingUser())),
                "s3"))));

        // All packages.
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 5000, /* package= */ null,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_PINNED),
                        getCallingUser())),
                "s1", "s3");

        // TODO More tests: pinned but dynamic, filter by activity
    }

    public void testGetShortcutInfo() {
        // Create shortcuts.
        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcut(
                "s1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo s1_2 = makeShortcut(
                "s2",
                "Title 2",
                /* activity */ null,
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);

        assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));
        dumpsysOnLogcat();

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_1 = makeShortcut(
                "s1",
                "ABC",
                makeComponent(ShortcutActivity2.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ANSWER, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);
        assertTrue(mManager.setDynamicShortcuts(list(s2_1)));
        dumpsysOnLogcat();

        // Pin some.
        setCaller(LAUNCHER_1);

        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                list("s2"), getCallingUser());

        dumpsysOnLogcat();

        // Delete some.
        setCaller(CALLING_PACKAGE_1);
        assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
        mManager.deleteDynamicShortcut("s2");
        assertShortcutIds(mManager.getPinnedShortcuts(), "s2");

        dumpsysOnLogcat();

        setCaller(LAUNCHER_1);
        List<ShortcutInfo> list;

        // Note we don't guarantee the orders.
        list = assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                assertAllNotKeyFieldsOnly(
                mLauncherApps.getShortcutInfo(CALLING_PACKAGE_1,
                list("s2", "s1", "s3", null), getCallingUser())))),
                "s1", "s2");
        assertEquals("Title 1", findById(list, "s1").getTitle());
        assertEquals("Title 2", findById(list, "s2").getTitle());

        assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                mLauncherApps.getShortcutInfo(CALLING_PACKAGE_1,
                        list("s3"), getCallingUser())))
                /* none */);

        list = assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                mLauncherApps.getShortcutInfo(CALLING_PACKAGE_2,
                        list("s1", "s2", "s3"), getCallingUser()))),
                "s1");
        assertEquals("ABC", findById(list, "s1").getTitle());
    }

    public void testPinShortcutAndGetPinnedShortcuts() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ShortcutInfo s1_1 = makeShortcutWithTimestamp("s1", 1000);
            final ShortcutInfo s1_2 = makeShortcutWithTimestamp("s2", 2000);

            assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            final ShortcutInfo s2_2 = makeShortcutWithTimestamp("s2", 1500);
            final ShortcutInfo s2_3 = makeShortcutWithTimestamp("s3", 3000);
            final ShortcutInfo s2_4 = makeShortcutWithTimestamp("s4", 500);
            assertTrue(mManager.setDynamicShortcuts(list(s2_2, s2_3, s2_4)));
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            final ShortcutInfo s3_2 = makeShortcutWithTimestamp("s2", 1000);
            assertTrue(mManager.setDynamicShortcuts(list(s3_2)));
        });

        // Pin some.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2", "s3"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3", "s4", "s5"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3,
                    list("s3"), getCallingUser());  // Note ID doesn't exist
        });

        // Delete some.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
            mManager.deleteDynamicShortcut("s2");
            assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3", "s4");
            mManager.deleteDynamicShortcut("s3");
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3", "s4");
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts() /* none */);
            mManager.deleteDynamicShortcut("s2");
            assertShortcutIds(mManager.getPinnedShortcuts() /* none */);
        });

        // Get pinned shortcuts from launcher
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // CALLING_PACKAGE_1 deleted s2, but it's pinned, so it still exists.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s2");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3", "s4");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_3,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))
                    /* none */);
        });
    }

    public void testPinShortcutAndGetPinnedShortcuts_multi() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        dumpsysOnLogcat();

        // Pin some.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3", "s4"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1", "s2", "s4"), getCallingUser());
        });

        dumpsysOnLogcat();

        // Delete some.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3");
            mManager.deleteDynamicShortcut("s3");
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3");
        });

        dumpsysOnLogcat();

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s1", "s2");
            mManager.deleteDynamicShortcut("s1");
            mManager.deleteDynamicShortcut("s3");
            assertShortcutIds(mManager.getPinnedShortcuts(), "s1", "s2");
        });

        dumpsysOnLogcat();

        // Get pinned shortcuts from launcher
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2");

            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");
        });

        dumpsysOnLogcat();

        runWithCaller(LAUNCHER_2, USER_0, () -> {
            // Launcher2 still has no pinned ones.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))
                    /* none */);
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))
                    /* none */);

            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s2");

            // Now pin some.
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1", "s2"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1", "s2"), getCallingUser());

            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");

            // S1 was not visible to it, so shouldn't be pinned.
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s2");
        });

        // Re-initialize and load from the files.
        mService.saveDirtyInfo();
        initService();

        // Load from file.
        mService.handleUnlockUser(USER_0);

        // Make sure package info is restored too.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2");
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s2");
        });

        // Delete all dynamic.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.deleteAllDynamicShortcuts();

            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s1", "s2", "s3");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            mManager.deleteAllDynamicShortcuts();

            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s2", "s1");
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2");

            // from all packages.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, null,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2", "s3");

            // Update pined.  Note s2 and s3 are actually available, but not visible to this
            // launcher, so still can't be pinned.
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3", "s4"),
                    getCallingUser());

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");
        });
        // Re-publish s1.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.addDynamicShortcut(makeShortcut("s1")));

            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()), "s1");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s1", "s2", "s3");
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");

            // Now "s1" is visible, so can be pinned.
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3", "s4"),
                    getCallingUser());

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s3");
        });

        // Now clear pinned shortcuts.  First, from launcher 1.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list(), getCallingUser());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list(), getCallingUser());

            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()), "s1");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s1", "s2");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s2");
        });

        // Clear all pins from launcher 2.
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list(), getCallingUser());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list(), getCallingUser());

            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()), "s1");
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });
    }

    public void testPinShortcutAndGetPinnedShortcuts_crossProfile_plusLaunch() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"),
                    makeShortcut("s4"), makeShortcut("s5"), makeShortcut("s6"))));
        });

        // Pin some shortcuts and see the result.

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1", "s2", "s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s2", "s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_2, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1", "s2", "s3"), HANDLE_USER_10);
        });

        // Cross profile pinning.
        final int PIN_AND_DYNAMIC = ShortcutQuery.FLAG_GET_PINNED | ShortcutQuery.FLAG_GET_DYNAMIC;

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_10, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_10)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_10)),
                    "s1", "s2", "s3", "s4", "s5", "s6");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_10)),
                    "s1", "s2", "s3", "s4", "s5", "s6");
        });

        // Remove some dynamic shortcuts.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_10, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_10)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_10)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_10)),
                    "s1", "s2", "s3");

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });

        // Save & load and make sure we still have the same information.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
    }

    public void testStartShortcut() {
        // Create some shortcuts.
        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcut(
                "s1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo s1_2 = makeShortcut(
                "s2",
                "Title 2",
                /* activity */ null,
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);

        assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_1 = makeShortcut(
                "s1",
                "ABC",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ANSWER, ShortcutActivity.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);
        assertTrue(mManager.setDynamicShortcuts(list(s2_1)));

        // Pin all.
        setCaller(LAUNCHER_1);
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                list("s1", "s2"), getCallingUser());

        mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                list("s1"), getCallingUser());

        // Just to make it complicated, delete some.
        setCaller(CALLING_PACKAGE_1);
        mManager.deleteDynamicShortcut("s2");

        // intent and check.
        setCaller(LAUNCHER_1);

        Intent intent;
        intent = launchShortcutAndGetIntent(CALLING_PACKAGE_1, "s1", USER_0);
        assertEquals(ShortcutActivity2.class.getName(), intent.getComponent().getClassName());


        intent = launchShortcutAndGetIntent(CALLING_PACKAGE_1, "s2", USER_0);
        assertEquals(ShortcutActivity3.class.getName(), intent.getComponent().getClassName());

        intent = launchShortcutAndGetIntent(CALLING_PACKAGE_2, "s1", USER_0);
        assertEquals(ShortcutActivity.class.getName(), intent.getComponent().getClassName());

        // TODO Check extra, etc
    }

    public void testLauncherCallback() throws Throwable {

        // TODO Add "multi" version -- run the test with two launchers and make sure the callback
        // argument only contains the ones that are actually visible to each launcher.

        when(mMockUserManager.isUserRunning(eq(USER_0))).thenReturn(true);

        LauncherApps.Callback c0 = mock(LauncherApps.Callback.class);

        // Set listeners

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerCallback(c0, new Handler(Looper.getMainLooper()));
        });

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        waitOnMainThread();
        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s1", "s2", "s3");

        // From different package.
        reset(c0);
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_2),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s1", "s2", "s3");

        // Different user, callback shouldn't be called.
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        waitOnMainThread();
        verify(c0, times(0)).onShortcutsChanged(
                anyString(),
                any(List.class),
                any(UserHandle.class)
        );

        // Test for addDynamicShortcut.
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.addDynamicShortcut(makeShortcut("s4")));
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s1", "s2", "s3", "s4");

        // Test for remove
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            mManager.deleteDynamicShortcut("s1");
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s2", "s3", "s4");

        // Test for update
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.updateShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"))));
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s2", "s3", "s4");

        // Test for deleteAll
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            mManager.deleteAllDynamicShortcuts();
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertEquals(0, shortcuts.getValue().size());

        // Remove CALLING_PACKAGE_2
        reset(c0);
        mService.cleanUpPackageLocked(CALLING_PACKAGE_2, USER_0, USER_0);

        // Should get a callback with an empty list.
        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_2),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertEquals(0, shortcuts.getValue().size());
    }

    // === Test for persisting ===

    public void testSaveAndLoadUser_empty() {
        assertTrue(mManager.setDynamicShortcuts(list()));

        Log.i(TAG, "Saved state");
        dumpsysOnLogcat();
        dumpUserFile(0);

        // Restore.
        mService.saveDirtyInfo();
        initService();

        assertEquals(0, mManager.getDynamicShortcuts().size());
    }

    /**
     * Try save and load, also stop/start the user.
     */
    public void testSaveAndLoadUser() {
        // First, create some shortcuts and save.
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.black_64x16);
            final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                    getTestContext().getResources(), R.drawable.icon2));

            final ShortcutInfo si1 = makeShortcut(
                    "s1",
                    "title1-1",
                    makeComponent(ShortcutActivity.class),
                    icon1,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                            "key1", "val1", "nest", makeBundle("key", 123)),
                        /* weight */ 10);

            final ShortcutInfo si2 = makeShortcut(
                    "s2",
                    "title1-2",
                        /* activity */ null,
                    icon2,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                        /* weight */ 12);

            assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));

            assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
            assertEquals(2, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.black_16x64);
            final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                    getTestContext().getResources(), R.drawable.icon2));

            final ShortcutInfo si1 = makeShortcut(
                    "s1",
                    "title2-1",
                    makeComponent(ShortcutActivity.class),
                    icon1,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                            "key1", "val1", "nest", makeBundle("key", 123)),
                        /* weight */ 10);

            final ShortcutInfo si2 = makeShortcut(
                    "s2",
                    "title2-2",
                        /* activity */ null,
                    icon2,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                        /* weight */ 12);

            assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));

            assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
            assertEquals(2, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.black_64x64);
            final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                    getTestContext().getResources(), R.drawable.icon2));

            final ShortcutInfo si1 = makeShortcut(
                    "s1",
                    "title10-1-1",
                    makeComponent(ShortcutActivity.class),
                    icon1,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                            "key1", "val1", "nest", makeBundle("key", 123)),
                        /* weight */ 10);

            final ShortcutInfo si2 = makeShortcut(
                    "s2",
                    "title10-1-2",
                        /* activity */ null,
                    icon2,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                        /* weight */ 12);

            assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));

            assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
            assertEquals(2, mManager.getRemainingCallCount());
        });

        mService.getShortcutsForTest().get(UserHandle.USER_SYSTEM).setLauncherComponent(
                mService, new ComponentName("pkg1", "class"));

        // Restore.
        mService.saveDirtyInfo();
        initService();

        // Before the load, the map should be empty.
        assertEquals(0, mService.getShortcutsForTest().size());

        // this will pre-load the per-user info.
        mService.handleUnlockUser(UserHandle.USER_SYSTEM);

        // Now it's loaded.
        assertEquals(1, mService.getShortcutsForTest().size());

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(assertAllHaveIntents(assertAllHaveIcon(
                    mManager.getDynamicShortcuts()))), "s1", "s2");
            assertEquals(2, mManager.getRemainingCallCount());

            assertEquals("title1-1", getCallerShortcut("s1").getTitle());
            assertEquals("title1-2", getCallerShortcut("s2").getTitle());
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(assertAllHaveIntents(assertAllHaveIcon(
                    mManager.getDynamicShortcuts()))), "s1", "s2");
            assertEquals(2, mManager.getRemainingCallCount());

            assertEquals("title2-1", getCallerShortcut("s1").getTitle());
            assertEquals("title2-2", getCallerShortcut("s2").getTitle());
        });

        assertEquals("pkg1", mService.getShortcutsForTest().get(UserHandle.USER_SYSTEM)
                .getLauncherComponent().getPackageName());

        // Start another user
        mService.handleUnlockUser(USER_10);

        // Now the size is 2.
        assertEquals(2, mService.getShortcutsForTest().size());

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertShortcutIds(assertAllDynamic(assertAllHaveIntents(assertAllHaveIcon(
                    mManager.getDynamicShortcuts()))), "s1", "s2");
            assertEquals(2, mManager.getRemainingCallCount());

            assertEquals("title10-1-1", getCallerShortcut("s1").getTitle());
            assertEquals("title10-1-2", getCallerShortcut("s2").getTitle());
        });
        assertNull(mService.getShortcutsForTest().get(USER_10).getLauncherComponent());

        // Try stopping the user
        mService.handleCleanupUser(USER_10);

        // Now it's unloaded.
        assertEquals(1, mService.getShortcutsForTest().size());

        // TODO Check all other fields
    }

    public void testCleanupPackage() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s0_1"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s0_2"))));
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s0_1"),
                    HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s0_2"),
                    HANDLE_USER_0);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s0_1"),
                    HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s0_2"),
                    HANDLE_USER_0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s10_1"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s10_2"))));
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s10_1"),
                    HANDLE_USER_10);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s10_2"),
                    HANDLE_USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s10_1"),
                    HANDLE_USER_10);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s10_2"),
                    HANDLE_USER_10);
        });

        // Remove all dynamic shortcuts; now all shortcuts are just pinned.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.deleteAllDynamicShortcuts();
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            mManager.deleteAllDynamicShortcuts();
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            mManager.deleteAllDynamicShortcuts();
        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            mManager.deleteAllDynamicShortcuts();
        });


        final SparseArray<ShortcutUser> users =  mService.getShortcutsForTest();
        assertEquals(2, users.size());
        assertEquals(USER_0, users.keyAt(0));
        assertEquals(USER_10, users.keyAt(1));

        final ShortcutUser user0 =  users.get(USER_0);
        final ShortcutUser user10 =  users.get(USER_10);


        // Check the registered packages.
        dumpsysOnLogcat();
        assertEquals(makeSet(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                set(user0.getPackages().keySet()));
        assertEquals(makeSet(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                set(user10.getPackages().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                set(user0.getAllLaunchers().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_10, LAUNCHER_1),
                        PackageWithUser.of(USER_10, LAUNCHER_2)),
                set(user10.getAllLaunchers().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_10),
                "s10_1", "s10_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Nonexistent package.
        mService.cleanUpPackageLocked("abc", USER_0, USER_0);

        // No changes.
        assertEquals(makeSet(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                set(user0.getPackages().keySet()));
        assertEquals(makeSet(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                set(user10.getPackages().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                set(user0.getAllLaunchers().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_10, LAUNCHER_1),
                        PackageWithUser.of(USER_10, LAUNCHER_2)),
                set(user10.getAllLaunchers().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_10),
                "s10_1", "s10_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove a package.
        mService.cleanUpPackageLocked(CALLING_PACKAGE_1, USER_0, USER_0);

        assertEquals(makeSet(CALLING_PACKAGE_2),
                set(user0.getPackages().keySet()));
        assertEquals(makeSet(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                set(user10.getPackages().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                set(user0.getAllLaunchers().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_10, LAUNCHER_1),
                        PackageWithUser.of(USER_10, LAUNCHER_2)),
                set(user10.getAllLaunchers().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_10),
                "s10_1", "s10_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove a launcher.
        mService.cleanUpPackageLocked(LAUNCHER_1, USER_10, USER_10);

        assertEquals(makeSet(CALLING_PACKAGE_2),
                set(user0.getPackages().keySet()));
        assertEquals(makeSet(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                set(user10.getPackages().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                set(user0.getAllLaunchers().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_10, LAUNCHER_2)),
                set(user10.getAllLaunchers().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove a package.
        mService.cleanUpPackageLocked(CALLING_PACKAGE_2, USER_10, USER_10);

        assertEquals(makeSet(CALLING_PACKAGE_2),
                set(user0.getPackages().keySet()));
        assertEquals(makeSet(CALLING_PACKAGE_1),
                set(user10.getPackages().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                set(user0.getAllLaunchers().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_10, LAUNCHER_2)),
                set(user10.getAllLaunchers().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1");
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutNotExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove the other launcher from user 10 too.
        mService.cleanUpPackageLocked(LAUNCHER_2, USER_10, USER_10);

        assertEquals(makeSet(CALLING_PACKAGE_2),
                set(user0.getPackages().keySet()));
        assertEquals(makeSet(CALLING_PACKAGE_1),
                set(user10.getPackages().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                set(user0.getAllLaunchers().keySet()));
        assertEquals(
                makeSet(),
                set(user10.getAllLaunchers().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");

        // Note the pinned shortcuts on user-10 no longer referred, so they should both be removed.
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutNotExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutNotExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // More remove.
        mService.cleanUpPackageLocked(CALLING_PACKAGE_1, USER_10, USER_10);

        assertEquals(makeSet(CALLING_PACKAGE_2),
                set(user0.getPackages().keySet()));
        assertEquals(makeSet(),
                set(user10.getPackages().keySet()));
        assertEquals(
                makeSet(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                set(user0.getAllLaunchers().keySet()));
        assertEquals(makeSet(),
                set(user10.getAllLaunchers().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");

        // Note the pinned shortcuts on user-10 no longer referred, so they should both be removed.
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutNotExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutNotExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();
    }


    public void testSaveAndLoadUser_forBackup() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Pin some.

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), UserHandle.of(USER_P0));

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), UserHandle.of(USER_P0));

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), HANDLE_USER_10);
        });

        // Check the state.

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Make sure all the information is persisted.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);
    }

    public void testHandleGonePackage_crossProfile() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Pin some.

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), UserHandle.of(USER_P0));

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), UserHandle.of(USER_P0));

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), HANDLE_USER_10);
        });

        // Check the state.

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Make sure all the information is persisted.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Start uninstalling.
        uninstallPackage(USER_10, LAUNCHER_1);
        mService.cleanupGonePackages(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Uninstall.
        uninstallPackage(USER_10, CALLING_PACKAGE_1);
        mService.cleanupGonePackages(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        uninstallPackage(USER_P0, LAUNCHER_1);
        mService.cleanupGonePackages(USER_0);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        mService.cleanupGonePackages(USER_P0);
        
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        uninstallPackage(USER_P0, CALLING_PACKAGE_1);

        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Uninstall
        uninstallPackage(USER_0, LAUNCHER_1);

        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        uninstallPackage(USER_0, CALLING_PACKAGE_2);

        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));
    }

    // TODO Detailed test for hasShortcutPermissionInner().

    // TODO Add tests for the command line functions too.

    private void checkCanRestoreTo(boolean expected, ShortcutPackageInfo spi,
            int version, String... signatures) {
        assertEquals(expected, spi.canRestoreTo(genPackage(
                "dummy", /* uid */ 0, version, signatures)));
    }

    public void testCanRestoreTo() {
        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 10, "sig1");
        addPackage(CALLING_PACKAGE_2, CALLING_UID_1, 10, "sig1", "sig2");

        final ShortcutPackageInfo spi1 = ShortcutPackageInfo.generateForInstalledPackage(
                mService, CALLING_PACKAGE_1, USER_0);
        final ShortcutPackageInfo spi2 = ShortcutPackageInfo.generateForInstalledPackage(
                mService, CALLING_PACKAGE_2, USER_0);

        checkCanRestoreTo(true, spi1, 10, "sig1");
        checkCanRestoreTo(true, spi1, 10, "x", "sig1");
        checkCanRestoreTo(true, spi1, 10, "sig1", "y");
        checkCanRestoreTo(true, spi1, 10, "x", "sig1", "y");
        checkCanRestoreTo(true, spi1, 11, "sig1");

        checkCanRestoreTo(false, spi1, 10 /* empty */);
        checkCanRestoreTo(false, spi1, 10, "x");
        checkCanRestoreTo(false, spi1, 10, "x", "y");
        checkCanRestoreTo(false, spi1, 10, "x");
        checkCanRestoreTo(false, spi1, 9, "sig1");

        checkCanRestoreTo(true, spi2, 10, "sig1", "sig2");
        checkCanRestoreTo(true, spi2, 10, "sig2", "sig1");
        checkCanRestoreTo(true, spi2, 10, "x", "sig1", "sig2");
        checkCanRestoreTo(true, spi2, 10, "x", "sig2", "sig1");
        checkCanRestoreTo(true, spi2, 10, "sig1", "sig2", "y");
        checkCanRestoreTo(true, spi2, 10, "sig2", "sig1", "y");
        checkCanRestoreTo(true, spi2, 10, "x", "sig1", "sig2", "y");
        checkCanRestoreTo(true, spi2, 10, "x", "sig2", "sig1", "y");
        checkCanRestoreTo(true, spi2, 11, "x", "sig2", "sig1", "y");

        checkCanRestoreTo(false, spi2, 10, "sig1", "sig2x");
        checkCanRestoreTo(false, spi2, 10, "sig2", "sig1x");
        checkCanRestoreTo(false, spi2, 10, "x", "sig1x", "sig2");
        checkCanRestoreTo(false, spi2, 10, "x", "sig2x", "sig1");
        checkCanRestoreTo(false, spi2, 10, "sig1", "sig2x", "y");
        checkCanRestoreTo(false, spi2, 10, "sig2", "sig1x", "y");
        checkCanRestoreTo(false, spi2, 10, "x", "sig1x", "sig2", "y");
        checkCanRestoreTo(false, spi2, 10, "x", "sig2x", "sig1", "y");
        checkCanRestoreTo(false, spi2, 11, "x", "sig2x", "sig1", "y");
    }

    public void testHandlePackageDelete() {
        setCaller(CALLING_PACKAGE_1, USER_0);
        assertTrue(mManager.addDynamicShortcut(makeShortcut("s1")));

        setCaller(CALLING_PACKAGE_2, USER_0);
        assertTrue(mManager.addDynamicShortcut(makeShortcut("s1")));

        setCaller(CALLING_PACKAGE_3, USER_0);
        assertTrue(mManager.addDynamicShortcut(makeShortcut("s1")));

        setCaller(CALLING_PACKAGE_1, USER_10);
        assertTrue(mManager.addDynamicShortcut(makeShortcut("s1")));

        setCaller(CALLING_PACKAGE_2, USER_10);
        assertTrue(mManager.addDynamicShortcut(makeShortcut("s1")));

        setCaller(CALLING_PACKAGE_3, USER_10);
        assertTrue(mManager.addDynamicShortcut(makeShortcut("s1")));

        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDeleteIntent(CALLING_PACKAGE_1, USER_0));

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDeleteIntent(CALLING_PACKAGE_2, USER_10));

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        mInjectedPackages.remove(CALLING_PACKAGE_1);
        mInjectedPackages.remove(CALLING_PACKAGE_3);

        mService.handleUnlockUser(USER_0);

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        mService.handleUnlockUser(USER_10);

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));
    }

    public void testHandlePackageUpdate() {
        // TODO: Make sure unshadow is called.
    }
}

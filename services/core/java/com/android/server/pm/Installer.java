/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageStats;
import android.os.Build;
import android.util.Slog;

import com.android.internal.os.InstallerConnection;
import com.android.internal.os.InstallerConnection.InstallerException;
import com.android.server.SystemService;

import dalvik.system.VMRuntime;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public final class Installer extends SystemService {
    private static final String TAG = "Installer";

    /* ***************************************************************************
     * IMPORTANT: These values are passed to native code. Keep them in sync with
     * frameworks/native/cmds/installd/installd.h
     * **************************************************************************/
    /** Application should be visible to everyone */
    public static final int DEXOPT_PUBLIC       = 1 << 1;
    /** Application wants to run in VM safe mode */
    public static final int DEXOPT_SAFEMODE     = 1 << 2;
    /** Application wants to allow debugging of its code */
    public static final int DEXOPT_DEBUGGABLE   = 1 << 3;
    /** The system boot has finished */
    public static final int DEXOPT_BOOTCOMPLETE = 1 << 4;
    /** Run the application with the JIT compiler */
    public static final int DEXOPT_USEJIT       = 1 << 5;

    /** @hide */
    @IntDef(flag = true, value = {
            FLAG_DE_STORAGE,
            FLAG_CE_STORAGE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StorageFlags {}

    public static final int FLAG_DE_STORAGE = 1 << 0;
    public static final int FLAG_CE_STORAGE = 1 << 1;

    public static final int FLAG_CLEAR_CACHE_ONLY = 1 << 2;
    public static final int FLAG_CLEAR_CODE_CACHE_ONLY = 1 << 3;

    private final InstallerConnection mInstaller;

    public Installer(Context context) {
        super(context);
        mInstaller = new InstallerConnection();
    }

    /**
     * Yell loudly if someone tries making future calls while holding a lock on
     * the given object.
     */
    public void setWarnIfHeld(Object warnIfHeld) {
        mInstaller.setWarnIfHeld(warnIfHeld);
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Waiting for installd to be ready.");
        mInstaller.waitForConnection();
    }

    public void createAppData(String uuid, String pkgname, int userid, int flags, int appid,
            String seinfo, int targetSdkVersion) throws InstallerException {
        mInstaller.execute("create_app_data", uuid, pkgname, userid, flags, appid, seinfo,
            targetSdkVersion);
    }

    public void restoreconAppData(String uuid, String pkgname, int userid, int flags, int appid,
            String seinfo) throws InstallerException {
        mInstaller.execute("restorecon_app_data", uuid, pkgname, userid, flags, appid,
                seinfo);
    }

    public void clearAppData(String uuid, String pkgname, int userid, int flags)
            throws InstallerException {
        mInstaller.execute("clear_app_data", uuid, pkgname, userid, flags);
    }

    public void destroyAppData(String uuid, String pkgname, int userid, int flags)
            throws InstallerException {
        mInstaller.execute("destroy_app_data", uuid, pkgname, userid, flags);
    }

    public void moveCompleteApp(String from_uuid, String to_uuid, String package_name,
            String data_app_name, int appid, String seinfo, int targetSdkVersion)
            throws InstallerException {
        mInstaller.execute("move_complete_app", from_uuid, to_uuid, package_name,
                data_app_name, appid, seinfo, targetSdkVersion);
    }

    public void getAppSize(String uuid, String pkgname, int userid, int flags, String apkPath,
            String libDirPath, String fwdLockApkPath, String asecPath, String[] instructionSets,
            PackageStats pStats) throws InstallerException {
        for (String instructionSet : instructionSets) {
            assertValidInstructionSet(instructionSet);
        }

        // TODO: Extend getSizeInfo to look at the full subdirectory tree,
        // not just the first level.
        // TODO: Extend getSizeInfo to look at *all* instrution sets, not
        // just the primary.
        final String rawRes = mInstaller.executeForResult("get_app_size", uuid, pkgname, userid,
                flags, apkPath, libDirPath, fwdLockApkPath, asecPath, instructionSets[0]);
        final String res[] = rawRes.split(" ");

        if ((res == null) || (res.length != 5)) {
            throw new InstallerException("Invalid size result: " + rawRes);
        }
        try {
            pStats.codeSize = Long.parseLong(res[1]);
            pStats.dataSize = Long.parseLong(res[2]);
            pStats.cacheSize = Long.parseLong(res[3]);
            pStats.externalCodeSize = Long.parseLong(res[4]);
        } catch (NumberFormatException e) {
            throw new InstallerException("Invalid size result: " + rawRes);
        }
    }

    public void dexopt(String apkPath, int uid, String instructionSet, int dexoptNeeded,
            int dexFlags, String volumeUuid, boolean useProfiles) throws InstallerException {
        assertValidInstructionSet(instructionSet);
        mInstaller.dexopt(apkPath, uid, instructionSet, dexoptNeeded, dexFlags,
                volumeUuid, useProfiles);
    }

    public void dexopt(String apkPath, int uid, String pkgName, String instructionSet,
            int dexoptNeeded, @Nullable String outputPath, int dexFlags,
            String volumeUuid, boolean useProfiles)
                    throws InstallerException {
        assertValidInstructionSet(instructionSet);
        mInstaller.dexopt(apkPath, uid, pkgName, instructionSet, dexoptNeeded,
                outputPath, dexFlags, volumeUuid, useProfiles);
    }

    public void idmap(String targetApkPath, String overlayApkPath, int uid)
            throws InstallerException {
        mInstaller.execute("idmap", targetApkPath, overlayApkPath, uid);
    }

    public void rmdex(String codePath, String instructionSet) throws InstallerException {
        assertValidInstructionSet(instructionSet);
        mInstaller.execute("rmdex", codePath, instructionSet);
    }

    public void rmPackageDir(String packageDir) throws InstallerException {
        mInstaller.execute("rmpackagedir", packageDir);
    }

    public void createUserConfig(int userid) throws InstallerException {
        mInstaller.execute("mkuserconfig", userid);
    }

    public void removeUserDataDirs(String uuid, int userid) throws InstallerException {
        mInstaller.execute("rmuser", uuid, userid);
    }

    public void markBootComplete(String instructionSet) throws InstallerException {
        assertValidInstructionSet(instructionSet);
        mInstaller.execute("markbootcomplete", instructionSet);
    }

    public void freeCache(String uuid, long freeStorageSize) throws InstallerException {
        mInstaller.execute("freecache", uuid, freeStorageSize);
    }

    public void moveFiles() throws InstallerException {
        mInstaller.execute("movefiles");
    }

    /**
     * Links the 32 bit native library directory in an application's data
     * directory to the real location for backward compatibility. Note that no
     * such symlink is created for 64 bit shared libraries.
     */
    public void linkNativeLibraryDirectory(String uuid, String dataPath, String nativeLibPath32,
            int userId) throws InstallerException {
        mInstaller.execute("linklib", uuid, dataPath, nativeLibPath32, userId);
    }

    public void createOatDir(String oatDir, String dexInstructionSet)
            throws InstallerException {
        mInstaller.execute("createoatdir", oatDir, dexInstructionSet);
    }

    public void linkFile(String relativePath, String fromBase, String toBase)
            throws InstallerException {
        mInstaller.execute("linkfile", relativePath, fromBase, toBase);
    }

    private static void assertValidInstructionSet(String instructionSet)
            throws InstallerException {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (VMRuntime.getInstructionSet(abi).equals(instructionSet)) {
                return;
            }
        }
        throw new InstallerException("Invalid instruction set: " + instructionSet);
    }
}

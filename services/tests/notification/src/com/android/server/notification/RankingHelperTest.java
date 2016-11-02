/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.server.notification;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.android.internal.util.FastXmlSerializer;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import android.app.Notification;
import android.content.Context;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Xml;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RankingHelperTest {
    @Mock NotificationUsageStats mUsageStats;
    @Mock RankingHandler handler;
    @Mock PackageManager mPm;

    private Notification mNotiGroupGSortA;
    private Notification mNotiGroupGSortB;
    private Notification mNotiNoGroup;
    private Notification mNotiNoGroup2;
    private Notification mNotiNoGroupSortA;
    private NotificationRecord mRecordGroupGSortA;
    private NotificationRecord mRecordGroupGSortB;
    private NotificationRecord mRecordNoGroup;
    private NotificationRecord mRecordNoGroup2;
    private NotificationRecord mRecordNoGroupSortA;
    private RankingHelper mHelper;
    private final String pkg = "com.android.server.notification";
    private final int uid = 0;
    private final String pkg2 = "pkg2";
    private final int uid2 = 1111111;

    private Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        UserHandle user = UserHandle.ALL;

        mHelper = new RankingHelper(getContext(), mPm, handler, mUsageStats,
                new String[] {ImportanceExtractor.class.getName()});

        mNotiGroupGSortA = new Notification.Builder(getContext())
                .setContentTitle("A")
                .setGroup("G")
                .setSortKey("A")
                .setWhen(1205)
                .build();
        mRecordGroupGSortA = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, 0, mNotiGroupGSortA, user), 
                getDefaultChannel());

        mNotiGroupGSortB = new Notification.Builder(getContext())
                .setContentTitle("B")
                .setGroup("G")
                .setSortKey("B")
                .setWhen(1200)
                .build();
        mRecordGroupGSortB = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, 0, mNotiGroupGSortB, user), 
                getDefaultChannel());

        mNotiNoGroup = new Notification.Builder(getContext())
                .setContentTitle("C")
                .setWhen(1201)
                .build();
        mRecordNoGroup = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, 0, mNotiNoGroup, user), 
                getDefaultChannel());

        mNotiNoGroup2 = new Notification.Builder(getContext())
                .setContentTitle("D")
                .setWhen(1202)
                .build();
        mRecordNoGroup2 = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, 0, mNotiNoGroup2, user), 
                getDefaultChannel());

        mNotiNoGroupSortA = new Notification.Builder(getContext())
                .setContentTitle("E")
                .setWhen(1201)
                .setSortKey("A")
                .build();
        mRecordNoGroupSortA = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, 0, mNotiNoGroupSortA, user), 
                getDefaultChannel());

        final ApplicationInfo legacy = new ApplicationInfo();
        legacy.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        final ApplicationInfo upgrade = new ApplicationInfo();
        upgrade.targetSdkVersion = Build.VERSION_CODES.N_MR1 + 1;
        try {
            when(mPm.getApplicationInfo(eq(pkg), anyInt())).thenReturn(legacy);
            when(mPm.getApplicationInfo(eq(pkg2), anyInt())).thenReturn(upgrade);
        } catch (PackageManager.NameNotFoundException e) {}
    }

    private NotificationChannel getDefaultChannel() {
        return new NotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID, "name",
                NotificationManager.IMPORTANCE_LOW);
    }

    private ByteArrayOutputStream writeXmlAndPurge(String pkg, int uid, String... channelIds)
            throws Exception {
        XmlSerializer serializer = new FastXmlSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        serializer.startTag(null, "ranking");
        mHelper.writeXml(serializer, false);
        serializer.endTag(null, "ranking");
        serializer.endDocument();
        serializer.flush();

        for (String channelId : channelIds) {
            mHelper.deleteNotificationChannel(pkg, uid, channelId);
        }
        return baos;
    }

    @Test
    public void testFindAfterRankingWithASplitGroup() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(3);
        notificationList.add(mRecordGroupGSortA);
        notificationList.add(mRecordGroupGSortB);
        notificationList.add(mRecordNoGroup);
        notificationList.add(mRecordNoGroupSortA);
        mHelper.sort(notificationList);
        assertTrue(mHelper.indexOf(notificationList, mRecordGroupGSortA) >= 0);
        assertTrue(mHelper.indexOf(notificationList, mRecordGroupGSortB) >= 0);
        assertTrue(mHelper.indexOf(notificationList, mRecordNoGroup) >= 0);
        assertTrue(mHelper.indexOf(notificationList, mRecordNoGroupSortA) >= 0);
    }

    @Test
    public void testSortShouldNotThrowWithPlainNotifications() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(2);
        notificationList.add(mRecordNoGroup);
        notificationList.add(mRecordNoGroup2);
        mHelper.sort(notificationList);
    }

    @Test
    public void testSortShouldNotThrowOneSorted() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(2);
        notificationList.add(mRecordNoGroup);
        notificationList.add(mRecordNoGroupSortA);
        mHelper.sort(notificationList);
    }

    @Test
    public void testSortShouldNotThrowOneNotification() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(1);
        notificationList.add(mRecordNoGroup);
        mHelper.sort(notificationList);
    }

    @Test
    public void testSortShouldNotThrowOneSortKey() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(1);
        notificationList.add(mRecordGroupGSortB);
        mHelper.sort(notificationList);
    }

    @Test
    public void testSortShouldNotThrowOnEmptyList() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>();
        mHelper.sort(notificationList);
    }

    @Test
    public void testChannelXml() throws Exception {
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_LOW);
        channel2.setRingtone(new Uri.Builder().scheme("test").build());
        channel2.setLights(true);
        channel2.setBypassDnd(true);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

        mHelper.createNotificationChannel(pkg, uid, channel1);
        mHelper.createNotificationChannel(pkg, uid, channel2);

        ByteArrayOutputStream baos = writeXmlAndPurge(pkg, uid, channel1.getId(), channel2.getId(),
                NotificationChannel.DEFAULT_CHANNEL_ID);

        mHelper.deleteNotificationChannel(pkg, uid, channel1.getId());
        mHelper.deleteNotificationChannel(pkg, uid, channel2.getId());
        mHelper.deleteNotificationChannel(pkg, uid, NotificationChannel.DEFAULT_CHANNEL_ID);

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false);

        assertEquals(channel1, mHelper.getNotificationChannel(pkg, uid, channel1.getId()));
        assertEquals(channel2, mHelper.getNotificationChannel(pkg, uid, channel2.getId()));
        assertNotNull(
                mHelper.getNotificationChannel(pkg, uid, NotificationChannel.DEFAULT_CHANNEL_ID));
    }

    @Test
    public void testChannelXml_defaultChannelLegacyApp_noUserSettings() throws Exception {
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_DEFAULT);

        mHelper.createNotificationChannel(pkg, uid, channel1);

        ByteArrayOutputStream baos = writeXmlAndPurge(pkg, uid, channel1.getId(),
                NotificationChannel.DEFAULT_CHANNEL_ID);

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false);

        final NotificationChannel updated =
                mHelper.getNotificationChannel(pkg, uid, NotificationChannel.DEFAULT_CHANNEL_ID);
        assertEquals(NotificationManager.IMPORTANCE_UNSPECIFIED, updated.getImportance());
        assertFalse(updated.canBypassDnd());
        assertEquals(NotificationManager.VISIBILITY_NO_OVERRIDE,updated.getLockscreenVisibility());
        assertEquals(0, updated.getUserLockedFields());
    }

    @Test
    public void testChannelXml_defaultChannelUpdatedApp() throws Exception {
        final ApplicationInfo updated = new ApplicationInfo();
        updated.targetSdkVersion = Build.VERSION_CODES.N_MR1 + 1;
        when(mPm.getApplicationInfo(anyString(), anyInt())).thenReturn(updated);

        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_MIN);

        mHelper.createNotificationChannel(pkg, uid, channel1);

        ByteArrayOutputStream baos = writeXmlAndPurge(pkg, uid, channel1.getId(),
                NotificationChannel.DEFAULT_CHANNEL_ID);

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false);

        assertEquals(NotificationManager.IMPORTANCE_LOW, mHelper.getNotificationChannel(
                pkg, uid, NotificationChannel.DEFAULT_CHANNEL_ID).getImportance());
    }

    @Test
    public void testChannelXml_upgradeCreateDefaultChannel() throws Exception {
        final String preupgradeXml = "<ranking version=\"1\">\n"
             + "<package name=\"" + pkg + "\" importance=\"" + NotificationManager.IMPORTANCE_HIGH
            + "\" priority=\"" + Notification.PRIORITY_MAX + "\" visibility=\""
            + Notification.VISIBILITY_SECRET + "\"" +" uid=\"" + uid + "\" />\n"
            + "<package name=\"" + pkg2 + "\" uid=\"" + uid2 + "\" visibility=\""
            + Notification.VISIBILITY_PRIVATE + "\" />\n"
            + "</ranking>";
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(preupgradeXml.getBytes())),
            null);
        parser.nextTag();
        mHelper.readXml(parser, false);

        final NotificationChannel updated1 =
            mHelper.getNotificationChannel(pkg, uid, NotificationChannel.DEFAULT_CHANNEL_ID);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, updated1.getImportance());
        assertTrue(updated1.canBypassDnd());
        assertEquals(Notification.VISIBILITY_SECRET, updated1.getLockscreenVisibility());
        assertEquals(NotificationChannel.USER_LOCKED_IMPORTANCE
            | NotificationChannel.USER_LOCKED_PRIORITY
            | NotificationChannel.USER_LOCKED_VISIBILITY, updated1.getUserLockedFields());

        final NotificationChannel updated2 =
            mHelper.getNotificationChannel(pkg2, uid2, NotificationChannel.DEFAULT_CHANNEL_ID);
        // clamped
        assertEquals(NotificationManager.IMPORTANCE_LOW, updated2.getImportance());
        assertFalse(updated2.canBypassDnd());
        assertEquals(Notification.VISIBILITY_PRIVATE, updated2.getLockscreenVisibility());
        assertEquals(NotificationChannel.USER_LOCKED_VISIBILITY, updated2.getUserLockedFields());
    }

    @Test
    public void testUpdate_userLockedImportance() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_LOW);
        channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);

        mHelper.createNotificationChannel(pkg, uid, channel);

        // same id, try to update
        final NotificationChannel channel2 =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);

        mHelper.updateNotificationChannelFromRanker(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId()));
    }

    @Test
    public void testUpdate_userLockedVisibility() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_LOW);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.lockFields(NotificationChannel.USER_LOCKED_VISIBILITY);

        mHelper.createNotificationChannel(pkg, uid, channel);

        // same id, try to update
        final NotificationChannel channel2 =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        mHelper.updateNotificationChannelFromRanker(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId()));
    }

    @Test
    public void testUpdate_userLockedVibration() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_LOW);
        channel.setLights(false);
        channel.lockFields(NotificationChannel.USER_LOCKED_VIBRATION);

        mHelper.createNotificationChannel(pkg, uid, channel);

        // same id, try to update
        final NotificationChannel channel2 =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setVibration(true);

        mHelper.updateNotificationChannelFromRanker(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId()));
    }

    @Test
    public void testUpdate_userLockedLights() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_LOW);
        channel.setLights(false);
        channel.lockFields(NotificationChannel.USER_LOCKED_LIGHTS);

        mHelper.createNotificationChannel(pkg, uid, channel);

        // same id, try to update
        final NotificationChannel channel2 =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setLights(true);

        mHelper.updateNotificationChannelFromRanker(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId()));
    }

    @Test
    public void testUpdate_userLockedPriority() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_LOW);
        channel.setBypassDnd(true);
        channel.lockFields(NotificationChannel.USER_LOCKED_PRIORITY);

        mHelper.createNotificationChannel(pkg, uid, channel);

        // same id, try to update all fields
        final NotificationChannel channel2 =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setBypassDnd(false);

        mHelper.updateNotificationChannelFromRanker(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId()));
    }

    @Test
    public void testUpdate_userLockedRingtone() throws Exception {
        // all fields locked by user
        final NotificationChannel channel =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_LOW);
        channel.setRingtone(new Uri.Builder().scheme("test").build());
        channel.lockFields(NotificationChannel.USER_LOCKED_RINGTONE);

        mHelper.createNotificationChannel(pkg, uid, channel);

        // same id, try to update all fields
        final NotificationChannel channel2 =
            new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setRingtone(new Uri.Builder().scheme("test2").build());

        mHelper.updateNotificationChannelFromRanker(pkg, uid, channel2);

        // no fields should be changed
        assertEquals(channel, mHelper.getNotificationChannel(pkg, uid, channel.getId()));
    }

    @Test
    public void testUpdate() throws Exception {
        // no fields locked by user
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_LOW);
        channel.setRingtone(new Uri.Builder().scheme("test").build());
        channel.setLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

        mHelper.createNotificationChannel(pkg, uid, channel);

        // same id, try to update all fields
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setRingtone(new Uri.Builder().scheme("test2").build());
        channel2.setLights(false);
        channel2.setBypassDnd(false);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        mHelper.updateNotificationChannel(pkg, uid, channel2);

        // all fields should be changed
        assertEquals(channel2, mHelper.getNotificationChannel(pkg, uid, channel.getId()));
    }

    @Test
    public void testGetChannelWithFallback() throws Exception {
        NotificationChannel channel =
                mHelper.getNotificationChannelWithFallback(pkg, uid, "garbage");
        assertEquals(NotificationChannel.DEFAULT_CHANNEL_ID, channel.getId());
    }
}

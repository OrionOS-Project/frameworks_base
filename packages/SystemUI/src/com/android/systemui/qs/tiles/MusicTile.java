/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2013 The SlimRoms Project
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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.media.RemoteController;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Handler;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import javax.inject.Inject;

/** Quick settings tile: Music **/
public class MusicTile extends QSTileImpl<BooleanState> {

    private final String TAG = "MusicTile";
    private final boolean DBG = false;
    private final AudioManager mAudioManager;
    public static final String TILE_SPEC = "music";

    private boolean mActive = false;
    private boolean mClientIdLost = true;
    private boolean mIsLoading = false;
    private String mLastPlayedTrack = null;
    private long mLastActiveTime = 0;

    private Metadata mMetadata = new Metadata();
    private Handler mHandler = new Handler();
    private RemoteController mRemoteController;
    private IAudioService mAudioService = null;

    private int mTaps = 0;

    private static final long LAST_ACTIVE_TIMEOUT = 1000 * 60 * 60; // 1 hour

    @Inject
    public MusicTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mRemoteController = new RemoteController(mContext, mRCClientUpdateListener);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mAudioManager.registerRemoteController(mRemoteController);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (mActive) {
            checkDoubleClick();
        } else {
            sendMediaButtonClick(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }

        refreshState();
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        sendMediaButtonClick(KeyEvent.KEYCODE_MEDIA_NEXT);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_music_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.DERPFEST;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mIsLoading) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_media_play);
            state.label = mContext.getString(R.string.quick_settings_music_loading);
            state.state = Tile.STATE_INACTIVE;
            return;
        }

        if (mActive) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_media_pause);
            state.label = mMetadata.trackTitle != null
                ? mMetadata.trackTitle 
                : mContext.getString(R.string.quick_settings_music_pause);
            state.state = Tile.STATE_ACTIVE;
            mLastPlayedTrack = mMetadata.trackTitle;
            mLastActiveTime = System.currentTimeMillis();
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_media_play);
            
            // Show last played track if within timeout
            if (mLastPlayedTrack != null && 
                (System.currentTimeMillis() - mLastActiveTime) < LAST_ACTIVE_TIMEOUT) {
                state.label = mContext.getString(
                    R.string.quick_settings_music_last_played, mLastPlayedTrack);
            } else {
                state.label = mContext.getString(R.string.quick_settings_music_play);
            }
            state.state = Tile.STATE_INACTIVE;
        }

        // Add secondary label based on state
        if (mClientIdLost) {
            state.secondaryLabel = mContext.getString(R.string.quick_settings_music_no_player);
        } else if (mIsLoading) {
            state.secondaryLabel = mContext.getString(R.string.quick_settings_music_loading);
        } else if (mActive && mMetadata.artist != null) {
            state.secondaryLabel = mMetadata.artist;
        } else {
            state.secondaryLabel = "";
        }
    }

    private void playbackStateUpdate(int state) {
        boolean active;
        switch (state) {
            case RemoteControlClient.PLAYSTATE_PLAYING:
                active = true;
                mIsLoading = false;
                break;
            case RemoteControlClient.PLAYSTATE_BUFFERING:
            case RemoteControlClient.PLAYSTATE_SKIPPING_FORWARDS:
            case RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS:
                mIsLoading = true;
                active = false;
                break;
            case RemoteControlClient.PLAYSTATE_ERROR:
            case RemoteControlClient.PLAYSTATE_PAUSED:
            default:
                active = false;
                mIsLoading = false;
                break;
        }
        if (active != mActive || mIsLoading) {
            mActive = active;
            refreshState();
        }
    }

    private void checkDoubleClick() {
        mHandler.removeCallbacks(checkDouble);
        if (mTaps > 0) {
            // Music app
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_APP_MUSIC);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mTaps = 0;
        } else {
            mTaps += 1;
            mHandler.postDelayed(checkDouble,
                    ViewConfiguration.getDoubleTapTimeout());
        }
    }

    private void sendMediaButtonClick(int keyCode) {
        if (!mClientIdLost) {
            mRemoteController.sendMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            mRemoteController.sendMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        } else {
            long eventTime = SystemClock.uptimeMillis();
            KeyEvent key = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
            dispatchMediaKeyWithWakeLockToAudioService(key);
            dispatchMediaKeyWithWakeLockToAudioService(
                KeyEvent.changeAction(key, KeyEvent.ACTION_UP));
        }
    }

    private void dispatchMediaKeyWithWakeLockToAudioService(KeyEvent event) {
        MediaSessionLegacyHelper.getHelper(mContext).sendMediaButtonEvent(event, true);
    }

    private IAudioService getAudioService() {
        if (mAudioService == null) {
            mAudioService = IAudioService.Stub.asInterface(
                    ServiceManager.checkService(Context.AUDIO_SERVICE));
            if (mAudioService == null) {
                if (DBG) Log.w(TAG, "Unable to find IAudioService interface.");
            }
        }
        return mAudioService;
    }

    final Runnable checkDouble = new Runnable () {
        public void run() {
            mTaps = 0;
            sendMediaButtonClick(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    };

    private RemoteController.OnClientUpdateListener mRCClientUpdateListener =
            new RemoteController.OnClientUpdateListener() {

        private String mCurrentTrack = null;

        @Override
        public void onClientChange(boolean clearing) {
            if (clearing) {
                mMetadata.clear();
                mCurrentTrack = null;
                mActive = false;
                mClientIdLost = true;
                mIsLoading = false;
                refreshState();
            }
        }

        @Override
        public void onClientPlaybackStateUpdate(int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            mClientIdLost = false;
            playbackStateUpdate(state);
        }

        @Override
        public void onClientPlaybackStateUpdate(int state) {
            mClientIdLost = false;
            playbackStateUpdate(state);
        }

//        @Override
//        public void onClientFolderInfoBrowsedPlayer(String stringUri) { }

//        @Override
//        public void onClientUpdateNowPlayingEntries(long[] playList) { }

//        @Override
//        public void onClientNowPlayingContentChange() { }

//        @Override
//        public void onClientPlayItemResponse(boolean success) { }

        @Override
        public void onClientMetadataUpdate(RemoteController.MetadataEditor data) {
            mMetadata.trackTitle = data.getString(MediaMetadataRetriever.METADATA_KEY_TITLE,
                    mMetadata.trackTitle);
            mMetadata.artist = data.getString(MediaMetadataRetriever.METADATA_KEY_ARTIST,
                    mMetadata.artist);
            mClientIdLost = false;
            if (mMetadata.trackTitle != null
                    && !mMetadata.trackTitle.equals(mCurrentTrack)) {
                mCurrentTrack = mMetadata.trackTitle;
                refreshState();
            }
        }

        @Override
        public void onClientTransportControlUpdate(int transportControlFlags) {
        }
    };

    class Metadata {
        private String trackTitle;
        private String artist;

        public void clear() {
            trackTitle = null;
            artist = null;
        }
    }

}

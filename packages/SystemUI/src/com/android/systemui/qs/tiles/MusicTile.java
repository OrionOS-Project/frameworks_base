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
import android.service.quicksettings.Tile;
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

import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.session.MediaSession;

import java.util.List;

/** Quick settings tile: Music **/
public class MusicTile extends QSTileImpl<BooleanState> {

    private final String TAG = "MusicTile";
    private final boolean DBG = false;
    private final AudioManager mAudioManager;
    private final MediaSessionManager mMediaSessionManager;
    private MediaController mMediaController;
    public static final String TILE_SPEC = "music";

    private boolean mActive = false;
    private boolean mClientIdLost = true;
    private boolean mIsLoading = false;
    private String mLastPlayedTrack = null;
    private long mLastActiveTime = 0;

    private Metadata mMetadata = new Metadata();
    private Handler mHandler = new Handler();

    private int mTaps = 0;

    private static final long LAST_ACTIVE_TIMEOUT = 1000 * 60 * 60; // 1 hour

    private final MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state == null) return;
            mClientIdLost = false;
            updatePlaybackState(state.getState());
        }

        @Override
        public void onMetadataChanged(android.media.MediaMetadata metadata) {
            if (metadata == null) return;
            mMetadata.trackTitle = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
            mMetadata.artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            mClientIdLost = false;
            refreshState();
        }

        @Override
        public void onSessionDestroyed() {
            updateMediaController(null);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override
        public void onActiveSessionsChanged(List<MediaController> controllers) {
            updateMediaController(controllers != null && !controllers.isEmpty() 
                ? controllers.get(0) : null);
        }
    };

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
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (listening) {
            try {
                mMediaSessionManager.addOnActiveSessionsChangedListener(mSessionListener, null);
                List<MediaController> controllers = mMediaSessionManager.getActiveSessions(null);
                updateMediaController(controllers != null && !controllers.isEmpty() 
                    ? controllers.get(0) : null);
            } catch (SecurityException e) {
                Log.d(TAG, "Security exception on media controller", e);
            }
        } else {
            mMediaSessionManager.removeOnActiveSessionsChangedListener(mSessionListener);
            updateMediaController(null);
        }
    }

    private void updateMediaController(MediaController controller) {
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mMediaCallback);
        }
        mMediaController = controller;
        if (mMediaController != null) {
            mMediaController.registerCallback(mMediaCallback);
            updatePlaybackState(mMediaController.getPlaybackState() != null 
                ? mMediaController.getPlaybackState().getState() 
                : PlaybackState.STATE_NONE);
            android.media.MediaMetadata metadata = mMediaController.getMetadata();
            if (metadata != null) {
                mMetadata.trackTitle = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
                mMetadata.artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            }
        } else {
            mMetadata.clear();
            mActive = false;
            mClientIdLost = true;
            mIsLoading = false;
        }
        refreshState();
    }

    private void updatePlaybackState(int state) {
        boolean active;
        switch (state) {
            case PlaybackState.STATE_PLAYING:
                active = true;
                mIsLoading = false;
                break;
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                mIsLoading = true;
                active = false;
                break;
            case PlaybackState.STATE_ERROR:
            case PlaybackState.STATE_PAUSED:
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

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (mMediaController == null) return;
        
        if (mActive) {
            checkDoubleClick();
        } else {
            mMediaController.getTransportControls().play();
        }
        refreshState();
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        if (mMediaController != null) {
            mMediaController.getTransportControls().skipToNext();
        }
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

    private void checkDoubleClick() {
        mHandler.removeCallbacks(checkDouble);
        if (mTaps > 0) {
            // Music app
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_APP_MUSIC);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
            mTaps = 0;
        } else {
            mTaps += 1;
            mHandler.postDelayed(checkDouble,
                    ViewConfiguration.getDoubleTapTimeout());
        }
    }

    final Runnable checkDouble = new Runnable () {
        public void run() {
            mTaps = 0;
            if (mMediaController != null) {
                mMediaController.getTransportControls().pause();
            }
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

/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.shell;

import static com.android.shell.BugreportProgressService.EXTRA_BUGREPORT;
import static com.android.shell.BugreportProgressService.EXTRA_ORIGINAL_INTENT;
import static com.android.shell.BugreportProgressService.INTENT_BUGREPORT_FINISHED;
import static com.android.shell.BugreportProgressService.getFileExtra;

import java.io.File;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.FileUtils;
import android.text.format.DateUtils;

/**
 * Receiver that handles finished bugreports, usually by attaching them to an
 * {@link Intent#ACTION_SEND_MULTIPLE}.
 */
public class BugreportReceiver extends BroadcastReceiver {

    /**
     * Always keep the newest 8 bugreport files; 4 reports and 4 screenshots are
     * roughly 17MB of disk space.
     */
    private static final int MIN_KEEP_COUNT = 8;

    /**
     * Always keep bugreports taken in the last week.
     */
    private static final long MIN_KEEP_AGE = DateUtils.WEEK_IN_MILLIS;

    @Override
    public void onReceive(Context context, Intent intent) {
        // Clean up older bugreports in background
        cleanupOldFiles(intent);

        // Delegate intent handling to service.
        Intent serviceIntent = new Intent(context, BugreportProgressService.class);
        serviceIntent.putExtra(EXTRA_ORIGINAL_INTENT, intent);
        context.startService(serviceIntent);
    }

    private void cleanupOldFiles(Intent intent) {
        if (!INTENT_BUGREPORT_FINISHED.equals(intent.getAction())) {
            return;
        }
        final File bugreportFile = getFileExtra(intent, EXTRA_BUGREPORT);
        final PendingResult result = goAsync();
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                FileUtils.deleteOlderFiles(
                        bugreportFile.getParentFile(), MIN_KEEP_COUNT, MIN_KEEP_AGE);
                result.finish();
                return null;
            }
        }.execute();
    }
}

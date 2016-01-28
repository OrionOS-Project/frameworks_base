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
 * limitations under the License
 */

package android.location;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidParameterException;

/**
 * A class implementing a container for data associated with a navigation message event.
 * Events are delivered to registered instances of {@link Callback}.
 */
public final class GpsNavigationMessageEvent implements Parcelable {
    /** The status of GPS measurements event. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATUS_NOT_SUPPORTED, STATUS_READY, STATUS_GPS_LOCATION_DISABLED})
    public @interface GpsNavigationMessageStatus {}

    /**
     * The system does not support tracking of GPS Navigation Messages. This status will not change
     * in the future.
     */
    public static final int STATUS_NOT_SUPPORTED = 0;

    /**
     * GPS Navigation Messages are successfully being tracked, it will receive updates once they are
     * available.
     */
    public static final int STATUS_READY = 1;

    /**
     * GPS provider or Location is disabled, updated will not be received until they are enabled.
     */
    public static final int STATUS_GPS_LOCATION_DISABLED = 2;

    private final GpsNavigationMessage mNavigationMessage;

    /**
     * Used for receiving GPS satellite Navigation Messages from the GPS engine.
     * You can implement this interface and call
     * {@link LocationManager#registerGpsNavigationMessageCallback}.
     */
    public static abstract class Callback {

        /**
         * Returns the latest collected GPS Navigation Message.
         */
        public void onGpsNavigationMessageReceived(GpsNavigationMessageEvent event) {}

        /**
         * Returns the latest status of the GPS Navigation Messages sub-system.
         */
        public void onStatusChanged(@GpsNavigationMessageStatus int status) {}
    }

    public GpsNavigationMessageEvent(GpsNavigationMessage message) {
        if (message == null) {
            throw new InvalidParameterException("Parameter 'message' must not be null.");
        }
        mNavigationMessage = message;
    }

    @NonNull
    public GpsNavigationMessage getNavigationMessage() {
        return mNavigationMessage;
    }

    public static final Creator<GpsNavigationMessageEvent> CREATOR =
            new Creator<GpsNavigationMessageEvent>() {
                @Override
                public GpsNavigationMessageEvent createFromParcel(Parcel in) {
                    ClassLoader classLoader = getClass().getClassLoader();
                    GpsNavigationMessage navigationMessage = in.readParcelable(classLoader);
                    return new GpsNavigationMessageEvent(navigationMessage);
                }

                @Override
                public GpsNavigationMessageEvent[] newArray(int size) {
                    return new GpsNavigationMessageEvent[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mNavigationMessage, flags);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("[ GpsNavigationMessageEvent:\n\n");
        builder.append(mNavigationMessage.toString());
        builder.append("\n]");
        return builder.toString();
    }
}

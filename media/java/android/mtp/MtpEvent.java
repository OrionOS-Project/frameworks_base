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

package android.mtp;

/**
 * This class encapsulates information about a MTP event.
 * Event constants are defined by the USB-IF MTP specification.
 */
public class MtpEvent {
    private int mEventCode = MtpConstants.EVENT_UNDEFINED;

    /**
     * Returns event code of MTP event.
     * See the USB-IF MTP specification for the details of event constants.
     * @return event code
     */
    public int getEventCode() { return mEventCode; }
}

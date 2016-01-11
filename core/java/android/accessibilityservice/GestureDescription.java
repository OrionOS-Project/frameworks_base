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

package android.accessibilityservice;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Accessibility services with the
 * {@link android.R.styleable#AccessibilityService_canPerformGestures} property can dispatch
 * gestures. This class describes those gestures. Gestures are made up of one or more strokes.
 * Gestures are immutable; use the {@code create} methods to get common gesture, or a
 * {@code Builder} to create a new one.
 * <p>
 * Spatial dimensions throughout are in screen pixels. Time is measured in milliseconds.
 */
public final class GestureDescription {
    /** Gestures may contain no more than this many strokes */
    public static final int MAX_STROKE_COUNT = 10;

    /**
     * Upper bound on total gesture duration. Nearly all gestures will be much shorter.
     */
    public static final long MAX_GESTURE_DURATION_MS = 60 * 1000;

    private final List<StrokeDescription> mStrokes = new ArrayList<>();
    private final float[] mTempPos = new float[2];

    /**
     * Create a description of a click gesture
     *
     * @param x The x coordinate to click. Must not be negative.
     * @param y The y coordinate to click. Must not be negative.
     *
     * @return A description of a click at (x, y)
     */
    public static GestureDescription createClick(@IntRange(from = 0) int x,
            @IntRange(from = 0) int y) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        clickPath.lineTo(x + 1, y);
        return new GestureDescription(
                new StrokeDescription(clickPath, 0, ViewConfiguration.getTapTimeout()));
    }

    /**
     * Create a description of a long click gesture
     *
     * @param x The x coordinate to click. Must not be negative.
     * @param y The y coordinate to click. Must not be negative.
     *
     * @return A description of a click at (x, y)
     */
    public static GestureDescription createLongClick(@IntRange(from = 0) int x,
            @IntRange(from = 0) int y) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        clickPath.lineTo(x + 1, y);
        int longPressTime = ViewConfiguration.getLongPressTimeout();
        return new GestureDescription(
                new StrokeDescription(clickPath, 0, longPressTime + (longPressTime / 2)));
    }

    /**
     * Create a description of a swipe gesture
     *
     * @param startX The x coordinate of the starting point. Must not be negative.
     * @param startY The y coordinate of the starting point. Must not be negative.
     * @param endX The x coordinate of the ending point. Must not be negative.
     * @param endY The y coordinate of the ending point. Must not be negative.
     * @param duration The time, in milliseconds, to complete the gesture. Must not be negative.
     *
     * @return A description of a swipe from ({@code startX}, {@code startY}) to
     * ({@code endX}, {@code endY}) that takes {@code duration} milliseconds. Returns {@code null}
     * if the path specified for the swipe is invalid.
     */
    public static GestureDescription createSwipe(@IntRange(from = 0) int startX,
            @IntRange(from = 0) int startY,
            @IntRange(from = 0) int endX,
            @IntRange(from = 0) int endY,
            @IntRange(from = 0, to = MAX_GESTURE_DURATION_MS) long duration) {
        Path swipePath = new Path();
        swipePath.moveTo(startX, startY);
        swipePath.lineTo(endX, endY);
        return new GestureDescription(new StrokeDescription(swipePath, 0, duration));
    }

    /**
     * Create a description for a pinch (or zoom) gesture.
     *
     * @param centerX The x coordinate of the center of the pinch. Must not be negative.
     * @param centerY The y coordinate of the center of the pinch. Must not be negative.
     * @param startSpacing The spacing of the touch points at the beginning of the gesture. Must not
     * be negative.
     * @param endSpacing The spacing of the touch points at the end of the gesture. Must not be
     * negative.
     * @param orientation The angle, in degrees, of the gesture. 0 represents a horizontal pinch
     * @param duration The time, in milliseconds, to complete the gesture. Must not be negative.
     *
     * @return A description of a pinch centered at ({code centerX}, {@code centerY}) that starts
     * with the touch points spaced by {@code startSpacing} and ends with them spaced by
     * {@code endSpacing} that lasts {@code duration} ms. Returns {@code null} if either path
     * specified for the pinch is invalid.
     */
    public static GestureDescription createPinch(@IntRange(from = 0) int centerX,
            @IntRange(from = 0) int centerY,
            @IntRange(from = 0) int startSpacing,
            @IntRange(from = 0) int endSpacing,
            float orientation,
            @IntRange(from = 0, to = MAX_GESTURE_DURATION_MS) long duration) {
        if ((startSpacing < 0) || (endSpacing < 0)) {
            throw new IllegalArgumentException("Pinch spacing cannot be negative");
        }
        float[] startPoint1 = new float[2];
        float[] endPoint1 = new float[2];
        float[] startPoint2 = new float[2];
        float[] endPoint2 = new float[2];

        /* Build points for a horizontal gesture centered at the origin */
        startPoint1[0] = startSpacing / 2;
        startPoint1[1] = 0;
        endPoint1[0] = endSpacing / 2;
        endPoint1[1] = 0;
        startPoint2[0] = -startSpacing / 2;
        startPoint2[1] = 0;
        endPoint2[0] = -endSpacing / 2;
        endPoint2[1] = 0;

        /* Rotate and translate the points */
        Matrix matrix = new Matrix();
        matrix.setRotate(orientation);
        matrix.postTranslate(centerX, centerY);
        matrix.mapPoints(startPoint1);
        matrix.mapPoints(endPoint1);
        matrix.mapPoints(startPoint2);
        matrix.mapPoints(endPoint2);

        Path path1 = new Path();
        path1.moveTo(startPoint1[0], startPoint1[1]);
        path1.lineTo(endPoint1[0], endPoint1[1]);
        Path path2 = new Path();
        path2.moveTo(startPoint2[0], startPoint2[1]);
        path2.lineTo(endPoint2[0], endPoint2[1]);

        return new GestureDescription(Arrays.asList(
                new StrokeDescription(path1, 0, duration),
                new StrokeDescription(path2, 0, duration)));
    }

    private GestureDescription() {}

    private GestureDescription(List<StrokeDescription> strokes) {
        mStrokes.addAll(strokes);
    }

    private GestureDescription(StrokeDescription stroke) {
        mStrokes.add(stroke);
    }

    /**
     * Get the number of stroke in the gesture.
     *
     * @return the number of strokes in this gesture
     */
    public int getStrokeCount() {
        return mStrokes.size();
    }

    /**
     * Read a stroke from the gesture
     *
     * @param index the index of the stroke
     *
     * @return A description of the stroke.
     */
    public StrokeDescription getStroke(@IntRange(from = 0) int index) {
        return mStrokes.get(index);
    }

    /**
     * Return the smallest key point (where a path starts or ends) that is at least a specified
     * offset
     * @param offset the minimum start time
     * @return The next key time that is at least the offset or -1 if one can't be found
     */
    private long getNextKeyPointAtLeast(long offset) {
        long nextKeyPoint = Long.MAX_VALUE;
        for (int i = 0; i < mStrokes.size(); i++) {
            long thisStartTime = mStrokes.get(i).mStartTime;
            if ((thisStartTime < nextKeyPoint) && (thisStartTime >= offset)) {
                nextKeyPoint = thisStartTime;
            }
            long thisEndTime = mStrokes.get(i).mEndTime;
            if ((thisEndTime < nextKeyPoint) && (thisEndTime >= offset)) {
                nextKeyPoint = thisEndTime;
            }
        }
        return (nextKeyPoint == Long.MAX_VALUE) ? -1L : nextKeyPoint;
    }

    /**
     * Get the points that correspond to a particular moment in time.
     * @param time The time of interest
     * @param touchPoints An array to hold the current touch points. Must be preallocated to at
     * least the number of paths in the gesture to prevent going out of bounds
     * @return The number of points found, and thus the number of elements set in each array
     */
    private int getPointsForTime(long time, TouchPoint[] touchPoints) {
        int numPointsFound = 0;
        for (int i = 0; i < mStrokes.size(); i++) {
            StrokeDescription strokeDescription = mStrokes.get(i);
            if (strokeDescription.hasPointForTime(time)) {
                touchPoints[numPointsFound].mPathIndex = i;
                touchPoints[numPointsFound].mIsStartOfPath = (time == strokeDescription.mStartTime);
                touchPoints[numPointsFound].mIsEndOfPath = (time == strokeDescription.mEndTime);
                strokeDescription.getPosForTime(time, mTempPos);
                touchPoints[numPointsFound].mX = Math.round(mTempPos[0]);
                touchPoints[numPointsFound].mY = Math.round(mTempPos[1]);
                numPointsFound++;
            }
        }
        return numPointsFound;
    }

    // Total duration assumes that the gesture starts at 0; waiting around to start a gesture
    // counts against total duration
    private static long getTotalDuration(List<StrokeDescription> paths) {
        long latestEnd = Long.MIN_VALUE;
        for (int i = 0; i < paths.size(); i++) {
            StrokeDescription path = paths.get(i);
            latestEnd = Math.max(latestEnd, path.mEndTime);
        }
        return Math.max(latestEnd, 0);
    }

    /**
     * Builder for a {@code GestureDescription}
     */
    public static class Builder {

        private final List<StrokeDescription> mStrokes = new ArrayList<>();

        /**
         * Add a stroke to the gesture description. Up to {@code MAX_STROKE_COUNT} paths may be
         * added to a gesture, and the total gesture duration (earliest path start time to latest path
         * end time) may not exceed {@code MAX_GESTURE_DURATION_MS}.
         *
         * @param strokeDescription the stroke to add.
         *
         * @return this
         */
        public Builder addStroke(@NonNull StrokeDescription strokeDescription) {
            if (mStrokes.size() >= MAX_STROKE_COUNT) {
                throw new RuntimeException("Attempting to add too many strokes to a gesture");
            }

            mStrokes.add(strokeDescription);

            if (getTotalDuration(mStrokes) > MAX_GESTURE_DURATION_MS) {
                mStrokes.remove(strokeDescription);
                throw new RuntimeException("Gesture would exceed maximum duration with new stroke");
            }
            return this;
        }

        public GestureDescription build() {
            if (mStrokes.size() == 0) {
                throw new RuntimeException("Gestures must have at least one stroke");
            }
            return new GestureDescription(mStrokes);
        }
    }

    /**
     * Immutable description of stroke that can be part of a gesture.
     */
    public static class StrokeDescription {
        Path mPath;
        long mStartTime;
        long mEndTime;
        private float mTimeToLengthConversion;
        private PathMeasure mPathMeasure;

        /**
         * @param path The path to follow. Must have exactly one contour, and that contour must
         * have nonzero length. The bounds of the path must not be negative.
         * @param startTime The time, in milliseconds, from the time the gesture starts to the
         * time the stroke should start. Must not be negative.
         * @param duration The duration, in milliseconds, the stroke takes to traverse the path.
         * Must not be negative.
         */
        public StrokeDescription(@NonNull Path path,
                @IntRange(from = 0, to = MAX_GESTURE_DURATION_MS) long startTime,
                @IntRange(from = 0, to = MAX_GESTURE_DURATION_MS) long duration) {
            if (duration <= 0) {
                throw new IllegalArgumentException("Duration must be positive");
            }
            if (startTime < 0) {
                throw new IllegalArgumentException("Start time must not be negative");
            }
            RectF bounds = new RectF();
            path.computeBounds(bounds, false /* unused */);
            if ((bounds.bottom < 0) || (bounds.top < 0) || (bounds.right < 0)
                    || (bounds.left < 0)) {
                throw new IllegalArgumentException("Path bounds must not be negative");
            }
            mPath = new Path(path);
            mPathMeasure = new PathMeasure(path, false);
            if (mPathMeasure.getLength() == 0) {
                throw new IllegalArgumentException("Path has zero length");
            }
            if (mPathMeasure.nextContour()) {
                throw new IllegalArgumentException("Path has more than one contour");
            }
            /*
             * Calling nextContour has moved mPathMeasure off the first contour, which is the only
             * one we care about. Set the path again to go back to the first contour.
             */
            mPathMeasure.setPath(path, false);
            mStartTime = startTime;
            mEndTime = startTime + duration;
            if (duration > 0) {
                mTimeToLengthConversion = getLength() / duration;
            }
        }

        /**
         * Retrieve a copy of the path for this stroke
         *
         * @return A copy of the path
         */
        public Path getPath() {
            return new Path(mPath);
        }

        /**
         * Get the stroke's start time
         *
         * @return the start time for this stroke.
         */
        public long getStartTime() {
            return mStartTime;
        }

        /**
         * Get the stroke's duration
         *
         * @return the duration for this stroke
         */
        public long getDuration() {
            return mEndTime - mStartTime;
        }

        float getLength() {
            return mPathMeasure.getLength();
        }

        /* Assumes hasPointForTime returns true */
        boolean getPosForTime(long time, float[] pos) {
            if (time == mEndTime) {
                // Close to the end time, roundoff can be a problem
                return mPathMeasure.getPosTan(getLength(), pos, null);
            }
            float length = mTimeToLengthConversion * ((float) (time - mStartTime));
            return mPathMeasure.getPosTan(length, pos, null);
        }

        boolean hasPointForTime(long time) {
            return ((time >= mStartTime) && (time <= mEndTime));
        }
    }

    private static class TouchPoint {
        int mPathIndex;
        boolean mIsStartOfPath;
        boolean mIsEndOfPath;
        float mX;
        float mY;

        void copyFrom(TouchPoint other) {
            mPathIndex = other.mPathIndex;
            mIsStartOfPath = other.mIsStartOfPath;
            mIsEndOfPath = other.mIsEndOfPath;
            mX = other.mX;
            mY = other.mY;
        }
    }

    /**
     * Class to convert a GestureDescription to a series of MotionEvents.
     */
    static class MotionEventGenerator {
        /**
         * Constants used to initialize all MotionEvents
         */
        private static final int EVENT_META_STATE = 0;
        private static final int EVENT_BUTTON_STATE = 0;
        private static final int EVENT_DEVICE_ID = 0;
        private static final int EVENT_EDGE_FLAGS = 0;
        private static final int EVENT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN;
        private static final int EVENT_FLAGS = 0;
        private static final float EVENT_X_PRECISION = 1;
        private static final float EVENT_Y_PRECISION = 1;

        /* Lazily-created scratch memory for processing touches */
        private static TouchPoint[] sCurrentTouchPoints;
        private static TouchPoint[] sLastTouchPoints;
        private static PointerCoords[] sPointerCoords;
        private static PointerProperties[] sPointerProps;

        static List<MotionEvent> getMotionEventsFromGestureDescription(
                GestureDescription description, int sampleTimeMs) {
            final List<MotionEvent> motionEvents = new ArrayList<>();

            // Point data at each time we generate an event for
            final TouchPoint[] currentTouchPoints =
                    getCurrentTouchPoints(description.getStrokeCount());
            // Point data sent in last touch event
            int lastTouchPointSize = 0;
            final TouchPoint[] lastTouchPoints =
                    getLastTouchPoints(description.getStrokeCount());

            /* Loop through each time slice where there are touch points */
            long timeSinceGestureStart = 0;
            long nextKeyPointTime = description.getNextKeyPointAtLeast(timeSinceGestureStart);
            while (nextKeyPointTime >= 0) {
                timeSinceGestureStart = (lastTouchPointSize == 0) ? nextKeyPointTime
                        : Math.min(nextKeyPointTime, timeSinceGestureStart + sampleTimeMs);
                int currentTouchPointSize = description.getPointsForTime(timeSinceGestureStart,
                        currentTouchPoints);

                appendMoveEventIfNeeded(motionEvents, lastTouchPoints, lastTouchPointSize,
                        currentTouchPoints, currentTouchPointSize, timeSinceGestureStart);
                lastTouchPointSize = appendUpEvents(motionEvents, lastTouchPoints,
                        lastTouchPointSize, currentTouchPoints, currentTouchPointSize,
                        timeSinceGestureStart);
                lastTouchPointSize = appendDownEvents(motionEvents, lastTouchPoints,
                        lastTouchPointSize, currentTouchPoints, currentTouchPointSize,
                        timeSinceGestureStart);

                /* Move to next time slice */
                nextKeyPointTime = description.getNextKeyPointAtLeast(timeSinceGestureStart + 1);
            }
            return motionEvents;
        }

        private static TouchPoint[] getCurrentTouchPoints(int requiredCapacity) {
            if ((sCurrentTouchPoints == null) || (sCurrentTouchPoints.length < requiredCapacity)) {
                sCurrentTouchPoints = new TouchPoint[requiredCapacity];
                for (int i = 0; i < requiredCapacity; i++) {
                    sCurrentTouchPoints[i] = new TouchPoint();
                }
            }
            return sCurrentTouchPoints;
        }

        private static TouchPoint[] getLastTouchPoints(int requiredCapacity) {
            if ((sLastTouchPoints == null) || (sLastTouchPoints.length < requiredCapacity)) {
                sLastTouchPoints = new TouchPoint[requiredCapacity];
                for (int i = 0; i < requiredCapacity; i++) {
                    sLastTouchPoints[i] = new TouchPoint();
                }
            }
            return sLastTouchPoints;
        }

        private static PointerCoords[] getPointerCoords(int requiredCapacity) {
            if ((sPointerCoords == null) || (sPointerCoords.length < requiredCapacity)) {
                sPointerCoords = new PointerCoords[requiredCapacity];
                for (int i = 0; i < requiredCapacity; i++) {
                    sPointerCoords[i] = new PointerCoords();
                }
            }
            return sPointerCoords;
        }

        private static PointerProperties[] getPointerProps(int requiredCapacity) {
            if ((sPointerProps == null) || (sPointerProps.length < requiredCapacity)) {
                sPointerProps = new PointerProperties[requiredCapacity];
                for (int i = 0; i < requiredCapacity; i++) {
                    sPointerProps[i] = new PointerProperties();
                }
            }
            return sPointerProps;
        }

        private static void appendMoveEventIfNeeded(List<MotionEvent> motionEvents,
                TouchPoint[] lastTouchPoints, int lastTouchPointsSize,
                TouchPoint[] currentTouchPoints, int currentTouchPointsSize, long currentTime) {
            /* Look for pointers that have moved */
            boolean moveFound = false;
            for (int i = 0; i < currentTouchPointsSize; i++) {
                int lastPointsIndex = findPointByPathIndex(lastTouchPoints, lastTouchPointsSize,
                        currentTouchPoints[i].mPathIndex);
                if (lastPointsIndex >= 0) {
                    moveFound |= (lastTouchPoints[lastPointsIndex].mX != currentTouchPoints[i].mX)
                            || (lastTouchPoints[lastPointsIndex].mY != currentTouchPoints[i].mY);
                    lastTouchPoints[lastPointsIndex].copyFrom(currentTouchPoints[i]);
                }
            }

            if (moveFound) {
                long downTime = motionEvents.get(motionEvents.size() - 1).getDownTime();
                motionEvents.add(obtainMotionEvent(downTime, currentTime, MotionEvent.ACTION_MOVE,
                        lastTouchPoints, lastTouchPointsSize));
            }
        }

        private static int appendUpEvents(List<MotionEvent> motionEvents,
                TouchPoint[] lastTouchPoints, int lastTouchPointsSize,
                TouchPoint[] currentTouchPoints, int currentTouchPointsSize, long currentTime) {
            /* Look for a pointer at the end of its path */
            for (int i = 0; i < currentTouchPointsSize; i++) {
                if (currentTouchPoints[i].mIsEndOfPath) {
                    int indexOfUpEvent = findPointByPathIndex(lastTouchPoints, lastTouchPointsSize,
                            currentTouchPoints[i].mPathIndex);
                    if (indexOfUpEvent < 0) {
                        continue; // Should not happen
                    }
                    long downTime = motionEvents.get(motionEvents.size() - 1).getDownTime();
                    int action = (lastTouchPointsSize == 1) ? MotionEvent.ACTION_UP
                            : MotionEvent.ACTION_POINTER_UP;
                    action |= indexOfUpEvent << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                    motionEvents.add(obtainMotionEvent(downTime, currentTime, action,
                            lastTouchPoints, lastTouchPointsSize));
                    /* Remove this point from lastTouchPoints */
                    for (int j = indexOfUpEvent; j < lastTouchPointsSize - 1; j++) {
                        lastTouchPoints[j].copyFrom(lastTouchPoints[j+1]);
                    }
                    lastTouchPointsSize--;
                }
            }
            return lastTouchPointsSize;
        }

        private static int appendDownEvents(List<MotionEvent> motionEvents,
                TouchPoint[] lastTouchPoints, int lastTouchPointsSize,
                TouchPoint[] currentTouchPoints, int currentTouchPointsSize, long currentTime) {
            /* Look for a pointer that is just starting */
            for (int i = 0; i < currentTouchPointsSize; i++) {
                if (currentTouchPoints[i].mIsStartOfPath) {
                    /* Add the point to last coords and use the new array to generate the event */
                    lastTouchPoints[lastTouchPointsSize++].copyFrom(currentTouchPoints[i]);
                    int action = (lastTouchPointsSize == 1) ? MotionEvent.ACTION_DOWN
                            : MotionEvent.ACTION_POINTER_DOWN;
                    long downTime = (action == MotionEvent.ACTION_DOWN) ? currentTime :
                            motionEvents.get(motionEvents.size() - 1).getDownTime();
                    action |= i << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                    motionEvents.add(obtainMotionEvent(downTime, currentTime, action,
                            lastTouchPoints, lastTouchPointsSize));
                }
            }
            return lastTouchPointsSize;
        }

        private static MotionEvent obtainMotionEvent(long downTime, long eventTime, int action,
                TouchPoint[] touchPoints, int touchPointsSize) {
            PointerCoords[] pointerCoords = getPointerCoords(touchPointsSize);
            PointerProperties[] pointerProperties = getPointerProps(touchPointsSize);
            for (int i = 0; i < touchPointsSize; i++) {
                pointerProperties[i].id = touchPoints[i].mPathIndex;
                pointerProperties[i].toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
                pointerCoords[i].clear();
                pointerCoords[i].pressure = 1.0f;
                pointerCoords[i].size = 1.0f;
                pointerCoords[i].x = touchPoints[i].mX;
                pointerCoords[i].y = touchPoints[i].mY;
            }
            return MotionEvent.obtain(downTime, eventTime, action, touchPointsSize,
                    pointerProperties, pointerCoords, EVENT_META_STATE, EVENT_BUTTON_STATE,
                    EVENT_X_PRECISION, EVENT_Y_PRECISION, EVENT_DEVICE_ID, EVENT_EDGE_FLAGS,
                    EVENT_SOURCE, EVENT_FLAGS);
        }

        private static int findPointByPathIndex(TouchPoint[] touchPoints, int touchPointsSize,
                int pathIndex) {
            for (int i = 0; i < touchPointsSize; i++) {
                if (touchPoints[i].mPathIndex == pathIndex) {
                    return i;
                }
            }
            return -1;
        }
    }
}

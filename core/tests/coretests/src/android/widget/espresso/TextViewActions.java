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
 * limitations under the License
 */

package android.widget.espresso;

import static android.support.test.espresso.action.ViewActions.actionWithAssertions;
import android.graphics.Rect;
import android.support.test.espresso.PerformException;
import android.support.test.espresso.ViewAction;
import android.support.test.espresso.action.CoordinatesProvider;
import android.support.test.espresso.action.GeneralClickAction;
import android.support.test.espresso.action.Press;
import android.support.test.espresso.action.Tap;
import android.support.test.espresso.util.HumanReadables;
import android.text.Layout;
import android.view.View;
import android.widget.Editor;
import android.widget.TextView;

/**
 * A collection of actions on a {@link android.widget.TextView}.
 */
public final class TextViewActions {

    private TextViewActions() {}

    /**
     * Returns an action that clicks on text at an index on the TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param index The index of the TextView's text to click on.
     */
    public static ViewAction clickOnTextAtIndex(int index) {
        return actionWithAssertions(
                new GeneralClickAction(Tap.SINGLE, new TextCoordinates(index), Press.FINGER));
    }

    /**
     * Returns an action that clicks by mouse on text at an index on the TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param index The index of the TextView's text to click on.
     */
    public static ViewAction mouseClickOnTextAtIndex(int index) {
        return actionWithAssertions(
                new MouseClickAction(Tap.SINGLE, new TextCoordinates(index), Press.PINPOINT));
    }

    /**
     * Returns an action that double-clicks on text at an index on the TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param index The index of the TextView's text to double-click on.
     */
    public static ViewAction doubleClickOnTextAtIndex(int index) {
        return actionWithAssertions(
                new GeneralClickAction(Tap.DOUBLE, new TextCoordinates(index), Press.FINGER));
    }

    /**
     * Returns an action that double-clicks by mouse on text at an index on the TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param index The index of the TextView's text to double-click on.
     */
    public static ViewAction mouseDoubleClickOnTextAtIndex(int index) {
        return actionWithAssertions(
                new MouseClickAction(Tap.DOUBLE, new TextCoordinates(index), Press.PINPOINT));
    }

    /**
     * Returns an action that long presses on text at an index on the TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param index The index of the TextView's text to long press on.
     */
    public static ViewAction longPressOnTextAtIndex(int index) {
        return actionWithAssertions(
                new GeneralClickAction(Tap.LONG, new TextCoordinates(index), Press.FINGER));
    }

    /**
     * Returns an action that long click by mouse on text at an index on the TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param index The index of the TextView's text to long click on.
     */
    public static ViewAction mouseLongClickOnTextAtIndex(int index) {
        return actionWithAssertions(
                new MouseClickAction(Tap.LONG, new TextCoordinates(index), Press.PINPOINT));
    }

    /**
     * Returns an action that long presses then drags on text from startIndex to endIndex on the
     * TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param startIndex The index of the TextView's text to start a drag from
     * @param endIndex The index of the TextView's text to end the drag at
     */
    public static ViewAction longPressAndDragOnText(int startIndex, int endIndex) {
        return actionWithAssertions(
                new DragAction(
                        DragAction.Drag.LONG_PRESS,
                        new TextCoordinates(startIndex),
                        new TextCoordinates(endIndex),
                        Press.FINGER,
                        TextView.class));
    }

    /**
     * Returns an action that double taps then drags on text from startIndex to endIndex on the
     * TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param startIndex The index of the TextView's text to start a drag from
     * @param endIndex The index of the TextView's text to end the drag at
     */
    public static ViewAction doubleTapAndDragOnText(int startIndex, int endIndex) {
        return actionWithAssertions(
                new DragAction(
                        DragAction.Drag.DOUBLE_TAP,
                        new TextCoordinates(startIndex),
                        new TextCoordinates(endIndex),
                        Press.FINGER,
                        TextView.class));
    }

    /**
     * Returns an action that click then drags by mouse on text from startIndex to endIndex on the
     * TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param startIndex The index of the TextView's text to start a drag from
     * @param endIndex The index of the TextView's text to end the drag at
     */
    public static ViewAction mouseDragOnText(int startIndex, int endIndex) {
        return actionWithAssertions(
                new DragAction(
                        DragAction.Drag.MOUSE_DOWN,
                        new TextCoordinates(startIndex),
                        new TextCoordinates(endIndex),
                        Press.PINPOINT,
                        TextView.class));
    }

    /**
     * Returns an action that double click then drags by mouse on text from startIndex to endIndex
     * on the TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param startIndex The index of the TextView's text to start a drag from
     * @param endIndex The index of the TextView's text to end the drag at
     */
    public static ViewAction mouseDoubleClickAndDragOnText(int startIndex, int endIndex) {
        return actionWithAssertions(
                new DragAction(
                        DragAction.Drag.MOUSE_DOUBLE_CLICK,
                        new TextCoordinates(startIndex),
                        new TextCoordinates(endIndex),
                        Press.PINPOINT,
                        TextView.class));
    }

    /**
     * Returns an action that long click then drags by mouse on text from startIndex to endIndex
     * on the TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView displayed on screen
     * <ul>
     *
     * @param startIndex The index of the TextView's text to start a drag from
     * @param endIndex The index of the TextView's text to end the drag at
     */
    public static ViewAction mouseLongClickAndDragOnText(int startIndex, int endIndex) {
        return actionWithAssertions(
                new DragAction(
                        DragAction.Drag.MOUSE_LONG_CLICK,
                        new TextCoordinates(startIndex),
                        new TextCoordinates(endIndex),
                        Press.PINPOINT,
                        TextView.class));
    }

    public enum Handle {
        SELECTION_START,
        SELECTION_END,
        INSERTION
    };

    /**
     * Returns an action that tap then drags on the handle from the current position to endIndex on
     * the TextView.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a TextView's drag-handle displayed on screen
     * <ul>
     *
     * @param textView TextView the handle is on
     * @param handleType Type of the handle
     * @param endIndex The index of the TextView's text to end the drag at
     */
    public static ViewAction dragHandle(TextView textView, Handle handleType, int endIndex) {
        final int currentOffset = handleType == Handle.SELECTION_START ?
                textView.getSelectionStart() : textView.getSelectionEnd();
        return actionWithAssertions(
                new DragAction(
                        DragAction.Drag.TAP,
                        new HandleCoordinates(textView, handleType, currentOffset),
                        new HandleCoordinates(textView, handleType, endIndex),
                        Press.FINGER,
                        Editor.HandleView.class));
    }

    /**
     * A provider of the x, y coordinates of the handle that points the specified text index in a
     * text view.
     */
    private static final class HandleCoordinates implements CoordinatesProvider {
        // Must be larger than Editor#LINE_SLOP_MULTIPLIER_FOR_HANDLEVIEWS.
        private final static float LINE_SLOP_MULTIPLIER = 0.6f;
        private final TextView mTextView;
        private final Handle mHandleType;
        private final int mIndex;
        private final String mActionDescription;

        public HandleCoordinates(TextView textView, Handle handleType, int index) {
            mTextView = textView;
            mHandleType = handleType;
            mIndex = index;
            mActionDescription = "Could not locate " + handleType.toString()
                    + " handle that points text index: " + index;
        }

        @Override
        public float[] calculateCoordinates(View view) {
            try {
                return locateHandlePointsTextIndex(view);
            } catch (StringIndexOutOfBoundsException e) {
                throw new PerformException.Builder()
                        .withActionDescription(mActionDescription)
                        .withViewDescription(HumanReadables.describe(view))
                        .withCause(e)
                        .build();
            }
        }

        private float[] locateHandlePointsTextIndex(View view) {
            final int currentOffset = mHandleType == Handle.SELECTION_START ?
                    mTextView.getSelectionStart() : mTextView.getSelectionEnd();

            final Layout layout = mTextView.getLayout();
            final int currentLine = layout.getLineForOffset(currentOffset);
            final int targetLine = layout.getLineForOffset(mIndex);

            final float[] currentCoordinates =
                    (new TextCoordinates(currentOffset)).calculateCoordinates(mTextView);
            final float[] targetCoordinates =
                    (new TextCoordinates(mIndex)).calculateCoordinates(mTextView);
            final Rect bounds = new Rect();
            view.getBoundsOnScreen(bounds);
            final Rect visibleDisplayBounds = new Rect();
            mTextView.getWindowVisibleDisplayFrame(visibleDisplayBounds);
            visibleDisplayBounds.right -= 1;
            visibleDisplayBounds.bottom -= 1;
            if (!visibleDisplayBounds.intersect(bounds)) {
                throw new PerformException.Builder()
                        .withActionDescription(mActionDescription
                                + " The handle is entirely out of the visible display frame of"
                                + "the TextView's window.")
                        .withViewDescription(HumanReadables.describe(view))
                        .build();
            }
            final float dragPointX = Math.max(Math.min(bounds.centerX(),
                    visibleDisplayBounds.right), visibleDisplayBounds.left);
            final float diffX = dragPointX - currentCoordinates[0];
            final float verticalOffset = bounds.height() * 0.7f;
            final float dragPointY = Math.max(Math.min(bounds.top + verticalOffset,
                    visibleDisplayBounds.bottom), visibleDisplayBounds.top);
            float diffY = dragPointY - currentCoordinates[1];
            if (currentLine > targetLine) {
                diffY -= mTextView.getLineHeight() * LINE_SLOP_MULTIPLIER;
            } else if (currentLine < targetLine) {
                diffY += mTextView.getLineHeight() * LINE_SLOP_MULTIPLIER;
            }
            return new float[] {targetCoordinates[0] + diffX, targetCoordinates[1] + diffY};
        }
    }

    /**
     * A provider of the x, y coordinates of the text at the specified index in a text view.
     */
    private static final class TextCoordinates implements CoordinatesProvider {

        private final int mIndex;
        private final String mActionDescription;

        public TextCoordinates(int index) {
            mIndex = index;
            mActionDescription = "Could not locate text at index: " + mIndex;
        }

        @Override
        public float[] calculateCoordinates(View view) {
            try {
                return locateTextAtIndex((TextView) view, mIndex);
            } catch (ClassCastException e) {
                throw new PerformException.Builder()
                        .withActionDescription(mActionDescription)
                        .withViewDescription(HumanReadables.describe(view))
                        .withCause(e)
                        .build();
            } catch (StringIndexOutOfBoundsException e) {
                throw new PerformException.Builder()
                        .withActionDescription(mActionDescription)
                        .withViewDescription(HumanReadables.describe(view))
                        .withCause(e)
                        .build();
            }
        }

        /**
         * @throws StringIndexOutOfBoundsException
         */
        private float[] locateTextAtIndex(TextView textView, int index) {
            if (index < 0 || index > textView.getText().length()) {
                throw new StringIndexOutOfBoundsException(index);
            }
            final int[] xy = new int[2];
            textView.getLocationOnScreen(xy);
            final Layout layout = textView.getLayout();
            final int line = layout.getLineForOffset(index);
            final float x = textView.getTotalPaddingLeft() - textView.getScrollX()
                    + layout.getPrimaryHorizontal(index);
            final float y = textView.getTotalPaddingTop() - textView.getScrollY()
                    + layout.getLineTop(line);
            return new float[]{x + xy[0], y + xy[1]};
        }
    }
}

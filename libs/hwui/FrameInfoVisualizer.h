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
#ifndef DRAWPROFILER_H
#define DRAWPROFILER_H

#include "FrameInfo.h"
#include "Properties.h"
#include "Rect.h"
#include "utils/RingBuffer.h"

#include <utils/Timers.h>

#include <memory>

namespace android {
namespace uirenderer {

class OpenGLRenderer;

// TODO: This is a bit awkward as it needs to match the thing in CanvasContext
// A better abstraction here would be nice but iterators are painful
// and RingBuffer having the size baked into the template is also painful
// But making DrawProfiler also be templated is ALSO painful
// At least this is a compile failure if this doesn't match, so there's that.
typedef RingBuffer<FrameInfo, 120> FrameInfoSource;

class FrameInfoVisualizer {
public:
    FrameInfoVisualizer(FrameInfoSource& source);
    ~FrameInfoVisualizer();

    bool consumeProperties();
    void setDensity(float density);

    void unionDirty(SkRect* dirty);
    void draw(OpenGLRenderer* canvas);

    void dumpData(int fd);

private:
    void createData();
    void destroyData();

    void initializeRects(const int baseline);
    void nextBarSegment(FrameInfoIndex start, FrameInfoIndex end);
    void drawGraph(OpenGLRenderer* canvas);
    void drawCurrentFrame(const int baseline, OpenGLRenderer* canvas);
    void drawThreshold(OpenGLRenderer* canvas);

    inline float duration(size_t index, FrameInfoIndex start, FrameInfoIndex end) {
        nsecs_t ns_start = mFrameSource[index][start];
        nsecs_t ns_end = mFrameSource[index][end];
        float duration = ((ns_end - ns_start) * 0.000001f);
        // Clamp to large to avoid spiking off the top of the screen
        duration = duration > 50.0f ? 50.0f : duration;
        return duration > 0.0f ? duration : 0.0f;
    }

    ProfileType mType = ProfileType::None;
    float mDensity = 0;

    FrameInfoSource& mFrameSource;

    int mVerticalUnit = 0;
    int mHorizontalUnit = 0;
    int mThresholdStroke = 0;

    /*
     * mRects represents an array of rect shapes, divided into NUM_ELEMENTS
     * groups such that each group is drawn with the same paint.
     * For example mRects[0] is the array of rect floats suitable for
     * OpenGLRenderer:drawRects() that makes up all the FrameTimingData:record
     * information.
     */
    std::unique_ptr<float[]> mRects;

    bool mShowDirtyRegions = false;
    SkRect mDirtyRegion;
    bool mFlashToggle = false;
    nsecs_t mLastFrameLogged = 0;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DRAWPROFILER_H */

/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "BakedOpRenderer.h"
#include "BakedOpDispatcher.h"
#include "FrameBuilder.h"
#include "LayerUpdateQueue.h"
#include "RecordingCanvas.h"
#include "tests/common/TestUtils.h"

#include <gtest/gtest.h>

using namespace android;
using namespace android::uirenderer;

const LayerUpdateQueue sEmptyLayerUpdateQueue;
const Vector3 sLightCenter = {100, 100, 100};

RENDERTHREAD_TEST(LeakCheck, saveLayerUnclipped_simple) {
    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 10, 190, 190, 128, (SaveFlags::Flags)(0));
        canvas.drawRect(0, 0, 200, 200, SkPaint());
        canvas.restore();
    });
    BakedOpRenderer::LightInfo lightInfo = {50.0f, 128, 128};
    RenderState& renderState = renderThread.renderState();
    Caches& caches = Caches::getInstance();

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    BakedOpRenderer renderer(caches, renderState, true, lightInfo);
    frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
}

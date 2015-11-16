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

#ifndef ANDROID_HWUI_BAKED_OP_RENDERER_H
#define ANDROID_HWUI_BAKED_OP_RENDERER_H

#include "BakedOpState.h"
#include "Matrix.h"

namespace android {
namespace uirenderer {

class Caches;
struct Glop;
class Layer;
class RenderState;

/**
 * Main rendering manager for a collection of work - one frame + any contained FBOs.
 *
 * Manages frame and FBO lifecycle, binding the GL framebuffer as appropriate. This is the only
 * place where FBOs are bound, created, and destroyed.
 *
 * All rendering operations will be sent by the Dispatcher, a collection of static methods,
 * which has intentionally limited access to the renderer functionality.
 */
class BakedOpRenderer {
public:
    /**
     * Position agnostic shadow lighting info. Used with all shadow ops in scene.
     */
    struct LightInfo {
        float lightRadius = 0;
        uint8_t ambientShadowAlpha = 0;
        uint8_t spotShadowAlpha = 0;
    };

    BakedOpRenderer(Caches& caches, RenderState& renderState, bool opaque, const LightInfo& lightInfo)
            : mRenderState(renderState)
            , mCaches(caches)
            , mOpaque(opaque)
            , mLightInfo(lightInfo) {
    }

    RenderState& renderState() { return mRenderState; }
    Caches& caches() { return mCaches; }

    void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect);
    void endFrame();
    OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height);
    void startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect);
    void endLayer();

    Texture* getTexture(const SkBitmap* bitmap);
    const LightInfo& getLightInfo() { return mLightInfo; }

    void renderGlop(const BakedOpState& state, const Glop& glop);
    bool didDraw() { return mHasDrawn; }
private:
    void setViewport(uint32_t width, uint32_t height);
    void clearColorBuffer(const Rect& clearRect);

    RenderState& mRenderState;
    Caches& mCaches;
    bool mOpaque;
    bool mHasDrawn = false;

    // render target state - setup by start/end layer/frame
    // only valid to use in between start/end pairs.
    struct {
        GLuint frameBufferId = 0;
        OffscreenBuffer* offscreenBuffer = nullptr;
        uint32_t viewportWidth = 0;
        uint32_t viewportHeight = 0;
        Matrix4 orthoMatrix;
    } mRenderTarget;

    const LightInfo mLightInfo;
};

/**
 * Provides all "onBitmapOp(...)" style static methods for every op type, which convert the
 * RecordedOps and their state to Glops, and renders them with the provided BakedOpRenderer.
 *
 * This dispatcher is separate from the renderer so that the dispatcher / renderer interaction is
 * minimal through public BakedOpRenderer APIs.
 */
class BakedOpDispatcher {
public:
    // Declares all "onBitmapOp(...)" style methods for every op type
#define DISPATCH_METHOD(Type) \
        static void on##Type(BakedOpRenderer& renderer, const Type& op, const BakedOpState& state);
    MAP_OPS(DISPATCH_METHOD);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_BAKED_OP_RENDERER_H

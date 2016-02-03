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

#pragma once

#include "BakedOpState.h"
#include "CanvasState.h"
#include "DisplayList.h"
#include "LayerBuilder.h"
#include "RecordedOp.h"
#include "utils/GLUtils.h"

#include <vector>
#include <unordered_map>

struct SkRect;

namespace android {
namespace uirenderer {

class BakedOpState;
class LayerUpdateQueue;
class OffscreenBuffer;
class Rect;

/**
 * Traverses all of the drawing commands from the layers and RenderNodes passed into it, preparing
 * them to be rendered.
 *
 * Resolves final drawing state for each operation (including clip, alpha and matrix), and then
 * reorder and merge each op as it is resolved for drawing efficiency. Each layer of content (either
 * from the LayerUpdateQueue, or temporary layers created by saveLayer operations in the
 * draw stream) will create different reorder contexts, each in its own LayerBuilder.
 *
 * Then the prepared or 'baked' drawing commands can be issued by calling the templated
 * replayBakedOps() function, which will dispatch them (including any created merged op collections)
 * to a Dispatcher and Renderer. See BakedOpDispatcher for how these baked drawing operations are
 * resolved into Glops and rendered via BakedOpRenderer.
 *
 * This class is also the authoritative source for traversing RenderNodes, both for standard op
 * traversal within a DisplayList, and for out of order RenderNode traversal for Z and projection.
 */
class FrameBuilder : public CanvasStateClient {
public:
    struct LightGeometry {
        Vector3 center;
        float radius;
    };

    // TODO: remove
    FrameBuilder(const LayerUpdateQueue& layers, const SkRect& clip,
            uint32_t viewportWidth, uint32_t viewportHeight,
            const std::vector< sp<RenderNode> >& nodes,
            const LightGeometry& lightGeometry,
            Caches* caches)
            : FrameBuilder(layers, clip, viewportWidth, viewportHeight, nodes, lightGeometry, Rect(), caches) {}

    FrameBuilder(const LayerUpdateQueue& layers, const SkRect& clip,
            uint32_t viewportWidth, uint32_t viewportHeight,
            const std::vector< sp<RenderNode> >& nodes,
            const LightGeometry& lightGeometry,
            const Rect &contentDrawBounds, Caches* caches);

    virtual ~FrameBuilder() {}

    /**
     * replayBakedOps() is templated based on what class will receive ops being replayed.
     *
     * It constructs a lookup array of lambdas, which allows a recorded BakeOpState to use
     * state->op->opId to lookup a receiver that will be called when the op is replayed.
     *
     */
    template <typename StaticDispatcher, typename Renderer>
    void replayBakedOps(Renderer& renderer) {
        /**
         * Defines a LUT of lambdas which allow a recorded BakedOpState to use state->op->opId to
         * dispatch the op via a method on a static dispatcher when the op is replayed.
         *
         * For example a BitmapOp would resolve, via the lambda lookup, to calling:
         *
         * StaticDispatcher::onBitmapOp(Renderer& renderer, const BitmapOp& op, const BakedOpState& state);
         */
        #define X(Type) \
                [](void* renderer, const BakedOpState& state) { \
                    StaticDispatcher::on##Type(*(static_cast<Renderer*>(renderer)), \
                            static_cast<const Type&>(*(state.op)), state); \
                },
        static BakedOpReceiver unmergedReceivers[] = BUILD_RENDERABLE_OP_LUT(X);
        #undef X

        /**
         * Defines a LUT of lambdas which allow merged arrays of BakedOpState* to be passed to a
         * static dispatcher when the group of merged ops is replayed.
         */
        #define X(Type) \
                [](void* renderer, const MergedBakedOpList& opList) { \
                    StaticDispatcher::onMerged##Type##s(*(static_cast<Renderer*>(renderer)), opList); \
                },
        static MergedOpReceiver mergedReceivers[] = BUILD_MERGEABLE_OP_LUT(X);
        #undef X

        // Relay through layers in reverse order, since layers
        // later in the list will be drawn by earlier ones
        for (int i = mLayerBuilders.size() - 1; i >= 1; i--) {
            GL_CHECKPOINT(MODERATE);
            LayerBuilder& layer = *(mLayerBuilders[i]);
            if (layer.renderNode) {
                // cached HW layer - can't skip layer if empty
                renderer.startRepaintLayer(layer.offscreenBuffer, layer.repaintRect);
                GL_CHECKPOINT(MODERATE);
                layer.replayBakedOpsImpl((void*)&renderer, unmergedReceivers, mergedReceivers);
                GL_CHECKPOINT(MODERATE);
                renderer.endLayer();
            } else if (!layer.empty()) { // save layer - skip entire layer if empty
                layer.offscreenBuffer = renderer.startTemporaryLayer(layer.width, layer.height);
                GL_CHECKPOINT(MODERATE);
                layer.replayBakedOpsImpl((void*)&renderer, unmergedReceivers, mergedReceivers);
                GL_CHECKPOINT(MODERATE);
                renderer.endLayer();
            }
        }

        GL_CHECKPOINT(MODERATE);
        const LayerBuilder& fbo0 = *(mLayerBuilders[0]);
        renderer.startFrame(fbo0.width, fbo0.height, fbo0.repaintRect);
        GL_CHECKPOINT(MODERATE);
        fbo0.replayBakedOpsImpl((void*)&renderer, unmergedReceivers, mergedReceivers);
        GL_CHECKPOINT(MODERATE);
        renderer.endFrame(fbo0.repaintRect);
    }

    void dump() const {
        for (auto&& layer : mLayerBuilders) {
            layer->dump();
        }
    }

    ///////////////////////////////////////////////////////////////////
    /// CanvasStateClient interface
    ///////////////////////////////////////////////////////////////////
    virtual void onViewportInitialized() override;
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) override;
    virtual GLuint getTargetFbo() const override { return 0; }

private:
    enum class ChildrenSelectMode {
        Negative,
        Positive
    };
    void saveForLayer(uint32_t layerWidth, uint32_t layerHeight,
            float contentTranslateX, float contentTranslateY,
            const Rect& repaintRect,
            const Vector3& lightCenter,
            const BeginLayerOp* beginLayerOp, RenderNode* renderNode);
    void restoreForLayer();

    LayerBuilder& currentLayer() { return *(mLayerBuilders[mLayerStack.back()]); }

    BakedOpState* tryBakeOpState(const RecordedOp& recordedOp) {
        return BakedOpState::tryConstruct(mAllocator, *mCanvasState.writableSnapshot(), recordedOp);
    }

    // should always be surrounded by a save/restore pair, and not called if DisplayList is null
    void deferNodePropsAndOps(RenderNode& node);

    template <typename V>
    void defer3dChildren(ChildrenSelectMode mode, const V& zTranslatedNodes);

    void deferShadow(const RenderNodeOp& casterOp);

    void deferProjectedChildren(const RenderNode& renderNode);

    void deferNodeOps(const RenderNode& renderNode);

    void deferRenderNodeOpImpl(const RenderNodeOp& op);

    void replayBakedOpsImpl(void* arg, BakedOpReceiver* receivers);

    SkPath* createFrameAllocatedPath() {
        return mAllocator.create<SkPath>();
    }

    void deferStrokeableOp(const RecordedOp& op, batchid_t batchId,
            BakedOpState::StrokeBehavior strokeBehavior = BakedOpState::StrokeBehavior::StyleDefined);

    /**
     * Declares all FrameBuilder::deferXXXXOp() methods for every RecordedOp type.
     *
     * These private methods are called from within deferImpl to defer each individual op
     * type differently.
     */
#define X(Type) void defer##Type(const Type& op);
    MAP_DEFERRABLE_OPS(X)
#undef X

    // List of every deferred layer's render state. Replayed in reverse order to render a frame.
    std::vector<LayerBuilder*> mLayerBuilders;

    /*
     * Stack of indices within mLayerBuilders representing currently active layers. If drawing
     * layerA within a layerB, will contain, in order:
     *  - 0 (representing FBO 0, always present)
     *  - layerB's index
     *  - layerA's index
     *
     * Note that this doesn't vector doesn't always map onto all values of mLayerBuilders. When a
     * layer is finished deferring, it will still be represented in mLayerBuilders, but it's index
     * won't be in mLayerStack. This is because it can be replayed, but can't have any more drawing
     * ops added to it.
    */
    std::vector<size_t> mLayerStack;

    CanvasState mCanvasState;

    Caches* mCaches = nullptr;

    float mLightRadius;

    // contains single-frame objects, such as BakedOpStates, LayerBuilders, Batches
    LinearAllocator mAllocator;
};

}; // namespace uirenderer
}; // namespace android

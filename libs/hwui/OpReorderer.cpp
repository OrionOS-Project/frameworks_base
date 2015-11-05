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

#include "OpReorderer.h"

#include "utils/PaintUtils.h"
#include "RenderNode.h"
#include "LayerUpdateQueue.h"

#include "SkCanvas.h"
#include "utils/Trace.h"

namespace android {
namespace uirenderer {

class BatchBase {

public:
    BatchBase(batchid_t batchId, BakedOpState* op, bool merging)
        : mBatchId(batchId)
        , mMerging(merging) {
        mBounds = op->computedState.clippedBounds;
        mOps.push_back(op);
    }

    bool intersects(const Rect& rect) const {
        if (!rect.intersects(mBounds)) return false;

        for (const BakedOpState* op : mOps) {
            if (rect.intersects(op->computedState.clippedBounds)) {
                return true;
            }
        }
        return false;
    }

    batchid_t getBatchId() const { return mBatchId; }
    bool isMerging() const { return mMerging; }

    const std::vector<BakedOpState*>& getOps() const { return mOps; }

    void dump() const {
        ALOGD("    Batch %p, id %d, merging %d, count %d, bounds " RECT_STRING,
                this, mBatchId, mMerging, mOps.size(), RECT_ARGS(mBounds));
    }
protected:
    batchid_t mBatchId;
    Rect mBounds;
    std::vector<BakedOpState*> mOps;
    bool mMerging;
};

class OpBatch : public BatchBase {
public:
    static void* operator new(size_t size, LinearAllocator& allocator) {
        return allocator.alloc(size);
    }

    OpBatch(batchid_t batchId, BakedOpState* op)
            : BatchBase(batchId, op, false) {
    }

    void batchOp(BakedOpState* op) {
        mBounds.unionWith(op->computedState.clippedBounds);
        mOps.push_back(op);
    }
};

class MergingOpBatch : public BatchBase {
public:
    static void* operator new(size_t size, LinearAllocator& allocator) {
        return allocator.alloc(size);
    }

    MergingOpBatch(batchid_t batchId, BakedOpState* op)
            : BatchBase(batchId, op, true) {
    }

    /*
     * Helper for determining if a new op can merge with a MergingDrawBatch based on their bounds
     * and clip side flags. Positive bounds delta means new bounds fit in old.
     */
    static inline bool checkSide(const int currentFlags, const int newFlags, const int side,
            float boundsDelta) {
        bool currentClipExists = currentFlags & side;
        bool newClipExists = newFlags & side;

        // if current is clipped, we must be able to fit new bounds in current
        if (boundsDelta > 0 && currentClipExists) return false;

        // if new is clipped, we must be able to fit current bounds in new
        if (boundsDelta < 0 && newClipExists) return false;

        return true;
    }

    static bool paintIsDefault(const SkPaint& paint) {
        return paint.getAlpha() == 255
                && paint.getColorFilter() == nullptr
                && paint.getShader() == nullptr;
    }

    static bool paintsAreEquivalent(const SkPaint& a, const SkPaint& b) {
        return a.getAlpha() == b.getAlpha()
                && a.getColorFilter() == b.getColorFilter()
                && a.getShader() == b.getShader();
    }

    /*
     * Checks if a (mergeable) op can be merged into this batch
     *
     * If true, the op's multiDraw must be guaranteed to handle both ops simultaneously, so it is
     * important to consider all paint attributes used in the draw calls in deciding both a) if an
     * op tries to merge at all, and b) if the op can merge with another set of ops
     *
     * False positives can lead to information from the paints of subsequent merged operations being
     * dropped, so we make simplifying qualifications on the ops that can merge, per op type.
     */
    bool canMergeWith(BakedOpState* op) const {
        bool isTextBatch = getBatchId() == OpBatchType::Text
                || getBatchId() == OpBatchType::ColorText;

        // Overlapping other operations is only allowed for text without shadow. For other ops,
        // multiDraw isn't guaranteed to overdraw correctly
        if (!isTextBatch || PaintUtils::hasTextShadow(op->op->paint)) {
            if (intersects(op->computedState.clippedBounds)) return false;
        }

        const BakedOpState* lhs = op;
        const BakedOpState* rhs = mOps[0];

        if (!MathUtils::areEqual(lhs->alpha, rhs->alpha)) return false;

        // Identical round rect clip state means both ops will clip in the same way, or not at all.
        // As the state objects are const, we can compare their pointers to determine mergeability
        if (lhs->roundRectClipState != rhs->roundRectClipState) return false;
        if (lhs->projectionPathMask != rhs->projectionPathMask) return false;

        /* Clipping compatibility check
         *
         * Exploits the fact that if a op or batch is clipped on a side, its bounds will equal its
         * clip for that side.
         */
        const int currentFlags = mClipSideFlags;
        const int newFlags = op->computedState.clipSideFlags;
        if (currentFlags != OpClipSideFlags::None || newFlags != OpClipSideFlags::None) {
            const Rect& opBounds = op->computedState.clippedBounds;
            float boundsDelta = mBounds.left - opBounds.left;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Left, boundsDelta)) return false;
            boundsDelta = mBounds.top - opBounds.top;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Top, boundsDelta)) return false;

            // right and bottom delta calculation reversed to account for direction
            boundsDelta = opBounds.right - mBounds.right;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Right, boundsDelta)) return false;
            boundsDelta = opBounds.bottom - mBounds.bottom;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Bottom, boundsDelta)) return false;
        }

        const SkPaint* newPaint = op->op->paint;
        const SkPaint* oldPaint = mOps[0]->op->paint;

        if (newPaint == oldPaint) {
            // if paints are equal, then modifiers + paint attribs don't need to be compared
            return true;
        } else if (newPaint && !oldPaint) {
            return paintIsDefault(*newPaint);
        } else if (!newPaint && oldPaint) {
            return paintIsDefault(*oldPaint);
        }
        return paintsAreEquivalent(*newPaint, *oldPaint);
    }

    void mergeOp(BakedOpState* op) {
        mBounds.unionWith(op->computedState.clippedBounds);
        mOps.push_back(op);

        const int newClipSideFlags = op->computedState.clipSideFlags;
        mClipSideFlags |= newClipSideFlags;

        const Rect& opClip = op->computedState.clipRect;
        if (newClipSideFlags & OpClipSideFlags::Left) mClipRect.left = opClip.left;
        if (newClipSideFlags & OpClipSideFlags::Top) mClipRect.top = opClip.top;
        if (newClipSideFlags & OpClipSideFlags::Right) mClipRect.right = opClip.right;
        if (newClipSideFlags & OpClipSideFlags::Bottom) mClipRect.bottom = opClip.bottom;
    }

private:
    int mClipSideFlags = 0;
    Rect mClipRect;
};

OpReorderer::LayerReorderer::LayerReorderer(uint32_t width, uint32_t height,
        const BeginLayerOp* beginLayerOp, RenderNode* renderNode)
        : width(width)
        , height(height)
        , offscreenBuffer(renderNode ? renderNode->getLayer() : nullptr)
        , beginLayerOp(beginLayerOp)
        , renderNode(renderNode) {}

// iterate back toward target to see if anything drawn since should overlap the new op
// if no target, merging ops still iterate to find similar batch to insert after
void OpReorderer::LayerReorderer::locateInsertIndex(int batchId, const Rect& clippedBounds,
        BatchBase** targetBatch, size_t* insertBatchIndex) const {
    for (int i = mBatches.size() - 1; i >= 0; i--) {
        BatchBase* overBatch = mBatches[i];

        if (overBatch == *targetBatch) break;

        // TODO: also consider shader shared between batch types
        if (batchId == overBatch->getBatchId()) {
            *insertBatchIndex = i + 1;
            if (!*targetBatch) break; // found insert position, quit
        }

        if (overBatch->intersects(clippedBounds)) {
            // NOTE: it may be possible to optimize for special cases where two operations
            // of the same batch/paint could swap order, such as with a non-mergeable
            // (clipped) and a mergeable text operation
            *targetBatch = nullptr;
            break;
        }
    }
}

void OpReorderer::LayerReorderer::deferUnmergeableOp(LinearAllocator& allocator,
        BakedOpState* op, batchid_t batchId) {
    OpBatch* targetBatch = mBatchLookup[batchId];

    size_t insertBatchIndex = mBatches.size();
    if (targetBatch) {
        locateInsertIndex(batchId, op->computedState.clippedBounds,
                (BatchBase**)(&targetBatch), &insertBatchIndex);
    }

    if (targetBatch) {
        targetBatch->batchOp(op);
    } else  {
        // new non-merging batch
        targetBatch = new (allocator) OpBatch(batchId, op);
        mBatchLookup[batchId] = targetBatch;
        mBatches.insert(mBatches.begin() + insertBatchIndex, targetBatch);
    }
}

// insertion point of a new batch, will hopefully be immediately after similar batch
// (generally, should be similar shader)
void OpReorderer::LayerReorderer::deferMergeableOp(LinearAllocator& allocator,
        BakedOpState* op, batchid_t batchId, mergeid_t mergeId) {
    MergingOpBatch* targetBatch = nullptr;

    // Try to merge with any existing batch with same mergeId
    auto getResult = mMergingBatchLookup[batchId].find(mergeId);
    if (getResult != mMergingBatchLookup[batchId].end()) {
        targetBatch = getResult->second;
        if (!targetBatch->canMergeWith(op)) {
            targetBatch = nullptr;
        }
    }

    size_t insertBatchIndex = mBatches.size();
    locateInsertIndex(batchId, op->computedState.clippedBounds,
            (BatchBase**)(&targetBatch), &insertBatchIndex);

    if (targetBatch) {
        targetBatch->mergeOp(op);
    } else  {
        // new merging batch
        targetBatch = new (allocator) MergingOpBatch(batchId, op);
        mMergingBatchLookup[batchId].insert(std::make_pair(mergeId, targetBatch));

        mBatches.insert(mBatches.begin() + insertBatchIndex, targetBatch);
    }
}

void OpReorderer::LayerReorderer::replayBakedOpsImpl(void* arg, BakedOpDispatcher* receivers) const {
    ATRACE_NAME("flush drawing commands");
    for (const BatchBase* batch : mBatches) {
        // TODO: different behavior based on batch->isMerging()
        for (const BakedOpState* op : batch->getOps()) {
            receivers[op->op->opId](arg, *op->op, *op);
        }
    }
}

void OpReorderer::LayerReorderer::dump() const {
    ALOGD("LayerReorderer %p, %ux%u buffer %p, blo %p, rn %p",
            this, width, height, offscreenBuffer, beginLayerOp, renderNode);
    for (const BatchBase* batch : mBatches) {
        batch->dump();
    }
}

OpReorderer::OpReorderer(const LayerUpdateQueue& layers, const SkRect& clip,
        uint32_t viewportWidth, uint32_t viewportHeight,
        const std::vector< sp<RenderNode> >& nodes)
        : mCanvasState(*this) {
    ATRACE_NAME("prepare drawing commands");
    mLayerReorderers.emplace_back(viewportWidth, viewportHeight);
        mLayerStack.push_back(0);

    mCanvasState.initializeSaveStack(viewportWidth, viewportHeight,
            clip.fLeft, clip.fTop, clip.fRight, clip.fBottom,
            Vector3());

    // Render all layers to be updated, in order. Defer in reverse order, so that they'll be
    // updated in the order they're passed in (mLayerReorderers are issued to Renderer in reverse)
    for (int i = layers.entries().size() - 1; i >= 0; i--) {
        RenderNode* layerNode = layers.entries()[i].renderNode;
        const Rect& layerDamage = layers.entries()[i].damage;

        saveForLayer(layerNode->getWidth(), layerNode->getHeight(), nullptr, layerNode);
        mCanvasState.writableSnapshot()->setClip(
                layerDamage.left, layerDamage.top, layerDamage.right, layerDamage.bottom);

        if (layerNode->getDisplayList()) {
            deferImpl(*(layerNode->getDisplayList()));
        }
        restoreForLayer();
    }

    // Defer Fbo0
    for (const sp<RenderNode>& node : nodes) {
        if (node->nothingToDraw()) continue;

        int count = mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);
        deferNodePropsAndOps(*node);
        mCanvasState.restoreToCount(count);
    }
}

OpReorderer::OpReorderer(int viewportWidth, int viewportHeight, const DisplayList& displayList)
        : mCanvasState(*this) {
    ATRACE_NAME("prepare drawing commands");

    mLayerReorderers.emplace_back(viewportWidth, viewportHeight);
    mLayerStack.push_back(0);

    mCanvasState.initializeSaveStack(viewportWidth, viewportHeight,
            0, 0, viewportWidth, viewportHeight, Vector3());
    deferImpl(displayList);
}

void OpReorderer::onViewportInitialized() {}

void OpReorderer::onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {}

void OpReorderer::deferNodePropsAndOps(RenderNode& node) {
    if (node.applyViewProperties(mCanvasState, mAllocator)) {
        // not rejected so render
        if (node.getLayer()) {
            // HW layer
            LayerOp* drawLayerOp = new (mAllocator) LayerOp(node);
            BakedOpState* bakedOpState = tryBakeOpState(*drawLayerOp);
            if (bakedOpState) {
                // Layer will be drawn into parent layer (which is now current, since we popped mLayerStack)
                currentLayer().deferUnmergeableOp(mAllocator, bakedOpState, OpBatchType::Bitmap);
            }
        } else {
            deferImpl(*(node.getDisplayList()));
        }
    }
}

/**
 * Used to define a list of lambdas referencing private OpReorderer::onXXXXOp() methods.
 *
 * This allows opIds embedded in the RecordedOps to be used for dispatching to these lambdas. E.g. a
 * BitmapOp op then would be dispatched to OpReorderer::onBitmapOp(const BitmapOp&)
 */
#define OP_RECEIVER(Type) \
        [](OpReorderer& reorderer, const RecordedOp& op) { reorderer.on##Type(static_cast<const Type&>(op)); },
void OpReorderer::deferImpl(const DisplayList& displayList) {
    static std::function<void(OpReorderer& reorderer, const RecordedOp&)> receivers[] = {
        MAP_OPS(OP_RECEIVER)
    };
    for (const DisplayList::Chunk& chunk : displayList.getChunks()) {
        for (size_t opIndex = chunk.beginOpIndex; opIndex < chunk.endOpIndex; opIndex++) {
            const RecordedOp* op = displayList.getOps()[opIndex];
            receivers[op->opId](*this, *op);
        }
    }
}

void OpReorderer::onRenderNodeOp(const RenderNodeOp& op) {
    if (op.renderNode->nothingToDraw()) {
        return;
    }
    int count = mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);

    // apply state from RecordedOp
    mCanvasState.concatMatrix(op.localMatrix);
    mCanvasState.clipRect(op.localClipRect.left, op.localClipRect.top,
            op.localClipRect.right, op.localClipRect.bottom, SkRegion::kIntersect_Op);

    // then apply state from node properties, and defer ops
    deferNodePropsAndOps(*op.renderNode);

    mCanvasState.restoreToCount(count);
}

static batchid_t tessellatedBatchId(const SkPaint& paint) {
    return paint.getPathEffect()
            ? OpBatchType::AlphaMaskTexture
            : (paint.isAntiAlias() ? OpBatchType::AlphaVertices : OpBatchType::Vertices);
}

void OpReorderer::onBitmapOp(const BitmapOp& op) {
    BakedOpState* bakedStateOp = tryBakeOpState(op);
    if (!bakedStateOp) return; // quick rejected

    mergeid_t mergeId = (mergeid_t) op.bitmap->getGenerationID();
    // TODO: AssetAtlas
    currentLayer().deferMergeableOp(mAllocator, bakedStateOp, OpBatchType::Bitmap, mergeId);
}

void OpReorderer::onRectOp(const RectOp& op) {
    BakedOpState* bakedStateOp = tryBakeOpState(op);
    if (!bakedStateOp) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedStateOp, tessellatedBatchId(*op.paint));
}

void OpReorderer::onSimpleRectsOp(const SimpleRectsOp& op) {
    BakedOpState* bakedStateOp = tryBakeOpState(op);
    if (!bakedStateOp) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedStateOp, OpBatchType::Vertices);
}

void OpReorderer::saveForLayer(uint32_t layerWidth, uint32_t layerHeight,
        const BeginLayerOp* beginLayerOp, RenderNode* renderNode) {

    mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);
    mCanvasState.writableSnapshot()->transform->loadIdentity();
    mCanvasState.writableSnapshot()->initializeViewport(layerWidth, layerHeight);
    mCanvasState.writableSnapshot()->roundRectClipState = nullptr;

    // create a new layer, and push its index on the stack
    mLayerStack.push_back(mLayerReorderers.size());
    mLayerReorderers.emplace_back(layerWidth, layerHeight, beginLayerOp, renderNode);
}

void OpReorderer::restoreForLayer() {
    // restore canvas, and pop finished layer off of the stack
    mCanvasState.restore();
    mLayerStack.pop_back();
}

// TODO: test rejection at defer time, where the bounds become empty
void OpReorderer::onBeginLayerOp(const BeginLayerOp& op) {
    const uint32_t layerWidth = (uint32_t) op.unmappedBounds.getWidth();
    const uint32_t layerHeight = (uint32_t) op.unmappedBounds.getHeight();
    saveForLayer(layerWidth, layerHeight, &op, nullptr);
}

void OpReorderer::onEndLayerOp(const EndLayerOp& /* ignored */) {
    const BeginLayerOp& beginLayerOp = *currentLayer().beginLayerOp;
    int finishedLayerIndex = mLayerStack.back();

    restoreForLayer();

    // record the draw operation into the previous layer's list of draw commands
    // uses state from the associated beginLayerOp, since it has all the state needed for drawing
    LayerOp* drawLayerOp = new (mAllocator) LayerOp(
            beginLayerOp.unmappedBounds,
            beginLayerOp.localMatrix,
            beginLayerOp.localClipRect,
            beginLayerOp.paint,
            &mLayerReorderers[finishedLayerIndex].offscreenBuffer);
    BakedOpState* bakedOpState = tryBakeOpState(*drawLayerOp);

    if (bakedOpState) {
        // Layer will be drawn into parent layer (which is now current, since we popped mLayerStack)
        currentLayer().deferUnmergeableOp(mAllocator, bakedOpState, OpBatchType::Bitmap);
    } else {
        // Layer won't be drawn - delete its drawing batches to prevent it from doing any work
        mLayerReorderers[finishedLayerIndex].clear();
        return;
    }
}

void OpReorderer::onLayerOp(const LayerOp& op) {
    LOG_ALWAYS_FATAL("unsupported");
}

} // namespace uirenderer
} // namespace android

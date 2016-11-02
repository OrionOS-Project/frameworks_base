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

#include "RenderNodeDrawable.h"
#include "RenderNode.h"
#include "SkiaDisplayList.h"
#include "SkiaFrameRenderer.h"
#include "utils/TraceUtils.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

static void clipOutline(const Outline& outline, SkCanvas* canvas, const SkRect* pendingClip) {
    SkASSERT(outline.willClip());
    Rect possibleRect;
    float radius;
    LOG_ALWAYS_FATAL_IF(!outline.getAsRoundRect(&possibleRect, &radius),
            "clipping outlines should be at most roundedRects");
    SkRect rect = possibleRect.toSkRect();
    if (radius != 0.0f) {
        if (pendingClip && !pendingClip->contains(rect)) {
            canvas->clipRect(*pendingClip);
        }
        canvas->clipRRect(SkRRect::MakeRectXY(rect, radius, radius), SkRegion::kIntersect_Op, true);
    } else {
        if (pendingClip) {
            (void)rect.intersect(*pendingClip);
        }
        canvas->clipRect(rect);
    }
}

const RenderProperties& RenderNodeDrawable::getNodeProperties() const {
    return mRenderNode->properties();
}

void RenderNodeDrawable::onDraw(SkCanvas* canvas) {
    //negative and positive Z order are drawn out of order
    if (MathUtils::isZero(mRenderNode->properties().getZ())) {
        this->forceDraw(canvas);
    }
}

void RenderNodeDrawable::forceDraw(SkCanvas* canvas) {
    RenderNode* renderNode = mRenderNode.get();
    if (SkiaFrameRenderer::skpCaptureEnabled()) {
        SkRect dimensions = SkRect::MakeWH(renderNode->getWidth(), renderNode->getHeight());
        canvas->drawAnnotation(dimensions, renderNode->getName(), nullptr);
    }

    // We only respect the nothingToDraw check when we are composing a layer. This
    // ensures that we paint the layer even if it is not currently visible in the
    // event that the properties change and it becomes visible.
    if (!renderNode->isRenderable() || (renderNode->nothingToDraw() && mComposeLayer)) {
        return;
    }

    SkASSERT(renderNode->getDisplayList()->isSkiaDL());
    SkiaDisplayList* displayList = (SkiaDisplayList*)renderNode->getDisplayList();

    SkAutoCanvasRestore acr(canvas, true);

    const RenderProperties& properties = this->getNodeProperties();
    if (displayList->mIsProjectionReceiver) {
        // this node is a projection receiver. We will gather the projected nodes as we draw our
        // children, and then draw them on top of this node's content.
        std::vector<ProjectedChild> newList;
        for (auto& child : displayList->mChildNodes) {
            // our direct children are not supposed to project into us (nodes project to, at the
            // nearest, their grandparents). So we "delay" the list's activation one level by
            // passing it into mNextProjectedChildrenTarget rather than mProjectedChildrenTarget.
            child.mProjectedChildrenTarget = mNextProjectedChildrenTarget;
            child.mNextProjectedChildrenTarget = &newList;
        }
        // draw ourselves and our children. As a side effect, this will add projected nodes to
        // newList.
        this->drawContent(canvas);
        bool willClip = properties.getOutline().willClip();
        if (willClip) {
            canvas->save();
            clipOutline(properties.getOutline(), canvas, nullptr);
        }
        // draw the collected projected nodes
        for (auto& projectedChild : newList) {
            canvas->setMatrix(projectedChild.matrix);
            projectedChild.node->drawContent(canvas);
        }
        if (willClip) {
            canvas->restore();
        }
    } else {
        if (properties.getProjectBackwards() && mProjectedChildrenTarget) {
            // We are supposed to project this node, so add it to the list and do not actually draw
            // yet. It will be drawn by its projection receiver.
            mProjectedChildrenTarget->push_back({ this, canvas->getTotalMatrix() });
            return;
        }
        for (auto& child : displayList->mChildNodes) {
            // storing these values in the nodes themselves is a bit ugly; they should "really" be
            // function parameters, but we have to go through the preexisting draw() method and
            // therefore cannot add additional parameters to it
            child.mProjectedChildrenTarget = mNextProjectedChildrenTarget;
            child.mNextProjectedChildrenTarget = mNextProjectedChildrenTarget;
        }
        this->drawContent(canvas);
    }
    mProjectedChildrenTarget = nullptr;
    mNextProjectedChildrenTarget = nullptr;
}

static bool layerNeedsPaint(const LayerProperties& properties,
                            float alphaMultiplier, SkPaint* paint) {
    if (alphaMultiplier < 1.0f
            || properties.alpha() < 255
            || properties.xferMode() != SkBlendMode::kSrcOver
            || properties.colorFilter() != nullptr) {
        paint->setAlpha(properties.alpha() * alphaMultiplier);
        paint->setBlendMode(properties.xferMode());
        paint->setColorFilter(sk_ref_sp(properties.colorFilter()));
        return true;
    }
    return false;
}

void RenderNodeDrawable::drawContent(SkCanvas* canvas) const {
    RenderNode* renderNode = mRenderNode.get();
    float alphaMultiplier = 1.0f;
    const RenderProperties& properties = renderNode->properties();

    // If we are drawing the contents of layer, we don't want to apply any of
    // the RenderNode's properties during this pass. Those will all be applied
    // when the layer is composited.
    if (mComposeLayer) {
        setViewProperties(properties, canvas, &alphaMultiplier);
    }

    //TODO should we let the bound of the drawable do this for us?
    const SkRect bounds = SkRect::MakeWH(properties.getWidth(), properties.getHeight());
    bool quickRejected = properties.getClipToBounds() && canvas->quickReject(bounds);
    if (!quickRejected) {
        SkiaDisplayList* displayList = (SkiaDisplayList*)renderNode->getDisplayList();
        const LayerProperties& layerProperties = properties.layerProperties();
        // composing a hardware layer
        if (renderNode->getLayerSurface() && mComposeLayer) {
            SkASSERT(properties.effectiveLayerType() == LayerType::RenderLayer);
            SkPaint* paint = nullptr;
            SkPaint tmpPaint;
            if (layerNeedsPaint(layerProperties, alphaMultiplier, &tmpPaint)) {
                paint = &tmpPaint;
            }
            renderNode->getLayerSurface()->draw(canvas, 0, 0, paint);
        // composing a software layer with alpha
        } else if (properties.effectiveLayerType() == LayerType::Software) {
            SkPaint paint;
            bool needsLayer = layerNeedsPaint(layerProperties, alphaMultiplier, &paint);
            if (needsLayer) {
                canvas->saveLayer(bounds, &paint);
            }
            canvas->drawDrawable(displayList->mDrawable.get());
            if (needsLayer) {
                canvas->restore();
            }
        } else {
            canvas->drawDrawable(displayList->mDrawable.get());
        }
    }
}

void RenderNodeDrawable::setViewProperties(const RenderProperties& properties, SkCanvas* canvas,
        float* alphaMultiplier) {
    if (properties.getLeft() != 0 || properties.getTop() != 0) {
        canvas->translate(properties.getLeft(), properties.getTop());
    }
    if (properties.getStaticMatrix()) {
        canvas->concat(*properties.getStaticMatrix());
    } else if (properties.getAnimationMatrix()) {
        canvas->concat(*properties.getAnimationMatrix());
    }
    if (properties.hasTransformMatrix()) {
        if (properties.isTransformTranslateOnly()) {
            canvas->translate(properties.getTranslationX(), properties.getTranslationY());
        } else {
            canvas->concat(*properties.getTransformMatrix());
        }
    }
    const bool isLayer = properties.effectiveLayerType() != LayerType::None;
    int clipFlags = properties.getClippingFlags();
    if (properties.getAlpha() < 1) {
        if (isLayer) {
            clipFlags &= ~CLIP_TO_BOUNDS; // bounds clipping done by layer
        }
        if (CC_LIKELY(isLayer || !properties.getHasOverlappingRendering())) {
            *alphaMultiplier = properties.getAlpha();
        } else {
            // savelayer needed to create an offscreen buffer
            Rect layerBounds(0, 0, properties.getWidth(), properties.getHeight());
            if (clipFlags) {
                properties.getClippingRectForFlags(clipFlags, &layerBounds);
                clipFlags = 0; // all clipping done by savelayer
            }
            SkRect bounds = SkRect::MakeLTRB(layerBounds.left, layerBounds.top,
                    layerBounds.right, layerBounds.bottom);
            canvas->saveLayerAlpha(&bounds, (int) (properties.getAlpha() * 255));
        }

        if (CC_UNLIKELY(ATRACE_ENABLED() && properties.promotedToLayer())) {
            // pretend alpha always causes savelayer to warn about
            // performance problem affecting old versions
            ATRACE_FORMAT("alpha caused saveLayer %dx%d", properties.getWidth(),
                    properties.getHeight());
        }
    }

    const SkRect* pendingClip = nullptr;
    SkRect clipRect;

    if (clipFlags) {
        Rect tmpRect;
        properties.getClippingRectForFlags(clipFlags, &tmpRect);
        clipRect = tmpRect.toSkRect();
        pendingClip = &clipRect;
    }

    if (properties.getRevealClip().willClip()) {
        canvas->clipPath(*properties.getRevealClip().getPath(), SkRegion::kIntersect_Op, true);
    } else if (properties.getOutline().willClip()) {
        clipOutline(properties.getOutline(), canvas, pendingClip);
        pendingClip = nullptr;
    }

    if (pendingClip) {
        canvas->clipRect(*pendingClip);
    }
}

}; // namespace skiapipeline
}; // namespace uirenderer
}; // namespace android

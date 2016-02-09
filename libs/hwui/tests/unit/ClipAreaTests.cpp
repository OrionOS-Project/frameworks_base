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

#include <gtest/gtest.h>
#include <SkPath.h>
#include <SkRegion.h>

#include "ClipArea.h"

#include "Matrix.h"
#include "Rect.h"
#include "utils/LinearAllocator.h"

namespace android {
namespace uirenderer {

static Rect kViewportBounds(0, 0, 2048, 2048);

static ClipArea createClipArea() {
    ClipArea area;
    area.setViewportDimensions(kViewportBounds.getWidth(), kViewportBounds.getHeight());
    return area;
}

TEST(TransformedRectangle, basics) {
    Rect r(0, 0, 100, 100);
    Matrix4 minus90;
    minus90.loadRotate(-90);
    minus90.mapRect(r);
    Rect r2(20, 40, 120, 60);

    Matrix4 m90;
    m90.loadRotate(90);
    TransformedRectangle tr(r, m90);
    EXPECT_TRUE(tr.canSimplyIntersectWith(tr));

    Matrix4 m0;
    TransformedRectangle tr0(r2, m0);
    EXPECT_FALSE(tr.canSimplyIntersectWith(tr0));

    Matrix4 m45;
    m45.loadRotate(45);
    TransformedRectangle tr2(r, m45);
    EXPECT_FALSE(tr2.canSimplyIntersectWith(tr));
}

TEST(RectangleList, basics) {
    RectangleList list;
    EXPECT_TRUE(list.isEmpty());

    Rect r(0, 0, 100, 100);
    Matrix4 m45;
    m45.loadRotate(45);
    list.set(r, m45);
    EXPECT_FALSE(list.isEmpty());

    Rect r2(20, 20, 200, 200);
    list.intersectWith(r2, m45);
    EXPECT_FALSE(list.isEmpty());
    EXPECT_EQ(1, list.getTransformedRectanglesCount());

    Rect r3(20, 20, 200, 200);
    Matrix4 m30;
    m30.loadRotate(30);
    list.intersectWith(r2, m30);
    EXPECT_FALSE(list.isEmpty());
    EXPECT_EQ(2, list.getTransformedRectanglesCount());

    SkRegion clip;
    clip.setRect(0, 0, 2000, 2000);
    SkRegion rgn(list.convertToRegion(clip));
    EXPECT_FALSE(rgn.isEmpty());
}

TEST(ClipArea, basics) {
    ClipArea area(createClipArea());
    EXPECT_FALSE(area.isEmpty());
}

TEST(ClipArea, paths) {
    ClipArea area(createClipArea());
    SkPath path;
    SkScalar r = 100;
    path.addCircle(r, r, r);
    area.clipPathWithTransform(path, &Matrix4::identity(), SkRegion::kIntersect_Op);
    EXPECT_FALSE(area.isEmpty());
    EXPECT_FALSE(area.isSimple());
    EXPECT_FALSE(area.isRectangleList());

    Rect clipRect(area.getClipRect());
    Rect expected(0, 0, r * 2, r * 2);
    EXPECT_EQ(expected, clipRect);
    SkRegion clipRegion(area.getClipRegion());
    auto skRect(clipRegion.getBounds());
    Rect regionBounds;
    regionBounds.set(skRect);
    EXPECT_EQ(expected, regionBounds);
}

TEST(ClipArea, replaceNegative) {
    ClipArea area(createClipArea());
    area.setClip(0, 0, 100, 100);

    Rect expected(-50, -50, 50, 50);
    area.clipRectWithTransform(expected, &Matrix4::identity(), SkRegion::kReplace_Op);
    EXPECT_EQ(expected, area.getClipRect());
}

TEST(ClipArea, serializeClip) {
    ClipArea area(createClipArea());
    LinearAllocator allocator;

    // unset clip
    EXPECT_EQ(nullptr, area.serializeClip(allocator));

    // rect clip
    area.setClip(0, 0, 200, 200);
    {
        auto serializedClip = area.serializeClip(allocator);
        ASSERT_NE(nullptr, serializedClip);
        ASSERT_EQ(ClipMode::Rectangle, serializedClip->mode);
        auto clipRect = reinterpret_cast<const ClipRect*>(serializedClip);
        EXPECT_EQ(Rect(200, 200), clipRect->rect);
        EXPECT_EQ(serializedClip, area.serializeClip(allocator))
                << "Requery of clip on unmodified ClipArea must return same pointer.";
    }

    // rect list
    Matrix4 rotate;
    rotate.loadRotate(2.0f);
    area.clipRectWithTransform(Rect(200, 200), &rotate, SkRegion::kIntersect_Op);
    {
        auto serializedClip = area.serializeClip(allocator);
        ASSERT_NE(nullptr, serializedClip);
        ASSERT_EQ(ClipMode::RectangleList, serializedClip->mode);
        auto clipRectList = reinterpret_cast<const ClipRectList*>(serializedClip);
        EXPECT_EQ(2, clipRectList->rectList.getTransformedRectanglesCount());
        EXPECT_FALSE(clipRectList->rect.isEmpty());
        EXPECT_FLOAT_EQ(199.87817f, clipRectList->rect.right)
            << "Right side should be clipped by rotated rect";
        EXPECT_EQ(serializedClip, area.serializeClip(allocator))
                << "Requery of clip on unmodified ClipArea must return same pointer.";
    }

    // region
    SkPath circlePath;
    circlePath.addCircle(100, 100, 100);
    area.clipPathWithTransform(circlePath, &Matrix4::identity(), SkRegion::kReplace_Op);
    {
        auto serializedClip = area.serializeClip(allocator);
        ASSERT_NE(nullptr, serializedClip);
        ASSERT_EQ(ClipMode::Region, serializedClip->mode);
        auto clipRegion = reinterpret_cast<const ClipRegion*>(serializedClip);
        EXPECT_EQ(SkIRect::MakeWH(200, 200), clipRegion->region.getBounds())
                << "Clip region should be 200x200";
        EXPECT_EQ(Rect(200, 200), clipRegion->rect);
        EXPECT_EQ(serializedClip, area.serializeClip(allocator))
                << "Requery of clip on unmodified ClipArea must return same pointer.";
    }
}

TEST(ClipArea, serializeIntersectedClip) {
    ClipArea area(createClipArea());
    LinearAllocator allocator;

    // simple state;
    EXPECT_EQ(nullptr, area.serializeIntersectedClip(allocator, nullptr, Matrix4::identity()));
    area.setClip(0, 0, 200, 200);
    {
        auto origRectClip = area.serializeClip(allocator);
        ASSERT_NE(nullptr, origRectClip);
        EXPECT_EQ(origRectClip, area.serializeIntersectedClip(allocator, nullptr, Matrix4::identity()));
    }

    // rect
    {
        ClipRect recordedClip(Rect(100, 100));
        Matrix4 translateScale;
        translateScale.loadTranslate(100, 100, 0);
        translateScale.scale(2, 3, 1);
        auto resolvedClip = area.serializeIntersectedClip(allocator, &recordedClip, translateScale);
        ASSERT_NE(nullptr, resolvedClip);
        ASSERT_EQ(ClipMode::Rectangle, resolvedClip->mode);
        EXPECT_EQ(Rect(100, 100, 200, 200),
                reinterpret_cast<const ClipRect*>(resolvedClip)->rect);

        EXPECT_EQ(resolvedClip, area.serializeIntersectedClip(allocator, &recordedClip, translateScale))
                << "Must return previous serialization, since input is same";

        ClipRect recordedClip2(Rect(100, 100));
        EXPECT_NE(resolvedClip, area.serializeIntersectedClip(allocator, &recordedClip2, translateScale))
                << "Shouldn't return previous serialization, since matrix location is different";
    }

    // rect list
    Matrix4 rotate;
    rotate.loadRotate(2.0f);
    area.clipRectWithTransform(Rect(200, 200), &rotate, SkRegion::kIntersect_Op);
    {
        ClipRect recordedClip(Rect(100, 100));
        auto resolvedClip = area.serializeIntersectedClip(allocator, &recordedClip, Matrix4::identity());
        ASSERT_NE(nullptr, resolvedClip);
        ASSERT_EQ(ClipMode::RectangleList, resolvedClip->mode);
        auto clipRectList = reinterpret_cast<const ClipRectList*>(resolvedClip);
        EXPECT_EQ(2, clipRectList->rectList.getTransformedRectanglesCount());
    }

    // region
    SkPath circlePath;
    circlePath.addCircle(100, 100, 100);
    area.clipPathWithTransform(circlePath, &Matrix4::identity(), SkRegion::kReplace_Op);
    {
        SkPath ovalPath;
        ovalPath.addOval(SkRect::MakeLTRB(50, 0, 150, 200));

        ClipRegion recordedClip;
        recordedClip.region.setPath(ovalPath, SkRegion(SkIRect::MakeWH(200, 200)));

        Matrix4 translate10x20;
        translate10x20.loadTranslate(10, 20, 0);
        auto resolvedClip = area.serializeIntersectedClip(allocator, &recordedClip,
                translate10x20); // Note: only translate for now, others not handled correctly
        ASSERT_NE(nullptr, resolvedClip);
        ASSERT_EQ(ClipMode::Region, resolvedClip->mode);
        auto clipRegion = reinterpret_cast<const ClipRegion*>(resolvedClip);
        EXPECT_EQ(SkIRect::MakeLTRB(60, 20, 160, 200), clipRegion->region.getBounds());
    }
}

} // namespace uirenderer
} // namespace android

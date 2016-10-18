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

#include "androidfw/AttributeResolution.h"
#include "TestHelpers.h"
#include "data/styles/R.h"

#include <android-base/file.h>
#include <android-base/macros.h>

using namespace android;
using android::base::ReadFileToString;
using com::android::app::R;

class AttributeResolutionTest : public ::testing::Test {
 public:
  virtual void SetUp() override {
    std::string test_source_dir = TestSourceDir();
    std::string contents;
    LOG_ALWAYS_FATAL_IF(!ReadFileToString(test_source_dir + "/styles/resources.arsc", &contents));
    LOG_ALWAYS_FATAL_IF(
        table_.add(contents.data(), contents.size(), 1 /*cookie*/, true /*copyData*/) != NO_ERROR);
  }

 protected:
  ResTable table_;
};

class AttributeResolutionXmlTest : public AttributeResolutionTest {
 public:
  virtual void SetUp() override {
    AttributeResolutionTest::SetUp();
    std::string test_source_dir = TestSourceDir();
    std::string contents;
    LOG_ALWAYS_FATAL_IF(!ReadFileToString(test_source_dir + "/styles/layout.xml", &contents));
    LOG_ALWAYS_FATAL_IF(xml_parser_.setTo(contents.data(), contents.size(), true /*copyData*/) !=
                        NO_ERROR);

    // Skip to the first tag.
    while (xml_parser_.next() != ResXMLParser::START_TAG) {
    }
  }

 protected:
  ResXMLTree xml_parser_;
};

TEST_F(AttributeResolutionTest, Theme) {
  ResTable::Theme theme(table_);
  ASSERT_EQ(NO_ERROR, theme.applyStyle(R::style::StyleTwo));

  uint32_t attrs[] = {R::attr::attr_one, R::attr::attr_two, R::attr::attr_three,
                      R::attr::attr_four};
  std::vector<uint32_t> values;
  values.resize(arraysize(attrs) * 6);

  ASSERT_TRUE(ResolveAttrs(&theme, 0 /*def_style_attr*/, 0 /*def_style_res*/,
                           nullptr /*src_values*/, 0 /*src_values_length*/, attrs, arraysize(attrs),
                           values.data(), nullptr /*out_indices*/));

  const uint32_t public_flag = ResTable_typeSpec::SPEC_PUBLIC;

  const uint32_t* values_cursor = values.data();
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(1u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_STRING, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(3u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_NULL, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(Res_value::DATA_NULL_UNDEFINED, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);
}

TEST_F(AttributeResolutionXmlTest, XmlParser) {
  uint32_t attrs[] = {R::attr::attr_one, R::attr::attr_two, R::attr::attr_three,
                      R::attr::attr_four};
  std::vector<uint32_t> values;
  values.resize(arraysize(attrs) * 6);

  ASSERT_TRUE(RetrieveAttributes(&table_, &xml_parser_, attrs, arraysize(attrs), values.data(),
                                 nullptr /*out_indices*/));

  uint32_t* values_cursor = values.data();
  EXPECT_EQ(Res_value::TYPE_NULL, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_NULL, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(10u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_ATTRIBUTE, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(R::attr::attr_indirect, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);
}

TEST_F(AttributeResolutionXmlTest, ThemeAndXmlParser) {
  ResTable::Theme theme(table_);
  ASSERT_EQ(NO_ERROR, theme.applyStyle(R::style::StyleTwo));

  uint32_t attrs[] = {R::attr::attr_one, R::attr::attr_two, R::attr::attr_three, R::attr::attr_four,
                      R::attr::attr_five};
  std::vector<uint32_t> values;
  values.resize(arraysize(attrs) * 6);

  ASSERT_TRUE(ApplyStyle(&theme, &xml_parser_, 0 /*def_style_attr*/, 0 /*def_style_res*/, attrs,
                         arraysize(attrs), values.data(), nullptr /*out_indices*/));

  const uint32_t public_flag = ResTable_typeSpec::SPEC_PUBLIC;

  uint32_t* values_cursor = values.data();
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(1u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_STRING, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(10u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(3u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_STRING, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(R::string::string_one, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);
}

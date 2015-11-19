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
#ifndef TESTS_BENCHMARK_H
#define TESTS_BENCHMARK_H

#include "TestScene.h"

#include <string>
#include <vector>

namespace android {
namespace uirenderer {

struct BenchmarkOptions {
    int count;
};

typedef test::TestScene* (*CreateScene)(const BenchmarkOptions&);

template <class T>
test::TestScene* simpleCreateScene(const BenchmarkOptions&) {
    return new T();
}

struct BenchmarkInfo {
    std::string name;
    std::string description;
    CreateScene createScene;
};

class Benchmark {
public:
    Benchmark(const BenchmarkInfo& info) {
        registerBenchmark(info);
    }

private:
    Benchmark() = delete;
    Benchmark(const Benchmark&) = delete;
    Benchmark& operator=(const Benchmark&) = delete;

    static void registerBenchmark(const BenchmarkInfo& info);
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* TESTS_BENCHMARK_H */

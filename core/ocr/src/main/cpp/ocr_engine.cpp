/*
 * Copyright (C) 2025-2026 Fleey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "ocr_engine.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <numeric>

#include "image_utils.h"
#include "litert_config.h"
#include "logging.h"

#define TAG "OcrEngine"

namespace ppocrv5 {

    namespace {

        constexpr int kWarmupIterations = 3;
        constexpr int kWarmupImageSize = 128;

        constexpr float kMinBoxArea = 100.0f;
        constexpr float kMinConfidenceThreshold = 0.0f;
        constexpr int kMaxBoxesPerFrame = 50;
        constexpr float kTinyImageDirectRecognitionThreshold = 0.72f;
        constexpr float kTinyImageFallbackRecognitionThreshold = 0.45f;
        constexpr int kTinyImageShortSideThreshold = 64;
        constexpr int kTinyImageMinProcessingShortSide = 96;
        constexpr int kTinyImageMaxUpscaleFactor = 4;
        constexpr int kRecInputHeight = 48;
        constexpr int kRecInputWidth = 320;
        constexpr int kRecognitionWidthBucket = 32;

        int GetFallbackStartIndex(AcceleratorType requested) {
            switch (requested) {
                case AcceleratorType::kNpu:
                    return 0;
                case AcceleratorType::kGpu:
                    return 1;
                case AcceleratorType::kCpu:
                default:
                    return 2;
            }
        }

        constexpr std::array<AcceleratorType, 3> kAcceleratorCandidates = {
                AcceleratorType::kNpu,
                AcceleratorType::kGpu,
                AcceleratorType::kCpu,
        };

        const char *AcceleratorName(AcceleratorType type) {
            switch (type) {
                case AcceleratorType::kNpu:
                    return "NPU";
                case AcceleratorType::kGpu:
                    return "GPU";
                case AcceleratorType::kCpu:
                default:
                    return "CPU";
            }
        }

        inline void SortTopBoxesByArea(std::vector<RotatedRect> &boxes,
                                       std::vector<size_t> &indices,
                                       size_t limit) {
            indices.resize(boxes.size());
            std::iota(indices.begin(), indices.end(), 0);

            auto comparator = [&boxes](size_t a, size_t b) {
                return boxes[a].width * boxes[a].height > boxes[b].width * boxes[b].height;
            };

            if (indices.size() <= limit) {
                std::sort(indices.begin(), indices.end(), comparator);
                return;
            }

            auto middle = indices.begin() + static_cast<std::ptrdiff_t>(limit);
            std::partial_sort(indices.begin(), middle, indices.end(), comparator);
            indices.resize(limit);
        }

        bool ShouldUseTinyImagePath(int width, int height) {
            const int short_side = std::min(width, height);
            const int long_side = std::max(width, height);
            const float aspect_ratio = static_cast<float>(long_side) / std::max(short_side, 1);
            return short_side <= kTinyImageShortSideThreshold ||
                   (short_side <= 80 && aspect_ratio >= 2.2f) ||
                   (width * height <= 160 * 80);
        }

        int ComputeTinyImageUpscaleFactor(int width, int height) {
            const int short_side = std::max(1, std::min(width, height));
            const int factor = static_cast<int>(std::ceil(
                    static_cast<float>(kTinyImageMinProcessingShortSide) / short_side));
            return std::clamp(factor, 1, kTinyImageMaxUpscaleFactor);
        }

        bool IsEffectivelyFullImageBox(const RotatedRect &box, int width, int height) {
            const float image_area = static_cast<float>(width * height);
            if (image_area <= 0.0f) return false;

            const float box_area = box.width * box.height;
            if (box_area / image_area < 0.8f) return false;

            const float center_dx = std::abs(box.center_x - (width / 2.0f));
            const float center_dy = std::abs(box.center_y - (height / 2.0f));
            return center_dx <= width * 0.1f && center_dy <= height * 0.1f;
        }

        void UpscaleImageRgba(const uint8_t *image_data,
                             int width, int height, int stride,
                             int factor,
                             std::vector<uint8_t> *buffer,
                             int *out_width, int *out_height, int *out_stride) {
            const int scaled_width = width * factor;
            const int scaled_height = height * factor;
            buffer->resize(static_cast<size_t>(scaled_width) * scaled_height * 4);
            image_utils::ResizeBilinear(
                    image_data, width, height, stride,
                    buffer->data(), scaled_width, scaled_height);
            *out_width = scaled_width;
            *out_height = scaled_height;
            *out_stride = scaled_width * 4;
        }

        RotatedRect MakeFullImageBox(int width, int height) {
            RotatedRect box;
            box.center_x = width / 2.0f;
            box.center_y = height / 2.0f;
            box.width = static_cast<float>(width);
            box.height = static_cast<float>(height);
            box.angle = 0.0f;
            box.confidence = 1.0f;
            return box;
        }

        // Уточнение боксов по самому кадру.
        //
        // Детектор смотрит на кадр, ужатый в 640x640, и в плотном абзаце
        // склеивает соседние строки в один бокс высотой в полторы-две строки,
        // а одиночные строки отдаёт с большим запасом по высоте. Распознаватель
        // получает полоску 48 px, где текст занимает треть, — и читает кашу.
        //
        // Кадр же лежит рядом в полном разрешении. По каждому боксу считается
        // профиль «чернил» по строкам — сумма горизонтальных перепадов яркости:
        // на строке текста их много, в межстрочном промежутке нет. Полосы
        // профиля и есть строки: бокс обрезается до них, а если полос
        // несколько — режется на отдельные боксы. Наклонные боксы не трогаются:
        // профиль по осям для них не имеет смысла.
        constexpr float kRefineMaxAngleDeg = 8.0f;
        constexpr int kRefineMinHeightPx = 12;
        constexpr float kBandShareOfPeak = 0.4f;
        // Перепад между полом и пиком меньше этого — в боксе нет строк, есть шум.
        constexpr float kBandMinContrast = 1.5f;
        constexpr int kBandMinRows = 6;
        constexpr float kBandMinShareOfTallest = 0.35f;
        constexpr int kBandMergeGapRows = 2;
        constexpr float kBandMarginShare = 0.18f;

        void RefineBoxes(const uint8_t *image, int width, int height, int stride,
                         const std::vector<RotatedRect> &in, std::vector<RotatedRect> &out,
                         std::vector<float> &profile, std::vector<float> &smoothed) {
            out.clear();
            out.reserve(in.size());
            for (const auto &box: in) {
                float angle = std::fmod(box.angle, 180.0f);
                if (angle > 90.0f) angle -= 180.0f;
                if (angle < -90.0f) angle += 180.0f;
                float box_w = box.width;
                float box_h = box.height;
                if (std::fabs(std::fabs(angle) - 90.0f) < kRefineMaxAngleDeg) {
                    std::swap(box_w, box_h);
                } else if (std::fabs(angle) >= kRefineMaxAngleDeg) {
                    out.push_back(box);
                    continue;
                }

                const int x0 = std::clamp(static_cast<int>(box.center_x - box_w / 2.0f), 0, width - 2);
                const int x1 = std::clamp(static_cast<int>(box.center_x + box_w / 2.0f), x0 + 1, width - 1);
                const int y0 = std::clamp(static_cast<int>(box.center_y - box_h / 2.0f), 0, height - 1);
                const int y1 = std::clamp(static_cast<int>(box.center_y + box_h / 2.0f), y0 + 1, height);
                const int rows = y1 - y0;
                if (rows < kRefineMinHeightPx) {
                    out.push_back(box);
                    continue;
                }

                profile.assign(rows, 0.0f);
                float peak = 0.0f;
                for (int y = y0; y < y1; ++y) {
                    const uint8_t *row = image + static_cast<size_t>(y) * stride;
                    int energy = 0;
                    for (int x = x0; x < x1; ++x) {
                        // Зелёный канал RGBA — как яркость.
                        energy += std::abs(static_cast<int>(row[(x + 1) * 4 + 1]) - row[x * 4 + 1]);
                    }
                    const float value = static_cast<float>(energy) / (x1 - x0);
                    profile[y - y0] = value;
                    peak = std::max(peak, value);
                }
                if (peak <= 0.0f) {
                    out.push_back(box);
                    continue;
                }

                // Сглаживание по трём строкам: шум сенсора и JPEG колет
                // профиль, и одна колючая строка в межстрочье рвала бы промежуток.
                smoothed.assign(rows, 0.0f);
                for (int r = 0; r < rows; ++r) {
                    const float prev = profile[std::max(r - 1, 0)];
                    const float next = profile[std::min(r + 1, rows - 1)];
                    smoothed[r] = (prev + profile[r] + next) / 3.0f;
                }
                float floor_value = smoothed[0];
                float peak_value = smoothed[0];
                for (float v: smoothed) {
                    floor_value = std::min(floor_value, v);
                    peak_value = std::max(peak_value, v);
                }
                if (peak_value - floor_value <= kBandMinContrast) {
                    out.push_back(box);
                    continue;
                }

                // Полосы строк: подряд идущие строки профиля выше порога.
                // Порог — от пола профиля, а не от нуля: у бледного текста
                // на бежевом фон шумит на треть пика, и от нуля межстрочье
                // не отличалось бы от строки.
                const float threshold = floor_value + (peak_value - floor_value) * kBandShareOfPeak;
                struct Band {
                    int start;
                    int end;
                };
                std::vector<Band> bands;
                int run_start = -1;
                for (int r = 0; r <= rows; ++r) {
                    const bool ink = r < rows && smoothed[r] >= threshold;
                    if (ink) {
                        if (run_start < 0) run_start = r;
                        continue;
                    }
                    if (run_start >= 0) {
                        if (!bands.empty() && run_start - bands.back().end <= kBandMergeGapRows) {
                            bands.back().end = r;
                        } else {
                            bands.push_back({run_start, r});
                        }
                        run_start = -1;
                    }
                }

                int tallest = 0;
                for (const auto &band: bands) tallest = std::max(tallest, band.end - band.start);
                if (bands.empty() || tallest < kBandMinRows) {
                    out.push_back(box);
                    continue;
                }

                for (const auto &band: bands) {
                    const int band_rows = band.end - band.start;
                    // Огрызок соседней строки, задетый краем бокса, — не строка.
                    if (band_rows < kBandMinRows || band_rows < tallest * kBandMinShareOfTallest) continue;
                    const float margin = band_rows * kBandMarginShare;
                    const float top = std::max(static_cast<float>(y0), y0 + band.start - margin);
                    const float bottom = std::min(static_cast<float>(y1), y0 + band.end + margin);
                    RotatedRect line;
                    line.center_x = (x0 + x1) / 2.0f;
                    line.center_y = (top + bottom) / 2.0f;
                    line.width = static_cast<float>(x1 - x0);
                    line.height = bottom - top;
                    line.angle = 0.0f;
                    line.confidence = box.confidence;
                    out.push_back(line);
                }
            }
        }

        // Дубли после уточнения.
        //
        // Раздутые боксы соседних строк захватывают друг друга, и после
        // разрезания одна строка приходит два-три раза: полной полосой из
        // своего бокса и обрезком из чужого. Распознавать втрое больше
        // незачем, а обрезок ещё и читается хуже полной строки и спорит с ней
        // в разборе. Из перекрывающихся остаётся самый высокий — полная полоса.
        constexpr float kDuplicateOverlapShare = 0.5f;

        void DropDuplicateBoxes(std::vector<RotatedRect> &boxes, std::vector<size_t> &order) {
            order.resize(boxes.size());
            std::iota(order.begin(), order.end(), 0);
            std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
                return boxes[a].height > boxes[b].height;
            });

            std::vector<RotatedRect> kept;
            kept.reserve(boxes.size());
            for (size_t idx: order) {
                const auto &box = boxes[idx];
                bool duplicate = false;
                for (const auto &other: kept) {
                    const float vertical = std::min(box.center_y + box.height / 2, other.center_y + other.height / 2) -
                                           std::max(box.center_y - box.height / 2, other.center_y - other.height / 2);
                    const float horizontal = std::min(box.center_x + box.width / 2, other.center_x + other.width / 2) -
                                             std::max(box.center_x - box.width / 2, other.center_x - other.width / 2);
                    if (vertical >= std::min(box.height, other.height) * kDuplicateOverlapShare &&
                        horizontal >= std::min(box.width, other.width) * kDuplicateOverlapShare) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) kept.push_back(box);
            }
            boxes.swap(kept);
        }

        void ScaleBoxes(std::vector<RotatedRect> &boxes, float scale_factor) {
            for (auto &box: boxes) {
                box.center_x *= scale_factor;
                box.center_y *= scale_factor;
                box.width *= scale_factor;
                box.height *= scale_factor;
            }
        }

        int EstimateRecognitionTargetWidth(const RotatedRect &box) {
            float src_width = box.width;
            float src_height = box.height;
            if (src_width < src_height) {
                std::swap(src_width, src_height);
            }

            const float aspect_ratio = src_width / std::max(src_height, 1.0f);
            return std::clamp(static_cast<int>(kRecInputHeight * aspect_ratio), 1, kRecInputWidth);
        }

        void BucketRecognitionOrder(const std::vector<RotatedRect> &boxes,
                                    std::vector<size_t> &indices) {
            std::stable_sort(indices.begin(), indices.end(),
                             [&boxes](size_t a, size_t b) {
                                 const int width_a = EstimateRecognitionTargetWidth(boxes[a]);
                                 const int width_b = EstimateRecognitionTargetWidth(boxes[b]);
                                 const int bucket_a = width_a / kRecognitionWidthBucket;
                                 const int bucket_b = width_b / kRecognitionWidthBucket;
                                 if (bucket_a != bucket_b) {
                                     return bucket_a < bucket_b;
                                 }
                                 return width_a < width_b;
                             });
        }

        void SortResultsByReadingOrder(std::vector<OcrResult> &results) {
            std::sort(results.begin(), results.end(), [](const OcrResult &a, const OcrResult &b) {
                constexpr float kLineThreshold = 20.0f;
                if (std::abs(a.box.center_y - b.box.center_y) < kLineThreshold) {
                    return a.box.center_x < b.box.center_x;
                }
                return a.box.center_y < b.box.center_y;
            });
        }

    }  // namespace

    std::unique_ptr<OcrEngine> OcrEngine::Create(
            const std::string &det_model_path,
            const std::string &rec_model_path,
            const std::string &keys_path,
            AcceleratorType accelerator_type) {

        auto engine = std::unique_ptr<OcrEngine>(new OcrEngine());
        const int start_index = GetFallbackStartIndex(accelerator_type);

        // Ускоритель выбирается для каждой модели отдельно. Детектор на GPU
        // не компилируется — делегат не знает DEQUANTIZE и TRANSPOSE_CONV v4
        // из его графа, — и раньше это роняло на CPU обе модели разом. А узкое
        // место — распознаватель: два десятка строк по двадцать миллисекунд
        // против ста у детектора. Ему GPU и достаётся, детектору — что выйдет.
        AcceleratorType det_accelerator = AcceleratorType::kCpu;
        for (size_t i = start_index; i < kAcceleratorCandidates.size(); ++i) {
            const AcceleratorType candidate = kAcceleratorCandidates[i];
            LOGD(TAG, "TextDetector: trying %s", AcceleratorName(candidate));
            auto detector = TextDetector::Create(det_model_path, candidate);
            if (!detector) continue;
            engine->detector_ = std::move(detector);
            det_accelerator = candidate;
            break;
        }
        if (!engine->detector_) {
            LOGE(TAG, "Failed to initialize TextDetector with any accelerator");
            return nullptr;
        }

        AcceleratorType rec_accelerator = AcceleratorType::kCpu;
        for (size_t i = start_index; i < kAcceleratorCandidates.size(); ++i) {
            const AcceleratorType candidate = kAcceleratorCandidates[i];
            LOGD(TAG, "TextRecognizer: trying %s", AcceleratorName(candidate));
            auto recognizer = TextRecognizer::Create(rec_model_path, keys_path, candidate);
            if (!recognizer) continue;
            engine->recognizer_ = std::move(recognizer);
            rec_accelerator = candidate;
            break;
        }
        if (!engine->recognizer_) {
            LOGE(TAG, "Failed to initialize TextRecognizer with any accelerator");
            return nullptr;
        }

        // Наружу отдаётся ускоритель распознавателя: он и определяет скорость.
        engine->active_accelerator_ = rec_accelerator;
        LOGD(TAG, "OcrEngine initialized: detector on %s, recognizer on %s",
             AcceleratorName(det_accelerator), AcceleratorName(rec_accelerator));

        engine->WarmUp();
        return engine;
    }

    const std::vector<OcrResult> &OcrEngine::ProcessView(const uint8_t *image_data,
                                                         int width, int height, int stride) {
        results_buffer_.clear();

        if (!detector_ || !recognizer_) {
            LOGE(TAG, "OcrEngine not properly initialized");
            return results_buffer_;
        }

        auto total_start = std::chrono::high_resolution_clock::now();

        const uint8_t *processing_image_data = image_data;
        int processing_width = width;
        int processing_height = height;
        int processing_stride = stride;
        float processing_to_original_scale = 1.0f;
        const bool use_tiny_image_path = ShouldUseTinyImagePath(width, height);

        RecognitionResult direct_recognition_result;
        float direct_recognition_time_ms = 0.0f;
        bool has_direct_recognition_candidate = false;

        if (use_tiny_image_path) {
            const int upscale_factor = ComputeTinyImageUpscaleFactor(width, height);
            if (upscale_factor > 1) {
                UpscaleImageRgba(
                        image_data, width, height, stride, upscale_factor,
                        &upscale_buffer_,
                        &processing_width, &processing_height, &processing_stride);
                processing_image_data = upscale_buffer_.data();
                processing_to_original_scale = 1.0f / upscale_factor;
            }

            direct_recognition_result = recognizer_->Recognize(
                    processing_image_data,
                    processing_width,
                    processing_height,
                    processing_stride,
                    MakeFullImageBox(processing_width, processing_height),
                    &direct_recognition_time_ms);
            has_direct_recognition_candidate = !direct_recognition_result.text.empty();

            if (has_direct_recognition_candidate &&
                direct_recognition_result.confidence >= kTinyImageDirectRecognitionThreshold) {
                OcrResult result;
                result.text = std::move(direct_recognition_result.text);
                result.confidence = direct_recognition_result.confidence;
                result.box = MakeFullImageBox(width, height);
                results_buffer_.push_back(std::move(result));

                benchmark_.detection_time_ms = 0.0f;
                benchmark_.recognition_time_ms = direct_recognition_time_ms;
                benchmark_.total_time_ms = direct_recognition_time_ms;
                benchmark_.fps = (benchmark_.total_time_ms > 0.0f) ? (1000.0f / benchmark_.total_time_ms) : 0.0f;
                return results_buffer_;
            }
        }

        float detection_time_ms = 0.0f;
        const auto &boxes = detector_->DetectView(
                processing_image_data,
                processing_width,
                processing_height,
                processing_stride,
                &detection_time_ms);
        benchmark_.detection_time_ms = detection_time_ms;

        if (boxes.empty()) {
            if (has_direct_recognition_candidate &&
                direct_recognition_result.confidence >= kTinyImageFallbackRecognitionThreshold) {
                OcrResult result;
                result.text = std::move(direct_recognition_result.text);
                result.confidence = direct_recognition_result.confidence;
                result.box = MakeFullImageBox(width, height);
                results_buffer_.push_back(std::move(result));

                auto total_end = std::chrono::high_resolution_clock::now();
                benchmark_.recognition_time_ms = direct_recognition_time_ms;
                benchmark_.total_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                        total_end - total_start).count() / 1000.0f;
                benchmark_.fps = (benchmark_.total_time_ms > 0.0f) ? (1000.0f / benchmark_.total_time_ms) : 0.0f;
                return results_buffer_;
            }

            auto total_end = std::chrono::high_resolution_clock::now();
            benchmark_.total_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                    total_end - total_start).count() / 1000.0f;
            benchmark_.recognition_time_ms = 0.0f;
            benchmark_.fps = (benchmark_.total_time_ms > 0.0f) ? (1000.0f / benchmark_.total_time_ms) : 0.0f;
            return results_buffer_;
        }

        RefineBoxes(processing_image_data, processing_width, processing_height, processing_stride,
                    boxes, refined_boxes_buffer_, row_profile_buffer_, row_smoothed_buffer_);
        DropDuplicateBoxes(refined_boxes_buffer_, sorted_indices_buffer_);

        filtered_boxes_buffer_.clear();
        filtered_boxes_buffer_.reserve(refined_boxes_buffer_.size());
        const float min_box_area = use_tiny_image_path ? 16.0f : kMinBoxArea;

        for (const auto &box: refined_boxes_buffer_) {
            RotatedRect scaled_box = box;
            if (processing_to_original_scale != 1.0f) {
                scaled_box.center_x *= processing_to_original_scale;
                scaled_box.center_y *= processing_to_original_scale;
                scaled_box.width *= processing_to_original_scale;
                scaled_box.height *= processing_to_original_scale;
            }

            if (scaled_box.width * scaled_box.height >= min_box_area) {
                filtered_boxes_buffer_.push_back(scaled_box);
            }
        }

        if (filtered_boxes_buffer_.empty()) {
            if (has_direct_recognition_candidate &&
                direct_recognition_result.confidence >= kTinyImageFallbackRecognitionThreshold) {
                OcrResult result;
                result.text = std::move(direct_recognition_result.text);
                result.confidence = direct_recognition_result.confidence;
                result.box = MakeFullImageBox(width, height);
                results_buffer_.push_back(std::move(result));

                auto total_end = std::chrono::high_resolution_clock::now();
                benchmark_.recognition_time_ms = direct_recognition_time_ms;
                benchmark_.total_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                        total_end - total_start).count() / 1000.0f;
                benchmark_.fps = (benchmark_.total_time_ms > 0.0f) ? (1000.0f / benchmark_.total_time_ms) : 0.0f;
                return results_buffer_;
            }

            auto total_end = std::chrono::high_resolution_clock::now();
            benchmark_.total_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                    total_end - total_start).count() / 1000.0f;
            benchmark_.recognition_time_ms = 0.0f;
            benchmark_.fps = (benchmark_.total_time_ms > 0.0f) ? (1000.0f / benchmark_.total_time_ms) : 0.0f;
            return results_buffer_;
        }

        SortTopBoxesByArea(filtered_boxes_buffer_, sorted_indices_buffer_, kMaxBoxesPerFrame);
        recognition_order_buffer_ = sorted_indices_buffer_;
        BucketRecognitionOrder(filtered_boxes_buffer_, recognition_order_buffer_);

        if (use_tiny_image_path &&
            has_direct_recognition_candidate &&
            direct_recognition_result.confidence >= kTinyImageFallbackRecognitionThreshold &&
            sorted_indices_buffer_.size() == 1 &&
            IsEffectivelyFullImageBox(filtered_boxes_buffer_[sorted_indices_buffer_.front()], width, height)) {
            OcrResult result;
            result.text = std::move(direct_recognition_result.text);
            result.confidence = direct_recognition_result.confidence;
            result.box = filtered_boxes_buffer_[sorted_indices_buffer_.front()];
            results_buffer_.push_back(std::move(result));

            auto total_end = std::chrono::high_resolution_clock::now();
            benchmark_.recognition_time_ms = direct_recognition_time_ms;
            benchmark_.total_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                    total_end - total_start).count() / 1000.0f;
            benchmark_.fps = (benchmark_.total_time_ms > 0.0f) ? (1000.0f / benchmark_.total_time_ms) : 0.0f;
            return results_buffer_;
        }

        results_buffer_.reserve(recognition_order_buffer_.size());

        auto rec_start = std::chrono::high_resolution_clock::now();

        for (size_t idx: recognition_order_buffer_) {
            const auto &box = filtered_boxes_buffer_[idx];
            float rec_time_ms = 0.0f;
            RotatedRect recognition_box = box;
            if (processing_to_original_scale != 1.0f) {
                recognition_box.center_x /= processing_to_original_scale;
                recognition_box.center_y /= processing_to_original_scale;
                recognition_box.width /= processing_to_original_scale;
                recognition_box.height /= processing_to_original_scale;
            }

            auto rec_result = recognizer_->Recognize(processing_image_data,
                                                     processing_width,
                                                     processing_height,
                                                     processing_stride,
                                                     recognition_box,
                                                     &rec_time_ms);

            if (!rec_result.text.empty() && rec_result.confidence >= kMinConfidenceThreshold) {
                OcrResult result;
                result.text = std::move(rec_result.text);
                result.confidence = rec_result.confidence;
                result.box = box;
                results_buffer_.push_back(std::move(result));
            }
        }

        auto rec_end = std::chrono::high_resolution_clock::now();
        benchmark_.recognition_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                rec_end - rec_start).count() / 1000.0f;

        auto total_end = std::chrono::high_resolution_clock::now();
        benchmark_.total_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                total_end - total_start).count() / 1000.0f;
        benchmark_.fps = (benchmark_.total_time_ms > 0.0f) ? (1000.0f / benchmark_.total_time_ms) : 0.0f;
        SortResultsByReadingOrder(results_buffer_);

        LOGD(TAG, "OCR: %zu/%zu results, det=%.1fms, rec=%.1fms (%.1fms/box), total=%.1fms",
             results_buffer_.size(), filtered_boxes_buffer_.size(),
             benchmark_.detection_time_ms, benchmark_.recognition_time_ms,
             filtered_boxes_buffer_.size() > 0
             ? benchmark_.recognition_time_ms / filtered_boxes_buffer_.size()
             : 0.0f,
             benchmark_.total_time_ms);

        return results_buffer_;
    }

    std::vector<OcrResult> OcrEngine::Process(const uint8_t *image_data,
                                              int width, int height, int stride) {
        return ProcessView(image_data, width, height, stride);
    }

    Benchmark OcrEngine::GetBenchmark() const {
        return benchmark_;
    }

    AcceleratorType OcrEngine::GetActiveAccelerator() const {
        return active_accelerator_;
    }

    void OcrEngine::WarmUp() {
        LOGD(TAG, "Starting warm-up (%d iterations)...", kWarmupIterations);

        std::vector<uint8_t> dummy_image(kWarmupImageSize * kWarmupImageSize * 4, 128);
        for (int i = 0; i < kWarmupImageSize * kWarmupImageSize; ++i) {
            dummy_image[i * 4 + 0] = static_cast<uint8_t>((i * 7) % 256);
            dummy_image[i * 4 + 1] = static_cast<uint8_t>((i * 11) % 256);
            dummy_image[i * 4 + 2] = static_cast<uint8_t>((i * 13) % 256);
            dummy_image[i * 4 + 3] = 255;
        }

        for (int iter = 0; iter < kWarmupIterations; ++iter) {
            float detection_time_ms = 0.0f;
            detector_->Detect(dummy_image.data(), kWarmupImageSize, kWarmupImageSize,
                              kWarmupImageSize * 4, &detection_time_ms);

            float recognition_time_ms = 0.0f;
            recognizer_->Recognize(dummy_image.data(), kWarmupImageSize, kWarmupImageSize,
                                   kWarmupImageSize * 4,
                                   MakeFullImageBox(kWarmupImageSize, kWarmupImageSize),
                                   &recognition_time_ms);
        }

        LOGD(TAG, "Warm-up completed (accelerator: %s)", AcceleratorName(active_accelerator_));
    }

}  // namespace ppocrv5

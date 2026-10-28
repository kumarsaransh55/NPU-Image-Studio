# Third-party notices

NPU Image Studio uses the third-party software and data listed below. Each part keeps its own licence. The licence of NPU Image Studio's own code (see `LICENSE`) does **not** apply to them.

| Component | Where it is | Licence | In this repo? | In the APK? |
|---|---|---|---|---|
| ONNX Runtime 1.31 (Android) | Maven Central dependency | MIT | No (downloaded by Gradle) | Yes |
| MediaTek ONNX Runtime NeuroPilot plugin, **patched** | `app/src/main/jniLibs/arm64-v8a/libonnxruntime_providers_neuropilot.so` | MIT | Yes (binary + patch) | Yes |
| `libneuron_op_capability.so` (MediaTek prebuilt) | `app/src/main/jniLibs/arm64-v8a/` | MIT (release asset of the MIT repository) | Yes | Yes |
| TAESD decoder (ONNX) | `app/src/main/assets/taesd/` | MIT | Yes | Yes |
| CLIP tokenizer vocabulary | `app/src/main/assets/tokenizer/` | MIT | Yes | Yes |
| AndroidX, Jetpack Compose, Kotlin, kotlinx-coroutines | Maven dependencies | Apache 2.0 | No (downloaded by Gradle) | Yes |
| Gradle wrapper | `gradle/wrapper/` | Apache 2.0 | Yes | No |
| Stable Diffusion 1.5 models | Downloaded inside the app by the user | CreativeML OpenRAIL-M (+ MIT for LCM) | **No** | **No** |
| MediaTek NeuroPilot runtime `libneuronusdk_adapter.mtk.so` | `bundled` flavor only | **Proprietary** (MediaTek) | **No** | Only in the `bundled` APK |

---

## ONNX Runtime

- Project: https://github.com/microsoft/onnxruntime
- Used as: `com.microsoft.onnxruntime:onnxruntime-android:1.31.0` (app) and `com.microsoft.onnxruntime:onnxruntime:1.17.3` (PC test runner only).
- Licence: MIT, Copyright (c) Microsoft Corporation. ONNX Runtime contains other open-source components. They are listed in its own [ThirdPartyNotices.txt](https://github.com/microsoft/onnxruntime/blob/main/ThirdPartyNotices.txt).

## MediaTek ONNX Runtime NeuroPilot plugin (modified)

- Project: https://github.com/onnxruntime/onnxruntime-ep-neuropilot, commit `984ce9c` (v0.1.0).
- Licence: MIT, Copyright (c) Microsoft Corporation. Full text: [`third_party/neuropilot-ep/LICENSE-onnxruntime-ep-neuropilot.txt`](third_party/neuropilot-ep/LICENSE-onnxruntime-ep-neuropilot.txt).
- **Modified.** `libonnxruntime_providers_neuropilot.so` is rebuilt from that source with the changes in [`third_party/neuropilot-ep/npu-image-studio-fixes.patch`](third_party/neuropilot-ep/npu-image-studio-fixes.patch). Why and how: [`third_party/neuropilot-ep/README.md`](third_party/neuropilot-ep/README.md).
- `libneuron_op_capability.so` is MediaTek's closed-source prebuilt from the v0.1.0 GitHub release of the same repository, unchanged. The release ships no separate licence text for it, so it is assumed to fall under the repository's MIT licence.

## TAESD (Tiny AutoEncoder for Stable Diffusion)

- Project: https://github.com/madebyollin/taesd
- ONNX conversion used: https://huggingface.co/julienkay/taesd (MIT)
- Licence: MIT, Copyright (c) 2023 Ollin Boer Bohan

## CLIP tokenizer files

- `vocab.json` and `merges.txt` are the byte-pair-encoding vocabulary of OpenAI's CLIP, as used by Stable Diffusion 1.5.
- Project: https://github.com/openai/CLIP
- Licence: MIT, Copyright (c) 2021 OpenAI

## AndroidX, Jetpack Compose, Kotlin, kotlinx-coroutines, Gradle wrapper

- Licence: Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0
- Copyright: The Android Open Source Project, JetBrains s.r.o. and Kotlin Programming Language contributors, Gradle Inc.

## Stable Diffusion 1.5 models (not included)

No model is included in this repository or in the APK. The app downloads a model from Hugging Face only when the user taps **Download**, and the user can also import their own model.

| Model in the app's catalog | Source (ONNX conversion) | Licence |
|---|---|---|
| DreamShaper v7 LCM | https://huggingface.co/TheyCallMeHex/LCM-Dreamshaper-V7-ONNX | CreativeML OpenRAIL-M (DreamShaper) / MIT (LCM) |
| InstaFlow 0.9B | https://huggingface.co/TheyCallMeHex/InstaFlow-0.9B-ONNX | CreativeML OpenRAIL-M |
| epiCRealism | https://huggingface.co/TheyCallMeHex/epiCRealism-ONNX | CreativeML OpenRAIL-M |
| Deliberate v3 | https://huggingface.co/TheyCallMeHex/Deliberate-V3-ONNX | CreativeML OpenRAIL-M |
| Segmind Tiny-SD | https://huggingface.co/danyalmalik/tiny-sd-ONNX-WebGPU (base: https://huggingface.co/segmind/tiny-sd) | CreativeML OpenRAIL-M |
| BK-SDM Small | https://huggingface.co/danyalmalik/bk-sdm-small-2m-ONNX-WebGPU (base: https://huggingface.co/nota-ai/bk-sdm-small-2m) | CreativeML OpenRAIL-M |

The compact ONNX conversions are modified versions by danyalmalik: text encoder and UNet weights use fp16 internally with fp32 interfaces; the VAE decoder is fp32. Outputs are close to, but not identical to, the originals. The catalog pins these exports to immutable Hugging Face revisions and downloads their `LICENSE.md` alongside the model files. They are not relicensed under the app's GPL.

The CreativeML OpenRAIL-M licence includes **use restrictions** (its Attachment A) that every user of these models accepts: https://huggingface.co/spaces/CompVis/stable-diffusion-license

## MediaTek NeuroPilot runtime (`bundled` flavor only, not in this repository)

- File: `libneuronusdk_adapter.mtk.so`, version 8.0.10, from MediaTek's NeuroPilot runtime package (the same package Google's LiteRT downloads for Dimensity 9400 devices).
- Licence: MediaTek's *Terms and Conditions of Use for NeuroPilot Express SDK License*. This is **not** an open-source licence. In short:
  - The library may be distributed only in object-code form, **incorporated in an application used with MediaTek chipsets**. It may not be distributed on its own (section 1.1).
  - No reverse engineering, and nothing may put it under an open-source licence (sections 2.1 and 2.3).
  - MediaTek can revoke the licence at any time (section 8.1).
- Because of this, the file is **not** in this repository (see `.gitignore`), and NPU Image Studio's own licence does not cover it. To build the `bundled` flavor, get the runtime from MediaTek yourself, accept MediaTek's terms, and place the file in `app/src/bundled/jniLibs/arm64-v8a/`.
- The normal (`system`) build does not contain it. It uses the NeuroPilot runtime that the phone maker ships with the phone's software.

---

NPU Image Studio is not affiliated with or endorsed by MediaTek, vivo, Microsoft, Stability AI, OpenAI or Hugging Face. All trademarks belong to their owners.

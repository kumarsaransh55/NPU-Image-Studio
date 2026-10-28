# NPU Image Studio

**Offline Stable Diffusion 1.5 on Android that uses the phone's NPU (AI chip) on MediaTek Dimensity phones.**

<!-- TODO before publishing: replace YOUR-GITHUB-USER/npu-image-studio everywhere, and add 1–2 screenshots (Create tab and Engine tab; harmless prompts only). -->

> **This is a tinkering project, not a finished product.** It was built for one phone, a vivo X200T, and tested only on that phone. On it, the NPU makes small 256×256 pictures in about 2 seconds; bigger pictures run on the CPU. If you like poking at on-device AI, this is for you. If you want a polished image app, try [Local Dream](https://github.com/xororz/local-dream) or [SDAI](https://github.com/ShiftHackZ/Stable-Diffusion-Android) first.

## Why this exists

Most Android image generators use the NPU only on Qualcomm Snapdragon phones. On MediaTek phones they fall back to the CPU or GPU. In October 2026 MediaTek released an open-source [ONNX Runtime NeuroPilot plugin](https://github.com/onnxruntime/onnxruntime-ep-neuropilot). Version 0.1.0 could not run the Stable Diffusion UNet. This project fixes the plugin bugs that blocked it and builds an app around it.

## What it does

- **Create:** text to image, fully offline after the model download. You choose the size, steps, seed, and a fast or full-quality decoder.
- **Live mode:** the picture redraws as you type.
- **Animate:** a short MP4 that morphs from one prompt to another.
- **Models → Add model:** browse six ready-to-use SD 1.5 models, including compact Tiny-SD and BK-SDM downloads. Tap Download; pause and resume are supported, and manual folder import remains available for advanced users.
- **Engine:** shows which chip ran each part of the model, runs a speed self-test, and lets you pick NPU, GPU or CPU for each part. If a chip fails or returns a broken image, the app falls back to the next chip by itself.
- **Help:** a beginner guide inside the app.

The app uses the internet only to download models from Hugging Face. It has no account, no ads and no analytics.

## Measured results (vivo X200T)

Phone: vivo X200T, MediaTek **Dimensity 9400+** (MT6991), Origin OS 6.
Settings: DreamShaper v7 LCM, 4 steps, fast decoder (TAESD), measured after the first picture.

| Size | Main model (UNet) runs on | One picture |
|---|---|---|
| 256×256 | **NPU** | **≈ 2.2 s** |
| 384×384 | CPU | ≈ 17 s |
| 512×512 | CPU | ≈ 30–40 s |

- At 256×256 one UNet step takes ≈ 0.54 s on the NPU, versus 1.1–1.8 s on the CPU.
- The first NPU picture after installing compiles the model for the NPU. This takes about 1.5 minutes once; the result is cached.
- **Why only 256×256 on the NPU?** At 320, 384 and 512 the compile either fails (`NEURON_UNMAPPABLE`) or the compiled parts are refused at run time (`NEURON_BAD_DATA`) by MediaTek's closed NPU driver. The cause is unknown, so the app runs those sizes on the CPU straight away. Details are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#npu-details).

## Which phones use the NPU

| Phone chip | Main model runs on | Notes |
|---|---|---|
| MediaTek Dimensity 9400 / 9400+ | NPU up to 256×256, CPU above | Tested on a vivo X200T |
| Other recent MediaTek Dimensity | Maybe the NPU, otherwise the CPU | Untested. Run the self-test in the Engine tab and please report the result |
| Qualcomm Snapdragon, Google Tensor, Samsung Exynos, others | CPU | The NPU path uses MediaTek's NeuroPilot, which exists only on MediaTek chips |

The app works on any arm64 phone with Android 12 or newer. Without a usable NPU everything runs on the CPU, which is much slower. A GPU option exists, but it is experimental (see Known issues).

## Install

You need:
- an arm64 Android phone with **Android 12+**;
- enough storage for the chosen download plus **at least 0.5 GB spare**: compact models are about 1.1–1.4 GB, full models about 4.1 GB. NPU compile caches use additional space (about 1.7 GB for a full model);
- enough RAM: full models use about 4–5 GB while generating, so close other apps. Compact download size is **not** a guarantee of peak RAM usage.

Steps:
1. Download `NPU-Image-Studio-v1.4.apk` from the [Releases page](https://github.com/YOUR-GITHUB-USER/npu-image-studio/releases).
2. Open it on the phone. Allow **Install unknown apps** if Android asks.
3. Open the app. It measures your chips by itself on first start (under a minute).
4. **Models** tab → **Add model** → **DreamShaper v7 LCM** → **Download** (4.1 GB; use Wi-Fi). Choose **Segmind Tiny-SD** instead if download size matters most.
5. **Create** tab → type a prompt → **Generate**. Start with **256×256**.

> The APK on the Releases page uses the NeuroPilot runtime that ships with your phone's software. If the Engine tab says it *can't open libneuronusdk_adapter.mtk.so*, your phone maker doesn't let apps use that runtime, and the app will use the CPU.

## Adding your own models

For the easiest route, open **Models → Add model**, choose from the built-in catalog and tap **Download**. Only the catalog entries are built into the app, **not the model weights**. Downloads come directly from Hugging Face; no separate browser, account or PC conversion is needed. Once installed, tap **Use** in Models to select it.

| Model | Download | Default steps | Trade-off |
|---|---|---|---|
| Segmind Tiny-SD | 1.09 GB | 20 | Smallest download; distilled SD 1.5 with lower detail |
| BK-SDM Small (Nota AI) | 1.41 GB | 20 | Pruned SD 1.5; smaller than a full model |
| DreamShaper v7 LCM | 4.13 GB | 4 | Recommended for few-step speed |
| InstaFlow 0.9B | 4.13 GB | 1 | Fast drafts |
| epiCRealism / Deliberate v3 | 4.13 GB each | 20 | Full-size classic models |

Sizes are decimal GB and include the components needed for generation. The two compact exports use fp16 weights internally but **fp32 floating-point inputs and outputs**, so they use the existing engine without changes. They are classic SD 1.5 models, **not** LCM: smaller downloads do not necessarily beat DreamShaper LCM in generation time. Their NPU compatibility and phone performance have not been verified; the existing backend fallback still applies. Their CreativeML OpenRAIL-M licence and restrictions are separate from the app's GPL licence; the compact downloads include `LICENSE.md`.

### Advanced: import a folder

Any **Stable Diffusion 1.5** model works if it is in the Hugging Face **diffusers ONNX** layout, in **fp32**:

```
my_model/
  tokenizer/vocab.json
  tokenizer/merges.txt
  text_encoder/model.onnx
  unet/model.onnx            (+ unet/model.onnx_data for big models)
  vae_decoder/model.onnx
  studio_model.json          (optional)
```

Copy the folder to the phone, then **Models → Import a model folder…**. LCM models are detected automatically. The optional `studio_model.json` looks like this:

```json
{"name": "My model", "type": "lcm", "defaultSteps": 4, "defaultGuidance": 8.0}
```

`type` can be `lcm`, `sd15` or `rectified_flow`.

**Not supported:** ONNX exports requiring fp16 floating-point inputs/outputs, SDXL, SD 2.x, SD 3, FLUX, GGUF files. The compact catalog models are a specific mixed-precision exception with fp32 interfaces, not general support for fp16 exports. A `.safetensors` or `.ckpt` SD 1.5 model works after you convert it on a PC with Hugging Face [Optimum](https://huggingface.co/docs/optimum/index):

```bash
pip install "optimum[exporters]" diffusers
optimum-cli export onnx --model <model-name-or-folder> my_model_onnx/
```

## Known issues

- **Forcing "NPU via Android NNAPI" for the main model can crash the app.** Android's NNAPI cannot run the UNet. Leave the main model on **Auto**. If a chip crashes the app, the app switches that chip off the next time it starts, and the Engine tab shows a **Turn … back on** button.
- **The GPU can give wrong-looking pictures** on some phones, and on the X200T it was slower than the CPU. Use **Auto** or **CPU**.
- **NPU only up to 256×256** on the X200T; see above.
- **The full-quality image decoder fails on the NPU** (`NEURON_BAD_DATA`). The app falls back automatically. Turn on **Fast decoder** to keep decoding on the NPU (≈ 0.06 s).
- **The first NPU picture takes 1–2 minutes** because of the one-time compile.
- **Slow with the screen off:** some phones (e.g. Origin OS) slow down background apps. Allow the app's notification and background activity.
- **Only tested on one phone.** Reports from other MediaTek phones are very welcome. Please include the text from **Engine → Copy report**.

## Responsible use

This app has **no content filter**, and the models have no "safety checker". **You are responsible for what you generate and share.**

- The SD 1.5 models are licensed under [CreativeML OpenRAIL-M](https://huggingface.co/spaces/CompVis/stable-diffusion-license), which forbids some uses (for example harming, harassing or impersonating people, or generating illegal content). By downloading a model you accept its licence.
- Do not create illegal content. That includes any sexual content involving minors, and sexual, deceptive or defamatory images of real people without their consent.
- Follow the laws where you live and the rules of any platform where you post.

## Build from source

You need JDK 17 or newer and the Android SDK (Android Studio installs both). The SDK is found through `local.properties` (`sdk.dir=...`) or the `ANDROID_HOME` environment variable.

```bash
git clone https://github.com/YOUR-GITHUB-USER/npu-image-studio.git
cd npu-image-studio
./gradlew :app:assembleSystemDebug
```

On Windows use `gradlew.bat :app:assembleSystemDebug`. The APK is at `app/build/outputs/apk/system/debug/app-system-debug.apk`.

**New to Android builds?** The app is native **Kotlin + Jetpack Compose (Material 3)**, not Flutter or React Native. **Gradle** and the **Android Gradle Plugin** compile the code, merge the Android manifest and resources, package the ONNX Runtime/native libraries and assets, and sign the result as an **APK** (the file you install on your phone). Model weights are downloaded later, not bundled in the APK. See the [beginner build walkthrough and stack](docs/DEVELOPMENT.md#your-first-android-build) for Android Studio and PowerShell instructions.

Notes:
- **Flavors.** `system` (the normal build) uses the phone's own NeuroPilot runtime. `bundled` also packs MediaTek's NeuroPilot runtime into the APK. That runtime is proprietary and **not in this repository**; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **Debug and release builds have different signatures.** A debug build can't be installed over a release build. Uninstalling first deletes the downloaded models.
- **Release signing.** Release builds read `keystore.properties` in the project root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it they are signed with the debug key. Never commit the keystore or that file; both are in `.gitignore`.
- **Windows: "Unable to establish loopback connection".** If Gradle fails with this, point Java's Unix-socket folder at a short path before building, for example `set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\gtmp` (the folder must exist). `build-apk.bat` does this and builds both release flavors.
- **The patched NPU plugin.** `libonnxruntime_providers_neuropilot.so` is prebuilt from MediaTek's source plus [our patch](third_party/neuropilot-ep/npu-image-studio-fixes.patch). How to rebuild it: [third_party/neuropilot-ep/README.md](third_party/neuropilot-ep/README.md).
- **Testing the engine on a PC** (CPU only), folder layout, the NPU details and the developer commands over adb: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

Every push is built by GitHub Actions (`.github/workflows/build.yml`), and the debug APK is attached to the run.

**Publishing under another GitHub account:** follow the [account, signing and release walkthrough](docs/DEVELOPMENT.md#publishing-on-github-with-a-different-account). Git commit identity and the account authenticating a push are separate. Publish only this project folder and the system APK, never the signing files or the separate developer work folder.

## How it works (short version)

1. ONNX Runtime 1.31 for Android loads MediaTek's NeuroPilot plugin (`registerExecutionProviderLibrary`). The app attaches the NPU to each model session, with fixed input shapes because the NPU needs static shapes.
2. Before the first run, the app removes a useless `ConstantOfShape(0) * 0` from the UNet's attention layers (`OnnxGraphFixer`). At 512×512 it would otherwise add 2.5 GB of zeros.
3. The pipeline is plain Kotlin (`sdcore/`): CLIP tokenizer → text encoder → UNet loop (LCM, Euler or rectified-flow scheduler) → VAE or TAESD decoder.
4. Parts the NPU can't run go to the CPU automatically. Compiled NPU models are cached as ONNX "EPContext" files, so later starts are fast.

## Credits

- **MediaTek**: the [ONNX Runtime NeuroPilot plugin](https://github.com/onnxruntime/onnxruntime-ep-neuropilot) (MIT) and the NeuroPilot runtime.
- **Microsoft**: [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT).
- **Ollin Boer Bohan**: [TAESD](https://github.com/madebyollin/taesd) (MIT); ONNX conversion by [julienkay](https://huggingface.co/julienkay/taesd).
- **TheyCallMeHex**: the ONNX conversions of the catalog models on [Hugging Face](https://huggingface.co/TheyCallMeHex).
- **danyalmalik**: the compact [Tiny-SD](https://huggingface.co/danyalmalik/tiny-sd-ONNX-WebGPU) and [BK-SDM Small](https://huggingface.co/danyalmalik/bk-sdm-small-2m-ONNX-WebGPU) ONNX conversions.
- Model authors: Segmind (Tiny-SD), Nota AI (BK-SDM), DreamShaper (Lykon), Latent Consistency Models (Simian Luo et al.), InstaFlow (Xingchao Liu et al.), epiCRealism (epinikion), Deliberate (XpucT), and Stable Diffusion 1.5 (CompVis, Runway, Stability AI).
- **OpenAI**: the CLIP tokenizer (MIT). **Hugging Face**: diffusers, Optimum and model hosting.
- Built with a lot of help from an AI coding assistant (Anthropic's Claude), and tested on a real vivo X200T.

Full licence details: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Licence

Copyright (C) 2026 Saransh Kumar.

The app's own code is licensed under the [GNU General Public License v3.0](LICENSE). You may use, change and share it. If you share a changed version, you must share its source code under the same licence.

An [additional permission](LICENSE-ADDITIONAL-PERMISSION.md) allows combining the app with MediaTek's binary-only NeuroPilot libraries. Third-party parts keep their own licences ([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).

Not affiliated with or endorsed by MediaTek, vivo, Microsoft or Stability AI. All trademarks belong to their owners.

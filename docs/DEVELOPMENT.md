# NPU Image Studio: developer notes

Offline Stable Diffusion 1.5 for Android. It runs on the MediaTek NPU through MediaTek's ONNX Runtime **NeuroPilot plugin**, with automatic fallback to the GPU (WebGPU/Vulkan) and the CPU. It was built for the vivo X200T (Dimensity 9400+, MT6991), but should run on any arm64 Android 12+ phone; the NPU part works only on MediaTek chips.

For users, see the [README](../README.md) and the app's **Help** tab. This page is for people who want to change or rebuild the app.

## Layout

| Folder | What |
| --- | --- |
| `sdcore/` | The engine, in plain Kotlin with no Android code. It has a CLIP tokenizer, LCM / Euler / rectified-flow schedulers, the pipeline, morph animation, and `FallbackSessionProvider` (tries backends in order, falls back on errors or NaN output) |
| `app/` | The Android app (Jetpack Compose). `engine/` loads the NPU (`NpuSetup`), the backends (`AndroidBackends`) and crash protection (`Engine`). `models/` handles the catalog and downloads. `media/` has the MP4 encoder and gallery saving. `ui/` holds the screens |
| `app/src/main/jniLibs/arm64-v8a/` | MediaTek ONNX Runtime NeuroPilot plugin v0.1.0. `libonnxruntime_providers_neuropilot.so` is **rebuilt with fixes** (see `third_party/neuropilot-ep/`); `libneuron_op_capability.so` is MediaTek's prebuilt from https://github.com/onnxruntime/onnxruntime-ep-neuropilot/releases |
| `app/src/bundled/jniLibs/arm64-v8a/` | MediaTek public NeuroPilot runtime `libneuronusdk_adapter.mtk.so` (v8.0.10, from the NeuroPilot tarball that Google's LiteRT uses). Only included in the `bundled` flavor. **Not in the git repository**: MediaTek's licence doesn't allow sharing it on its own (see `THIRD_PARTY_NOTICES.md`). Add it yourself to build that flavor |
| `app/src/main/assets/` | TAESD decoder (ONNX, MIT) and the CLIP tokenizer files |
| `pctest/` | Command-line runner that tests the engine on a Windows or Linux PC (CPU) |

## Build the APKs (Windows)

### Your first Android build

An Android build turns the source project into an installable **APK**. You do not need to publish it or sign into GitHub to build it.

| Component | This project uses it for |
|---|---|
| Kotlin 2.2.20, JVM target 17 | App logic and the shared image-generation engine |
| Jetpack Compose + Material 3 | Native Android screens, buttons and state-driven UI |
| AndroidX ViewModel, StateFlow, Kotlin coroutines | Screen state, background downloads and generation |
| ONNX Runtime 1.31.0 | Running the downloaded text encoder, UNet and image decoder |
| MediaTek NeuroPilot plugin, NNAPI, experimental WebGPU/Vulkan, CPU | Available inference backends; device compatibility varies |
| Android media APIs | Saving pictures and encoding morph frames as MP4 |
| Gradle wrapper 8.14.3 + Android Gradle Plugin 8.13.0 | Dependency resolution, compilation, resource packaging and APK signing |
| Android SDK | Compile/target API 36; minimum supported phone API 31 (Android 12) |

**Install and open:**

1. Install Android Studio (includes a JDK). In **SDK Manager**, install **Android SDK Platform 36**, **Android SDK Build-Tools 35.0.0** (enable **Show Package Details**), and **Android SDK Platform-Tools**. Accept the SDK licences.
2. Open the **NPUImageStudio** folder (the one containing `settings.gradle.kts`), not its parent. Let Gradle sync finish; the first sync needs internet.
3. Use a JDK **17 or newer** as the Gradle JDK (Android Studio's bundled JDK works). Android Studio normally writes the SDK path into the ignored `local.properties`; command-line builds can also use `ANDROID_HOME`.
4. In Android Studio's **Build Variants** tool window choose **systemDebug**. Use **Build → Build Bundle(s) / APK(s) → Build APK(s)**, or run the PowerShell commands below.

**From PowerShell inside NPUImageStudio:**

```powershell
# Only needed if Java is not already configured; use your installed JDK path.
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleSystemDebug
```

The APK is `app\build\outputs\apk\system\debug\app-system-debug.apk`. Copy it to the phone and open it to install (allow **Install unknown apps** when asked). Alternatively, enable USB debugging, connect the phone, authorise this PC, and run:

```powershell
# Use the SDK location shown in SDK Manager if yours differs.
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r ".\app\build\outputs\apk\system\debug\app-system-debug.apk"
```

**What happens during the build:** Gradle downloads the declared dependencies, compiles Kotlin, processes the manifest/resources, converts JVM bytecode into Android DEX, packages native libraries and assets, then signs an APK. The application has no JavaScript server or cloud inference service. The model **catalog** ships in the APK; large model weights download only when the user requests them. A build does not generate an image or prove NPU compatibility.

**Debug vs release:** debug is for development and is automatically signed with a local debug key. For an APK you distribute, use `.\gradlew.bat :app:assembleSystemRelease` with your existing private release signing configuration; output: `app\build\outputs\apk\system\release\app-system-release.apk`. Without that configuration this project falls back to debug signing even for release, so check before distributing. A different signing key cannot update an already installed release. Do not uninstall casually: uninstalling removes downloaded models.

**Use `system` for public distribution.** It uses the phone's runtime. The `bundled` variant additionally packages the proprietary MediaTek runtime and has separate distribution restrictions; the existing script below builds both, so do not upload both by default. Keep the existing signing key and passwords private and backed up. The app code remains GPL-3.0 with its existing additional permission; model weights retain their own licences. No GitHub publication or account change is part of building.

### Existing release script

You need JDK 17+ and the Android SDK, with `local.properties` pointing to it (or `ANDROID_HOME` set). Then run:

```bat
build-apk.bat
```

Outputs:
- `app/build/outputs/apk/system/release/app-system-release.apk`
- `app/build/outputs/apk/bundled/release/app-bundled-release.apk` (only useful if you added `libneuronusdk_adapter.mtk.so`, see above)

`build-apk.bat` also copies them to `../APK/`.

Release signing reads `keystore.properties` from the project root. If that file is missing, release builds are signed with the debug key. Example (never commit this file or the keystore; both are in `.gitignore`):

```properties
storeFile=keystore/npu-studio.jks
storePassword=...
keyAlias=...
keyPassword=...
```

**Keep the keystore safe and backed up.** Every future release must be signed with the same key, so that an update installs over the old app without deleting the user's models.

> Why `build-apk.bat` sets `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=...`: on the developer's Windows PC, Java's Unix-socket temp folder path broke Gradle ("Unable to establish loopback connection"). Pointing it at a short folder fixes that. Other PCs usually don't need it.

## Test the engine on a PC

```bat
gradlew.bat :pctest:installDist
pctest\build\install\pctest\bin\pctest.bat --model <model folder> --taesd app\src\main\assets\taesd\vae_decoder.onnx --prompt "a red fox in the snow" --steps 4 --size 384 --out fox.png
```

Extra flags: `--tiny` (TAESD decoder), `--previews`, `--seed N`, `--guidance G`, `--negative "..."`, `--frames N --promptB "..."` (morph test), `--tokens "text"` (print token ids).

The PC runner uses ONNX Runtime 1.17.3. Newer Windows builds crash with the old `msvcp140.dll` that ships inside the JDK.

## Model folder format

This is the standard diffusers ONNX layout, plus an optional `studio_model.json`:

```
tokenizer/vocab.json, tokenizer/merges.txt
text_encoder/model.onnx
unet/model.onnx (+ unet/model.onnx_data)
vae_decoder/model.onnx
studio_model.json   {"name": "...", "type": "lcm" | "sd15" | "rectified_flow", "defaultSteps": 4, "defaultGuidance": 8.0}
```

If `type` is missing, LCM models are detected automatically from the UNet's `timestep_cond` input.

The compact catalog entries (Segmind Tiny-SD and BK-SDM Small) use single-file ONNX components, with no external weight sidecars. Their floating-point interfaces are fp32 despite fp16 internal weights. They use the existing SD15 scheduler and default to 20 steps / guidance 7.5, not LCM settings. Their file sizes and revision-pinned URLs are covered by `:app:testSystemDebugUnitTest`. Keep the existing full-model downloads unchanged when adding catalog entries; `ModelStore` already handles both single-file graphs and the existing UNet sidecars.

On 2026-10-28, both pinned downloads were checked against their upstream component SHA-256 hashes and generated a 256x256 fox image using the existing Windows CPU runner, 20 steps, guidance 7.5, seed 42 and the full VAE decoder. Tiny-SD took about 91 seconds and BK-SDM Small about 134 seconds in those desktop runs, including session setup. These are compatibility checks, not phone benchmarks or evidence of lower peak RAM; neither compact export has been verified on the phone/NPU.

Both exports also completed 512x512 generation with the same prompt/settings and the fast TAESD decoder. All four PNGs were checked for the expected dimensions and nonblank pixels, and visually inspected. The system debug and release APKs built successfully and both catalog unit tests passed. The release signature matches the existing signing certificate. No phone was connected for Android UI, download/resume or NPU testing; those checks remain necessary before claiming device compatibility for the compact models.

### Earlier local compact-model experiments

The separate `npu-studio-work` folder is a developer work area, **not another Android app project**. It contains conversion scripts, Hugging Face caches, upstream source, logs and earlier PC/device outputs. Its `smallsd/USER_STEPS.md` describes locally converted BK-SDM Tiny, BK-SDM Small and BK-SDM Tiny LCM models; `out/smallsd/results.txt` and the accompanying images record earlier CPU runs. In the copy inspected for this change, `models/` is empty: those records are historical evidence, not proof that those weights are still available.

Those local fp32 conversions are different from the public, mixed-precision catalog exports. In particular, the earlier Tiny LCM experiment reportedly needed 512x512 and explicit LCM selection. Do not apply those settings or its performance claims to Segmind Tiny-SD or BK-SDM Small in the catalog. A local conversion needs a complete, licensed public download before it can become a one-tap catalog entry; advanced folder import remains available without publishing it.

For a reproducible desktop check of either current catalog download, use the PC runner above with `--steps 20 --guidance 7.5 --seed 42`. Use `--size 256` first, then `--size 512`; repeat with `--tiny --taesd app\src\main\assets\taesd\vae_decoder.onnx` to exercise the fast decoder. Desktop success proves CPU pipeline compatibility, **not** Android/NPU compatibility. With the phone connected, check `adb devices`, install the APK with `adb install -r`, and test **Models -> Add model -> Download -> Use -> Create** on the phone. Start with **Auto** at 256x256, then try 512x512; record **Engine -> Copy report**. Do not enable the experimental big-NPU override for a normal user test.

## Publishing on GitHub with a different account

Building locally does not use a GitHub account. Publishing does. Do not publish the parent workspace, `npu-studio-work`, signing keys, downloaded models or developer caches; publish only the **NPUImageStudio** project folder.

1. Sign in to the intended personal GitHub account in your browser and create an **empty** `npu-image-studio` repository (no generated README or licence). Keep the existing GPL-3.0 licence, additional permission and third-party notices. Replace `YOUR-GITHUB-USER` in the project README with that account name.
2. In PowerShell, inside **NPUImageStudio**, initialise Git and set a **repository-local** author identity. Use the exact private noreply address shown in that account's **Settings -> Emails**, not the work address from global Git settings:

   ```powershell
   git init -b main
   git config --local user.name "YOUR DISPLAY NAME"
   git config --local user.email "YOUR EXACT GITHUB NOREPLY ADDRESS"
   git add --dry-run .
   ```

3. Review the dry-run list before staging. The existing `.gitignore` must exclude `keystore/`, `keystore.properties`, `local.properties`, build outputs and `app/src/bundled/jniLibs/`. Then stage, inspect and commit:

   ```powershell
   git add .
   git diff --cached --stat
   git diff --cached --name-only
   git commit -m "Initial public release"
   ```

4. Author name/email **does not choose the account that pushes**. For command-line authentication, run `gh auth login --hostname github.com --web` for the intended account, or `gh auth switch --hostname github.com --user YOUR-GITHUB-USER` if it is already saved. Check `gh api user --jq .login` and `gh auth status`, then configure Git to use that authenticated CLI account:

   ```powershell
   gh auth setup-git --hostname github.com
   git remote add origin https://github.com/YOUR-GITHUB-USER/npu-image-studio.git
   git remote -v
   git push -u origin main
   ```

   Authentication changes may affect other repositories on this PC. Switch back to the work account afterwards if needed. Never put tokens in remote URLs, source files or chat messages.

5. Check the repository's **Actions** build. Build your distributable locally with `.\gradlew.bat :app:assembleSystemRelease` and the existing private release signing configuration. Preserve the same signing key for future updates. Verify the signature and calculate the APK hash:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs ".\app\build\outputs\apk\system\release\app-system-release.apk"
   Get-FileHash ".\app\build\outputs\apk\system\release\app-system-release.apk" -Algorithm SHA256
   ```

6. In GitHub **Releases -> Draft a new release**, choose a tag matching `versionName` in `app/build.gradle.kts` (currently `v1.4`, if not already released). Attach **only the system release APK**, not the bundled-runtime APK, debug APK, keystore or model weights. Use the existing release-note draft in the parent workspace, update its checksum from the actual APK, and describe compact-model phone/NPU testing honestly. Publish corresponding source at that tag alongside the GPL licence and notices. If that version has already been distributed, increment `versionCode` and `versionName` before making a new release.

The GitHub account name and final permission to publish are deliberately not assumed. No repository creation, push or release is needed to build or test the app.

## NPU details

- The plugin is registered with `OrtEnvironment.registerExecutionProviderLibrary`. NPU devices come from `getEpDevices()` and are attached with `SessionOptions.addExecutionProvider(devices, ...)`.
- Symbolic dimensions are fixed with `setSymbolicDimensionValue`, because the NPU needs static shapes: batch 1, latent size = image size / 8, sequence length 77.
- Nodes the NPU can't run are assigned to the CPU by ONNX Runtime automatically.
- The UNet runs from `unet/model_studio2.onnx`, written once by `OnnxGraphFixer`: diffusers' baddbmm attention adds `ConstantOfShape(0) * 0`, which becomes 512 MB of zeros per layer at 512×512. Without it the saved NPU compile went over protobuf's 2 GB limit.
- The UNet goes to the NPU only up to 256×256 (`NeuroPilotBackend.MAX_UNET_TOKENS`). Tested on the X200T at 320, 384 and 512: the whole-UNet compile fails with `NEURON_UNMAPPABLE`. With the full-resolution self-attention left on the CPU (`NEURON_FLAG_SKIP_NODE_PREFIXES`), every part compiles, but the runtime then refuses one part's `setInput`/`setOutput` with `NEURON_BAD_DATA`. The bigger the image, the earlier that happens; padded sizes, aligned or shared buffers didn't change it. Put an empty `npu_try_big.txt` in `Android/data/studio.npu.x200t/files/` to try bigger sizes anyway.
- First compile results are saved as EPContext models (`ep.context_enable`, sidecar `.bin` files) in `files/npu_cache/`.
- Developer commands over USB (adb) go to `engine/WorkService`; see its header comment. Logs: `Android/data/studio.npu.x200t/files/app_log.txt` and `npu_plugin_log.txt`.
- `accelerator_in_use.txt` is a marker file that exists only while NPU/GPU work runs. If it is still there at startup, that accelerator is switched off (it crashed the app).

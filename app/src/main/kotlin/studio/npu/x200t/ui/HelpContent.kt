package studio.npu.x200t.ui

/** One piece of a help page. `**text**` inside a string is shown bold. */
sealed interface HelpBlock {
    data class Text(val text: String) : HelpBlock
    data class Bullets(val items: List<String>) : HelpBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : HelpBlock
    data class Tip(val text: String) : HelpBlock
}

data class HelpTopic(val title: String, val blocks: List<HelpBlock>)

data class HelpSection(val title: String, val summary: String, val topics: List<HelpTopic>)

private fun text(s: String) = HelpBlock.Text(s)
private fun bullets(vararg items: String) = HelpBlock.Bullets(items.toList())
private fun tip(s: String) = HelpBlock.Tip(s)
private fun table(header: List<String>, vararg rows: List<String>) = HelpBlock.Table(header, rows.toList())

/** Everything shown in the Help tab: menus (sections) with sub-menus (topics). */
val HELP: List<HelpSection> = listOf(
    HelpSection(
        "Start here",
        "What the app does and how to make your first picture",
        listOf(
            HelpTopic(
                "What this app does",
                listOf(
                    text("NPU Image Studio makes pictures from a text description, **on your phone, without internet**. It runs Stable Diffusion 1.5 models, the same kind of AI that many websites use, but here everything stays on the phone."),
                    text("It also tries to use your phone's **NPU**, a chip made only for AI, to make pictures faster. Where the NPU can't help, it uses the normal processor (CPU)."),
                    tip("It's a tinkering tool for exploring what your phone can do, not a professional design tool. Small phone models are great at moods, scenes and styles, but not at exact things like logos or text."),
                ),
            ),
            HelpTopic(
                "Your first picture",
                listOf(
                    bullets(
                        "**Models tab → Add model:** choose a ready-to-use download. DreamShaper v7 LCM (about 4.1 GB) is the best start for speed; Segmind Tiny-SD (about 1.1 GB) is the smallest download. Use Wi-Fi. This is needed only once.",
                        "**Engine tab:** tap **Run NPU self-test**. It takes about a minute and finds the fastest chip on your phone. (The app also does this by itself the first time.)",
                        "**Create tab:** describe the picture, for example \"a cozy cabin in a snowy forest at night, warm light in the windows\", and tap **Generate**.",
                        "Tap **Save** to keep it. Pictures go to Gallery → Pictures → NPU Image Studio.",
                    ),
                    tip("The very first picture on the NPU can take a minute or two longer: the phone translates (compiles) the model for its NPU once and saves the result."),
                ),
            ),
            HelpTopic(
                "Offline, privacy and content",
                listOf(
                    text("After a model is downloaded, the app needs **no internet** and sends nothing anywhere. Your prompts and pictures stay on your phone."),
                    text("The app has **no content filter**, and the models have no \"safety checker\". You are responsible for what you make. Don't create anything illegal: no sexual content involving minors, and no fake intimate pictures of real people."),
                ),
            ),
        ),
    ),
    HelpSection(
        "How AI pictures are made",
        "The idea behind it, in plain words",
        listOf(
            HelpTopic(
                "The idea in one minute",
                listOf(
                    text("The model learned from millions of pictures with captions. To make a new picture it does something surprising: it **starts from random noise** (like TV static) and removes a bit of noise in each **step**, guided by your words, until a picture appears."),
                    text("To save work, this happens on a small \"sketch\" that is 8 times smaller than the final picture (a 512×512 picture is worked on as 64×64). At the end, a decoder turns the sketch into real pixels."),
                ),
            ),
            HelpTopic(
                "The parts of a model",
                listOf(
                    table(
                        listOf("Part", "What it does", "Cost"),
                        listOf("Tokenizer", "Cuts your words into pieces (max 75)", "Tiny"),
                        listOf("Text encoder", "Turns the words into numbers the model understands", "Small, once per picture"),
                        listOf("UNet (main model)", "Removes noise, once per step", "Big: this decides the speed"),
                        listOf("Decoder (VAE)", "Turns the finished sketch into pixels", "Medium, once per picture"),
                        listOf("Fast decoder (TAESD)", "Same job, ~25× faster, slightly softer", "Tiny"),
                    ),
                ),
            ),
            HelpTopic(
                "What steps do",
                listOf(
                    text("Each step runs the UNet once (classic models run it twice per step, see Create → Steps). More steps = more chances to refine, but also more time."),
                    text("**Picture time ≈ steps × time per step**, plus a little for reading the prompt and decoding."),
                    text("Special fast models (LCM, InstaFlow) were trained to finish in 1–4 steps instead of 20+. That's why they are the best choice on a phone."),
                ),
            ),
            HelpTopic(
                "Why logos and text don't work",
                listOf(
                    text("Stable Diffusion 1.5 models can't spell and don't copy logos. Asking for \"the Amazon logo\" gives something logo-like, not the real one. This is a limit of these models, not of your phone."),
                    text("At 256×256 there is even less room for detail. Newer, much bigger models (SDXL, FLUX) are better at text, but they are too big for phones today."),
                    tip("Good prompts describe a scene, a style and the light: \"portrait of an old fisherman, wrinkles, natural light, 85mm photo, highly detailed\"."),
                ),
            ),
        ),
    ),
    HelpSection(
        "The Create tab",
        "Every setting explained",
        listOf(
            HelpTopic(
                "Describe the image and Avoid",
                listOf(
                    text("**Describe the image** is your prompt. The counter shows how many of the 75 word pieces you used; anything after that is ignored."),
                    text("**Avoid (negative prompt)** lists things you don't want, for example \"blurry, extra fingers, text\". Only classic models (epiCRealism, Deliberate) use it; LCM and InstaFlow ignore it."),
                ),
            ),
            HelpTopic(
                "Size",
                listOf(
                    text("These models learned on 512×512, so 512 looks best. Smaller sizes are much faster but show less detail. 512×768 (tall) and 768×512 (wide) are good for portraits and landscapes."),
                    text("On phones where the NPU works, it handles the main model **up to 256×256**. Bigger sizes run on the CPU. The badge at the top of Create shows where the main model will run for the size you picked."),
                    tip("A good way to work: try prompts and seeds at 256 (fast), then make the final picture at 512 with the same seed. It won't be identical, but close in idea."),
                ),
            ),
            HelpTopic(
                "Steps",
                listOf(
                    table(
                        listOf("Model type", "Good steps", "Note"),
                        listOf("LCM (DreamShaper LCM)", "4 (2 fast, 6–8 sharper)", "Best balance"),
                        listOf("InstaFlow", "1", "Made for one step"),
                        listOf("Classic SD 1.5", "15–25", "Each step runs twice (prompt + Avoid), so ~10× slower than LCM"),
                    ),
                ),
            ),
            HelpTopic(
                "Prompt strength",
                listOf(
                    text("How strictly the model follows your words. Higher = follows them more closely but can look harsh or over-saturated. Lower = freer, softer pictures."),
                    text("The app picks a good value for each model (about 7–8). Change it only to experiment."),
                ),
            ),
            HelpTopic(
                "Seed",
                listOf(
                    text("The seed is the number the random starting noise comes from. **Same seed + same prompt + same settings = same picture.**"),
                    text("Turn **random** off to keep a seed, then change one word at a time and see what it does. **New seed** gives a fresh picture."),
                ),
            ),
            HelpTopic(
                "Fast decoder and Live mode",
                listOf(
                    text("**Fast decoder (TAESD)** makes the last step about 25× faster, with slightly softer details. Recommended, especially on the CPU where the full decoder can take 10–40 seconds at 512."),
                    text("**Live mode** redraws the picture while you type. It uses fewer steps, the fast decoder and a fixed seed. Fun with InstaFlow or LCM at 256."),
                ),
            ),
            HelpTopic(
                "Reading the result",
                listOf(
                    text("Under each picture the app shows the total time and where each part ran, for example \"Main model (UNet): 4 × 0.54 s on NPU (NeuroPilot)\"."),
                    text("The first picture after opening the app also shows **One-time loading/compiling**: the model being loaded into memory (or compiled for the NPU). The next pictures skip it."),
                    text("If a chip fails, the app switches to the next one by itself and shows a yellow box saying what happened."),
                ),
            ),
        ),
    ),
    HelpSection(
        "The Animate tab",
        "Short videos made from pictures",
        listOf(
            HelpTopic(
                "What it makes",
                listOf(
                    text("Real AI video is still too heavy for phones. Instead, Animate makes a **morph video**: it generates every frame as a real picture while sliding from the **Start** description to the **End** description, then saves an MP4 to Gallery → Movies → NPU Image Studio."),
                    text("Leave End empty to morph between two random variations of the same prompt."),
                ),
            ),
            HelpTopic(
                "Frames, loop and time",
                listOf(
                    bullets(
                        "**Frames to generate:** each frame is one full picture, so 16 frames take as long as 16 pictures.",
                        "**Loop back to the start** plays the frames forward and back: double the length for free.",
                        "**Playback speed (fps):** frames per second. 16 frames + loop at 8 fps ≈ 4 seconds of video.",
                    ),
                    tip("Use a fast setup for videos: LCM or InstaFlow, a small size, and the fast decoder."),
                ),
            ),
        ),
    ),
    HelpSection(
        "Models",
        "Which model to use, and how to add your own",
        listOf(
            HelpTopic(
                "Built-in models",
                listOf(
                    table(
                        listOf("Model", "Steps", "Best for"),
                        listOf("Segmind Tiny-SD", "~20", "Smallest download: 1.1 GB; lower detail"),
                        listOf("BK-SDM Small", "~20", "Compact pruned SD 1.5: 1.4 GB"),
                        listOf("DreamShaper v7 LCM ⭐", "4", "All-round: people, art, fantasy, photos"),
                        listOf("InstaFlow 0.9B", "1", "Fastest; live typing, quick ideas"),
                        listOf("epiCRealism", "~20", "Photo-realistic people and scenes (slow)"),
                        listOf("Deliberate v3", "~20", "All-round, detailed (slow)"),
                    ),
                    text("Open **Models → Add model** and tap **Download**. Compact models are **1.1–1.4 GB**, full models about **4.1 GB**. Downloads are automatic and can be paused/resumed; no PC or browser needed. Tap **Use** after installing. All are community models from Hugging Face; their licences and use restrictions are separate from the app's GPL licence."),
                ),
            ),
            HelpTopic(
                "Which model for your phone's memory",
                listOf(
                    text("While making a picture the **full-size models** need about **4–5 GB of free memory (RAM)**. Compact downloads save storage, but their peak RAM use depends on the backend and image size; do not treat download size as a RAM requirement. For full models, as a rough guide:"),
                    table(
                        listOf("Phone RAM", "What to expect"),
                        listOf("16 GB or more", "Everything, any size"),
                        listOf("12 GB", "Everything; close big apps first (tested on a 12 GB phone)"),
                        listOf("8 GB", "Should work at 256–384; 512 may close the app"),
                        listOf("6 GB or less", "Likely to run out of memory with these 4 GB models"),
                    ),
                    tip("If the app closes while generating, it ran out of memory. Close other apps, use a smaller size, and tap Engine → Free memory between models."),
                ),
            ),
            HelpTopic(
                "Adding your own model",
                listOf(
                    text("Any **Stable Diffusion 1.5** model in the **diffusers ONNX** folder layout works. The folder must contain:"),
                    bullets(
                        "tokenizer/vocab.json and tokenizer/merges.txt",
                        "text_encoder/model.onnx",
                        "unet/model.onnx (plus unet/model.onnx_data for big models)",
                        "vae_decoder/model.onnx",
                    ),
                    text("Copy the folder to the phone (for example into Download), then **Models → Import a model folder…**, pick the folder and choose its type. LCM models are detected automatically."),
                    tip("The app makes its own copy, so you need about twice the model's size free while importing. Afterwards you can delete the copy in Download."),
                ),
            ),
            HelpTopic(
                "Where to find models",
                listOf(
                    text("Search Hugging Face for **\"stable diffusion 1.5 onnx\"** and pick repositories that have the folders above. The built-in models come from the user TheyCallMeHex, who has more SD 1.5 ONNX models."),
                    text("These **don't work** in this app:"),
                    bullets(
                        "**GGUF** files: they are for other engines (llama.cpp, stable-diffusion.cpp).",
                        "**.safetensors / .ckpt** files (e.g. from Civitai): they work after converting to ONNX on a PC (next topic).",
                        "**SDXL, SD 2.x, SD 3, FLUX**: different, much bigger models.",
                        "**ONNX exports with fp16 floating-point inputs/outputs**: the app expects fp32 interfaces. The compact catalog entries have fp16 weights internally but keep fp32 interfaces; this does not enable arbitrary fp16 imports.",
                    ),
                ),
            ),
            HelpTopic(
                "Converting a model on a PC",
                listOf(
                    text("With Python on a PC you can turn almost any SD 1.5 model into the right format using Hugging Face's free **Optimum** tool:"),
                    bullets(
                        "pip install \"optimum[exporters]\" diffusers",
                        "For a model on Hugging Face: optimum-cli export onnx --model <name-or-folder> my_model_onnx/",
                        "For a single .safetensors file, first load it with diffusers' StableDiffusionPipeline.from_single_file(\"model.safetensors\") and save it with save_pretrained(\"my_model\"), then run the export on that folder.",
                    ),
                    text("Copy the resulting folder to the phone and import it."),
                ),
            ),
            HelpTopic(
                "Smaller models",
                listOf(
                    text("**Models → Add model → Smaller downloads** offers **Segmind Tiny-SD (1.1 GB)** and **BK-SDM Small by Nota AI (1.4 GB)**. Tap Download; no manual conversion or separate download site is needed. Advanced folder import is still available."),
                    text("Both are classic SD 1.5 models: start with **20 steps**, prompt strength **7.5**, and **Fast decoder**. Smaller downloads do not necessarily generate faster than 4-step DreamShaper LCM, and details may be weaker. NPU support and phone performance for these exports are unverified; the normal backend fallback applies."),
                ),
            ),
            HelpTopic(
                "Storage used",
                listOf(
                    bullets(
                        "Compact models: about 1.1–1.4 GB each; full models: about 4.1 GB each. Keep at least another 0.5 GB free for downloading. Delete models in the Models tab.",
                        "Saved NPU compile: about 1.7 GB per model and size. Delete it in Engine → NPU compile cache (it is rebuilt on the next NPU picture).",
                        "Your pictures and videos: in the Gallery like any photo.",
                    ),
                ),
            ),
        ),
    ),
    HelpSection(
        "Your phone's chips",
        "CPU, GPU and NPU, and which phones use the NPU",
        listOf(
            HelpTopic(
                "CPU, GPU and NPU",
                listOf(
                    table(
                        listOf("Chip", "Like…", "In this app"),
                        listOf("CPU", "A few very smart workers", "Always works; slowest for AI"),
                        listOf("GPU", "Thousands of simple workers (graphics)", "Experimental; often not faster here"),
                        listOf("NPU", "A factory built only for AI", "Fastest, when the app can reach it"),
                    ),
                    text("The difficulty is that every phone maker's NPU speaks its own language. An app has to use the maker's own tools to reach it."),
                ),
            ),
            HelpTopic(
                "Which phones use the NPU",
                listOf(
                    text("This app reaches the NPU through **MediaTek's** tools, so the NPU part is for MediaTek Dimensity phones. On every other phone the app still works, on the CPU."),
                    table(
                        listOf("Phone chip", "Main model", "Notes"),
                        listOf("MediaTek Dimensity 9400 / 9400+", "NPU up to 256×256, CPU above", "Tested on a vivo X200T"),
                        listOf("Other recent MediaTek Dimensity", "Maybe NPU, otherwise CPU", "Untested: run the NPU self-test"),
                        listOf("Qualcomm Snapdragon", "CPU", "Needs Qualcomm's own tools; the Local Dream app uses the Snapdragon NPU"),
                        listOf("Google Tensor (Pixel)", "CPU", ""),
                        listOf("Samsung Exynos", "CPU", ""),
                    ),
                ),
            ),
            HelpTopic(
                "How long will it take",
                listOf(
                    text("Measured on a Dimensity 9400+ phone with DreamShaper LCM, 4 steps, fast decoder, after the first (loading) picture:"),
                    table(
                        listOf("Size", "Main model on", "One picture"),
                        listOf("256×256", "NPU", "≈ 2 s"),
                        listOf("256×256", "CPU", "≈ 7 s"),
                        listOf("384×384", "CPU", "≈ 17 s"),
                        listOf("512×512", "CPU", "≈ 30–40 s"),
                    ),
                    bullets(
                        "Classic models (20 steps + Avoid) take about 10× longer.",
                        "Phones with older or mid-range chips can be several times slower.",
                        "The first picture after opening the app adds loading time; the first NPU picture after installing adds a one-time compile (1–2 minutes).",
                    ),
                ),
            ),
            HelpTopic(
                "Heat, battery and screen-off",
                listOf(
                    text("AI work is heavy: the phone gets warm and long sessions use battery. The NPU uses much less energy than the CPU for the same work."),
                    text("While working with the screen off, the app shows a **\"Working on your image…\"** notification. It keeps Android from pausing the app; allow notifications when asked. Some phones also need Settings → Battery → this app → **allow background activity**."),
                ),
            ),
        ),
    ),
    HelpSection(
        "The Engine tab",
        "NPU status, hardware choices and tests",
        listOf(
            HelpTopic(
                "NPU status and self-test",
                listOf(
                    text("**NPU status** shows whether the app could connect to the NPU. **Run NPU self-test** runs the small fast decoder on every chip and shows the time for each; Auto then uses the fastest."),
                    text("**Copy report** copies the details, handy for asking for help."),
                ),
            ),
            HelpTopic(
                "Where each part runs",
                listOf(
                    text("Each part of the model (text encoder, UNet, decoders) has its own menu:"),
                    bullets(
                        "**Auto** (recommended): the fastest chip measured on your phone, with automatic fallback.",
                        "**A specific chip:** use that one; if it fails, the app quietly uses the CPU.",
                        "**Strict mode:** a chip you picked is used alone; if it fails, you see the error instead of a CPU fallback. Good for experiments.",
                        "**Reset all to Auto** undoes all choices.",
                    ),
                ),
            ),
            HelpTopic(
                "Test the UNet on each chip",
                listOf(
                    text("Runs one step of the real main model on each chip at the size chosen in Create, and tells Auto which is fastest. Slow: the first NPU run compiles the big model (minutes)."),
                ),
            ),
            HelpTopic(
                "Saved NPU compiles",
                listOf(
                    text("Translating (compiling) a big model for the NPU takes a minute or more, so the app saves the result in its own storage and reuses it next time. That is the **NPU compile cache** (about 1.7 GB per model and size)."),
                    text("**Delete saved compiles** frees that space; the next NPU picture compiles again. This is storage, not memory."),
                ),
            ),
            HelpTopic(
                "Free memory",
                listOf(
                    text("While the app is open, the loaded model stays in memory (RAM) so the next picture starts fast. **Free memory** unloads it, for example before switching models or using another heavy app. Closing the app frees it too."),
                ),
            ),
            HelpTopic(
                "Failed chips and the safety switch",
                listOf(
                    text("When a chip fails for a part at a certain size, the app remembers it and skips it next time instead of wasting a slow compile. **Try them again** clears that list (useful after an app update)."),
                    text("If a chip ever **crashes the app**, it is switched off the next time you open the app. The Engine tab then shows **Turn … back on**. Turn it on, then close the app completely and open it again."),
                ),
            ),
            HelpTopic(
                "Logs",
                listOf(
                    text("**App log** shows what the app did, step by step. **Detailed system log** shows Android's messages for this app. Both can be copied to share when something goes wrong."),
                ),
            ),
        ),
    ),
    HelpSection(
        "The technology inside",
        "ONNX Runtime, NeuroPilot, NNAPI and more",
        listOf(
            HelpTopic(
                "ONNX and ONNX Runtime",
                listOf(
                    text("**ONNX** is a standard file format for AI models. **ONNX Runtime** (by Microsoft, free) is the engine that runs them. It can hand parts of a model to different chips through \"execution providers\": one for the CPU, one for the GPU, one for each NPU."),
                    text("Whatever a chip can't do automatically stays on the CPU, so a model can be split between the NPU and the CPU."),
                ),
            ),
            HelpTopic(
                "MediaTek NeuroPilot plugin",
                listOf(
                    text("**NeuroPilot** is MediaTek's AI toolkit. In October 2026 MediaTek released a free, open-source NeuroPilot plugin for ONNX Runtime, the door this app uses to reach MediaTek NPUs."),
                    text("The first version (0.1.0) couldn't run the Stable Diffusion UNet. This app carries a rebuilt plugin with fixes (wrong reading of some model constants, an unsupported layer form, and better error messages)."),
                ),
            ),
            HelpTopic(
                "Android NNAPI",
                listOf(
                    text("**NNAPI** is Android's own built-in way to reach AI chips. Google stopped developing it in Android 15, but on many phones it still works for small networks. In this app it can run the small decoder; the big main model is too much for it."),
                ),
            ),
            HelpTopic(
                "GPU (WebGPU on Vulkan)",
                listOf(
                    text("The GPU is reached through ONNX Runtime's WebGPU provider, which runs on Vulkan graphics. On the tested phone it was slower than the CPU, so Auto skips it. Treat it as experimental."),
                ),
            ),
            HelpTopic(
                "16-bit math on the NPU",
                listOf(
                    text("NPUs calculate with 16-bit numbers (half the precision of the CPU's 32-bit) to go faster. For pictures the difference is invisible. If a chip ever produces broken values, the app notices and switches to another chip."),
                ),
            ),
            HelpTopic(
                "LCM, InstaFlow and classic samplers",
                listOf(
                    bullets(
                        "**Classic (Euler)**: the original method, ~20 steps, uses Avoid.",
                        "**LCM (Latent Consistency Model)**: a model trained to jump to the result in 2–8 steps.",
                        "**InstaFlow (rectified flow)**: trained to go straight from noise to picture in about 1 step.",
                    ),
                    text("The app picks the right method for each model automatically."),
                ),
            ),
            HelpTopic(
                "TAESD",
                listOf(
                    text("**TAESD** (Tiny AutoEncoder for Stable Diffusion) is a tiny version of the decoder, built into the app. It is what makes live mode, previews and the Fast decoder switch possible."),
                ),
            ),
        ),
    ),
    HelpSection(
        "Problems and fixes",
        "Crashes, slow runs, odd pictures",
        listOf(
            HelpTopic(
                "The app closes while generating",
                listOf(
                    bullets(
                        "Usually the phone ran out of memory: close other apps, use a smaller size, tap Engine → Free memory.",
                        "Known issue: forcing **NPU via Android NNAPI** for the main model can crash the app on some phones. Use Auto.",
                        "If a chip crashed it, the app switches that chip off for safety (Engine tab shows it).",
                    ),
                ),
            ),
            HelpTopic(
                "Pictures look wrong",
                listOf(
                    bullets(
                        "Known issue: on some phones the **GPU** gives wrong-looking pictures. Use Auto or CPU.",
                        "Black or grey pictures: the app should detect this and switch chips. If not, set the decoder to CPU in the Engine tab.",
                        "Text and logos come out as gibberish: a limit of these models (see How AI pictures are made).",
                    ),
                ),
            ),
            HelpTopic(
                "It's very slow",
                listOf(
                    bullets(
                        "Check the badge on Create: above 256×256 the main model runs on the CPU.",
                        "Turn on Fast decoder; use LCM or InstaFlow instead of classic models.",
                        "The first picture after opening the app includes loading; the first NPU picture after installing includes a one-time compile.",
                        "With the screen off, allow the app's notification and background activity.",
                    ),
                ),
            ),
            HelpTopic(
                "Downloads and imports",
                listOf(
                    bullets(
                        "Downloads need Wi-Fi and about 4.6 GB free per model. If one stops, tap Resume; it continues where it left off.",
                        "Importing needs about twice the model's size free. The folder must have the layout shown in Models → Adding your own model.",
                    ),
                ),
            ),
            HelpTopic(
                "\"Too big for the NPU\"",
                listOf(
                    text("On the tested MediaTek phone the NPU handles the main model only up to 256×256; at bigger sizes MediaTek's driver refuses it. The app then uses the CPU right away instead of wasting minutes on an NPU compile."),
                ),
            ),
        ),
    ),
)

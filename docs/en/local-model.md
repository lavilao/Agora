# Local Models

Agora can import GGUF language models for on-device llama.cpp inference, and can additionally run the [Cactus](https://github.com/cactus-compute/cactus) engine as an alternative for models published as prebuilt `.cactus` bundles.

## Import

Open **Settings → Providers → Local** and import a GGUF file. Agora copies the model into app-managed storage; an optional multimodal projection file can be associated when supported. Imported models are enabled automatically and can be selected from **Settings → Models** or the chat model picker.

## Cactus engine

Cactus is a self-contained on-device engine with its own kernels and CQ (rotational, 1–4 bit) quantized weights — it does not use GGUF files. From **Settings → Providers → Local → Add Cactus Model** you can download ready-made bundles (for example Gemma 4 E2B in CQ4, or the tiny Needle tool-calling model) straight from the official Cactus weight hub, with checksum-verified downloads and background progress. Bundles you converted yourself on a computer (`cactus convert`) can be imported from a `.zip` archive or a folder.

Cactus models appear with their own badge in the local model list and reuse the same chat experience: streaming, thinking output, tool calling, images (for multimodal bundles) and message statistics all work the same as with llama.cpp models. Cactus manages its own context window and always runs on its own CPU kernels, so the Auto/CPU/Vulkan runtime switch only applies to GGUF models.

The full Cactus kernels require a 64-bit (arm64-v8a) build of Agora. On 32-bit phones Agora ships Cactus' prebuilt unified engine instead (runtime 4.1.1, `libcactus.a` with `cactus.h`), which runs single-file `.cact` text, speech and vision models: the catalog offers the single-file `needle3.cact` model (about 35 MB), downloaded with checksum verification or imported from a `.cact` file. Needle 3 is a tool-calling specialist — given the conversation's enabled tools it picks the right ones and fills every argument, entirely on device. It does not free-form chat: without tools active it answers with an explanatory note, and tool rounds surface the model's reasoning as a thought. Full Cactus models (Gemma 4 E2B) keep requiring a 64-bit build, and every llama.cpp model keeps working as usual.

The same catalog offers **Whistle**, Cactus' on-device speech-to-text model: a single 16.9 MB `.cact` file that runs on the very same engine as Needle 3, beside whichever chat model is active. Installing it adds a microphone button to the chat composer (next to send): hold it to record, release to insert the transcript, slide left to cancel — the WhatsApp interaction. Dictation works with every local chat model, `.cact` or GGUF, detects the language automatically (English, German, French, Spanish, Italian, Dutch and Polish), records up to 30 seconds per clip, and never leaves the device; the microphone permission is requested the first time you hold the button. Whistle stays resident next to the chat model, so the first dictation after a fresh app start also loads the model (~17 MB) before transcribing. Removing Whistle from the catalog row also removes the button.

The same catalog offers **Mirage**, Cactus' on-device image-understanding model: a single 21.6 MB `.cact` running on that same prebuilt engine. It describes the scene, names the objects and regions with their properties, records how they relate and reads the writing in a picture, fully offline. Installing it registers a local model that answers with that description, so it works in two places: attach a photo to a chat with Mirage selected, or pick Mirage as the image transcription model in **Settings → Image Transcription** and every model in the app can read images through it. A request without an image answers with a short note instead of a description. Mirage always returns its own visual record, so the custom transcription prompt does not reach it. Mirage needs the 32-bit prebuilt engine, so 64-bit builds keep using Gemma 4 E2B's own vision tower for images.

## Configure and use

Model-specific context and generation capability depend on the GGUF and available device memory. Large context sizes and models require more RAM and may be slow or fail on constrained devices.

## Delete

Deleting an imported local model removes Agora's managed GGUF and associated projection file. It does not delete an unrelated source file outside Agora's managed storage. The Whistle speech model is not a chat model: it is removed from its row in **Add Cactus Model**.

Review [Models](models.md), [Generation](generation.md), and [Privacy & Security](privacy.md). The separate Alpine sandbox is F-Droid-only, but it is not required for llama.cpp model inference.

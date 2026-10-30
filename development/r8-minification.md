# R8 / Minification Policy

Status: authoritative module contract, 2026-10-30, after a confirmed on-device crash
caused by an R8 (shrinker) release build.

## 1. Policy

Release minification (`isMinifyEnabled` / R8 / ProGuard shrinking) stays **disabled**
in `app/build.gradle.kts`. Do not re-enable it in a merge, a build fix, or a size
optimization. The size cost (tens of MB) is accepted deliberately.

Enforcement, so the policy survives upstream merges:

1. `app/build.gradle.kts` carries `isMinifyEnabled = false` with an incident comment.
   Any merge that flips it back produces a visible conflict or diff here.
2. Every release build path in CI (`.github/workflows/agora-cpu.yml`,
   `agora-arm64-cactus.yml`, `agora-vulkan.yml`) verifies the flag is false before
   assembling and fails loudly with an explanatory message otherwise. A future merge
   cannot silently produce a shrunk APK.
3. `agora-cpu.yml` keeps an explicit `minify_enabled` dispatch input (default false).
   Setting it true is an experimental opt-in only.
4. `app/proguard-rules.pro` carries the defensive keep-rule inventory and stays
   effective (inert while minify is off).

## 2. Incident (evidence)

The 2026-10-30 post-merge release builds (upstream merge `73d6a711`, CI commit
`426edb60`, v2.1.0) crashed on the user's devices the moment the first message was
sent — both native UI and WebUI, both 32-bit and 64-bit phones, every model size,
always at title generation (first model load). Tombstone:

```
F libc : Fatal signal 6 (SIGABRT) in tid (DefaultDispatch)
Abort message: 'Throwing new exception 'no "Z" field "useMmap" in class
"Lcom/newoether/agora/api/LlamaEngineOptions;" or its superclasses'
with unexpected pending exception:
java.lang.NoSuchFieldError: no "Ljava/lang/String;" field "flashAttention"
in class "Lcom/newoether/agora/api/LlamaEngineOptions;" or its superclasses'

#08 libagora_llama.so (agora::chat::read_engine_options(...)+96)
#09 libagora_llama.so (Java_com_newoether_agora_api_LlamaChatEngine_nativeChatLoadModel+376)
```

Upstream had enabled `isMinifyEnabled = true`; the merge kept it. R8 renamed the
`LlamaEngineOptions` fields to `a`, `b`, `c`… (verified in the installed APK's dex).
`read_engine_options()` resolves every field by exact name through `GetFieldID`;
`GetFieldID("flashAttention")` failed and left a pending `NoSuchFieldError`, and the
next `GetFieldID("useMmap")` aborted the process (throwing while an exception is
pending → SIGABRT). The same APK rebuilt with minification off worked on the same
phones, which closed the diagnosis.

## 3. Why CI cannot catch this

CI assembles APKs and runs JVM unit tests. Nothing loads a model through the JNI
brides on a device. A missing keep rule therefore compiles green and crashes only in
the owner's hands, on the first real inference. This is why the policy is a
build-flag default plus CI guards, not a keep-rule test.

## 4. JNI by-name inventory (the surface R8 may not touch)

The native bridges resolve these Java members by exact name at runtime. Any new
native code that adds a `FindClass`/`GetFieldID`/`GetMethodID` lookup must extend
this inventory and the keep rules in the same change.

| Java member | Native reader | Access |
|---|---|---|
| `com.newoether.agora.api.LlamaEngineOptions` — all 18 fields (`flashAttention`, `useMmap`, `cacheTypeK`, `cacheTypeV`, `swaFull`, `threads`, `speculativeType`, `specDraftAmount`, `ngramMatch`, `draftModelPath`, `smartCache`, `smartCacheSlots`, `smartContext`, `contextShift`, `fastForward`, `directIo`, `nBatch`, `nUbatch`) | `llama_chat_options.h` `read_engine_options()`, called from `nativeChatLoadModel` | `GetFieldID` per field — **the missing keep rule in the incident** |
| `com.newoether.agora.api.LlamaChatTemplateRequest.enableThinking` | `llama_chat_template.cpp` | `GetFieldID` |
| `com.newoether.agora.api.LlamaChatTemplateResult` (`format`, `grammarLazy`, constructor) | `llama_chat_template.cpp` | `GetFieldID`, `GetMethodID` |
| `com.newoether.agora.api.ChatTemplateGrammarTrigger` (`token`, `type`, `value`, constructor) | `llama_chat_template.cpp` | `GetFieldID`, `GetMethodID` |
| `com.newoether.agora.api.NativeChatCallback` and implementors (`onText`) | `llama_chat_callbacks.cpp` | `GetMethodID` |
| `com.newoether.agora.api.CactusTokenStream` and implementors (`onToken`) | `cactus_engine_jni.cpp` | `GetMethodID` |
| Classes with `external`/`native` methods (`LlamaChatEngine`, `CactusEngine`, `CactusSpeechEngine`, embedding/whisper bridges) | JNI registration by symbol name | `keepclasseswithmembernames native <methods>` |

The Kotlin class names must also not change shape visible to JNI: the native symbol
names (`Java_com_newoether_agora_api_...`) bake in the package and class names.

## 5. If minification is ever deliberately re-enabled

This requires the owner's explicit request, and all of the following before any
release artifact is published:

1. Every row of the Section 4 inventory has a `-keep` rule in
   `app/proguard-rules.pro` (full-member keeps, not name-only).
2. A grep audit of `app/src/main/cpp/**` for `GetFieldID|GetMethodID|FindClass`
   shows no lookup outside the inventory; new lookups found this way are added to
   the inventory and the keep rules first.
3. The release APK is installed and exercised on a real device: model load, streamed
   generation, title generation, Cactus (`.cact`) chat, and Whistle transcription
   (each engine touches a different part of the by-name surface). CI green alone is
   not evidence.
4. `adb logcat -b crash` shows no `NoSuchFieldError`/`NoSuchMethodError`/
   `UnsatisfiedLinkError` during that session.

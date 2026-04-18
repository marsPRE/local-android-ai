# Model Loading & Organization Strategy

Design for how `local-android-ai` discovers, acquires, tracks, and serves LLM
model files on device. Covers three independent acquisition paths:

1. **Self-download** — app fetches `.task`/`.litertlm` from HuggingFace/URL
2. **Edge Gallery import** — user takes a model Edge Gallery already downloaded
3. **User import** — arbitrary file the user picked (SAF picker)

The app must treat all three as the same kind of thing once the file is local.

---

## 1. Core concepts

### Catalog vs. Registry

Two distinct layers, currently conflated in `AIModel` enum:

| Layer         | What it is                                     | Source of truth                   |
|---------------|------------------------------------------------|-----------------------------------|
| **Catalog**   | Models that *could* exist (metadata, URLs)     | Bundled JSON + remote manifest    |
| **Registry**  | Models that *do* exist on this device (files)  | Filesystem scan + SharedPrefs     |

A catalog entry becomes a registry entry once a valid file is present.
A registry entry without a catalog match is a `DynamicAIModel` with inferred
metadata (what we have today for imports).

### Canonical model ID

One stable ID per logical model, independent of file variant. Examples:

- `gemma-4-e4b-it` — the logical model
- Variants: `gemma-4-e4b-it@cpu-q8`, `gemma-4-e4b-it@gpu-modalities-thinking`

IDs come from the catalog. Files get matched to IDs by filename pattern
(regex or glob list per catalog entry). Unknown files get an auto-generated
`unknown:<basename>` ID.

---

## 2. Catalog

### Bundled catalog (ships with the APK)

`app/src/main/assets/model_catalog.json` — handwritten, small, covers the
models we actively support. One entry per logical model, many variants per
entry.

```json
{
  "models": [
    {
      "id": "gemma-4-e4b-it",
      "displayName": "Gemma 4 E4B IT",
      "license": { "url": "...", "needsAuth": true, "statement": "..." },
      "tags": ["vision", "thinking-capable"],
      "variants": [
        {
          "id": "gpu-modalities-thinking",
          "fileNamePatterns": ["gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm"],
          "format": "LITERT_LM",
          "backend": "GPU",
          "thinking": true, "supportsVision": true,
          "maxTokens": 1024,
          "download": { "url": "https://huggingface.co/..." },
          "source": "edge-gallery"
        }
      ]
    }
  ]
}
```

### Remote catalog (fetched at runtime)

Edge Gallery maintains `model_allowlist.json` in the
[`google-ai-edge/gallery`](https://github.com/google-ai-edge/gallery) repo and
writes a copy to `/sdcard/Android/data/com.google.ai.edge.gallery/files/`.

- Fetch the GitHub raw URL on app start (cached, TTL ~24h).
- Merge remote entries into the catalog, keyed by filename pattern.
- New models appear without an app update.

### Merging

Catalog is `bundled ∪ remote`, with remote overlaying bundled for metadata
updates (URL changes, new variants). Bundled wins for our curated defaults
(e.g. `preferredBackend`).

---

## 3. Storage layout on device

One owned root: `context.getExternalFilesDir("models")`
(= `/sdcard/Android/data/me.bechberger.phoneserver/files/models/`).

```
models/
  downloaded/                        # self-downloaded via URL
    gemma-3n-E2B-it-int4.task
  imported/                          # user-imported or pulled from Edge Gallery
    gemma4_4b_v09_..._thinking.litertlm
  refs/                              # .ref files pointing elsewhere (legacy / in-place)
    llama-3.2-3b.ref                 # text file containing absolute path
  catalog_cache.json                 # merged catalog snapshot
  scan_cache.json                    # last filesystem scan result
```

Why one root: simplifies permissions, cleanup, disk-usage UI, uninstall
hygiene. `getExternalFilesDir` needs no runtime permission and survives app
updates.

### In-place vs. copy

Default is **copy into `imported/`** for robustness. Reasons:

- The source location may be a SAF URI that expires.
- `/sdcard/Android/data/<other-app>/` may become unreadable after an
  Android update or app reinstall.
- Copying gives us stable `File` paths usable by MediaPipe/LiteRT.

`refs/` kept for the existing `.ref` flow (a file whose content is the
absolute path of the real model) when the user deliberately wants to point at
a file they manage themselves.

---

## 4. Discovery pipeline

Run on every `AIModelManagerActivity.onResume()` and before serving API
requests. Results cached in `scan_cache.json`.

```
┌────────────────────────────────────────────────────┐
│ 1. Load catalog  (bundled + cached remote)         │
├────────────────────────────────────────────────────┤
│ 2. Scan file roots, priority order:                │
│    a. <externalFiles>/models/downloaded/           │
│    b. <externalFiles>/models/imported/             │
│    c. <externalFiles>/models/refs/   (resolve)     │
│    d. /sdcard/Download/                            │
│    e. /sdcard/Android/data/com.google.ai.edge...   │
│       (only if readable — Android ≤12 or root)     │
├────────────────────────────────────────────────────┤
│ 3. For each file:                                  │
│    - size/extension sanity check                   │
│    - match against catalog variants by pattern     │
│    - matched  → RegistryEntry(variant, path)       │
│    - no match → DynamicEntry(auto metadata)        │
├────────────────────────────────────────────────────┤
│ 4. For unresolved catalog variants, record as      │
│    "available to download" with download URL       │
└────────────────────────────────────────────────────┘
```

File matching uses the catalog's `fileNamePatterns`. First match wins. A file
in a higher-priority root wins over a duplicate in a lower-priority root.

---

## 5. Edge Gallery integration

Three tiers, degrade gracefully:

### Tier 1 — Direct scan (Android ≤12, rooted, or if Google changes policy)

`File("/sdcard/Android/data/com.google.ai.edge.gallery/files").walk()` and
match against catalog. Zero user action.

### Tier 2 — Guided import (Android 13+, current Pixel 9)

App cannot read Edge Gallery's private dir. UI does:

1. Show catalog entries known to ship via Edge Gallery, marked "Edge Gallery".
2. For each, display the **expected path**:
   `Android/data/com.google.ai.edge.gallery/files/<model>/<date>/<file>.litertlm`
3. Offer two import routes:
   - **Files app**: "Open in Files" intent → user long-press → Copy to
     Downloads → back to our app → auto-detect in Downloads.
   - **ADB**: show the exact `adb pull` command (one-time setup).

Our app re-scans `/sdcard/Download/` on resume and auto-imports files whose
names match known variants.

### Tier 3 — Manifest-only hints

Even without file access we can read Edge Gallery's catalog from GitHub and
show "Gallery has model X" entries in our catalog. User taps → app gives the
exact command/steps to bring that one file over.

---

## 6. Download flow

Owned models only (catalog variant has `download.url`).

1. User taps "Download" on a catalog entry.
2. If `needsAuth`: show HF-token dialog, save in EncryptedSharedPreferences.
3. Use `WorkManager` job, writes to
   `<externalFiles>/models/downloaded/<fileName>.part`, renames on complete.
4. Progress notification. Cancellable. Resumable via `Range` header.
5. On finish: SHA256 check if catalog provides `sha256`, then rescan.

Downloads are per-variant, not per-model. The same logical model may have
downloaded + imported variants side by side.

---

## 7. Runtime API

### Interface (generalize current `AIModelConfig`)

```kotlin
interface ModelEntry {
  val id: String                  // "gemma-4-e4b-it@gpu-modalities-thinking"
  val logicalId: String           // "gemma-4-e4b-it"
  val displayName: String
  val filePath: String?           // null = not present
  val format: ModelFormat
  val config: InferenceConfig     // temp/topK/topP/maxTokens/...
  val origin: Origin              // DOWNLOADED / IMPORTED / EDGE_GALLERY / REFERENCED
  val catalogVariant: CatalogVariant?
  val availability: Availability  // READY / NEEDS_DOWNLOAD / NEEDS_IMPORT
}
```

`AIModel` enum becomes a thin compatibility shim that reads from the catalog
so callers don't break. Long term: remove the enum.

### Registry API

```kotlin
ModelRegistry.all(): List<ModelEntry>
ModelRegistry.ready(): List<ModelEntry>        // filePath != null
ModelRegistry.byId(id): ModelEntry?
ModelRegistry.rescan(): Flow<ScanProgress>
ModelRegistry.import(uri, variant?): ModelEntry
ModelRegistry.download(variantId): Flow<DownloadProgress>
ModelRegistry.delete(entry)
```

REST endpoints map 1:1 to these.

---

## 8. UI organization

Single list in `AIModelManagerActivity`, grouped with section headers:

```
━━ Ready ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  ● Gemma 4 E4B IT        GPU  3.4 GB     [Start]
  ● Gemma 3 1B IT         CPU  0.8 GB     [Start]
━━ Available to download ━━━━━━━━━━━━━━━━
  ○ Llama 3.2 3B Instruct      1.9 GB     [Download]
  ○ DeepSeek R1 Qwen 1.5B      1.4 GB     [Download]
━━ From Edge Gallery ━━━━━━━━━━━━━━━━━━━━
  ⟳ Gemma 4 E2B IT        in Gallery      [How to import]
━━ Imported (unmatched) ━━━━━━━━━━━━━━━━━
  ? mystery_model.litertlm                [Details] [Delete]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[ Refresh ] [ Import File ] [ Check Edge Gallery ]
```

Bottom actions only, not per-row, to keep the list clean.

Per-row action is contextual: `Start` when ready and not loaded, `Stop` when
loaded, `Download` when catalog-available, `How to import` when Edge-Gallery
only.

---

## 9. Implementation phases

Small, mergeable steps:

1. **Extract catalog JSON** — move the hardcoded `AIModel` enum data into
   `assets/model_catalog.json`, keep the enum as a generated wrapper.
2. **Unify file layout** — migrate existing files to `models/downloaded/`
   and `models/imported/` under `getExternalFilesDir`.
3. **Pattern-based matching** — replace exact filename compares with the
   `fileNamePatterns` list in the catalog.
4. **Remote catalog fetch** — pull Edge Gallery's `model_allowlist.json`
   from GitHub, merge, cache 24h.
5. **Scan pipeline + cache** — one `ModelRegistry.rescan()` that produces
   the unified list consumed by UI and API.
6. **UI sections** — replace flat adapter with grouped layout.
7. **Import-from-Downloads auto-detect** — on resume, scan `/sdcard/Download/`
   for matching filenames, offer one-tap import.
8. **Retire the enum** — callers talk to `ModelRegistry` only.

---

## 10. Open questions

- **Disk hygiene** — warn/block when free space < model size × 1.5.
- **Storage location choice** — offer internal vs. external choice for users
  on devices with an SD card? (Pixel has none, skip for now.)
- **Integrity** — do we want to add SHA256 validation for imported files, or
  only for downloads?
- **Multi-user** — ignore until someone asks.

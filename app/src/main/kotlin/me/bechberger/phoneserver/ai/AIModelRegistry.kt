package me.bechberger.phoneserver.ai

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import timber.log.Timber
import java.io.File

/**
 * Central registry combining static AIModel enum entries and dynamically discovered models.
 */
object AIModelRegistry {

    private const val PREFS_NAME = "ai_model_registry"
    private const val KEY_DYNAMIC_MODELS = "dynamic_models"

    private val dynamicModels = mutableListOf<DynamicAIModel>()
    private val gson = Gson()
    private var loaded = false

    fun init(context: Context) {
        if (!loaded) {
            loadFromPrefs(context)
            loaded = true
        }
    }

    fun getAllModels(context: Context? = null): List<AIModelConfig> {
        if (context != null && !loaded) init(context)
        return AIModel.getAllModels() + dynamicModels
    }

    fun getDynamicModels(context: Context? = null): List<DynamicAIModel> {
        if (context != null && !loaded) init(context)
        return dynamicModels.toList()
    }

    fun fromString(id: String, context: Context? = null): AIModelConfig? {
        if (context != null && !loaded) init(context)
        return AIModel.fromString(id)
            ?: dynamicModels.find { it.id == id || it.modelName == id || it.fileName == id }
    }

    fun addDynamicModel(context: Context, model: DynamicAIModel) {
        val existing = dynamicModels.indexOfFirst { it.id == model.id }
        if (existing >= 0) {
            dynamicModels[existing] = model
        } else {
            dynamicModels.add(model)
        }
        saveToPrefs(context)
        Timber.i("Registered dynamic model: ${model.modelName} (${model.absoluteFilePath})")
    }

    fun removeDynamicModel(context: Context, id: String) {
        dynamicModels.removeAll { it.id == id }
        saveToPrefs(context)
    }

    fun isDynamic(model: AIModelConfig) = model is DynamicAIModel

    /**
     * Scans the Downloads folder for .litertlm/.task files not yet registered.
     * Copies matches to getExternalFilesDir("models")/imported/ and registers them.
     * Returns count of newly imported models.
     */
    fun scanAndAutoImport(context: Context): Int {
        init(context)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists() || !downloadsDir.canRead()) return 0

        val importDir = (context.getExternalFilesDir("models") ?: context.filesDir)
            .let { File(it, "imported") }
        if (!importDir.exists()) importDir.mkdirs()

        val modelFiles = downloadsDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".litertlm") || f.name.endsWith(".task")) && f.length() > 0
        } ?: return 0

        val knownFileNames = getAllModels(context).map { it.fileName }.toSet()
        var imported = 0

        for (src in modelFiles) {
            if (src.name in knownFileNames) continue
            val dest = File(importDir, src.name)
            if (!dest.exists() || dest.length() != src.length()) {
                try { src.copyTo(dest, overwrite = true) }
                catch (e: Exception) { Timber.e(e, "scanAndAutoImport: copy failed for ${src.name}"); continue }
            }
            val alreadyRegistered = dynamicModels.any { it.fileName == src.name }
            if (!alreadyRegistered) {
                addDynamicModel(context, buildDynamicModel(context, dest))
                imported++
            }
        }
        Timber.i("scanAndAutoImport: imported $imported new model(s)")
        return imported
    }

    private fun buildDynamicModel(context: Context, file: File): DynamicAIModel {
        val match = CatalogLoader.findVariantForFile(context, file.name)
        return if (match != null) {
            val (catalogModel, variant) = match
            DynamicAIModel(
                id = "dynamic_${file.nameWithoutExtension}",
                modelName = catalogModel.displayName,
                fileName = file.name,
                absoluteFilePath = file.absolutePath,
                licenseUrl = catalogModel.license.url,
                thinking = variant.thinking,
                temperature = variant.temperature.toFloat(),
                topK = variant.topK,
                topP = variant.topP.toFloat(),
                supportsVision = variant.supportsVision,
                maxTokens = variant.maxTokens,
                needsAuth = catalogModel.license.needsAuth,
                licenseStatement = catalogModel.license.statement,
                modelFormat = if (variant.format == "LITERT_LM") ModelFormat.LITERT_LM else ModelFormat.MEDIAPIPE,
                description = "Imported: ${catalogModel.displayName} (${variant.id})"
            )
        } else {
            DynamicAIModel.fromFile(file.absolutePath)
        }
    }

    /** Models whose files are currently present and accessible on disk. */
    fun getReadyModels(context: Context): List<AIModelConfig> =
        getAllModels(context).filter { ModelDetector.isModelAvailable(context, it) }

    /** Catalog entries that have a direct download URL but are not yet on device. */
    fun getDownloadableModels(context: Context): List<AIModel> {
        init(context)
        val readyFileNames = getReadyModels(context).map { it.fileName }.toSet()
        return AIModel.values().filter { it.url.isNotBlank() && it.fileName !in readyFileNames }
    }

    /** Edge-Gallery-only variants with no downloadUrl and no matching file registered. */
    fun getEdgeGalleryOnlyModels(context: Context): List<Pair<CatalogModel, CatalogVariant>> {
        init(context)
        val allFileNames = getAllModels(context).map { it.fileName }.toSet()
        return CatalogLoader.getEdgeGalleryVariants(context).filter { (_, variant) ->
            allFileNames.none { fn -> variant.matchesFileName(fn) }
        }
    }

    private fun saveToPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(dynamicModels)
        prefs.edit().putString(KEY_DYNAMIC_MODELS, json).apply()
    }

    private fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_DYNAMIC_MODELS, null) ?: return
        try {
            val type = object : TypeToken<List<DynamicAIModel>>() {}.type
            val loaded: List<DynamicAIModel> = gson.fromJson(json, type)
            dynamicModels.clear()
            // Only keep models whose files still exist
            loaded.filter { java.io.File(it.absoluteFilePath).exists() }.also {
                dynamicModels.addAll(it)
                Timber.i("Loaded ${it.size} dynamic models from prefs")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load dynamic models from prefs")
        }
    }
}

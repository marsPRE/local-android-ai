package me.bechberger.phoneserver.ai

import android.content.Context
import com.google.gson.Gson
import timber.log.Timber

data class CatalogLicense(
    val url: String = "",
    val needsAuth: Boolean = false,
    val statement: String? = null
)

data class CatalogVariant(
    val id: String,
    val enumId: String? = null,
    val fileNamePatterns: List<String> = emptyList(),
    val format: String = "LITERT_LM",
    val thinking: Boolean = false,
    val supportsVision: Boolean = false,
    val maxTokens: Int = 1024,
    val temperature: Double = 1.0,
    val topK: Int = 64,
    val topP: Double = 0.95,
    val downloadUrl: String? = null,
    val sources: List<String> = emptyList()
) {
    /** Returns true if [fileName] matches any pattern. Supports `*` wildcard, case-insensitive. */
    fun matchesFileName(fileName: String): Boolean = fileNamePatterns.any { pattern ->
        val regex = pattern
            .replace(".", "\\.")   // escape literal dots first
            .replace("*", ".*")    // then expand glob star
            .let { Regex(it, RegexOption.IGNORE_CASE) }
        regex.matches(fileName)
    }
}

data class CatalogModel(
    val id: String = "",
    val displayName: String = "",
    val license: CatalogLicense = CatalogLicense(),
    val variants: List<CatalogVariant> = emptyList()
)

data class ModelCatalog(
    val models: List<CatalogModel> = emptyList()
)

object CatalogLoader {

    private const val CATALOG_FILE = "model_catalog.json"
    private val gson = Gson()

    @Volatile private var cached: ModelCatalog? = null

    fun load(context: Context): ModelCatalog {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return try {
                val json = context.assets.open(CATALOG_FILE).bufferedReader().use { it.readText() }
                gson.fromJson(json, ModelCatalog::class.java).also {
                    cached = it
                    Timber.d("CatalogLoader: loaded ${it.models.size} catalog models")
                }
            } catch (e: Exception) {
                Timber.e(e, "CatalogLoader: failed to load model_catalog.json")
                ModelCatalog()
            }
        }
    }

    /** First (CatalogModel, CatalogVariant) whose patterns match [fileName], or null. */
    fun findVariantForFile(context: Context, fileName: String): Pair<CatalogModel, CatalogVariant>? {
        for (model in load(context).models) {
            for (variant in model.variants) {
                if (variant.matchesFileName(fileName)) return model to variant
            }
        }
        return null
    }

    /** All (CatalogModel, CatalogVariant) pairs across the catalog. */
    fun getAllVariants(context: Context): List<Pair<CatalogModel, CatalogVariant>> =
        load(context).models.flatMap { model -> model.variants.map { model to it } }

    /** Variants from edge-gallery source with no direct download URL. */
    fun getEdgeGalleryVariants(context: Context): List<Pair<CatalogModel, CatalogVariant>> =
        getAllVariants(context).filter { (_, v) -> "edge-gallery" in v.sources && v.downloadUrl == null }
}

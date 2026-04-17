package me.bechberger.phoneserver.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import timber.log.Timber

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

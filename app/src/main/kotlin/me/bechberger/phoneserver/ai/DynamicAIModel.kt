package me.bechberger.phoneserver.ai

/**
 * A model discovered at runtime (e.g. from Edge Gallery or any local folder).
 * Unlike AIModel enum entries, these are created dynamically from found files.
 */
data class DynamicAIModel(
    override val id: String,
    override val modelName: String,
    override val fileName: String,
    override val absoluteFilePath: String,
    override val licenseUrl: String = "",
    override val preferredBackend: Any? = null,
    override val thinking: Boolean = false,
    override val temperature: Float = 1.0f,
    override val topK: Int = 64,
    override val topP: Float = 0.95f,
    override val supportsVision: Boolean = false,
    override val maxTokens: Int = 1024,
    override val description: String = "",
    override val needsAuth: Boolean = false,
    override val licenseStatement: String? = null,
    override val modelFormat: ModelFormat = ModelFormat.LITERT_LM
) : AIModelConfig {

    companion object {
        fun fromFile(absoluteFilePath: String): DynamicAIModel {
            val file = java.io.File(absoluteFilePath)
            val fileName = file.name
            val baseName = fileName.substringBeforeLast(".")
            val ext = fileName.substringAfterLast(".", "")
            val format = if (ext == "litertlm") ModelFormat.LITERT_LM else ModelFormat.MEDIAPIPE
            // Heuristics from filename
            val thinking = baseName.contains("thinking", ignoreCase = true)
            val vision = baseName.contains("vision", ignoreCase = true)
                    || baseName.contains("multimodal", ignoreCase = true)
                    || baseName.contains("modalities", ignoreCase = true)
            return DynamicAIModel(
                id = "dynamic_${baseName}",
                modelName = baseName.replace("_", " ").replace("-", " "),
                fileName = fileName,
                absoluteFilePath = absoluteFilePath,
                modelFormat = format,
                thinking = thinking,
                supportsVision = vision,
                description = "Dynamically imported: $fileName"
            )
        }
    }
}

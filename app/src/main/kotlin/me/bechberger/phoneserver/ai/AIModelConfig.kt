package me.bechberger.phoneserver.ai

/**
 * Common interface for both static (AIModel enum) and dynamic models discovered at runtime.
 */
interface AIModelConfig {
    val id: String
    val modelName: String
    val fileName: String
    val licenseUrl: String
    val preferredBackend: Any?
    val thinking: Boolean
    val temperature: Float
    val topK: Int
    val topP: Float
    val supportsVision: Boolean
    val maxTokens: Int
    val description: String
    val needsAuth: Boolean
    val licenseStatement: String?
    val modelFormat: ModelFormat
}

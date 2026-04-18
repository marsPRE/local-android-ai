package me.bechberger.phoneserver.ai

import com.google.ai.edge.litertlm.Backend as LiteRTBackend
import com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend as MediaPipeBackend

/**
 * Model format types for different inference engines
 */
enum class ModelFormat {
    MEDIAPIPE,  // .task format for MediaPipe
    LITERT_LM   // .litertlm format for LiteRT-LM
}

/**
 * Supported AI models for LLM inference.
 * Supports both MediaPipe and LiteRT-LM backends.
 */
enum class AIModel(
    override val modelName: String,
    override val fileName: String, // Just the filename (without path)
    val url: String,
    override val licenseUrl: String,
    override val preferredBackend: Any?, // MediaPipeBackend or LiteRTBackend
    override val thinking: Boolean,
    override val temperature: Float,
    override val topK: Int,
    override val topP: Float,
    override val supportsVision: Boolean,
    override val maxTokens: Int,
    override val description: String,
    override val needsAuth: Boolean = false,
    override val licenseStatement: String? = null,
    override val modelFormat: ModelFormat = ModelFormat.MEDIAPIPE
) : AIModelConfig {

    GEMMA_3_1B_IT(
        modelName = "Gemma 3n E2B IT",
        fileName = "gemma-3n-E2B-it-int4.task",
        url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/blob/b2b54222ba849ee74ac9f88d6af2470b390afa9e/gemma-3n-E2B-it-int4.task",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        preferredBackend = MediaPipeBackend.CPU,
        thinking = false,
        temperature = 1.0f,
        topK = 64,
        topP = 0.95f,
        supportsVision = true,
        maxTokens = 2048,
        description = "Gemma 3 2B model optimized for instruction following",
        needsAuth = true,
        licenseStatement = "This response was generated using Gemma, a model developed by Google. Usage is subject to the Gemma Terms of Use: https://ai.google.dev/gemma/terms"
    ),
    
    DEEPSEEK_R1_DISTILL_QWEN_1_5B(
        modelName = "DeepSeek-R1 Distill Qwen 1.5B",
        fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
        url = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
        licenseUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
        preferredBackend = MediaPipeBackend.CPU,
        thinking = true,
        temperature = 0.6f,
        topK = 40,
        topP = 0.7f,
        supportsVision = false,
        maxTokens = 1280,
        description = "DeepSeek R1 distilled model with reasoning capabilities",
        needsAuth = false,
        licenseStatement = "This response was generated using DeepSeek R1, developed by DeepSeek AI."
    ),
    
    LLAMA_3_2_1B_INSTRUCT(
        modelName = "Llama 3.2 1B Instruct",
        fileName = "Llama-3.2-1B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        url = "https://huggingface.co/litert-community/Llama-3.2-1B-Instruct/resolve/main/Llama-3.2-1B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        licenseUrl = "https://huggingface.co/litert-community/Llama-3.2-1B-Instruct",
        preferredBackend = MediaPipeBackend.CPU,
        thinking = false,
        temperature = 0.6f,
        topK = 64,
        topP = 0.9f,
        supportsVision = false,
        maxTokens = 1280,
        description = "Meta's Llama 3.2 1B model for instruction following",
        needsAuth = true,
        licenseStatement = "This response was generated using Llama, a model developed by Meta. Usage is subject to the Llama license terms."
    ),
    
    LLAMA_3_2_3B_INSTRUCT(
        modelName = "Llama 3.2 3B Instruct",
        fileName = "Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        url = "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct/resolve/main/Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        licenseUrl = "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct",
        preferredBackend = MediaPipeBackend.CPU,
        thinking = false,
        temperature = 0.6f,
        topK = 64,
        topP = 0.9f,
        supportsVision = false,
        maxTokens = 1280,
        description = "Meta's Llama 3.2 3B model for instruction following",
        needsAuth = true,
        licenseStatement = "This response was generated using Llama, a model developed by Meta. Usage is subject to the Llama license terms."
    ), 
    
    TINYLLAMA_1_1B_CHAT(
        modelName = "TinyLlama 1.1B Chat",
        fileName = "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1024.task",
        url = "https://huggingface.co/litert-community/TinyLlama-1.1B-Chat-v1.0/resolve/main/TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
        licenseUrl = "https://huggingface.co/TinyLlama/TinyLlama-1.1B-Chat-v1.0",
        preferredBackend = MediaPipeBackend.CPU,
        thinking = false,
        temperature = 0.7f,
        topK = 40,
        topP = 0.9f,
        supportsVision = false,
        maxTokens = 1024,
        description = "Compact 1.1B parameter model optimized for chat",
        needsAuth = false,
        licenseStatement = "This response was generated using TinyLlama."
    ),
    
    GEMMA_4_E2B_IT(
        modelName = "Gemma 4 E2B IT",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        preferredBackend = null,
        thinking = false,
        temperature = 1.0f,
        topK = 64,
        topP = 0.95f,
        supportsVision = true,
        maxTokens = 1024,
        description = "Gemma 4 2B instruction-tuned model — faster and lighter than E4B (LiteRT-LM)",
        needsAuth = true,
        licenseStatement = "This response was generated using Gemma 4, a model developed by Google. Usage is subject to the Gemma Terms of Use: https://ai.google.dev/gemma/terms",
        modelFormat = ModelFormat.LITERT_LM
    ),

    GEMMA_4_E4B_IT(
        modelName = "Gemma 4 E4B IT",
        fileName = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        preferredBackend = null, // LiteRTBackend set at runtime
        thinking = false,
        temperature = 1.0f,
        topK = 64,
        topP = 0.95f,
        supportsVision = true,
        maxTokens = 1024,
        description = "Gemma 4 4B instruction-tuned model with vision support (LiteRT-LM)",
        needsAuth = true,
        licenseStatement = "This response was generated using Gemma 4, a model developed by Google. Usage is subject to the Gemma Terms of Use: https://ai.google.dev/gemma/terms",
        modelFormat = ModelFormat.LITERT_LM
    ),

    GEMMA_4_E4B_IT_GALLERY(
        modelName = "Gemma 4 E4B IT (Gallery)",
        fileName = "gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm",
        url = "",  // Imported from Edge Gallery — not downloadable via URL
        licenseUrl = "https://ai.google.dev/gemma/terms",
        preferredBackend = null,
        thinking = true,
        temperature = 1.0f,
        topK = 64,
        topP = 0.95f,
        supportsVision = true,
        maxTokens = 1024,
        description = "Gemma 4 4B GPU-optimized model from Edge Gallery (all modalities + thinking)",
        needsAuth = false,
        licenseStatement = "This response was generated using Gemma 4, a model developed by Google. Usage is subject to the Gemma Terms of Use: https://ai.google.dev/gemma/terms",
        modelFormat = ModelFormat.LITERT_LM
    );

    override val id: String get() = name
    override val absoluteFilePath: String get() = ""

    companion object {
        /**
         * Get model by name string
         */
        fun fromString(modelName: String): AIModel? {
            return values().find { it.name == modelName || it.modelName == modelName }
        }
        
        /**
         * Get all available models
         */
        fun getAllModels(): List<AIModel> = values().toList()
    }
}

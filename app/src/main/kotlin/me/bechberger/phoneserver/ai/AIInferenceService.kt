package me.bechberger.phoneserver.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend as LiteRTBackend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Message
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.max

/**
 * Core AI inference service supporting both MediaPipe and LiteRT-LM backends.
 * Manages model loading, session creation, and text generation.
 */
class AIInferenceService private constructor(
    private val context: Context,
    private val model: AIModel
) {
    
    // MediaPipe components
    private var llmInference: LlmInference? = null
    private var llmInferenceSession: LlmInferenceSession? = null
    
    // LiteRT-LM components
    private var litertEngine: Engine? = null
    private var litertConversation: Conversation? = null
    
    private val tag = "AIInferenceService"
    
    companion object {
        // Maximum tokens the model can process
        private const val MAX_TOKENS = 1024
        
        // Token offset for response generation
        private const val DECODE_TOKEN_OFFSET = 256
        
        /**
         * Create an AI inference service for the specified model
         */
        suspend fun create(context: Context, model: AIModel): AIInferenceService {
            return withContext(Dispatchers.IO) {
                val service = AIInferenceService(context, model)
                
                Timber.d("Starting initialization for model: ${model.modelName} (${model.modelFormat})")
                val initStartTime = System.currentTimeMillis()
                
                try {
                    service.initialize()
                    val initTime = System.currentTimeMillis() - initStartTime
                    Timber.d("Initialization completed in ${initTime}ms for model: ${model.modelName}")
                } catch (e: Exception) {
                    val initTime = System.currentTimeMillis() - initStartTime
                    Timber.e(e, "Initialization failed after ${initTime}ms for model: ${model.modelName}")
                    throw e
                }
                
                service
            }
        }
    }
    
    /**
     * Initialize the inference engine based on model format
     */
    private suspend fun initialize() {
        if (!ModelDetector.isModelAvailable(context, model)) {
            throw ModelNotDownloadedException("Model ${model.modelName} is not available (missing file)")
        }
        
        try {
            when (model.modelFormat) {
                ModelFormat.MEDIAPIPE -> initializeMediaPipe()
                ModelFormat.LITERT_LM -> initializeLiteRTLM()
            }
            Timber.i("AI inference service initialized for model: ${model.modelName}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AI inference service")
            throw AIServiceException("Failed to initialize AI service", e)
        }
    }
    
    /**
     * Initialize MediaPipe backend
     */
    private fun initializeMediaPipe() {
        val modelFile = ModelDetector.getModelFile(context, model)
            ?: throw ModelNotDownloadedException("Model file not found for ${model.modelName}")
        
        val modelPath = modelFile.absolutePath
        
        Timber.d("Loading MediaPipe model: ${model.modelName}")
        Timber.d("  Path: $modelPath")
        Timber.d("  Size: ${formatBytes(modelFile.length())}")
        
        val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(MAX_TOKENS)
            .setMaxNumImages(if (model.supportsVision) 1 else 0)
        
        // Set backend preference if specified (MediaPipe Backend)
        val backend = model.preferredBackend as? com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend
        backend?.let {
            optionsBuilder.setPreferredBackend(it)
            Timber.d("Using MediaPipe backend: ${it.name}")
        }
        
        llmInference = LlmInference.createFromOptions(context, optionsBuilder.build())
        createMediaPipeSession()
        
        Timber.i("MediaPipe engine created for ${model.modelName}")
    }
    
    /**
     * Initialize LiteRT-LM backend
     */
    private fun initializeLiteRTLM() {
        val modelFile = ModelDetector.getModelFile(context, model)
            ?: throw ModelNotDownloadedException("Model file not found for ${model.modelName}")
        
        val modelPath = modelFile.absolutePath
        
        Timber.d("Loading LiteRT-LM model: ${model.modelName}")
        Timber.d("  Path: $modelPath")
        Timber.d("  Size: ${formatBytes(modelFile.length())}")
        
        val backend = model.preferredBackend as? LiteRTBackend ?: LiteRTBackend.CPU
        
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            cacheDir = context.cacheDir.path
        )
        
        litertEngine = Engine(engineConfig)
        litertEngine?.initialize()
        
        // Create conversation with sampler config
        val samplerConfig = SamplerConfig(
            topK = model.topK,
            topP = model.topP.toDouble(),
            temperature = model.temperature.toDouble()
        )
        
        val conversationConfig = ConversationConfig(
            samplerConfig = samplerConfig
        )
        
        litertConversation = litertEngine?.createConversation(conversationConfig)
        
        Timber.i("LiteRT-LM engine created for ${model.modelName} with backend: ${backend.name}")
    }
    
    /**
     * Create MediaPipe inference session
     */
    private fun createMediaPipeSession() {
        val inference = llmInference 
            ?: throw AIServiceException("LLM inference engine not initialized")
        
        val sessionOptions = LlmInferenceSessionOptions.builder()
            .setTemperature(model.temperature)
            .setTopK(model.topK)
            .setTopP(model.topP)
            .build()
        
        llmInferenceSession = LlmInferenceSession.createFromOptions(inference, sessionOptions)
        Timber.d("MediaPipe session created")
    }
    
    /**
     * Generate text response asynchronously
     */
    suspend fun generateText(
        prompt: String,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        progressCallback: ((String, Boolean) -> Unit)? = null
    ): AITextResponse {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            try {
                val response = when (model.modelFormat) {
                    ModelFormat.MEDIAPIPE -> generateTextMediaPipe(prompt, temperature, topK, topP, progressCallback)
                    ModelFormat.LITERT_LM -> generateTextLiteRTLM(prompt, temperature, topK, topP, progressCallback)
                }
                
                val inferenceTime = System.currentTimeMillis() - startTime
                val tokenCount = estimateTokenCount(prompt + response)
                
                AITextResponse(
                    response = response,
                    model = model.name,
                    thinking = if (model.thinking) extractThinking(response) else null,
                    license = model.licenseStatement,
                    metadata = AIResponseMetadata(
                        model = model.modelName,
                        inferenceTime = inferenceTime,
                        tokenCount = tokenCount,
                        temperature = temperature ?: model.temperature,
                        topK = topK ?: model.topK,
                        topP = topP ?: model.topP,
                        backend = getBackendName()
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to generate text")
                throw AIServiceException("Text generation failed", e)
            }
        }
    }
    
    /**
     * Generate text using MediaPipe
     */
    private fun generateTextMediaPipe(
        prompt: String,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        progressCallback: ((String, Boolean) -> Unit)? = null
    ): String {
        // Update session parameters if provided (requires session recreation in MediaPipe)
        if (temperature != null || topK != null || topP != null) {
            Timber.d("Dynamic parameter updates not fully supported in MediaPipe, using model defaults")
        }
        
        val session = llmInferenceSession 
            ?: throw AIServiceException("Inference session not initialized")
        
        session.addQueryChunk(prompt)
        
        val progressListener = ProgressListener<String> { partialResult, isDone ->
            progressCallback?.invoke(partialResult, isDone)
        }
        
        val future: ListenableFuture<String> = session.generateResponseAsync(progressListener)
        return future.get()
    }
    
    /**
     * Generate text using LiteRT-LM
     */
    private fun generateTextLiteRTLM(
        prompt: String,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        progressCallback: ((String, Boolean) -> Unit)? = null
    ): String {
        val conversation = litertConversation
            ?: throw AIServiceException("LiteRT-LM conversation not initialized")
        
        // Update sampler config if parameters changed
        if (temperature != null || topK != null || topP != null) {
            val newSamplerConfig = SamplerConfig(
                topK = topK ?: model.topK,
                topP = (topP ?: model.topP).toDouble(),
                temperature = (temperature ?: model.temperature).toDouble()
            )
            // Note: LiteRT-LM doesn't support dynamic config changes, would need new conversation
            Timber.d("Dynamic parameter changes require new conversation in LiteRT-LM")
        }
        
        val userMessage = Message.of(prompt)
        
        // Use streaming if callback provided
        return if (progressCallback != null) {
            val responseBuilder = StringBuilder()
            conversation.generateResponse(userMessage) { partialResponse, isDone ->
                responseBuilder.append(partialResponse)
                progressCallback.invoke(responseBuilder.toString(), isDone)
            }
            responseBuilder.toString()
        } else {
            conversation.generateResponse(userMessage)
        }
    }
    
    /**
     * Generate multimodal text response with image input
     */
    suspend fun generateMultimodalText(
        prompt: String,
        image: Bitmap,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        progressCallback: ((String, Boolean) -> Unit)? = null
    ): AITextResponse {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            try {
                if (!model.supportsVision) {
                    throw AIServiceException("Model ${model.modelName} does not support vision/multimodal input")
                }
                
                val response = when (model.modelFormat) {
                    ModelFormat.MEDIAPIPE -> generateMultimodalMediaPipe(prompt, image, temperature, topK, topP, progressCallback)
                    ModelFormat.LITERT_LM -> throw AIServiceException("Vision not yet implemented for LiteRT-LM")
                }
                
                val inferenceTime = System.currentTimeMillis() - startTime
                val tokenCount = estimateTokenCount(prompt + response)
                
                AITextResponse(
                    response = response,
                    model = model.name,
                    thinking = if (model.thinking) extractThinking(response) else null,
                    license = model.licenseStatement,
                    metadata = AIResponseMetadata(
                        model = model.modelName,
                        inferenceTime = inferenceTime,
                        tokenCount = tokenCount,
                        temperature = temperature ?: model.temperature,
                        topK = topK ?: model.topK,
                        topP = topP ?: model.topP,
                        backend = getBackendName(),
                        isMultimodal = true
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to generate multimodal text")
                throw AIServiceException("Multimodal text generation failed", e)
            }
        }
    }
    
    /**
     * Generate multimodal response using MediaPipe
     */
    private fun generateMultimodalMediaPipe(
        prompt: String,
        image: Bitmap,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        progressCallback: ((String, Boolean) -> Unit)? = null
    ): String {
        val inference = llmInference
            ?: throw AIServiceException("LLM inference not initialized")
        
        val sessionOptions = LlmInferenceSessionOptions.builder()
            .setTemperature(temperature ?: model.temperature)
            .setTopK(topK ?: model.topK)
            .setTopP(topP ?: model.topP)
            .setGraphOptions(
                GraphOptions.builder()
                    .setEnableVisionModality(true)
                    .build()
            )
            .build()
        
        val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
        
        session.addQueryChunk(prompt)
        val mpImage = BitmapImageBuilder(image).build()
        session.addImage(mpImage)
        
        Timber.d("Added image to session (${image.width}x${image.height})")
        
        val progressListener = ProgressListener<String> { partialResult, isDone ->
            progressCallback?.invoke(partialResult, isDone)
        }
        
        val future: ListenableFuture<String> = session.generateResponseAsync(progressListener)
        val response = future.get()
        
        session.close()
        return response
    }
    
    /**
     * Get backend name for metadata
     */
    private fun getBackendName(): String {
        return when (model.modelFormat) {
            ModelFormat.MEDIAPIPE -> (model.preferredBackend as? com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend)?.name ?: "CPU"
            ModelFormat.LITERT_LM -> (model.preferredBackend as? LiteRTBackend)?.name ?: "CPU"
        }
    }
    
    /**
     * Reset the inference session/conversation
     */
    suspend fun resetSession() {
        withContext(Dispatchers.IO) {
            try {
                when (model.modelFormat) {
                    ModelFormat.MEDIAPIPE -> {
                        llmInferenceSession?.close()
                        createMediaPipeSession()
                    }
                    ModelFormat.LITERT_LM -> {
                        litertConversation?.close()
                        val samplerConfig = SamplerConfig(
                            topK = model.topK,
                            topP = model.topP.toDouble(),
                            temperature = model.temperature.toDouble()
                        )
                        litertConversation = litertEngine?.createConversation(
                            ConversationConfig(samplerConfig = samplerConfig)
                        )
                    }
                }
                Timber.d("Session reset")
            } catch (e: Exception) {
                Timber.e(e, "Failed to reset session")
                throw AIServiceException("Failed to reset session", e)
            }
        }
    }
    
    /**
     * Close the inference service and free resources
     */
    fun close() {
        try {
            when (model.modelFormat) {
                ModelFormat.MEDIAPIPE -> {
                    llmInferenceSession?.close()
                    llmInference?.close()
                }
                ModelFormat.LITERT_LM -> {
                    litertConversation?.close()
                    litertEngine?.close()
                }
            }
            Timber.d("AI inference service closed")
        } catch (e: Exception) {
            Timber.e(e, "Error closing AI inference service")
        }
    }
    
    /**
     * Extract thinking process from response for reasoning models
     */
    private fun extractThinking(response: String): String? {
        return if (model.thinking) {
            val thinkingPattern = Regex("""\<think\>(.*?)\</think\>""", RegexOption.DOT_MATCHES_ALL)
            thinkingPattern.find(response)?.groupValues?.get(1)?.trim()
        } else null
    }
    
    /**
     * Estimate token count for text
     */
    private fun estimateTokenCount(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }
    
    /**
     * Format bytes to human readable format
     */
    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        if (bytes == 0L) return "0 B"
        
        val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt()
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        
        return String.format("%.1f %s", value, units[digitGroups])
    }
}

/**
 * Exception thrown when a model is not downloaded
 */
class ModelNotDownloadedException(message: String) : Exception(message)

/**
 * General AI service exception
 */
class AIServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

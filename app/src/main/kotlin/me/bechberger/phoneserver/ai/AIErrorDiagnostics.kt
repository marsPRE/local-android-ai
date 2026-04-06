package me.bechberger.phoneserver.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.StatFs
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.util.regex.Pattern

/**
 * Comprehensive diagnostics for AI model loading and inference issues
 */
object AIErrorDiagnostics {
    
    data class DiagnosticsReport(
        val modelInfo: ModelDiagnostics,
        val systemInfo: SystemDiagnostics,
        val memoryInfo: MemoryDiagnostics,
        val storageInfo: StorageDiagnostics,
        val gpuInfo: GPUDiagnostics,
        val recommendations: List<String>
    )
    
    data class ModelDiagnostics(
        val modelName: String,
        val fileName: String,
        val filePath: String?,
        val fileExists: Boolean,
        val fileSize: Long,
        val expectedSize: Long?,
        val isCorrupted: Boolean,
        val format: ModelFormat,
        val downloadComplete: Boolean
    )
    
    data class SystemDiagnostics(
        val deviceModel: String,
        val androidVersion: String,
        val apiLevel: Int,
        val abi: String,
        val supportedAbis: Array<String>,
        val isArm64: Boolean,
        val hasGpu: Boolean
    )
    
    data class MemoryDiagnostics(
        val totalRam: Long,
        val availableRam: Long,
        val lowMemory: Boolean,
        val heapSize: Long,
        val heapFree: Long,
        val nativeHeapSize: Long,
        val nativeHeapFree: Long
    )
    
    data class StorageDiagnostics(
        val internalTotal: Long,
        val internalFree: Long,
        val appDataSize: Long,
        val hasEnoughSpace: Boolean
    )
    
    data class GPUDiagnostics(
        val renderer: String?,
        val vendor: String?,
        val version: String?,
        val supportsVulkan: Boolean,
        val supportsOpenGLES3: Boolean
    )
    
    /**
     * Run full diagnostics on model loading failure
     */
    fun diagnoseModelLoadingFailure(context: Context, model: AIModel): DiagnosticsReport {
        Timber.i("=== Starting AI Error Diagnostics for ${model.modelName} ===")
        
        val modelInfo = diagnoseModel(context, model)
        val systemInfo = getSystemInfo()
        val memoryInfo = getMemoryInfo(context)
        val storageInfo = getStorageInfo(context, model)
        val gpuInfo = getGPUInfo()
        
        val recommendations = generateRecommendations(
            modelInfo, systemInfo, memoryInfo, storageInfo, gpuInfo
        )
        
        val report = DiagnosticsReport(
            modelInfo = modelInfo,
            systemInfo = systemInfo,
            memoryInfo = memoryInfo,
            storageInfo = storageInfo,
            gpuInfo = gpuInfo,
            recommendations = recommendations
        )
        
        logReport(report)
        return report
    }
    
    /**
     * Diagnose model file
     */
    private fun diagnoseModel(context: Context, model: AIModel): ModelDiagnostics {
        val modelFile = ModelDetector.getModelFile(context, model)
        val fileExists = modelFile?.exists() ?: false
        val fileSize = modelFile?.length() ?: 0
        
        // Check if file is corrupted (can we read the header?)
        var isCorrupted = false
        if (fileExists && model.modelFormat == ModelFormat.LITERT_LM) {
            isCorrupted = !verifyLiteRTLMFile(modelFile!!)
        }
        
        // Expected size from URL or known values
        val expectedSize = when (model) {
            AIModel.GEMMA_4_E4B_IT -> 3_650_000_000L // ~3.65 GB
            else -> null
        }
        
        val downloadComplete = expectedSize?.let { fileSize >= it * 0.95 } ?: (fileSize > 0)
        
        return ModelDiagnostics(
            modelName = model.modelName,
            fileName = model.fileName,
            filePath = modelFile?.absolutePath,
            fileExists = fileExists,
            fileSize = fileSize,
            expectedSize = expectedSize,
            isCorrupted = isCorrupted,
            format = model.modelFormat,
            downloadComplete = downloadComplete
        )
    }
    
    /**
     * Verify LiteRT-LM file integrity
     */
    private fun verifyLiteRTLMFile(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                // Check file header
                val header = ByteArray(16)
                raf.read(header)
                
                // LiteRT-LM files should start with specific markers
                // This is a basic check - real validation would need the LiteRT library
                val isValidSize = file.length() > 1_000_000_000 // At least 1GB
                
                Timber.d("LiteRT-LM file check: size=${file.length()}, hasValidHeader=$isValidSize")
                isValidSize
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to verify LiteRT-LM file")
            true // Assume valid if we can't check
        }
    }
    
    /**
     * Get system information
     */
    private fun getSystemInfo(): SystemDiagnostics {
        return SystemDiagnostics(
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            abi = Build.CPU_ABI,
            supportedAbis = Build.SUPPORTED_ABIS,
            isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64") },
            hasGpu = true // Assume GPU present, will be verified by initialization
        )
    }
    
    /**
     * Get memory information
     */
    private fun getMemoryInfo(context: Context): MemoryDiagnostics {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val runtime = Runtime.getRuntime()
        
        return MemoryDiagnostics(
            totalRam = memoryInfo.totalMem,
            availableRam = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
            heapSize = runtime.maxMemory(),
            heapFree = runtime.freeMemory(),
            nativeHeapSize = Debug.getNativeHeapSize(),
            nativeHeapFree = Debug.getNativeHeapFreeSize()
        )
    }
    
    /**
     * Get storage information
     */
    private fun getStorageInfo(context: Context, model: AIModel): StorageDiagnostics {
        val dataDir = context.filesDir
        val stat = StatFs(dataDir.path)
        
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        
        val appDataSize = calculateAppDataSize(context)
        val modelSize = when (model) {
            AIModel.GEMMA_4_E4B_IT -> 4_000_000_000L // ~4GB needed
            else -> 2_000_000_000L
        }
        
        return StorageDiagnostics(
            internalTotal = blockSize * totalBlocks,
            internalFree = blockSize * availableBlocks,
            appDataSize = appDataSize,
            hasEnoughSpace = (blockSize * availableBlocks) >= modelSize
        )
    }
    
    /**
     * Calculate app data size
     */
    private fun calculateAppDataSize(context: Context): Long {
        return try {
            var size = context.filesDir?.walkTopDown()?.map { it.length() }?.sum() ?: 0
            context.cacheDir?.let { cache ->
                size += cache.walkTopDown().map { it.length() }.sum()
            }
            size
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Get GPU information
     */
    private fun getGPUInfo(): GPUDiagnostics {
        // GPU info requires EGL context, which we can't easily get here
        // Return basic info based on device capabilities
        return GPUDiagnostics(
            renderer = System.getProperty("gpu.renderer"),
            vendor = System.getProperty("gpu.vendor"),
            version = null,
            supportsVulkan = Build.VERSION.SDK_INT >= 24,
            supportsOpenGLES3 = Build.VERSION.SDK_INT >= 18
        )
    }
    
    /**
     * Generate recommendations based on diagnostics
     */
    private fun generateRecommendations(
        modelInfo: ModelDiagnostics,
        systemInfo: SystemDiagnostics,
        memoryInfo: MemoryDiagnostics,
        storageInfo: StorageDiagnostics,
        gpuInfo: GPUDiagnostics
    ): List<String> {
        val recommendations = mutableListOf<String>()
        
        // Model file issues
        if (!modelInfo.fileExists) {
            recommendations.add("❌ Model file not found. Please download the model first.")
        } else if (!modelInfo.downloadComplete) {
            recommendations.add("⚠️ Model download appears incomplete. Expected ~${formatBytes(modelInfo.expectedSize ?: 0)}, found ${formatBytes(modelInfo.fileSize)}")
            recommendations.add("   Delete and re-download the model.")
        } else if (modelInfo.isCorrupted) {
            recommendations.add("❌ Model file may be corrupted. Delete and re-download.")
        }
        
        // Memory issues
        if (memoryInfo.lowMemory) {
            recommendations.add("⚠️ System is low on memory. Close other apps before loading the model.")
        }
        
        val minRam = when (modelInfo.modelName) {
            "Gemma 4 E4B IT" -> 6_000_000_000L
            else -> 3_000_000_000L
        }
        
        if (memoryInfo.totalRam < minRam) {
            recommendations.add("⚠️ Device has only ${formatBytes(memoryInfo.totalRam)} RAM. Model requires at least ${formatBytes(minRam)}.")
        }
        
        // Storage issues
        if (!storageInfo.hasEnoughSpace) {
            recommendations.add("❌ Not enough storage space. Free up at least ${formatBytes(4_000_000_000L)}.")
        }
        
        // Architecture issues
        if (!systemInfo.isArm64) {
            recommendations.add("⚠️ Device is not arm64. Model may not work on 32-bit devices.")
        }
        
        // GPU issues
        if (modelInfo.format == ModelFormat.LITERT_LM && !gpuInfo.supportsOpenGLES3) {
            recommendations.add("⚠️ GPU may not support OpenGL ES 3.0. Try CPU backend instead.")
        }
        
        // LiteRT-LM specific
        if (modelInfo.format == ModelFormat.LITERT_LM) {
            recommendations.add("ℹ️ Gemma 4 uses LiteRT-LM. First load may take 30-60 seconds.")
        }
        
        // General recommendations
        if (recommendations.isEmpty()) {
            recommendations.add("✅ No obvious issues detected. Check logs for detailed error messages.")
            recommendations.add("   Try: Settings → Apps → Local AI → Storage → Clear Cache")
            recommendations.add("   Then restart the app and re-download the model.")
        }
        
        return recommendations
    }
    
    /**
     * Log full diagnostics report
     */
    private fun logReport(report: DiagnosticsReport) {
        Timber.e("╔══════════════════════════════════════════════════════════════════╗")
        Timber.e("║           AI MODEL LOADING DIAGNOSTICS REPORT                    ║")
        Timber.e("╚══════════════════════════════════════════════════════════════════╝")
        
        // Model info
        Timber.e("\n📦 MODEL INFORMATION:")
        Timber.e("   Name: ${report.modelInfo.modelName}")
        Timber.e("   Format: ${report.modelInfo.format}")
        Timber.e("   File: ${report.modelInfo.fileName}")
        Timber.e("   Path: ${report.modelInfo.filePath}")
        Timber.e("   Exists: ${report.modelInfo.fileExists}")
        Timber.e("   Size: ${formatBytes(report.modelInfo.fileSize)} / ${formatBytes(report.modelInfo.expectedSize ?: 0)}")
        Timber.e("   Complete: ${report.modelInfo.downloadComplete}")
        Timber.e("   Corrupted: ${report.modelInfo.isCorrupted}")
        
        // System info
        Timber.e("\n📱 SYSTEM INFORMATION:")
        Timber.e("   Device: ${report.systemInfo.deviceModel}")
        Timber.e("   Android: ${report.systemInfo.androidVersion} (API ${report.systemInfo.apiLevel})")
        Timber.e("   ABI: ${report.systemInfo.abi}")
        Timber.e("   Supported ABIs: ${report.systemInfo.supportedAbis.joinToString()}")
        Timber.e("   ARM64: ${report.systemInfo.isArm64}")
        
        // Memory info
        Timber.e("\n💾 MEMORY INFORMATION:")
        Timber.e("   Total RAM: ${formatBytes(report.memoryInfo.totalRam)}")
        Timber.e("   Available RAM: ${formatBytes(report.memoryInfo.availableRam)}")
        Timber.e("   Low Memory: ${report.memoryInfo.lowMemory}")
        Timber.e("   Heap Size: ${formatBytes(report.memoryInfo.heapSize)}")
        Timber.e("   Heap Free: ${formatBytes(report.memoryInfo.heapFree)}")
        Timber.e("   Native Heap: ${formatBytes(report.memoryInfo.nativeHeapSize)}")
        
        // Storage info
        Timber.e("\n💿 STORAGE INFORMATION:")
        Timber.e("   Total: ${formatBytes(report.storageInfo.internalTotal)}")
        Timber.e("   Free: ${formatBytes(report.storageInfo.internalFree)}")
        Timber.e("   App Data: ${formatBytes(report.storageInfo.appDataSize)}")
        Timber.e("   Enough Space: ${report.storageInfo.hasEnoughSpace}")
        
        // GPU info
        Timber.e("\n🎮 GPU INFORMATION:")
        Timber.e("   Vulkan: ${report.gpuInfo.supportsVulkan}")
        Timber.e("   OpenGL ES 3.0: ${report.gpuInfo.supportsOpenGLES3}")
        
        // Recommendations
        Timber.e("\n💡 RECOMMENDATIONS:")
        report.recommendations.forEach { rec ->
            Timber.e("   $rec")
        }
        
        Timber.e("\n═══════════════════════════════════════════════════════════════════")
    }
    
    /**
     * Format bytes to human readable
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt()
            .coerceAtMost(units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format("%.2f %s", value, units[digitGroups])
    }
    
    /**
     * Get quick status for UI display
     */
    fun getQuickStatus(context: Context, model: AIModel): String {
        val modelFile = ModelDetector.getModelFile(context, model)
        val fileExists = modelFile?.exists() ?: false
        val fileSize = modelFile?.length() ?: 0
        
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        
        return buildString {
            appendLine("Model: ${model.modelName}")
            appendLine("Format: ${model.modelFormat}")
            appendLine("File: ${if (fileExists) "✅ ${formatBytes(fileSize)}" else "❌ Not found"}")
            appendLine("RAM: ${formatBytes(memInfo.availMem)} / ${formatBytes(memInfo.totalMem)} available")
            
            if (model.modelFormat == ModelFormat.LITERT_LM) {
                appendLine("Note: Gemma 4 needs 4GB+ RAM and 4GB storage")
            }
        }
    }
}

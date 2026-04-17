package me.bechberger.phoneserver.adapter

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import me.bechberger.phoneserver.R
import me.bechberger.phoneserver.ai.AIModel
import me.bechberger.phoneserver.ai.AIModelConfig
import me.bechberger.phoneserver.ai.AIModelRegistry
import me.bechberger.phoneserver.ai.AIService
import me.bechberger.phoneserver.ai.DynamicAIModel
import me.bechberger.phoneserver.ai.ModelDetector
import me.bechberger.phoneserver.ai.ModelFileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Adapter for displaying AI models in the model manager
 */
class AIModelAdapter(
    private val context: Context,
    private var models: List<AIModelConfig>,
    private val aiService: AIService,
    private val onLoadFileRequested: (AIModelConfig) -> Unit,
    private val onTestRequested: (AIModelConfig) -> Unit,
    private val onRefreshRequested: () -> Unit
) : RecyclerView.Adapter<AIModelAdapter.ModelViewHolder>() {

    private var modelInfoMap = mutableMapOf<AIModelConfig, ModelFileInfo>()
    private var processingModels = mutableSetOf<String>()
    private var downloadingModels = mutableMapOf<String, Int>() // modelName -> progress percentage

    init {
        refreshModelInfo()
    }

    class ModelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textModelName: TextView = itemView.findViewById(R.id.textModelName)
        val textStatus: TextView = itemView.findViewById(R.id.textStatus)
        val textDescription: TextView = itemView.findViewById(R.id.textDescription)
        val textFileSize: TextView = itemView.findViewById(R.id.textFileSize)
        val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)
        val buttonPrimaryAction: Button = itemView.findViewById(R.id.buttonPrimaryAction)
        val buttonSecondaryActions: Button = itemView.findViewById(R.id.buttonSecondaryActions)
        val layoutSecondaryActions: View = itemView.findViewById(R.id.layoutSecondaryActions)
        val buttonLoadLocal: Button = itemView.findViewById(R.id.buttonLoadLocal)
        val buttonDownload: Button = itemView.findViewById(R.id.buttonDownload)
        val buttonTest: Button = itemView.findViewById(R.id.buttonTest)
        val buttonDelete: Button = itemView.findViewById(R.id.buttonDelete)
        
        // Download progress elements
        val layoutDownloadProgress: View = itemView.findViewById(R.id.layoutDownloadProgress)
        val textDownloadProgress: TextView = itemView.findViewById(R.id.textDownloadProgress)
        val progressBarDownload: ProgressBar = itemView.findViewById(R.id.progressBarDownload)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ai_model, parent, false)
        return ModelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val model = models[position]
        val fileInfo = modelInfoMap[model] ?: ModelFileInfo(model.fileName, 0, 0, false)

        // Basic model info
        holder.textModelName.text = model.modelName
        
        // Build description with capabilities and license (cleaner, no emojis)
        val descriptionWithFeatures = buildString {
            append(model.description)
            
            // Add capabilities
            val capabilities = mutableListOf<String>()
            if (model.supportsVision) capabilities.add("Vision")
            if (model.thinking) capabilities.add("Reasoning")
            if (capabilities.isNotEmpty()) {
                append(" • ${capabilities.joinToString(", ")}")
            }
            
            // Add full license information
            append("\n\nLicense: ")
            if (model.licenseStatement != null) {
                append(model.licenseStatement)
                append("\n\nFull license terms: ")
            } else {
                append("Please review the license terms at: ")
            }
        }
        
        // Create a spannable string to make the license URL clickable
        val fullText = descriptionWithFeatures + model.licenseUrl
        val spannable = SpannableString(fullText)
        
        // Find the start of the license URL
        val urlStartIndex = fullText.indexOf(model.licenseUrl)
        if (urlStartIndex != -1) {
            val urlEndIndex = urlStartIndex + model.licenseUrl.length
            
            // Make the URL clickable
            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openLicenseUrl(model.licenseUrl)
                }
            }
            
            spannable.setSpan(clickableSpan, urlStartIndex, urlEndIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(Color.BLUE), urlStartIndex, urlEndIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(UnderlineSpan(), urlStartIndex, urlEndIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        holder.textDescription.text = spannable
        holder.textDescription.movementMethod = LinkMovementMethod.getInstance()
        
        // Remove the old click listener since we now have clickable spans
        holder.textDescription.setOnClickListener(null)

        // Status and file size
        val isProcessing = processingModels.contains(model.modelName)
        val isDownloading = downloadingModels.containsKey(model.modelName)
        val downloadProgress = downloadingModels[model.modelName] ?: 0
        
        // Hide secondary actions by default
        hideSecondaryActions(holder)
        
        // Hide download progress by default
        holder.layoutDownloadProgress.visibility = View.GONE
        
        when {
            isDownloading -> {
                updateStatusIndicator(holder, "processing")
                holder.textStatus.text = "📥 Downloading"
                holder.textFileSize.text = "Downloading..."
                holder.buttonPrimaryAction.text = "Downloading..."
                holder.buttonPrimaryAction.isEnabled = false
                holder.buttonSecondaryActions.visibility = View.GONE
                
                // Show download progress
                holder.layoutDownloadProgress.visibility = View.VISIBLE
                holder.textDownloadProgress.text = "Downloading ${model.modelName}... $downloadProgress%"
                holder.progressBarDownload.progress = downloadProgress
            }
            isProcessing -> {
                updateStatusIndicator(holder, "processing")
                holder.textStatus.text = "🔄 Processing"
                holder.textFileSize.text = "Loading..."
                holder.buttonPrimaryAction.text = "Loading..."
                holder.buttonPrimaryAction.isEnabled = false
                holder.buttonSecondaryActions.visibility = View.GONE
            }
            fileInfo.isAvailable -> {
                updateStatusIndicator(holder, "available")
                holder.textStatus.text = "Ready"
                holder.textFileSize.text = fileInfo.getFormattedSize()
                holder.buttonPrimaryAction.text = "🤖 Test Model"
                holder.buttonPrimaryAction.isEnabled = true
                holder.buttonSecondaryActions.visibility = View.VISIBLE
            }
            else -> {
                updateStatusIndicator(holder, "not_available")
                holder.textStatus.text = "Not Available"
                holder.textFileSize.text = "Not downloaded"
                
                // Make "Load File" prominent only for auth models (non-downloadable)
                // Make "Download" prominent for non-auth models (downloadable)
                if (model.needsAuth) {
                    holder.buttonPrimaryAction.text = "Load File"
                } else {
                    holder.buttonPrimaryAction.text = "Download"
                }
                holder.buttonPrimaryAction.isEnabled = true
                holder.buttonSecondaryActions.visibility = View.VISIBLE
                
                // Set download button text based on auth requirements
                if (model.needsAuth) {
                    holder.buttonDownload.text = "View Source"
                } else {
                    holder.buttonDownload.text = "Download"
                }
            }
        }

        // Primary action button
        holder.buttonPrimaryAction.setOnClickListener {
            when {
                isProcessing -> {
                    // Do nothing when processing
                }
                fileInfo.isAvailable -> {
                    // Test the model
                    onTestRequested(model)
                }
                model.needsAuth -> {
                    // For auth models, prioritize "Load File" (can't download directly)
                    if (!processingModels.contains(model.modelName)) {
                        setModelProcessing(model, true)
                        onLoadFileRequested(model)
                    }
                }
                else -> {
                    // For non-auth models, prioritize "Download"
                    showDownloadDialog(model)
                }
            }
        }

        // Secondary actions menu
        holder.buttonSecondaryActions.setOnClickListener {
            toggleSecondaryActions(holder)
        }

        // Secondary action buttons
        holder.buttonLoadLocal.setOnClickListener {
            if (!processingModels.contains(model.modelName)) {
                setModelProcessing(model, true)
                onLoadFileRequested(model)
            }
            hideSecondaryActions(holder)
        }

        holder.buttonDownload.setOnClickListener {
            if (model.needsAuth) {
                // Open source page for auth models
                openDownloadUrl(model)
            } else {
                // Show download dialog for non-auth models
                showDownloadDialog(model)
            }
            hideSecondaryActions(holder)
        }

        holder.buttonTest.setOnClickListener {
            if (!processingModels.contains(model.modelName)) {
                onTestRequested(model)
            }
            hideSecondaryActions(holder)
        }

        holder.buttonDelete.setOnClickListener {
            showDeleteConfirmation(model, holder)
            hideSecondaryActions(holder)
        }
    }

    override fun getItemCount(): Int = models.size

    private fun showDownloadDialog(model: AIModelConfig) {
        val staticModel = model as? AIModel
        val message = buildString {
            append("Download ${model.modelName}?\n\n")
            append("📱 Model: ${model.modelName}\n")
            append("📄 File: ${model.fileName}\n")
            val capabilities = mutableListOf("Text")
            if (model.supportsVision) capabilities.add("Vision")
            if (model.thinking) capabilities.add("Reasoning")
            append("\n✨ Capabilities: ${capabilities.joinToString(", ")}\n")
            if (staticModel != null) {
                append("🔗 Source: ${staticModel.url}\n\n")
                if (model.needsAuth) {
                    append("⚠️ Requires Hugging Face auth.\n1. Log in\n2. Accept license\n3. Download manually")
                } else {
                    append("✅ Can be downloaded directly!")
                }
            } else {
                append("\nThis is a locally imported model.")
            }
        }

        val dialogBuilder = AlertDialog.Builder(context)
            .setTitle("Download AI Model")
            .setMessage(message)
            .setNegativeButton("Cancel", null)

        if (staticModel != null) {
            if (model.needsAuth) {
                dialogBuilder.setPositiveButton("🌐 Open Source") { _, _ -> openDownloadUrl(model) }
            } else {
                dialogBuilder.setPositiveButton("📥 Download") { _, _ -> startDirectDownload(model) }
                dialogBuilder.setNeutralButton("🌐 Open Browser") { _, _ -> openDownloadUrl(model) }
            }
        }
        dialogBuilder.show()
    }

    private fun startDirectDownload(model: AIModelConfig) {
        val staticModel = model as? AIModel ?: return
        downloadingModels[model.modelName] = 0
        notifyDataSetChanged()
        Toast.makeText(context, "Starting download of ${model.modelName}...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = aiService.downloadModel(staticModel) { _, _, percentage ->
                    CoroutineScope(Dispatchers.Main).launch {
                        downloadingModels[model.modelName] = percentage
                        notifyDataSetChanged()
                    }
                }
                withContext(Dispatchers.Main) {
                    downloadingModels.remove(model.modelName)
                    if (result.success) {
                        Toast.makeText(context, "✅ ${model.modelName} downloaded successfully!", Toast.LENGTH_LONG).show()
                        refreshModelInfo()
                        setModelProcessing(model, true)
                        testModelAfterDownload(model)
                    } else {
                        Toast.makeText(context, "❌ Download failed: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                    notifyDataSetChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadingModels.remove(model.modelName)
                    notifyDataSetChanged()
                    Timber.e(e, "Download failed for ${model.modelName}")
                    Toast.makeText(context, "❌ Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun testModelAfterDownload(model: AIModelConfig) {
        val staticModel = model as? AIModel ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val testResult = aiService.testModel(staticModel)
                withContext(Dispatchers.Main) {
                    setModelProcessing(model, false)
                    if (testResult.success) {
                        Toast.makeText(context, "🤖 ${model.modelName} is ready to use!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "⚠️ Model downloaded but test failed: ${testResult.message}", Toast.LENGTH_LONG).show()
                    }
                    refreshModelInfo()
                    notifyDataSetChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setModelProcessing(model, false)
                    refreshModelInfo()
                    notifyDataSetChanged()
                    Timber.e(e, "Model test failed after download")
                    Toast.makeText(context, "⚠️ Model downloaded but couldn't be tested", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openDownloadUrl(model: AIModelConfig) {
        val staticModel = model as? AIModel ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(staticModel.url))
            context.startActivity(intent)
            val helpMessage = if (model.needsAuth) {
                "After downloading, use 'Load File' to import the model."
            } else {
                "After downloading, use 'Load File' to import the model to the app."
            }
            Toast.makeText(context, helpMessage, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Timber.e(e, "Failed to open download URL")
            Toast.makeText(context, "Failed to open browser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLicenseUrl(licenseUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(licenseUrl))
            context.startActivity(intent)
            Toast.makeText(context, "Opening license terms...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Timber.e(e, "Failed to open license URL")
            Toast.makeText(context, "Failed to open license URL", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmation(model: AIModelConfig, holder: ModelViewHolder) {
        val fileInfo = modelInfoMap[model]
        val message = "Delete ${model.modelName}?\n\nThis will remove the ${fileInfo?.getFormattedSize() ?: "model"} file from your device."

        AlertDialog.Builder(context)
            .setTitle("Delete AI Model")
            .setMessage(message)
            .setPositiveButton("🗑️ Delete") { _, _ ->
                deleteModel(model)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteModel(model: AIModelConfig) {
        try {
            if (model is DynamicAIModel) {
                AIModelRegistry.removeDynamicModel(context, model.id)
                Toast.makeText(context, "Removed ${model.modelName}", Toast.LENGTH_SHORT).show()
            } else {
                val referenceRemoved = ModelDetector.removeModelReference(context, model)
                if (referenceRemoved) {
                    Toast.makeText(context, "Removed ${model.modelName} reference", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to remove model reference", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            refreshModelInfo()
            notifyDataSetChanged()
            onRefreshRequested()
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove model")
            Toast.makeText(context, "Error deleting model: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSecondaryActions(holder: ModelViewHolder) {
        if (holder.layoutSecondaryActions.visibility == View.VISIBLE) {
            hideSecondaryActions(holder)
        } else {
            showSecondaryActions(holder)
        }
    }
    
    private fun showSecondaryActions(holder: ModelViewHolder) {
        holder.layoutSecondaryActions.visibility = View.VISIBLE
    }
    
    private fun hideSecondaryActions(holder: ModelViewHolder) {
        holder.layoutSecondaryActions.visibility = View.GONE
    }
    
    private fun updateStatusIndicator(holder: ModelViewHolder, status: String) {
        when (status) {
            "available" -> {
                holder.statusIndicator.setBackgroundResource(R.drawable.status_indicator_available)
                holder.statusIndicator.contentDescription = "Model available"
            }
            "processing" -> {
                holder.statusIndicator.setBackgroundResource(R.drawable.status_indicator_downloading)
                holder.statusIndicator.contentDescription = "Model processing"
            }
            "not_available" -> {
                holder.statusIndicator.setBackgroundResource(R.drawable.status_indicator_not_available)
                holder.statusIndicator.contentDescription = "Model not available"
            }
        }
    }

    fun refreshModelInfo() {
        modelInfoMap.clear()
        for (model in models) {
            modelInfoMap[model] = ModelDetector.getModelFileInfo(context, model)
        }
    }

    fun updateModels(newModels: List<AIModelConfig>) {
        models = newModels
        refreshModelInfo()
        notifyDataSetChanged()
    }

    fun setModelProcessing(model: AIModelConfig, isProcessing: Boolean) {
        if (isProcessing) {
            processingModels.add(model.modelName)
        } else {
            processingModels.remove(model.modelName)
        }
        notifyDataSetChanged()
    }

    fun clearProcessingState() {
        processingModels.clear()
        notifyDataSetChanged()
    }
}

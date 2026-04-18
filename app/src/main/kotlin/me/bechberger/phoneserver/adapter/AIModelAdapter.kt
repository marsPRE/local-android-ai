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
import me.bechberger.phoneserver.ai.CatalogModel
import me.bechberger.phoneserver.ai.CatalogVariant
import me.bechberger.phoneserver.ai.DynamicAIModel
import me.bechberger.phoneserver.ai.ModelDetector
import me.bechberger.phoneserver.ai.ModelFileInfo
import me.bechberger.phoneserver.ai.ModelFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

sealed class ModelListItem {
    data class SectionHeader(val title: String) : ModelListItem()
    data class ModelItem(val config: AIModelConfig) : ModelListItem()
}

class AIModelAdapter(
    private val context: Context,
    models: List<AIModelConfig>,
    private val aiService: AIService,
    private val onLoadFileRequested: (AIModelConfig) -> Unit,
    private val onTestRequested: (AIModelConfig) -> Unit,
    private val onRefreshRequested: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_SECTION = 0
        private const val VIEW_TYPE_MODEL = 1
    }

    private var items: List<ModelListItem> = models.map { ModelListItem.ModelItem(it) }
    private var modelInfoMap = mutableMapOf<AIModelConfig, ModelFileInfo>()
    private var processingModels = mutableSetOf<String>()
    private var downloadingModels = mutableMapOf<String, Int>()

    private val modelItems: List<AIModelConfig>
        get() = items.filterIsInstance<ModelListItem.ModelItem>().map { it.config }

    init {
        refreshModelInfo()
    }

    class SectionHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textTitle: TextView = itemView.findViewById(R.id.textSectionTitle)
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
        val layoutDownloadProgress: View = itemView.findViewById(R.id.layoutDownloadProgress)
        val textDownloadProgress: TextView = itemView.findViewById(R.id.textDownloadProgress)
        val progressBarDownload: ProgressBar = itemView.findViewById(R.id.progressBarDownload)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ModelListItem.SectionHeader -> VIEW_TYPE_SECTION
        is ModelListItem.ModelItem -> VIEW_TYPE_MODEL
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SECTION) {
            SectionHeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false))
        } else {
            ModelViewHolder(inflater.inflate(R.layout.item_ai_model, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ModelListItem.SectionHeader -> (holder as SectionHeaderViewHolder).textTitle.text = item.title
            is ModelListItem.ModelItem -> bindModel(holder as ModelViewHolder, item.config)
        }
    }

    private fun bindModel(holder: ModelViewHolder, model: AIModelConfig) {
        val fileInfo = modelInfoMap[model] ?: ModelFileInfo(model.fileName, 0, 0, false)

        holder.textModelName.text = model.modelName

        val descriptionWithFeatures = buildString {
            append(model.description)
            val capabilities = mutableListOf<String>()
            if (model.supportsVision) capabilities.add("Vision")
            if (model.thinking) capabilities.add("Reasoning")
            if (capabilities.isNotEmpty()) append(" • ${capabilities.joinToString(", ")}")
            append("\n\nLicense: ")
            if (model.licenseStatement != null) {
                append(model.licenseStatement)
                append("\n\nFull license terms: ")
            } else {
                append("Please review the license terms at: ")
            }
        }

        val fullText = descriptionWithFeatures + model.licenseUrl
        val spannable = SpannableString(fullText)
        val urlStartIndex = fullText.indexOf(model.licenseUrl)
        if (urlStartIndex != -1 && model.licenseUrl.isNotEmpty()) {
            val urlEndIndex = urlStartIndex + model.licenseUrl.length
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { openLicenseUrl(model.licenseUrl) }
            }, urlStartIndex, urlEndIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(Color.BLUE), urlStartIndex, urlEndIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(UnderlineSpan(), urlStartIndex, urlEndIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        holder.textDescription.text = spannable
        holder.textDescription.movementMethod = LinkMovementMethod.getInstance()
        holder.textDescription.setOnClickListener(null)

        val isProcessing = processingModels.contains(model.modelName)
        val isDownloading = downloadingModels.containsKey(model.modelName)
        val downloadProgress = downloadingModels[model.modelName] ?: 0

        hideSecondaryActions(holder)
        holder.layoutDownloadProgress.visibility = View.GONE

        when {
            isDownloading -> {
                updateStatusIndicator(holder, "processing")
                holder.textStatus.text = "Downloading"
                holder.textFileSize.text = "Downloading..."
                holder.buttonPrimaryAction.text = "Downloading..."
                holder.buttonPrimaryAction.isEnabled = false
                holder.buttonSecondaryActions.visibility = View.GONE
                holder.layoutDownloadProgress.visibility = View.VISIBLE
                holder.textDownloadProgress.text = "Downloading ${model.modelName}... $downloadProgress%"
                holder.progressBarDownload.progress = downloadProgress
            }
            isProcessing -> {
                updateStatusIndicator(holder, "processing")
                holder.textStatus.text = "Processing"
                holder.textFileSize.text = "Loading..."
                holder.buttonPrimaryAction.text = "Loading..."
                holder.buttonPrimaryAction.isEnabled = false
                holder.buttonSecondaryActions.visibility = View.GONE
            }
            fileInfo.isAvailable -> {
                updateStatusIndicator(holder, "available")
                holder.textStatus.text = "Ready"
                holder.textFileSize.text = fileInfo.getFormattedSize()
                holder.buttonPrimaryAction.text = "Test Model"
                holder.buttonPrimaryAction.isEnabled = true
                holder.buttonSecondaryActions.visibility = View.VISIBLE
            }
            else -> {
                updateStatusIndicator(holder, "not_available")
                holder.textStatus.text = "Not Available"
                holder.textFileSize.text = "Not downloaded"
                holder.buttonPrimaryAction.text = if (model.needsAuth) "Load File" else "Download"
                holder.buttonPrimaryAction.isEnabled = true
                holder.buttonSecondaryActions.visibility = View.VISIBLE
                holder.buttonDownload.text = if (model.needsAuth) "View Source" else "Download"
            }
        }

        holder.buttonPrimaryAction.setOnClickListener {
            when {
                isProcessing -> Unit
                fileInfo.isAvailable -> onTestRequested(model)
                model.needsAuth -> {
                    if (!processingModels.contains(model.modelName)) {
                        setModelProcessing(model, true)
                        onLoadFileRequested(model)
                    }
                }
                else -> showDownloadDialog(model)
            }
        }

        holder.buttonSecondaryActions.setOnClickListener { toggleSecondaryActions(holder) }

        holder.buttonLoadLocal.setOnClickListener {
            if (!processingModels.contains(model.modelName)) {
                setModelProcessing(model, true)
                onLoadFileRequested(model)
            }
            hideSecondaryActions(holder)
        }

        holder.buttonDownload.setOnClickListener {
            if (model.needsAuth) openDownloadUrl(model) else showDownloadDialog(model)
            hideSecondaryActions(holder)
        }

        holder.buttonTest.setOnClickListener {
            if (!processingModels.contains(model.modelName)) onTestRequested(model)
            hideSecondaryActions(holder)
        }

        holder.buttonDelete.setOnClickListener {
            showDeleteConfirmation(model, holder)
            hideSecondaryActions(holder)
        }
    }

    private fun showDownloadDialog(model: AIModelConfig) {
        val staticModel = model as? AIModel
        val message = buildString {
            append("Download ${model.modelName}?\n\n")
            append("Model: ${model.modelName}\n")
            append("File: ${model.fileName}\n")
            val capabilities = mutableListOf("Text")
            if (model.supportsVision) capabilities.add("Vision")
            if (model.thinking) capabilities.add("Reasoning")
            append("\nCapabilities: ${capabilities.joinToString(", ")}\n")
            if (staticModel != null) {
                append("Source: ${staticModel.url}\n\n")
                if (model.needsAuth) append("Requires Hugging Face auth.\n1. Log in\n2. Accept license\n3. Download manually")
                else append("Can be downloaded directly!")
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
                dialogBuilder.setPositiveButton("Open Source") { _, _ -> openDownloadUrl(model) }
            } else {
                dialogBuilder.setPositiveButton("Download") { _, _ -> startDirectDownload(model) }
                dialogBuilder.setNeutralButton("Open Browser") { _, _ -> openDownloadUrl(model) }
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
                        Toast.makeText(context, "${model.modelName} downloaded!", Toast.LENGTH_LONG).show()
                        refreshModelInfo()
                        setModelProcessing(model, true)
                        testModelAfterDownload(model)
                    } else {
                        Toast.makeText(context, "Download failed: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                    notifyDataSetChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadingModels.remove(model.modelName)
                    notifyDataSetChanged()
                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(context, "${model.modelName} is ready!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Test failed: ${testResult.message}", Toast.LENGTH_LONG).show()
                    }
                    refreshModelInfo()
                    notifyDataSetChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setModelProcessing(model, false)
                    refreshModelInfo()
                    notifyDataSetChanged()
                }
            }
        }
    }

    private fun openDownloadUrl(model: AIModelConfig) {
        val staticModel = model as? AIModel ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(staticModel.url)))
            Toast.makeText(context, "After downloading, use 'Load File' to import.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to open browser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLicenseUrl(licenseUrl: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(licenseUrl)))
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to open license URL", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmation(model: AIModelConfig, holder: ModelViewHolder) {
        val fileInfo = modelInfoMap[model]
        AlertDialog.Builder(context)
            .setTitle("Delete AI Model")
            .setMessage("Delete ${model.modelName}?\n\nThis will remove the ${fileInfo?.getFormattedSize() ?: "model"} file from your device.")
            .setPositiveButton("Delete") { _, _ -> deleteModel(model) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteModel(model: AIModelConfig) {
        try {
            if (model is DynamicAIModel) {
                AIModelRegistry.removeDynamicModel(context, model.id)
                // Also delete the actual file if it's in our imported dir
                if (model.absoluteFilePath.isNotEmpty()) {
                    File(model.absoluteFilePath).takeIf { it.exists() }?.delete()
                }
                Toast.makeText(context, "Removed ${model.modelName}", Toast.LENGTH_SHORT).show()
            } else {
                if (ModelDetector.removeModelReference(context, model)) {
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
        if (holder.layoutSecondaryActions.visibility == View.VISIBLE) hideSecondaryActions(holder)
        else showSecondaryActions(holder)
    }

    private fun showSecondaryActions(holder: ModelViewHolder) { holder.layoutSecondaryActions.visibility = View.VISIBLE }
    private fun hideSecondaryActions(holder: ModelViewHolder) { holder.layoutSecondaryActions.visibility = View.GONE }

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
        for (model in modelItems) {
            modelInfoMap[model] = ModelDetector.getModelFileInfo(context, model)
        }
    }

    /** Flat list with no section headers — backward-compatible. */
    fun updateModels(newModels: List<AIModelConfig>) {
        items = newModels.map { ModelListItem.ModelItem(it) }
        refreshModelInfo()
        notifyDataSetChanged()
    }

    /** Builds grouped sections: Ready / Available to Download / From Edge Gallery / Imported (unmatched). */
    fun updateWithSections(
        ready: List<AIModelConfig>,
        downloadable: List<AIModel>,
        edgeGalleryHints: List<Pair<CatalogModel, CatalogVariant>>,
        unknown: List<DynamicAIModel>
    ) {
        val newItems = mutableListOf<ModelListItem>()

        if (ready.isNotEmpty()) {
            newItems += ModelListItem.SectionHeader("Ready")
            ready.forEach { newItems += ModelListItem.ModelItem(it) }
        }
        if (downloadable.isNotEmpty()) {
            newItems += ModelListItem.SectionHeader("Available to Download")
            downloadable.forEach { newItems += ModelListItem.ModelItem(it) }
        }
        if (edgeGalleryHints.isNotEmpty()) {
            newItems += ModelListItem.SectionHeader("From Edge Gallery")
            edgeGalleryHints.forEach { (catalogModel, variant) ->
                newItems += ModelListItem.ModelItem(
                    DynamicAIModel(
                        id = "catalog_hint_${variant.id}",
                        modelName = "${catalogModel.displayName} (Gallery)",
                        fileName = variant.fileNamePatterns.firstOrNull() ?: variant.id,
                        absoluteFilePath = "",
                        licenseUrl = catalogModel.license.url,
                        thinking = variant.thinking,
                        supportsVision = variant.supportsVision,
                        maxTokens = variant.maxTokens,
                        temperature = variant.temperature.toFloat(),
                        topK = variant.topK,
                        topP = variant.topP.toFloat(),
                        needsAuth = catalogModel.license.needsAuth,
                        licenseStatement = catalogModel.license.statement,
                        modelFormat = if (variant.format == "LITERT_LM") ModelFormat.LITERT_LM else ModelFormat.MEDIAPIPE,
                        description = "Copy from Edge Gallery to Downloads — will be auto-detected on next open."
                    )
                )
            }
        }
        if (unknown.isNotEmpty()) {
            newItems += ModelListItem.SectionHeader("Imported (unmatched)")
            unknown.forEach { newItems += ModelListItem.ModelItem(it) }
        }

        items = newItems
        refreshModelInfo()
        notifyDataSetChanged()
    }

    fun setModelProcessing(model: AIModelConfig, isProcessing: Boolean) {
        if (isProcessing) processingModels.add(model.modelName)
        else processingModels.remove(model.modelName)
        notifyDataSetChanged()
    }

    fun clearProcessingState() {
        processingModels.clear()
        notifyDataSetChanged()
    }
}

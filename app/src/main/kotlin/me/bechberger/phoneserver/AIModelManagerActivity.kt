package me.bechberger.phoneserver

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.bechberger.phoneserver.adapter.AIModelAdapter
import me.bechberger.phoneserver.ai.AIModel
import me.bechberger.phoneserver.ai.AIModelConfig
import me.bechberger.phoneserver.ai.AIModelRegistry
import me.bechberger.phoneserver.ai.CatalogLoader
import me.bechberger.phoneserver.ai.AIService
import me.bechberger.phoneserver.ai.DynamicAIModel
import me.bechberger.phoneserver.ai.ModelDetector
import me.bechberger.phoneserver.testing.ApiTester
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin

/**
 * Activity for managing AI models - downloading, loading, and deleting
 */
class AIModelManagerActivity : AppCompatActivity() {
    
    companion object {
        private const val STORAGE_PERMISSION_REQUEST_CODE = 123
    }
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AIModelAdapter
    private lateinit var buttonRefresh: Button
    private lateinit var buttonClose: Button
    private lateinit var buttonScanGallery: Button
    private lateinit var aiService: AIService
    
    private var currentModelForFileLoad: AIModelConfig? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                currentModelForFileLoad?.let { model ->
                    loadModelFromUri(uri, model)
                }
            }
        }
    }

    private val manageStorageResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User returned from Settings — check if permission was granted now
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            scanEdgeGalleryModels()
        } else {
            Toast.makeText(this, "Permission not granted — can't scan Edge Gallery folder", Toast.LENGTH_LONG).show()
        }
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Timber.d("✅ Storage permissions granted")
            currentModelForFileLoad?.let { model ->
                showFilePicker(model)
            }
        } else {
            Timber.w("❌ Storage permissions denied")
            Toast.makeText(this, "Storage permissions required to import model files", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_model_manager)

        aiService = AIService(this)
        AIModelRegistry.init(this)

        initViews()
        setupRecyclerView()
        setupButtons()
        refreshModels()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewModels)
        buttonRefresh = findViewById(R.id.buttonRefresh)
        buttonClose = findViewById(R.id.buttonClose)
        buttonScanGallery = findViewById(R.id.buttonScanGallery)
    }

    private fun setupRecyclerView() {
        adapter = AIModelAdapter(
            context = this,
            models = AIModelRegistry.getAllModels(this),
            aiService = aiService,
            onLoadFileRequested = { model -> showFilePicker(model) },
            onTestRequested = { model -> testModel(model) },
            onRefreshRequested = { refreshModels() }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        buttonRefresh.setOnClickListener { refreshModels() }
        buttonClose.setOnClickListener { finish() }
        buttonScanGallery.setOnClickListener { openGalleryFolderPicker() }
    }

    private fun openGalleryFolderPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Permission required")
                    .setMessage("To scan Edge Gallery models, this app needs 'All files access'. You'll be taken to Settings — enable it for this app, then come back.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"))
                        manageStorageResultLauncher.launch(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }
        scanEdgeGalleryModels()
    }

    private fun scanEdgeGalleryModels() {
        CoroutineScope(Dispatchers.IO).launch {
            val ext = Environment.getExternalStorageDirectory()
            val searchRoots = listOf(
                File(ext, "Android/data/com.google.ai.edge.gallery/files"),
                File(ext, "Android/data/com.google.aiedge.gallery/files"),
                File(ext, "Download"),
                File(ext, "Downloads")
            )
            val found = mutableListOf<File>()
            val accessible = mutableListOf<File>()
            val inaccessible = mutableListOf<File>()
            for (root in searchRoots) {
                if (!root.exists()) continue
                if (!root.canRead()) { inaccessible.add(root); continue }
                accessible.add(root)
                root.walkTopDown().maxDepth(5).forEach { file ->
                    if (file.isFile && (file.name.endsWith(".litertlm") || file.name.endsWith(".task"))) {
                        found.add(file)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (found.isNotEmpty()) {
                    showFoundFilesDialog(found)
                } else if (inaccessible.isNotEmpty()) {
                    showAdbCopyDialog()
                } else {
                    Toast.makeText(this@AIModelManagerActivity,
                        "No model files found. Copy them to Downloads first.", Toast.LENGTH_LONG).show()
                    showAdbCopyDialog()
                }
            }
        }
    }

    private fun showAdbCopyDialog() {
        val simpleCmd = "adb pull \"/sdcard/Android/data/com.google.ai.edge.gallery/files\" /sdcard/Download/"
        android.app.AlertDialog.Builder(this)
            .setTitle("Models not directly accessible")
            .setMessage(
                "Android 15 blocks direct access to other apps' data folders.\n\n" +
                "On-device (no PC needed):\n" +
                "1. Open the Files app (Google)\n" +
                "2. Navigate to:\n   Android/data/com.google.ai.edge.gallery/files/\n" +
                "3. Copy the .litertlm files to Downloads\n" +
                "4. Tap 'Import Models' again\n\n" +
                "With PC (ADB):\n$simpleCmd"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showFoundFilesDialog(files: List<File>) {
        val names = files.map { it.name }.toTypedArray()
        val checked = BooleanArray(files.size) { true }
        android.app.AlertDialog.Builder(this)
            .setTitle("Found ${files.size} model file(s)")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Import selected") { _, _ ->
                val selected = files.filterIndexed { i, _ -> checked[i] }
                importLocalFiles(selected)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importLocalFiles(files: List<File>) {
        CoroutineScope(Dispatchers.Main).launch {
            var imported = 0
            for (file in files) {
                val knownPaths = AIModelRegistry.getAllModels(this@AIModelManagerActivity)
                    .map { it.absoluteFilePath }.toSet()
                if (file.absolutePath in knownPaths) continue
                val dynModel = DynamicAIModel.fromFile(file.absolutePath)
                AIModelRegistry.addDynamicModel(this@AIModelManagerActivity, dynModel)
                imported++
            }
            Toast.makeText(this@AIModelManagerActivity, "Registered $imported/${files.size} models", Toast.LENGTH_LONG).show()
            refreshModels()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            val count = AIModelRegistry.scanAndAutoImport(this@AIModelManagerActivity)
            if (count > 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AIModelManagerActivity,
                        "Auto-imported $count new model(s) from Downloads", Toast.LENGTH_SHORT).show()
                    refreshModels()
                }
            }
        }
    }

    private fun refreshModels() {
        val models = AIModelRegistry.getDynamicModels(this)
            .filter { java.io.File(it.absoluteFilePath).exists() }
        adapter.updateModels(models)
        ModelDetector.logModelStatus(this)
    }

    private fun showFilePicker(model: AIModelConfig) {
        currentModelForFileLoad = model
        
        // Check permissions first
        if (!hasStoragePermissions()) {
            Timber.d("🔒 Requesting storage permissions for model import")
            requestStoragePermissions()
            return
        }
        
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "Select ${model.fileName}")
        }
        
        try {
            filePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open file picker")
            Toast.makeText(this, "Failed to open file picker", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun hasStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+) - check granular media permissions
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-12 (API 23-32) - check READ_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            // Below Android 6 - permissions granted at install time
            true
        }
    }
    
    private fun requestStoragePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+) - request granular media permissions
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-12 (API 23-32) - request READ_EXTERNAL_STORAGE
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // Below Android 6 - no runtime permissions needed
            return
        }
        
        permissionLauncher.launch(permissions)
    }

    private fun loadModelFromUri(uri: Uri, model: AIModelConfig) {
        // Set processing state
        // Mark model as processing in UI
        // adapter.setModelProcessing(model, true)
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    copyModelFromUri(uri, model)
                }
                
                // Clear processing state
                // adapter.setModelProcessing(model, false)
                adapter.notifyDataSetChanged()
                
                if (success) {
                    Toast.makeText(this@AIModelManagerActivity, 
                        "✅ Successfully loaded ${model.modelName}", Toast.LENGTH_LONG).show()
                    refreshModels() // Auto-refresh to update UI
                } else {
                    // Get more specific error message from logs
                    val errorMsg = when {
                        !Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED) -> 
                            "External storage not available"
                        else -> "Failed to save model file - check storage permissions"
                    }
                    Toast.makeText(this@AIModelManagerActivity, 
                        "❌ $errorMsg", Toast.LENGTH_LONG).show()
                    Timber.w("Model import failed for ${model.modelName}")
                }
            } catch (e: Exception) {
                // Clear processing state on error
                // adapter.setModelProcessing(model, false)
                adapter.notifyDataSetChanged()
                Timber.e(e, "Failed to load model from URI")
                Toast.makeText(this@AIModelManagerActivity, 
                    "Error loading model: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun copyModelFromUri(uri: Uri, model: AIModelConfig): Boolean {
        return try {
            Timber.d("🔄 Starting model import for ${model.modelName}")
            Timber.d("   Source URI: $uri")
            Timber.d("   Expected filename: ${model.fileName}")
            
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Timber.e("❌ Failed to open input stream from URI: $uri")
                return false
            }
            
            Timber.d("✅ Successfully opened input stream from URI")

            // Copy to Downloads/ai-models so the auto-scanner picks it up
            val modelsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ai-models"
            )
            
            if (!modelsDir.exists()) {
                val created = modelsDir.mkdirs()
                if (!created) {
                    Timber.e("❌ Failed to create models directory: ${modelsDir.absolutePath}")
                    return false
                }
                Timber.d("✅ Created models directory: ${modelsDir.absolutePath}")
            }
            
            if (!modelsDir.canWrite()) {
                Timber.e("❌ Models directory is not writable: ${modelsDir.absolutePath}")
                return false
            }
            
            val targetFile = File(modelsDir, model.fileName)
            Timber.d("🔧 Target file for import: ${targetFile.absolutePath}")
            
            // Create backup if file already exists
            if (targetFile.exists()) {
                val backupFile = File(modelsDir, "${model.fileName}.backup")
                val renamed = targetFile.renameTo(backupFile)
                if (!renamed) {
                    Timber.w("⚠️  Failed to create backup, continuing anyway")
                } else {
                    Timber.d("📦 Created backup: ${backupFile.absolutePath}")
                }
            }

            try {
                val outputStream = FileOutputStream(targetFile)
                Timber.d("✅ Successfully created output stream for: ${targetFile.absolutePath}")
            
                inputStream.use { input ->
                    outputStream.use { output ->
                        val bytesCopied = input.copyTo(output)
                        Timber.d("📁 Copied $bytesCopied bytes to target file")
                    }
                }
            } catch (e: SecurityException) {
                Timber.e(e, "🔒 Permission denied creating file: ${targetFile.absolutePath}")
                return false
            } catch (e: java.io.IOException) {
                Timber.e(e, "💾 IO error during file copy: ${targetFile.absolutePath}")
                return false
            }

            // Verify the file was copied successfully
            if (targetFile.exists() && targetFile.length() > 0) {
                Timber.i("Successfully copied model file: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                
                // Register the copied file in-place
                val dynModel = DynamicAIModel.fromFile(targetFile.absolutePath)
                AIModelRegistry.addDynamicModel(this@AIModelManagerActivity, dynModel)
                Timber.i("Registered imported model: ${model.modelName} at ${targetFile.absolutePath}")

                // Remove backup if copy was successful
                val backupFile = File(modelsDir, "${model.fileName}.backup")
                if (backupFile.exists()) backupFile.delete()

                true
            } else {
                Timber.e("Model file copy failed - file is empty or doesn't exist")
                val backupFile = File(modelsDir, "${model.fileName}.backup")
                if (backupFile.exists()) backupFile.renameTo(targetFile)
                false
            }
        } catch (e: SecurityException) {
            Timber.e(e, "❌ Security/Permission error during model file copy for ${model.modelName}")
            false
        } catch (e: java.io.IOException) {
            Timber.e(e, "💾 IO error during model file copy for ${model.modelName}")  
            false
        } catch (e: Exception) {
            Timber.e(e, "❌ Unexpected error during model file copy for ${model.modelName}")
            false
        }
    }
    
    /**
     * Test a model to see if it can be loaded and used
     */
    private fun testModel(model: AIModelConfig) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@AIModelManagerActivity, "Testing ${model.modelName}...", Toast.LENGTH_SHORT).show()
                
                val aiService = AIService(this@AIModelManagerActivity)
                
                // Show streaming test dialog
                showStreamingTestDialog(model, aiService)
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AIModelManagerActivity, "❌ Test failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Show a modern dialog for testing AI models with custom prompts and markdown formatting
     */
    private suspend fun showStreamingTestDialog(model: AIModelConfig, aiService: AIService) {
        withContext(Dispatchers.Main) {
            val dialogBuilder = android.app.AlertDialog.Builder(this@AIModelManagerActivity)
            
            // Create modern layout with better styling
            val dialogLayout = android.widget.LinearLayout(this@AIModelManagerActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
            }
            
            // Header with model info
            val headerText = android.widget.TextView(this@AIModelManagerActivity).apply {
                text = "🤖 ${model.modelName}"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 16)
                setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.primary_text_light))
            }
            dialogLayout.addView(headerText)
            
            // Input section
            val inputLabel = android.widget.TextView(this@AIModelManagerActivity).apply {
                text = "Test Prompt:"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 8)
                setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.primary_text_light))
            }
            dialogLayout.addView(inputLabel)
            
            // Input layout (EditText + Run button)
            val inputLayout = android.widget.LinearLayout(this@AIModelManagerActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 16)
            }
            
            val promptInput = android.widget.EditText(this@AIModelManagerActivity).apply {
                hint = "Enter your prompt here..."
                setText("Ping")
                textSize = 14f
                setPadding(16, 16, 16, 16)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = ContextCompat.getDrawable(this@AIModelManagerActivity, android.R.drawable.edit_text)
            }
            inputLayout.addView(promptInput)
            
            val runButton = android.widget.Button(this@AIModelManagerActivity).apply {
                text = "▶"
                textSize = 20f
                setPadding(20, 20, 20, 20)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 8, 0, 8)
                }
                
                // Clean, flat, minimal design
                isAllCaps = false
                elevation = 0f
                stateListAnimator = null
                
                // Simple flat background with subtle press state
                val normalColor = ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.transparent)
                val pressedColor = ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.darker_gray)
                
                val stateListDrawable = android.graphics.drawable.StateListDrawable()
                val normalDrawable = android.graphics.drawable.ColorDrawable(normalColor)
                val pressedDrawable = android.graphics.drawable.ColorDrawable(pressedColor)
                
                stateListDrawable.addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
                stateListDrawable.addState(intArrayOf(), normalDrawable)
                
                background = stateListDrawable
                setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.black))
            }
            inputLayout.addView(runButton)
            dialogLayout.addView(inputLayout)
            
            // Status and timing section
            val statusLayout = android.widget.LinearLayout(this@AIModelManagerActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 16)
            }
            
            val statusText = android.widget.TextView(this@AIModelManagerActivity).apply {
                text = "Ready to test"
                textSize = 14f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.secondary_text_light))
            }
            statusLayout.addView(statusText)
            
            val timingText = android.widget.TextView(this@AIModelManagerActivity).apply {
                text = ""
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.secondary_text_light))
            }
            statusLayout.addView(timingText)
            dialogLayout.addView(statusLayout)
            
            // Progress indicator
            val progressBar = android.widget.ProgressBar(this@AIModelManagerActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                visibility = android.view.View.GONE
            }
            dialogLayout.addView(progressBar)
            
            // Create scrollable text view for formatted output using Markwon
            val scrollView = android.widget.ScrollView(this@AIModelManagerActivity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    600 // Fixed height for better UX
                )
            }
            
            val outputTextView = android.widget.TextView(this@AIModelManagerActivity).apply {
                text = "Click '▶' to test the model with your prompt"
                textSize = 14f
                setPadding(24, 24, 24, 24)
                setTextIsSelectable(true)
                setBackgroundColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.white))
                setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.black))
                background = ContextCompat.getDrawable(this@AIModelManagerActivity, android.R.drawable.edit_text)
                
                // Use monospace font to preserve spacing
                typeface = android.graphics.Typeface.MONOSPACE
                
                // Better line spacing for readability
                setLineSpacing(8f, 1.4f)
            }
            scrollView.addView(outputTextView)
            dialogLayout.addView(scrollView)
            
            // Initialize Markwon for formatting with preserving whitespace
            val markwon = io.noties.markwon.Markwon.builder(this@AIModelManagerActivity)
                .usePlugin(io.noties.markwon.html.HtmlPlugin.create())
                .build()
            
            // Helper function to format text with proper spacing - use raw text for better control
            fun setFormattedText(textView: android.widget.TextView, text: String) {
                // Skip Markwon entirely and just use raw text with proper formatting
                // This preserves all spacing and shows <think> tags as-is
                val formattedText = text
                    .replace("**", "") // Remove markdown bold markers for cleaner display
                    .replace("*", "")  // Remove markdown italic markers
                    .replace("```", "") // Remove code block markers
                
                textView.text = formattedText
            }
            
            // Helper function to update button styling with different states
            fun updateButtonStyle(button: android.widget.Button, state: String) {
                // For flat design, just change text color to indicate state
                val textColor = when (state) {
                    "running" -> android.R.color.holo_orange_dark
                    "success" -> android.R.color.holo_green_dark
                    "error" -> android.R.color.holo_red_dark
                    else -> android.R.color.black // default
                }
                
                button.setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, textColor))
            }
            
            val dialog = dialogBuilder
                .setTitle("AI Model Test")
                .setView(dialogLayout)
                .setNegativeButton("Close") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .create()
            
            dialog.show()
            
            // Variables to track test state
            var isTestRunning = false
            var currentTestJob: kotlinx.coroutines.Job? = null
            var accumulatedResponse = ""  // Track accumulated text for cancellation
            
            // Function to run the test
            fun runTest() {
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) {
                    Toast.makeText(this@AIModelManagerActivity, "Please enter a prompt", Toast.LENGTH_SHORT).show()
                    return
                }
                
                runButton.isEnabled = true  // Keep enabled so user can stop
                runButton.text = "⏸"  // Pause icon to indicate it can be stopped
                updateButtonStyle(runButton, "running")
                promptInput.isEnabled = false
                progressBar.visibility = android.view.View.VISIBLE
                statusText.text = "Checking server connectivity..."
                timingText.text = ""
                isTestRunning = true
                accumulatedResponse = ""  // Reset accumulated text for new test
                
                val startTime = System.currentTimeMillis()
                var tokenCount = 0 // Track tokens for T/s calculation
                
                setFormattedText(outputTextView, "🔌 **Connecting to server...**\n\nChecking server availability at localhost:8005")
                
                currentTestJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // First check server connectivity
                        val apiTester = ApiTester()
                        val serverReachable = apiTester.isServerReachable()
                        
                        if (!serverReachable) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                statusText.text = "Server not reachable"
                                setFormattedText(outputTextView, "**Connection Error**\n\nThe AI server at localhost:8005 is not responding. Please check:\n1. Is the server running?\n2. Is the port 8005 available?\n3. Are there firewall restrictions?")
                                timingText.text = "Failed immediately"
                                progressBar.visibility = android.view.View.GONE
                                runButton.isEnabled = true
                                runButton.text = "🔄"
                                updateButtonStyle(runButton, "error")
                                promptInput.isEnabled = true
                                isTestRunning = false
                                currentTestJob = null
                            }
                            return@launch
                        }
                        
                        lifecycleScope.launch(Dispatchers.Main) {
                            statusText.text = "Initializing model..."
                            setFormattedText(outputTextView, "🤖 **Testing model...**\n\nInitializing and preparing response...")
                        }
                        
                        aiService.testModelStreaming(
                            model = model,
                            prompt = prompt,
                            temperature = 0.7f,
                            topK = 40,
                            topP = 0.95f
                        ) { chunk ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                when (chunk.type) {
                                    "token" -> {
                                        statusText.text = "Generating response..."
                                        val elapsedTime = System.currentTimeMillis() - startTime
                                        tokenCount++
                                        
                                        // Calculate tokens per second with padding for stable display
                                        val elapsedSeconds = elapsedTime / 1000.0
                                        val tokensPerSecond = if (elapsedSeconds > 0) tokenCount / elapsedSeconds else 0.0
                                        timingText.text = "${String.format("%.1f", elapsedSeconds)}s | ${String.format("%4.1f", tokensPerSecond)} T/s"
                                        
                                        // Use fullText which contains the properly spaced accumulated text from MediaPipe
                                        // This is equivalent to the gallery's partialResult parameter
                                        if (!chunk.fullText.isNullOrEmpty()) {
                                            accumulatedResponse = chunk.fullText
                                        }
                                        
                                        // Display the accumulated response directly
                                        setFormattedText(outputTextView, accumulatedResponse)
                                        
                                        // Auto-scroll to bottom
                                        scrollView.post {
                                            scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                                        }
                                    }
                                    "complete" -> {
                                        val elapsedTime = System.currentTimeMillis() - startTime
                                        
                                        // Calculate final tokens per second
                                        val elapsedSeconds = elapsedTime / 1000.0
                                        val tokensPerSecond = if (elapsedSeconds > 0) tokenCount / elapsedSeconds else 0.0
                                        
                                        // Hide progress bar and update status
                                        progressBar.visibility = android.view.View.GONE
                                        statusText.text = "✅ Test completed successfully!"
                                        statusText.setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.holo_green_dark))
                                        timingText.text = "⏱️ ${String.format("%.1f", elapsedSeconds)}s | ${String.format("%4.1f", tokensPerSecond)} T/s"
                                        timingText.setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.holo_green_dark))
                                        
                                        // Update header
                                        headerText.text = "✅ ${model.modelName}"
                                        
                                        // Re-enable inputs for another test
                                        runButton.isEnabled = true
                                        promptInput.isEnabled = true
                                        runButton.text = "▶"
                                        updateButtonStyle(runButton, "success")
                                        isTestRunning = false
                                        currentTestJob = null
                                        
                                        // Display final accumulated response (use accumulated if fullText not available)
                                        val finalText = chunk.fullText ?: accumulatedResponse
                                        setFormattedText(outputTextView, finalText)
                                        
                                        // Clear failed status if test was successful
                                        ModelDetector.clearModelFailedStatus(this@AIModelManagerActivity, model)
                                        refreshModels() // Refresh to update UI
                                        
                                        scrollView.post {
                                            scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                                        }
                                    }
                                    "error" -> {
                                        val elapsedTime = System.currentTimeMillis() - startTime
                                        val elapsedSeconds = elapsedTime / 1000.0
                                        val tokensPerSecond = if (elapsedSeconds > 0) tokenCount / elapsedSeconds else 0.0
                                        val cleanError = cleanupErrorMessage(chunk.error ?: "Unknown error")
                                        
                                        // Hide progress bar and update status with error
                                        progressBar.visibility = android.view.View.GONE
                                        statusText.text = "❌ Test failed"
                                        statusText.setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.holo_red_dark))
                                        timingText.text = "⏱️ Failed after ${String.format("%.1f", elapsedSeconds)}s | ${String.format("%4.1f", tokensPerSecond)} T/s"
                                        timingText.setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.holo_red_dark))
                                        
                                        // Update header
                                        headerText.text = "❌ ${model.modelName}"
                                        
                                        // Re-enable inputs for retry
                                        runButton.isEnabled = true
                                        promptInput.isEnabled = true


                                        runButton.text = "🔄"
                                        updateButtonStyle(runButton, "error")
                                        
                                        // Display error with suggestions
                                        setFormattedText(outputTextView, "**Error:** $cleanError\n\n**Troubleshooting:**\n1. Check if the model file is accessible\n2. Verify model format compatibility\n3. Check available memory\n4. Try restarting the server")
                                        
                                        // Mark model as failed for future reference
                                        ModelDetector.markModelAsFailed(this@AIModelManagerActivity, model)
                                        refreshModels() // Refresh to update UI
                                        
                                        scrollView.post {
                                            scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        // Coroutine was cancelled - this is expected when user stops generation
                        // The stopTest() function will handle UI updates
                        Timber.d("Generation cancelled by user")
                    } catch (e: Exception) {
                        val elapsedTime = System.currentTimeMillis() - startTime
                        val cleanError = cleanupErrorMessage(e.message ?: "Unknown error")
                        
                        lifecycleScope.launch(Dispatchers.Main) {
                            // Hide progress bar and update status with error
                            progressBar.visibility = android.view.View.GONE
                            statusText.text = "❌ Test failed"
                            statusText.setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.holo_red_dark))
                            timingText.text = "⏱️ Failed after ${String.format("%.1f", elapsedTime / 1000.0)}s"
                            timingText.setTextColor(ContextCompat.getColor(this@AIModelManagerActivity, android.R.color.holo_red_dark))
                            
                            // Update header
                            headerText.text = "❌ ${model.modelName}"
                            
                            // Re-enable inputs for retry
                            runButton.isEnabled = true
                            promptInput.isEnabled = true
                            runButton.text = "🔄"
                            updateButtonStyle(runButton, "error")
                            isTestRunning = false
                            currentTestJob = null
                            
                            // Format error output - show any partial response, then error
                            val errorText = "❌ **Error:** $cleanError"
                            markwon.setMarkdown(outputTextView, errorText)
                            
                            scrollView.post {
                                scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                            }
                        }
                    }
                }
            }
            
            // Function to stop the current test
            fun stopTest() {
                if (isTestRunning) {
                    currentTestJob?.cancel()
                    currentTestJob = null
                    isTestRunning = false
                    
                    // Reset UI state
                    runButton.isEnabled = true
                    runButton.text = "▶"  // Play icon to indicate it can be started
                    updateButtonStyle(runButton, "default")
                    promptInput.isEnabled = true
                    progressBar.visibility = android.view.View.GONE
                    statusText.text = "Generation stopped"
                    
                    // Preserve accumulated text and add stop notice
                    val stoppedOutput = if (accumulatedResponse.isNotEmpty()) {
                        "$accumulatedResponse\n\n---\n⏹ **Generation stopped by user**"
                    } else {
                        "⏹ **Generation stopped by user**\n\nNo text was generated before stopping."
                    }
                    setFormattedText(outputTextView, stoppedOutput)
                }
            }
            
            // Set click listener for run button
            runButton.setOnClickListener { 
                if (isTestRunning) {
                    // Stop the current test
                    stopTest()
                } else {
                    // Start a new test
                    runTest()
                }
            }
            
            // Handle dialog dismissal to stop any running test
            dialog.setOnDismissListener {
                if (isTestRunning) {
                    stopTest()
                }
            }
            
            // Auto-run with default prompt on dialog open
            runTest()
        }
    }
    
    /**
     * Clean up technical error messages to be more user-friendly
     */
    private fun cleanupErrorMessage(rawError: String): String {
        return when {
            rawError.contains("Failed to initialize AI inference service", ignoreCase = true) -> {
                "Model initialization failed. The model file may be corrupted or incompatible."
            }
            rawError.contains("Failed to initialize AI service", ignoreCase = true) -> {
                "Could not start the AI engine. This model may not be compatible with your device."
            }
            rawError.contains("MediaPipe", ignoreCase = true) -> {
                "AI engine error. Try restarting the app or using a different model."
            }
            rawError.contains("Model file not found", ignoreCase = true) -> {
                "Model file not found. Please re-download the model."
            }
            rawError.contains("not readable", ignoreCase = true) -> {
                "Cannot access model file. Check storage permissions and file location."
            }
            rawError.contains("memory", ignoreCase = true) || rawError.contains("OutOfMemory", ignoreCase = true) -> {
                "Insufficient memory. Try restarting the app or using a smaller model."
            }
            rawError.contains("timeout", ignoreCase = true) || rawError.contains("timed out", ignoreCase = true) -> {
                "Connection timeout - the server is taking too long to respond. This may indicate the server is not running on localhost:8005, or the AI model is taking an extremely long time to process."
            }
            rawError.contains("corrupted", ignoreCase = true) -> {
                "Model file appears to be corrupted. Please re-download the model."
            }
            rawError.contains("permission", ignoreCase = true) -> {
                "Storage permission issue. Check app permissions in device settings."
            }
            // Extract the first meaningful line from multi-line errors
            rawError.contains('\n') -> {
                val lines = rawError.split('\n')
                val meaningfulLine = lines.find { line ->
                    line.trim().isNotEmpty() && 
                    !line.contains("Exception type:", ignoreCase = true) &&
                    !line.contains("Model file:", ignoreCase = true) &&
                    !line.contains("File exists:", ignoreCase = true) &&
                    !line.contains("File size:", ignoreCase = true) &&
                    !line.contains("MediaPipe backend:", ignoreCase = true) &&
                    !line.startsWith("at ", ignoreCase = true)
                } ?: lines.firstOrNull()
                
                meaningfulLine?.trim()?.let { cleanupErrorMessage(it) } ?: "Unknown error occurred"
            }
            // If the message is already short and clear, keep it
            rawError.length < 100 && !rawError.contains(".") -> rawError
            // For long technical messages, extract the key part
            else -> {
                val sentences = rawError.split(". ")
                sentences.firstOrNull()?.trim() ?: "Model testing failed"
            }
        }
    }
}

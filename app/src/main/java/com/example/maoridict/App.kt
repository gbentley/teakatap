// Full Android app base with: Persistent highlights, Te Aka search, PDF support, and OCR via camera
// This version includes navigation via buttons and OCR preprocessing

// Step 1: Define Room Entity, DAO, and Database

@Entity(tableName = "highlighted_words")
data class HighlightedWord(
    @PrimaryKey val word: String
)

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlighted_words")
    suspend fun getAll(): List<HighlightedWord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(word: HighlightedWord)

    @Delete
    suspend fun delete(word: HighlightedWord)
}

@Database(entities = [HighlightedWord::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun highlightDao(): HighlightDao
}

// Step 2: ViewModel for managing highlight state and current text

class HighlightViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java, "highlight-db"
    ).build()
    private val dao = db.highlightDao()

    var highlightedWords = mutableStateListOf<String>()
        private set

    var currentText by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            highlightedWords.addAll(dao.getAll().map { it.word })
        }
    }

    fun setText(newText: String) {
        currentText = newText
    }

    fun toggleHighlight(word: String) {
        viewModelScope.launch {
            if (highlightedWords.contains(word)) {
                dao.delete(HighlightedWord(word))
                highlightedWords.remove(word)
            } else {
                dao.insert(HighlightedWord(word))
                highlightedWords.add(word)
            }
        }
    }

    fun removeHighlight(word: String) {
        viewModelScope.launch {
            dao.delete(HighlightedWord(word))
            highlightedWords.remove(word)
        }
    }
}

// Step 3: Base Composable with word tap and long-press

@Composable
fun HighlightableText(
    text: String,
    viewModel: HighlightViewModel,
    onSearch: (String) -> Unit
) {
    val words = text.split(" ")
    val annotatedText = buildAnnotatedString {
        for ((index, word) in words.withIndex()) {
            pushStringAnnotation(tag = "WORD", annotation = word)
            withStyle(
                style = if (viewModel.highlightedWords.contains(word)) {
                    SpanStyle(color = Color.Red, textDecoration = TextDecoration.Underline)
                } else {
                    SpanStyle(color = Color.Black)
                }
            ) {
                append(word)
            }
            pop()
            if (index != words.lastIndex) append(" ")
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        annotatedText.getStringAnnotations("WORD", offset, offset)
                            .firstOrNull()?.let { ann ->
                                val word = ann.item
                                if (viewModel.highlightedWords.contains(word)) {
                                    onSearch(word)
                                } else {
                                    viewModel.toggleHighlight(word)
                                }
                            }
                    },
                    onLongPress = { offset ->
                        annotatedText.getStringAnnotations("WORD", offset, offset)
                            .firstOrNull()?.let { ann ->
                                viewModel.removeHighlight(ann.item)
                            }
                    }
                )
            }
    ) {
        ClickableText(
            text = annotatedText,
            style = TextStyle(fontSize = 18.sp),
            onClick = {} // handled in pointerInput
        )
    }
}

// Step 4: Te Aka URL utility

fun searchTeAka(context: Context, word: String) {
    val url = "https://www.maoridictionary.co.nz/search?keywords=${word}"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}

// Step 5: MainActivity with navigation buttons

@Composable
fun MainScreen(viewModel: HighlightViewModel) {
    var screen by remember { mutableStateOf("home") }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { screen = "home" }) { Text("Home") }
            Button(onClick = { screen = "pdf" }) { Text("Open PDF") }
            Button(onClick = { screen = "ocr" }) { Text("OCR Camera") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (screen) {
            "home" -> HighlightableText(
                text = if (viewModel.currentText.isNotBlank()) viewModel.currentText else "Tap on any word to highlight it and tap again to search in Te Aka.",
                viewModel = viewModel,
                onSearch = { word -> searchTeAka(context, word) }
            )
            "pdf" -> PDFViewerScreen(viewModel)
            "ocr" -> OCRCaptureScreen(viewModel)
        }
    }
}

// PDF file picker + renderer
@Composable
fun PDFViewerScreen(viewModel: HighlightViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val pdfRenderer = PdfRenderer(pfd)
                val page = pdfRenderer.openPage(0)
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val preprocessed = preprocessBitmap(bitmap)
                val text = extractTextFromBitmap(context, preprocessed)
                viewModel.setText(text)
                page.close()
                pdfRenderer.close()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Select a PDF file to extract text")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { launcher.launch(arrayOf("application/pdf")) }) {
            Text("Pick PDF")
        }
    }
}

// OCR capture using ML Kit
@Composable
fun OCRCaptureScreen(viewModel: HighlightViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        bitmap?.let {
            val preprocessed = preprocessBitmap(it)
            val text = extractTextFromBitmap(context, preprocessed)
            viewModel.setText(text)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Capture image with text")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { launcher.launch() }) {
            Text("Open Camera")
        }
    }
}

// ML Kit OCR text extraction helper
fun extractTextFromBitmap(context: Context, bitmap: Bitmap): String {
    val image = InputImage.fromBitmap(bitmap, 0)
    val recognizer = TextRecognition.getClient()

    var extractedText = ""
    val latch = CountDownLatch(1)

    recognizer.process(image)
        .addOnSuccessListener { visionText ->
            extractedText = visionText.text
            latch.countDown()
        }
        .addOnFailureListener {
            latch.countDown()
        }

    latch.await(3, TimeUnit.SECONDS)
    return extractedText
}

// Bitmap preprocessing to improve OCR results
fun preprocessBitmap(original: Bitmap): Bitmap {
    val grayscale = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(grayscale)
    val paint = Paint()
    val colorMatrix = ColorMatrix()
    colorMatrix.setSaturation(0f)
    val filter = ColorMatrixColorFilter(colorMatrix)
    paint.colorFilter = filter
    canvas.drawBitmap(original, 0f, 0f, paint)

    return grayscale
}

// Add ML Kit dependencies in build.gradle:
// implementation 'com.google.mlkit:text-recognition:16.0.0'

// Permissions needed in AndroidManifest.xml:
// <uses-permission android:name="android.permission.CAMERA" />
 

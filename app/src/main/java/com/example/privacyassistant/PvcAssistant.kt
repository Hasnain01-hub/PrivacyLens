package com.example.privacyassistant

import android.content.Intent
import android.net.Uri
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch


/*
Class to handle the Privacy Assistant logic
It does the following:
1. Lets the user choose one or more images from his/her gallery
2. Displays these images afterwards
3. ?
4. Redirects the user back to Instagram at the posting stage
 */
class PvcAssistant : ComponentActivity() {

    private lateinit var dbHandler: DBHandler
    private lateinit var viewModel: ImQuestViewModel
    private lateinit var user: UserHelper

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceName = ComponentName(
            context,
            InstagramAccessibilityService::class.java
        ).flattenToString()

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        return enabledServices.contains(serviceName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseFirestore.setLoggingEnabled(true)

        dbHandler = DBHandler(this)
        viewModel = dbHandler.createViewModel(this)
        user = UserHelper(-1, -1, -1, -1, -1, -1)

        setContent {
            var isDarkTheme by remember { mutableStateOf(false) }
            val lightColors = lightColorScheme(
                primary = Color(0xFF8E24AA),
                onPrimary = Color.White,
                background = Color.White,
                onBackground = Color.Black,
                surface = Color.White,
                onSurface = Color.Black
            )
            val darkColors = darkColorScheme(
                primary = Color(0xFF8E24AA),
                onPrimary = Color.White,
                background = Color(0xFF121212),
                onBackground = Color.White,
                surface = Color(0xFF1E1E1E),
                onSurface = Color.White
            )
            MaterialTheme(
                colorScheme = if (isDarkTheme) darkColors else lightColors
            ) {
                PvcAssistantScreen(
                    viewModel = viewModel,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { isDarkTheme = !isDarkTheme }
                )
            }
        }
    }

    @Composable
    fun PvcAssistantScreen(
        viewModel: ImQuestViewModel,
        isDarkTheme: Boolean,
        onToggleTheme: () -> Unit
    ) {
        val context = LocalContext.current
        var selectedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var currentStep by remember { mutableIntStateOf(0) }
        
        // State for accessibility prompt dialog
        var showAccessibilityDialog by remember { mutableStateOf(false) }

        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            if (uris.isNotEmpty()) selectedImages = uris
        }

        val colorScheme = if (isDarkTheme) darkColorScheme(
            primary = Color(0xFF8E24AA),
            onPrimary = Color.White,
            background = Color(0xFF121212),
            onBackground = Color.White,
            surface = Color(0xFF1E1E1E),
            onSurface = Color.White
        ) else lightColorScheme(
            primary = Color(0xFF8E24AA),
            onPrimary = Color.White,
            background = Color.White,
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black
        )

        // Check accessibility service state on first composition
        LaunchedEffect(Unit) {
            val prefs = context.getSharedPreferences("app_prefs", MODE_PRIVATE)
            val hasShownPrompt = prefs.getBoolean("has_shown_accessibility_prompt", false)
            val isEnabled = isAccessibilityServiceEnabled(context)
            prefs.edit().putBoolean("accessibility_enabled", isEnabled).apply()
            
            if (!isEnabled && !hasShownPrompt) {
                showAccessibilityDialog = true
            }
        }

        MaterialTheme(colorScheme = colorScheme) {
            // Surface to apply background color properly for all screens
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colorScheme.background
            ) {
                // Show accessibility prompt dialog if needed
                if (showAccessibilityDialog) {
                    AccessibilityPromptDialog(
                        onDismiss = {
                            val prefs = context.getSharedPreferences("app_prefs", MODE_PRIVATE)
                            prefs.edit().putBoolean("has_shown_accessibility_prompt", true).apply()
                            showAccessibilityDialog = false
                        },
                        onEnable = {
                            val prefs = context.getSharedPreferences("app_prefs", MODE_PRIVATE)
                            prefs.edit().putBoolean("has_shown_accessibility_prompt", true).apply()
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                            showAccessibilityDialog = false
                        }
                    )
                }

                when (currentStep) {

                    0 -> WelcomeScreen(
                        onProceed = {
                            currentStep++
                        },
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = onToggleTheme
                    )

                    1 -> LoginScreen(
                        onRegister = {
                            currentStep++
                        },
                        onLogin = {
                            currentStep += 8
                        }
                    )

                    2 -> Register(
                        onReturn = { currentStep-- },
                        onConfirm = { currentStep++ }
                    )

                    3 -> RegisterScreen(
                        user = user,
                        n = 1,
                        text = "I am willing to share personal details in photos if it helps me connect with others.",
                        onReturn = { currentStep-- },
                        onNext = { currentStep++ }
                    )

                    4 -> RegisterScreen(
                        user = user,
                        n = 2,
                        text = "I often post photos that show who I am or where I am.",
                        onReturn = { currentStep-- },
                        onNext = { currentStep++ }
                    )

                    5 -> RegisterScreen(
                        user = user,
                        n = 3,
                        text = "I remove or block location and other metadata before posting photos.",
                        onReturn = { currentStep-- },
                        onNext = { currentStep++ }
                    )

                    6 -> RegisterScreen(
                        user = user,
                        n = 4,
                        text = "People could misuse personal details from the photos I share.",
                        onReturn = { currentStep-- },
                        onNext = { currentStep++ }
                    )

                    7 -> RegisterScreen(
                        user = user,
                        n = 5,
                        text = "I worry that my photos could be linked to my identity using face recognition or similar tools.",
                        onReturn = { currentStep-- },
                        onNext = {
                            createNewUser(user)
                            currentStep++
                        }
                    )

                    8 -> SuccessfulRegistrationScreen(
                        user = user,
                        onConfirm = { currentStep++ }
                    )

                    9 -> ImageScreen(
                        onSelectImages = {
                            imagePickerLauncher.launch("image/*")
                            currentStep++
                        }
                    )

                    10 -> ConfirmImagesScreen(
                        images = selectedImages,
                        onStartQuestionnaire = { currentStep++ },
                        onReSelect = { imagePickerLauncher.launch("image/*") }
                    )

                    11 -> {
                        QuestionnaireScreen(
                            images = selectedImages,
                            onEvent = viewModel::onEvent,
                            onFinish = { currentStep++ }
                        )
                    }

                    12 -> ThankYouScreen(onBackToInstagram = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://www.instagram.com/create/style/")
                            setPackage("com.instagram.android")
                        }
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        }
                    })
                }
            }
        }
    }

    @Composable
    private fun AccessibilityPromptDialog(
        onDismiss: () -> Unit,
        onEnable: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Enable Message Monitoring") },
            text = {
                Text(
                    "Would you like to enable message monitoring? " +
                            "This will help analyze Instagram messaging patterns. The service will:\n\n" +
                            "• Auto-start when you open Instagram\n" +
                            "• Save contact names when you visit messages\n" +
                            "• Record message timestamps (not content)\n\n" +
                            "All data stays on your device and is anonymized."
                )
            },
            confirmButton = {
                TextButton(onClick = onEnable) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Not Now")
                }
            }
        )
    }

    private fun createNewUser(user: UserHelper) {
        viewModel.createNewUser(user)
    }
    // Add inside PvcAssistant class
private fun checkMonitoringStatus(): String {
    val service = InstagramAccessibilityService.getInstance()
    return if (service != null) {
        "Service running, monitoring DMs: ${service.isMonitoring()}"
    } else {
        "Service not running"
    }
}

    @Composable
    fun WelcomeScreen(
        onProceed: () -> Unit,
        isDarkTheme: Boolean,
        onToggleTheme: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Welcome to Privacy Assistant!\n\n" +
                            "We are working to improve privacy awareness on social media. ",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onProceed,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Proceed", color = MaterialTheme.colorScheme.onPrimary)
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Theme toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isDarkTheme) "Dark Mode" else "Light Mode",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = { onToggleTheme() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                }
            }
        }
    }

    @Composable
    fun LoginScreen(
        onRegister: () -> Unit,
        onLogin: () -> Unit
    ) {
        var userId by remember { mutableStateOf("") }
        var placeholderText by remember { mutableStateOf("Enter your User ID") }
        val existingIds by viewModel.userIDs.collectAsState(initial = emptyList())

        LaunchedEffect(Unit) {
            viewModel.fetchUserIds()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "Login",
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // User ID input (only numbers)
            OutlinedTextField(
                value = userId,
                onValueChange = { input ->
                    // Allow only digits
                    if (input.all { it.isDigit() }) {
                        userId = input
                    }
                },
                label = { Text("User ID") },
                placeholder = { Text(placeholderText) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Register link
            Text(
                text = "Register as a new user",
                color = Color.Blue,
                fontSize = 14.sp,
                modifier = Modifier.clickable {
                    onRegister()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Login button
            Button(
                onClick = {

                    if (userId.isBlank()) {
                        // do nothing
                    } else if (userId.toInt() in existingIds) {
                        viewModel.setUserID(userId.toInt())
                        onLogin()
                    } else {
                        userId = ""
                        placeholderText = "ID does not exist."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Login")
            }
        }
    }

    @Composable
    fun Register(
        onReturn: () -> Unit,
        onConfirm: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "Register new user",
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Register link
            Text(
                text = "In order to register as a new user, please answer a few general questions " +
                        "about your preferences and personal assessments in social media environments.",
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onReturn,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                    modifier = Modifier.weight(1f)
                ) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Back", color = Color.White)
                }

                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)), // purple
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Okay", color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
    }

    @Composable
    fun RegisterScreen(
        user: UserHelper,
        n: Int,
        text: String,
        onReturn: () -> Unit,
        onNext: () -> Unit
    ) {
        var sliderValue by remember { mutableStateOf(2f) }

        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Title
                Text(
                    text = "Register as new user",
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = Color.LightGray,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 32.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Question text
                Text(
                    text = text,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Slider with labels
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 1f..4f,
                        steps = 2,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Strongly \n disagree",
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Strongly\n agree",
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onReturn,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                        modifier = Modifier.weight(1f)
                    ) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Back", color = Color.White)
                    }

                    Button(
                        onClick = {
                            if (n == 1) user.setQuestion1(sliderValue.toInt())
                            else if (n == 2) user.setQuestion2(sliderValue.toInt())
                            else if (n == 3) user.setQuestion3(sliderValue.toInt())
                            else if (n == 4) user.setQuestion4(sliderValue.toInt())
                            else if (n == 5) user.setQuestion5(sliderValue.toInt())
                            else {
                                // TODO: throw error
                            }
                            onNext()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)), // purple
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Next", color = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable
    fun SuccessfulRegistrationScreen(
        user: UserHelper,
        onConfirm: () -> Unit
    ) {

        val loading by viewModel.loading.collectAsState()
        if (loading) {
            // Show a loading screen
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {

                Text(
                    text = "Thank you for your answers! \n\n" +
                            "You have been registered as user \n\n",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = user.getID().toString(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 24.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .border(width = 2.dp, color = Color.Black)
                        .padding(8.dp)
                )
                Text(
                    text = "\n\nin the system. \n\n" +
                            "Please use this identification number whenever using our app.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)), // purple
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Okay", color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                }

            }
        }
    }

    @Composable
    fun ImageScreen(
        onSelectImages: () -> Unit
    ) {
        val userId = viewModel.getUserID()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {

                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Success",
                    tint = Color(0xFF4CAF50), // light green
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "You are now logged in as user $userId.\n\n" +
                            "Please select the image(s) you would normally post on Instagram.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onSelectImages,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Select Images", color = MaterialTheme.colorScheme.onPrimary)
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }


    @Composable
    fun ConfirmImagesScreen(
        images: List<Uri>,
        onStartQuestionnaire: () -> Unit,
        onReSelect: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Friendly prompt
            Text(
                "Are these the images you want to share?",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 20.dp)
                    .statusBarsPadding()
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(images) { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Selected image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(16.dp)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onReSelect,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Choose Again", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }

                Button(
                    onClick = onStartQuestionnaire,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Questionnaire", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }


    @Composable
    fun QuestionnaireScreen(
        onEvent: (ImQuestEvent) -> Unit,
        images: List<Uri>,
        onFinish: () -> Unit = {}
    ) {
        var currentIndex by remember { mutableIntStateOf(0) }
        val currentImage = images.getOrNull(currentIndex)
        val visible = remember { mutableStateOf(true) }

        // Question 1: Instagram audience
        val selectedAudience = remember { mutableStateOf<String?>(null) }

        // Question 2: Privacy level (1,1.5,2,...4)
        var privacyLevel by remember { mutableStateOf(2f) }

        // Question 3: Multi-select comfort audience
        val selectedOptions = remember { mutableStateListOf<String>() }

        val fadeIn = remember { fadeIn(animationSpec = tween(600)) }
        val context = LocalContext.current
        val storeDatabase = { dbHandler.store(context, viewModel) }

        if (currentImage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                AnimatedVisibility(visible = visible.value, enter = fadeIn) {
                    AsyncImage(
                        model = currentImage,
                        contentDescription = "Questionnaire Image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .padding(16.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                RoundedCornerShape(20.dp)
                            )
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Question 1
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "1. Who would you share this picture with?",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                listOf(
                                    "Close Friends",
                                    "All Followers",
                                    "Public"
                                ).forEach { audience ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = selectedAudience.value == audience,
                                            onCheckedChange = {
                                                if (it) selectedAudience.value = audience
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                        Text(
                                            text = audience,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Question 2
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "2. How private is this image?",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    (1..4).forEach { i ->
                                        Text(
                                            text = i.toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                Slider(
                                    value = privacyLevel,
                                    onValueChange = { privacyLevel = (it * 2).toInt() / 2f },
                                    valueRange = 1f..4f,
                                    steps = 5,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }

                    // Question 3
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "3. Whom do you feel comfortable sharing this picture with (other than Insta categories)?",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val options =
                                    listOf("Family", "Close Friends", "Colleagues", "Acquaintances")
                                options.forEach { option ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = selectedOptions.contains(option),
                                            onCheckedChange = { checked ->
                                                if (checked) selectedOptions.add(option) else selectedOptions.remove(
                                                    option
                                                )
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                        Text(option, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }

                val isNextEnabled = selectedAudience.value != null && selectedOptions.isNotEmpty()

                Button(
                    onClick = {
                        viewModel.state.value.imageURI = currentImage.toString()
                        viewModel.state.value.answer1 = selectedAudience.value.toString()
                        viewModel.state.value.answer2 = privacyLevel
                        viewModel.state.value.answer3 = selectedOptions.joinToString(", ")
                        viewModel.state.value.timestamp = dbHandler.createTimestamp().toString()
                        onEvent(ImQuestEvent.SaveImQuest)

                        // Reset for next image
                        selectedAudience.value = null
                        privacyLevel = 2f
                        selectedOptions.clear()

                        if (currentIndex < images.size - 1) {
                            visible.value = false
                            currentIndex++
                            visible.value = true
                        } else {
                            onFinish()
                            storeDatabase()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .navigationBarsPadding(),
                    enabled = isNextEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isNextEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    val btnText = if (currentIndex < images.size - 1) "Next" else "Finish"
                    Text(
                        btnText,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }


    @Composable
    fun ThankYouScreen(onBackToInstagram: () -> Unit) {
        val context = LocalContext.current

        fun openInstagramApp() {
            val intent = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
            if (intent != null) {
                context.startActivity(intent)
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/"))
                context.startActivity(webIntent)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFFEDE7F6), Color(0xFFF3E5F5))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "🎉 Thank You! 🎉",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF8E24AA),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Your input helps make social media safer and more aware of privacy.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { openInstagramApp() },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = "Back to Instagram",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

}
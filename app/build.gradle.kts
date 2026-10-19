import java.io.File
import java.util.Properties
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

fun sanitizeSupabaseBaseUrl(raw: String?): String {
  if (raw.isNullOrBlank()) return ""
  var url = raw.trim().trim('"', '\'')
  while (url.endsWith("/")) {
    url = url.substring(0, url.length - 1)
  }
  if (url.endsWith("/rest/v1")) {
    url = url.substring(0, url.length - "/rest/v1".length)
  } else if (url.endsWith("/auth/v1")) {
    url = url.substring(0, url.length - "/auth/v1".length)
  }
  while (url.endsWith("/")) {
    url = url.substring(0, url.length - 1)
  }
  if (url.contains("localhost") || url.contains("127.0.0.1")) {
    return ""
  }
  return url
}

// Ensure .env exists with clean base URLs before Secrets Gradle Plugin reads it
val rootEnvFile = rootProject.file(".env")
val loadedEnvProps = Properties()

if (rootEnvFile.exists()) {
  try {
    rootEnvFile.reader().use { reader -> loadedEnvProps.load(reader) }
  } catch (_: Exception) {}
}

val devEnvFile = File("/app/.dev.env.json")
if (devEnvFile.exists()) {
  try {
    val text = devEnvFile.readText()
    val regex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
    for (match in regex.findAll(text)) {
      val k = match.groupValues[1]
      val v = match.groupValues[2]
      if (!loadedEnvProps.containsKey(k) || loadedEnvProps.getProperty(k).isNullOrBlank()) {
        loadedEnvProps.setProperty(k, v)
      }
    }
  } catch (_: Exception) {}
}

val resolvedSupabaseUrl = sanitizeSupabaseBaseUrl(
  loadedEnvProps.getProperty("SUPABASE_URL") ?: System.getenv("SUPABASE_URL") ?: ""
)
val resolvedSupabaseKey = (loadedEnvProps.getProperty("SUPABASE_PUBLISHABLE_KEY")
  ?: System.getenv("SUPABASE_PUBLISHABLE_KEY") ?: "").trim().trim('"', '\'')
val resolvedMapsKey = (loadedEnvProps.getProperty("MAPS_API_KEY")
  ?: System.getenv("MAPS_API_KEY") ?: "").trim().trim('"', '\'')
val resolvedGeminiKey = (loadedEnvProps.getProperty("GEMINI_API_KEY")
  ?: System.getenv("GEMINI_API_KEY") ?: "").trim().trim('"', '\'')

// Always synchronize clean values to .env for Secrets Gradle Plugin
try {
  val content = buildString {
    appendLine("GEMINI_API_KEY=$resolvedGeminiKey")
    appendLine("MAPS_API_KEY=$resolvedMapsKey")
    appendLine("SUPABASE_PUBLISHABLE_KEY=$resolvedSupabaseKey")
    appendLine("SUPABASE_URL=$resolvedSupabaseUrl")
  }
  rootEnvFile.writeText(content)
} catch (_: Exception) {}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.womensafety.kypxql"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    manifestPlaceholders["MAPS_API_KEY"] = resolvedMapsKey

    buildConfigField("String", "GEMINI_API_KEY", "\"$resolvedGeminiKey\"")
    buildConfigField("String", "MAPS_API_KEY", "\"$resolvedMapsKey\"")
    buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$resolvedSupabaseKey\"")
    buildConfigField("String", "SUPABASE_URL", "\"$resolvedSupabaseUrl\"")
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(platform(libs.supabase.bom))
  implementation(libs.supabase.auth)
  implementation(libs.supabase.postgrest)
  implementation(libs.supabase.realtime)
  implementation(libs.supabase.storage)
  implementation(libs.bouncycastle.bcprov)
  implementation(libs.bouncycastle.bcpkix)
  // implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Firebase Auth with Google Sign-In requires all of the following to be uncommented together.
  // If you are using Firebase Auth with other providers (e.g. Email/Password), you may only need
  // firebase-auth.
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  implementation(libs.maps.compose)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

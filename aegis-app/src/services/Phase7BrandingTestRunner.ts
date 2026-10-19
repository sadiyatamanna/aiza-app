import * as fs from 'fs';
import * as path from 'path';

let passed = 0;
let failed = 0;

function assert(condition: boolean, testName: string, detail?: string) {
  if (condition) {
    console.log(`[PASS] ${testName}`);
    passed++;
  } else {
    console.error(`[FAIL] ${testName}${detail ? ` - ${detail}` : ''}`);
    failed++;
  }
}

async function runPhase7BrandingTests() {
  console.log('--- Starting AIZA Phase 7 Branding & Splash Regression Tests ---');

  const rootDir = path.resolve(__dirname, '../../..');
  const aegisAppDir = path.resolve(__dirname, '../..');

  // Test 1: Logo asset exists in aegis-app and Android native drawables
  try {
    const aegisLogoPath = path.join(aegisAppDir, 'assets/logo.png');
    const androidLogoPath = path.join(rootDir, 'app/src/main/res/drawable/logo.png');
    const aegisLogoExists = fs.existsSync(aegisLogoPath);
    const androidLogoExists = fs.existsSync(androidLogoPath);

    assert(
      aegisLogoExists && androidLogoExists,
      '1. Logo asset exists in both assets/logo.png and res/drawable/logo.png',
      `aegisLogo: ${aegisLogoExists}, androidLogo: ${androidLogoExists}`
    );
  } catch (err: any) {
    assert(false, '1. Logo asset exists', err.message);
  }

  // Test 2: Android strings.xml contains app_name = AIZA
  try {
    const stringsXmlPath = path.join(rootDir, 'app/src/main/res/values/strings.xml');
    const stringsContent = fs.readFileSync(stringsXmlPath, 'utf-8');
    const hasAizaName = stringsContent.includes('<string name="app_name">AIZA</string>');
    assert(hasAizaName, '2. Android strings.xml defines app_name as AIZA');
  } catch (err: any) {
    assert(false, '2. Android strings.xml check', err.message);
  }

  // Test 3: AndroidManifest.xml specifies app label as AIZA (either direct or via @string/app_name)
  try {
    const manifestPath = path.join(rootDir, 'app/src/main/AndroidManifest.xml');
    const manifestContent = fs.readFileSync(manifestPath, 'utf-8');
    const stringsXmlPath = path.join(rootDir, 'app/src/main/res/values/strings.xml');
    const stringsContent = fs.readFileSync(stringsXmlPath, 'utf-8');
    const hasAizaString = stringsContent.includes('<string name="app_name">AIZA</string>');
    const hasAizaLabel = manifestContent.includes('android:label="AIZA"') || 
      (manifestContent.includes('android:label="@string/app_name"') && hasAizaString);
    assert(hasAizaLabel, '3. AndroidManifest.xml specifies app label as AIZA (via @string/app_name or direct)');
  } catch (err: any) {
    assert(false, '3. AndroidManifest.xml check', err.message);
  }

  // Test 4: Android 12+ Splash Theme is configured
  try {
    const v31ThemesPath = path.join(rootDir, 'app/src/main/res/values-v31/themes.xml');
    const exists = fs.existsSync(v31ThemesPath);
    let hasSplashConfig = false;
    if (exists) {
      const content = fs.readFileSync(v31ThemesPath, 'utf-8');
      hasSplashConfig = content.includes('android:windowSplashScreenAnimatedIcon') && content.includes('@drawable/logo');
    }
    assert(exists && hasSplashConfig, '4. Android 12+ values-v31/themes.xml splash screen config exists and points to @drawable/logo');
  } catch (err: any) {
    assert(false, '4. Android 12+ splash theme check', err.message);
  }

  // Test 5: Native Compose SplashScreen.kt renders AIZA and no old branding (protected /app)
  try {
    const nativeSplashPath = path.join(rootDir, 'app/src/main/java/com/example/ui/splash/SplashScreen.kt');
    const content = fs.readFileSync(nativeSplashPath, 'utf-8');
    const hasAiza = content.includes('"AIZA"');
    const noOldTagline = !content.includes('Quantum-Safe Women Safety & Emergency Communication System');
    const noOldAppTitle = !content.includes('text = "Aegis"');

    assert(
      hasAiza && noOldTagline && noOldAppTitle,
      '5. Native Compose SplashScreen preserves protected Android project with AIZA branding and no old branding'
    );
  } catch (err: any) {
    assert(false, '5. Native Compose SplashScreen check', err.message);
  }

  // Test 6: React Native SplashScreen.tsx renders AIZA, Developed by Tam, and logo
  try {
    const rnSplashPath = path.join(aegisAppDir, 'src/screens/SplashScreen.tsx');
    const content = fs.readFileSync(rnSplashPath, 'utf-8');
    const hasAiza = content.includes('AIZA');
    const hasTam = content.includes('Developed by Tam');
    const hasLogo = content.includes('logo.png');
    const noOldTagline = !content.includes('Quantum-Safe Women Safety & Emergency Communication System');

    assert(
      hasAiza && hasTam && hasLogo && noOldTagline,
      '6. React Native SplashScreen displays AIZA, Developed by Tam, and references logo.png'
    );
  } catch (err: any) {
    assert(false, '6. React Native SplashScreen check', err.message);
  }

  // Test 7: Verify absence of deprecated branding phrases across UI screens
  try {
    const forbiddenPhrases = [
      'Quantum Safe Safety App',
      'Quantum-Safe Women Safety & Emergency Communication System',
      'Aegis Women & Personal Safety',
      'AEGIS Women & Personal Safety'
    ];

    const screenFiles = [
      path.join(rootDir, 'app/src/main/java/com/example/ui/splash/SplashScreen.kt'),
      path.join(rootDir, 'app/src/main/java/com/example/ui/onboarding/OnboardingScreen.kt'),
      path.join(rootDir, 'app/src/main/java/com/example/ui/home/HomeScreen.kt'),
      path.join(rootDir, 'app/src/main/java/com/example/ui/contacts/ContactsScreen.kt'),
      path.join(rootDir, 'app/src/main/java/com/example/ui/profile/ProfileScreen.kt'),
      path.join(rootDir, 'app/src/main/java/com/example/ui/maps/MapsScreen.kt'),
      path.join(rootDir, 'app/src/main/java/com/example/ui/auth/AuthScreen.kt'),
      path.join(aegisAppDir, 'src/screens/SplashScreen.tsx'),
      path.join(aegisAppDir, 'src/screens/HomeScreen.tsx'),
      path.join(aegisAppDir, 'src/screens/ProfileScreen.tsx')
    ];

    let foundOld = false;
    for (const file of screenFiles) {
      if (fs.existsSync(file)) {
        const text = fs.readFileSync(file, 'utf-8');
        for (const phrase of forbiddenPhrases) {
          if (text.includes(phrase)) {
            console.error(`Found forbidden phrase "${phrase}" in ${path.basename(file)}`);
            foundOld = true;
          }
        }
      }
    }

    assert(!foundOld, '7. No forbidden old branding phrases found in UI screens');
  } catch (err: any) {
    assert(false, '7. Forbidden old branding phrases check', err.message);
  }

  console.log(`\nPhase 7 Branding Tests Completed: ${passed} Passed, ${failed} Failed`);
  if (failed > 0) {
    process.exit(1);
  }
}

runPhase7BrandingTests().catch(err => {
  console.error('Fatal error running branding tests:', err);
  process.exit(1);
});

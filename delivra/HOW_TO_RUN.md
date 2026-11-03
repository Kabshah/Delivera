# How to Run Delivra from your Terminal

This is a native Android application built with Kotlin, Jetpack Compose, and Node.js. 

Since you are not building a web app, you do **not** run `npm start` to see it in a browser. Instead, you need to compile it into an Android app (APK) and install it on an Android phone or an Android Emulator.

Here are the step-by-step instructions.

## Method 1: The Easiest Way (Android Studio)
By far the easiest way to view and run this project:
1. Download and install **Android Studio**.
2. Open Android Studio → Click **Open** → Select the `Delivra/delivra` folder.
3. Let it sync for a minute (it will download Gradle and all dependencies).
4. Connect your Android phone with a USB cable (with "USB Debugging" turned on in Developer Options) OR create a Virtual Device (Emulator) inside Android Studio.
5. Click the green **▶ Play** button at the top to build, install, and open the app!

---

## Method 2: Running from the Terminal (Command Line)

If you strictly want to use your terminal to build and run the app, follow these steps:

### Prerequisites:
- You must have the **Java Development Kit (JDK 17)** installed.
- You must have the **Android SDK** installed.
- An Android phone connected to your PC with **USB Debugging enabled**, OR an **Android Emulator** running.

### Step-by-Step:

1. **Open your terminal (PowerShell / Command Prompt)** and navigate to the project directory:
   ```bash
   cd e:\Delivra\delivra
   ```

2. **Clean and Build the Debug APK:**
   This command will download the build tools and compile your code.
   ```bash
   .\gradlew assembleDebug
   ```
   *(If you are using Git Bash or WSL, use `./gradlew assembleDebug` instead).*

3. **Install the App on your Phone/Emulator:**
   If your phone is plugged in, or your emulator is open, run this to install the app directly:
   ```bash
   .\gradlew installDebug
   ```

4. **Launch the App:**
   Once installed, you can simply find **Delivra** in your phone's app drawer and open it!

### Where is the actual `.apk` file?
If you successfully ran `.\gradlew assembleDebug`, the generated APK file you can share or install manually is located here:
`e:\Delivra\delivra\app\build\outputs\apk\debug\app-debug.apk`

---

## What about the Node.js code?
The Node.js code inside `assets/nodejs-project` does **not** normally run on your computer. When Gradle builds the APK, it bundles that Node.js code *inside* the Android app so it can run directly on the phone via the `nodejs-mobile-android` library!

*(Note: We already ran `npm install` inside that folder, so it is fully ready to be packaged into the app).*

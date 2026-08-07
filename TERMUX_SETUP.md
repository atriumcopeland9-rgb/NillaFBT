# Termux Build Environment Setup

Run these once, in Termux (not proot-distro — plain Termux works fine for Gradle+SDK).

## 1. Base packages
```bash
pkg update -y && pkg upgrade -y
pkg install -y git wget unzip openjdk-17 gradle
termux-setup-storage
```
Verify Java:
```bash
javac -version   # should show 17.x
```

## 2. Android command-line SDK
```bash
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mv cmdline-tools latest
rm commandlinetools-linux-11076708_latest.zip
```
> If that exact filename 404s, grab the current link from
> https://developer.android.com/studio#command-line-tools-only — same steps.

Add to `~/.bashrc`:
```bash
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```
```bash
source ~/.bashrc
```

## 3. Install SDK platform + build tools
```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## 4. Get this project onto the device
Copy the `vrchat-fbt/` folder into Termux (e.g. via `termux-setup-storage` + copy from Downloads, or `git clone` if you push it to a repo you control).

## 5. Download the MediaPipe pose model
The `.task` model file is a few MB and isn't bundled here (binary, and changes version to version). Grab it directly in Termux:
```bash
cd vrchat-fbt/app/src/main/assets
mkdir -p .
wget -O pose_landmarker_full.task https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task
```
(There's also a lighter `pose_landmarker_lite.task` at the same path with `_lite` — swap it in if the `full` model is too slow on your phone; edit the filename in `PoseProcessor.kt` to match.)

## 6. Build the APK
```bash
cd ~/vrchat-fbt
gradle wrapper --gradle-version 8.7   # generates gradlew, only needed once
./gradlew assembleDebug
```
The APK lands at:
```
app/build/outputs/apk/debug/app-debug.apk
```
Install it:
```bash
termux-setup-storage
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/
```
Then install it from your file manager (allow "install unknown apps" for the file manager/Termux).

## Notes / gotchas
- First `./gradlew` run will download Gradle dependencies — needs real internet, will take a while on phone data.
- If you hit `INSTALL_FAILED_NO_MATCHING_ABIS`, your device is unusual (very rare); check `app/build.gradle`'s `ndk.abiFilters`.
- Camera permission + a decent front camera view of your body (mirror, tripod, whatever) is required at runtime.

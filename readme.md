# OpenRing (UbiComp 25' Companion)
Android application for smart ring monitoring and data collection. If you just want to use the application for ring monitoring (Android devices), you can simply download the APK directly from releases.

If you want to modify the code or build the application from source, below are the requirements to build the project.

## Features

### Real-time Monitoring
- **PPG Data**: Green, IR, and Red light measurements
- **3-Axis Accelerometer**: Motion tracking and analysis
- **3-Axis Gyroscope**: Rotation and orientation data
- **Temperature Sensors**: Multi-point temperature monitoring

### Data Management
- **File Download**: Retrieve stored data from ring device```


### Android SDK
The project requires:
- **Minimum SDK**: Android 7.0 (API level 24)
- **Target SDK**: Android 14 (API level 34)
- **Compile SDK**: Android 14 (API level 34)

### Dependencies
The following libraries are used in the OpenRing project and will be automatically downloaded when building:

#### Bluetooth and Connectivity
- `ChipletRing1.0.81.aar` - SDK for device communication
- Custom BLE utilities for ring connectivity

#### Data Processing and Visualization
- Custom PlotView for real-time data visualization
- Ring data parsing utilities
- Notification handling system

#### File Management
- Built-in file download and management system
- CSV/binary data export capabilities

### Hardware Requirements
- Android device with Bluetooth Low Energy (BLE) support
- Minimum 4GB RAM recommended
- At least 100MB free storage space

## Project Structure
```
OpenRing/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/tsinghua/sample/
│   │   │   │   ├── activity/
│   │   │   │   │   ├── MainActivity.java
│   │   │   │   │   └── RingSettingsActivity.java
│   │   │   │   ├── utils/
│   │   │   │   │   └── NotificationHandler.java
│   │   │   │   ├── PlotView.java
│   │   │   │   └── RingViewHolder.java
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   ├── values/
│   │   │   │   └── drawable/
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   ├── build.gradle
│   └── proguard-rules.pro
├── gradle/
├── build.gradle
└── README.md
```

## Setup Instructions for Windows

### Requirements for Android Studio
1. **Install Android Studio**: Download and install from [Android Studio](https://developer.android.com/studio)
2. **Install JDK**: Java Development Kit 11 or later
3. **Configure SDK**: During setup, ensure the following components are installed:
    - Android SDK Platform-Tools
    - Android SDK Build-Tools
    - Android Emulator
    - Intel x86 Emulator Accelerator (HAXM installer)

### Additional Components
In Android Studio SDK Manager, install:
- **SDK Platforms**: Android 14 (API level 34)
- **SDK Tools**:
    - Android SDK Build-Tools
    - CMake
    - NDK (Side by side)
    - Android Emulator
    - Android SDK Platform-Tools

## Setup Instructions for macOS

### Requirements for Xcode and Android Studio
1. **Install Xcode**: Available from Mac App Store (required for development tools)
2. **Install Android Studio**: Download from the official website
3. **Configure SDK**: Same as Windows requirements

### Apple Silicon Notes
- Android Emulator supports ARM64 images on Apple Silicon Macs
- Use ARM64 system images for better performance on M1/M2 Macs

## Setup Instructions for Linux

### Requirements for Linux Development
1. **Install Java JDK**:
   ```bash
   sudo apt update
   sudo apt install openjdk-11-jdk
   ```

2. **Install Android Studio**:
   ```bash
   tar -xzf android-studio-*-linux.tar.gz
   cd android-studio/bin
   ./studio.sh
   ```

3. **Install additional dependencies**:
   ```bash
   sudo apt-get install -y libc6:i386 libncurses5:i386 libstdc++6:i386 lib32z1 libbz2-1.0:i386
   ```

## Building the Project

### Clone the Repository
```bash
git clone https://github.com/thuhci/OpenRing.git
cd OpenRing
```

### Build with Android Studio
1. Open Android Studio
2. Select "Open an existing Android Studio project"
3. Navigate to the cloned OpenRing directory
4. Wait for Gradle sync to complete
5. Build → Make Project (Ctrl+F9 / Cmd+F9)

### Build with Command Line
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```



## Known Issues and Solutions

### Common Build Errors
1. **SDK Version Mismatch**: Update target and compile SDK versions in `build.gradle`
2. **Permission Errors**: Ensure all required permissions are granted
3. **BLE Connection Issues**: Check device Bluetooth and location services

### Troubleshooting
- **Data Not Downloading**: Check storage permissions and available space
- **Connection Drops**: Verify device is within BLE range (< 10 meters)

## License
MIT License - see [LICENSE](LICENSE) file for details

## Support
For questions and support:
- Create an issue on [GitHub Issues](https://github.com/thuhci/OpenRing/issues)

## Releases
Current stable version: **v1.1.0**
- Download the latest APK from [Releases](https://github.com/thuhci/OpenRing/releases)

---

**Project Statistics:**
- Language: Java (85%), XML (15%)
- Minimum Android Version: 7.0 (API 24)
- License: MIT
- Last Updated: August 2025


## 📄 Citation
Title: [τ-Ring: A Smart Ring Platform for Multimodal Physiological and Behavioral Sensing](https://arxiv.org/abs/2508.00778)  
Jiankai Tang, Zhe He, Mingyu Zhang, Wei Geng, Chengchi Zhou, Weinan Shi, Yuanchun Shi, Yuntao Wang, "τ-Ring: A Smart Ring Platform for Multimodal Physiological and Behavioral Sensing", UbiComp Companion, 2025  
```
@article{tang2025ring,
      title={{\tau}-Ring: A Smart Ring Platform for Multimodal Physiological and Behavioral Sensing}, 
      author={Jiankai Tang and Zhe He and Mingyu Zhang and Wei Geng and Chengchi Zhou and Weinan Shi and Yuanchun Shi and Yuntao Wang},
      year={2025},
      eprint={2508.00778},
      archivePrefix={arXiv},
      primaryClass={cs.CE},
      url={https://arxiv.org/abs/2508.00778}, 
}
```

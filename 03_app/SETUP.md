# 개발 환경 (이 Mac 기준, 2026-11-09 구성 완료)

Homebrew가 없어 사용자 폴더에 설치함:
- JDK 17: `~/dev-tools/jdk-17.0.21+9/Contents/Home`
- Android SDK (platform 35, build-tools 35): `~/dev-tools/android-sdk`

```bash
export JAVA_HOME=$(ls -d ~/dev-tools/jdk-17*/Contents/Home) ANDROID_HOME=~/dev-tools/android-sdk
./gradlew testDebugUnitTest assembleRelease   # APK: app/build/outputs/apk/release/app-release.apk
```

## 남은 것: Pleos Connect 에뮬레이터 (실차 시뮬레이션·시연용)
이미지 4.7GB + 42dot 라이선스 동의가 필요해서 직접 설치해야 함.
1. Android Studio 설치 (https://developer.android.com/studio) → SDK 위치를 `~/dev-tools/android-sdk`로 지정
2. Settings → SDK Manager → SDK Update Sites → `+`
   - Name: `Pleos Connect System Image`
   - URL: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
3. SDK Platforms → API 34 → Pleos Connect system image 체크 (Show Package Details) → 설치
4. Device Manager → Create → Automotive → `Pleos Connect v2.1.0` → Cold boot, RAM 4GB
5. 마이크 접근 허용: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator
6. Virtual Console에서 기어(P/D)·시트·창문 신호를 바꿔가며 확인

## 실차/에뮬레이터에서 확인할 것
- [ ] 시트 모터 방향 (`CarController.moveSeat` 주석) — 특히 높이·허리
- [ ] `MS_PER_PERCENT` 보정 (시트 1% 이동 시간)
- [ ] 창문 Capability 범위에서 min=닫힘이 맞는지
- [ ] 열선/통풍 레벨 범위 (현재 0~3 가정)

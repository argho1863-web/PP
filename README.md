# Phone Agent - voice/text AI phone assistant (Kotlin, Compose, Material 3)

## Build & run
1. Install Android Studio (Ladybug 2024.2+ / JDK 17). Use **File > Open** on this folder; let Gradle sync
   (if there is no `gradlew`, Android Studio uses `gradle/wrapper/gradle-wrapper.properties`;
   alternatively run `gradle wrapper --gradle-version 8.9` once).
2. Connect a physical Android 11+ device (USB debugging on) and press **Run**.
3. Open the app, grant **Microphone** (and Notifications on Android 13+).
4. **Settings > API Setup**: pick a preset, paste your API key, choose a model, tap **Test Connection**, then **Save**.
5. Enable the Accessibility service: tap "Open Accessibility settings" > *Phone Agent* > turn on > confirm.
   - Android 13+ sideloaded apps: if the toggle is greyed out ("Restricted setting"), go to
     **Settings > Apps > Phone Agent > ⋮ > Allow restricted settings**, then retry.
6. Grant **Display over other apps** (button on the main screen), then switch on **Floating bubble**.
7. Type or speak a task, e.g. "Open Chrome and search for the weather in Dhaka".

## Notes
- Sensitive taps (send / pay / delete / security settings, or anything the model flags with
  `requires_confirmation`) pause for Approve/Deny - in the app and on the bubble.
- STOP (app button or bubble) cancels the loop, the in-flight HTTP request and any dialog.
- Everything on screen (and screenshots when Vision is on) is sent to the provider you configure.
  Password fields are not special-cased: don't run the agent on screens showing secrets.
- Google Play restricts AccessibilityService use to accessibility tools; this is intended for sideloading/personal use.
- Tunables: `AgentOrchestrator.sensitiveWords`, `AGENT_SYSTEM_PROMPT`, `UiTreeSerializer.MAX_NODES`.
- Release builds are minified (`proguard-rules.pro`) and signed with the debug key by default - set your own keystore.

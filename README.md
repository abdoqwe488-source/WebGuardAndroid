# WebGuard Android
Arabic web protection app for Android 8+.

Build:
gradle --no-daemon assembleDebug

The app uses Android VpnService for DNS filtering and an AccessibilityService for clearing blocked URL text. Device Owner is optional and is required for system-enforced Always-on VPN/uninstall blocking.

# Fast Download — standalone Android app

- Android 7.0+ (minSdk 24).
- Embedded browser and Android DownloadManager for direct file links and downloads initiated by browser links.
- Rejects navigation and downloads for a basic built-in set of adult domains.
- Dark/light UI toggle; downloads appear in Android Downloads and system notifications.

## Limitations
This is not a universal extractor for every streaming site. Websites using DRM, HLS/DASH streaming, authentication flows, or download restrictions may not expose a downloadable file URL. YouTube extraction, playlist parsing, batch downloads, reliable file-size prediction, and system-wide adult-site blocking are not implemented. The built-in domain list is not exhaustive. Blocking applies only inside this app.

## Build
Run the GitHub Actions workflow named Build Fast Download APK on branch fast-download-standalone. It builds FastDownload/app/build/outputs/apk/debug/app-debug.apk.

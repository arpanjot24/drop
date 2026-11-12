# Drop

Minimal Android app for moving files and text between a phone and a computer on the same Wi-Fi.
The phone runs a tiny web server; the computer just opens the address shown in the app.

- Computer → phone: drag files onto the page (saved to `Downloads/Drop`), or send text (copied to clipboard).
- Phone → computer: "Send files" / "Send clipboard" in the app, or share to Drop from any app.
- Received files and text show Open / Share / Copy buttons in the app, so they can be forwarded without leaving it.
- New computers must be approved once on the phone.
- The app updates itself from this repository's GitHub Releases.

## Releasing an update

Push to `main`. The workflow in `.github/workflows/release.yml` builds, signs and publishes `Drop.apk`
as release `v<number>`; phones pick it up the next time Drop is opened.

One-time setup: add a repository secret `KEYSTORE_B64` with the output of `base64 -w0 drop.keystore`,
and keep the repository public (the app downloads releases without signing in).

## Build

No Gradle or Android SDK needed.

    sudo apt install aapt apksigner zipalign dalvik-exchange
    curl -o android.jar https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
    ./build.sh

`build.sh` creates a signing key (`drop.keystore`, not committed) on first run. A build signed with a
different key cannot update an installed copy; uninstall first.

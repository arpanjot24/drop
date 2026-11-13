# Drop

Minimal Android app for moving files and text between a phone and a computer on the same Wi-Fi.
The phone runs a tiny web server; the computer just opens the address shown in the app.

- Computer → phone: drag files onto the page (saved to `Downloads/Drop`), or send text (copied to clipboard).
- Phone → computer: "Send files" / "Send clipboard" in the app, or share to Drop from any app.
- Received files and text show Open / Share / Copy buttons in the app, so they can be forwarded without leaving it.
- New computers must be approved once on the phone.
- The app updates itself from this repository's GitHub Releases.

## Windows portal (optional)

`pc/DropPortal.cs` is a small tray app. It probes the network for a phone running Drop and, while one
is open, shows a drop target on the right edge of the screen. Drag files or text onto it to send them;
click it to open the full page. It adds itself to Windows startup on first run (toggle in the tray menu).

Build it on Windows with the C# compiler that ships with .NET Framework (an exe built with Mono's `mcs`
crashes on Windows .NET):

    C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /codepage:65001 /target:winexe /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Web.Extensions.dll /r:Microsoft.VisualBasic.dll /out:pc\DropPortal.exe pc\DropPortal.cs pc\BuildInfo.cs

Files and text you send from the phone ("Send files" / "Send clipboard") appear as rows under the drop
target; click a file to save it and open it, or click text to copy it. Right-click a row to ignore it
(hide it on this PC) or delete it (PC copy to the Recycle Bin, and removed from Drop's list on the phone;
the original file on the phone is never touched). Files are saved to `Downloads\Drop` by default; change
it with "Save received files to…" in the tray menu.

It finds the phone by broadcast, and also probes each address on the local network directly, since many
routers drop broadcasts.

## Releasing an update

Push to `main`. The workflow in `.github/workflows/release.yml` publishes release `v<number>` with only
the parts that changed since the last release:

- `Drop.apk` when the Android app changed: phones offer the update the next time Drop is opened.
- `DropPortal.exe` when anything in `pc/` changed: the portal offers to update itself (it checks shortly
  after starting and every 6 hours, or use "Check for updates" in the tray menu).

A push that changes neither publishes nothing. Run the workflow by hand with "force" to publish both.

One-time setup: add a repository secret `KEYSTORE_B64` with the output of `base64 -w0 drop.keystore`,
and keep the repository public (the app downloads releases without signing in).

## Build

No Gradle or Android SDK needed.

    sudo apt install aapt apksigner zipalign dalvik-exchange
    curl -o android.jar https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
    ./build.sh

`build.sh` creates a signing key (`drop.keystore`, not committed) on first run. A build signed with a
different key cannot update an installed copy; uninstall first.

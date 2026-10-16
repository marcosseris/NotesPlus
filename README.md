# NotesPlus

Annotate your lecture PDFs with a stylus, straight from **OneDrive or Google Drive**.
Browse your drive, tap a PDF, write on it; the annotations sync back automatically and
show up on your computer too.

* **Drive browser** – sign in to OneDrive and/or Google Drive, browse folders (cached, so it
  is instant and works offline), open any PDF. Google Drive shows My Drive, Shared with me
  and Shared drives.
* **Stylus-first inking** – pressure-sensitive pen, highlighter (multiply blend, auto-straight
  bars), stroke eraser, undo/redo, palm rejection, side-button erase, finger pan/zoom,
  platform motion prediction so ink keeps up with the pen tip.
* **Colours** – 16 pen inks and 10 highlighter colours on the rail, plus a full colour picker
  with recent colours.
* **Shapes** – line, arrow, rectangle, ellipse, triangle tools, and *hold-to-snap*: pause the pen
  at the end of a rough stroke and it becomes a clean shape.
* **Annotations you can always edit** – strokes live in a small `<name>.pdf.ink.json` next to
  the PDF. Reopen any annotated PDF later, erase individual strokes or everything, add more.
  Two devices editing offline merge cleanly instead of overwriting each other.
* **Visible everywhere** – after you stop editing, the PDF on your drive is rewritten with your
  ink on a separate, marked layer. Any viewer shows it; Acrobat lets you hide the layer. The
  original pages stay inside the file untouched, so NotesPlus can always recover the pristine
  PDF (and you can switch to a sibling `*.annotated.pdf` or turn write-back off in Settings).
* **Tablet UI** – floating tool rail, page navigator and keyboard shortcuts on 600 dp+ screens
  (the Honor Pad 9 in both orientations); bottom bar on phones. 120 Hz where available.
* **Self-updating** – every push to the default branch publishes a signed build to GitHub
  Releases; the app installs new builds by itself.

## Building

Requirements: JDK 17+, Android SDK 35 (the Gradle wrapper fetches everything else).

```bash
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # merge / geometry / shape / undo unit tests
```

## Connecting drives

Both providers use standard OAuth with PKCE from a Custom Tab. Neither needs a secret, but
each needs an app registration (a *client ID*) that you create once, for free.

### OneDrive (personal or university account)

You can register the app under a **personal** Microsoft account even if you will sign in with
a university account; the registration only has to be multi-tenant.

1. Sign in to <https://portal.azure.com> with a personal Microsoft account → **App registrations** → **New registration**.
2. Name `NotesPlus`. Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**.
3. Redirect URI: platform **Public client/native (mobile & desktop)**, value `notesplus://auth`.
4. Register, copy the **Application (client) ID**.
5. **Authentication** → *Allow public client flows* → **Yes** → Save.
6. In the app: **Settings → OneDrive client ID** → paste → Save → **Sign in** with the account that holds your notes.

If a university tenant answers "Need admin approval", ask IT to grant admin consent for your
client ID (delegated `Files.ReadWrite` + `User.Read`), or use the request-approval form shown
on that page.

### Google Drive

1. <https://console.cloud.google.com> → create a project (any name).
2. **APIs & Services → Library** → enable **Google Drive API**.
3. **OAuth consent screen** → External → fill in the app name and your email → add the scope
   `https://www.googleapis.com/auth/drive` → add your Google account under **Test users**.
4. **Credentials → Create credentials → OAuth client ID** → Application type **Android** →
   package name `com.notesplus.app`, SHA-1 of your signing key (see below; for the debug build
   use `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`).
5. Copy the **Client ID** into **Settings → Google Drive client ID** → Save → **Sign in**.

The redirect used is `com.notesplus.app:/oauth2redirect`, which Google accepts for Android
clients without further configuration. While the consent screen is in "Testing", Google shows
an "unverified app" warning once; continue past it.

## How sync works

```
OneDrive or Google Drive/…/Week 1/
  Lecture 3.pdf            ← rewritten with your ink on a marked layer (original pages intact)
  Lecture 3.pdf.ink.json   ← your strokes (page points, per page); the source of truth
```

* Opening a PDF downloads it, strips any NotesPlus layer so the local copy is pristine, and
  merges a remote `.ink.json` if one exists.
* Each stroke saves locally at once; the sidecar uploads a few seconds after you stop writing.
  Uploads are conditional (OneDrive `If-Match`, Drive version check) and a conflict is resolved by
  merging, never by overwriting.
* About 45 s after you stop editing, or when you leave the note, the annotated PDF is rebuilt and
  uploaded in place (or as `*.annotated.pdf`, or not at all; Settings → Sync).
* Recently opened notes are re-checked on app start and every 15 minutes in the background.
* **Remove from device** deletes only the local copy; the drive keeps everything.

## Automatic updates

Android requires every update to be signed with the same key, so the pipeline needs a
signing key stored in GitHub Secrets. One-time setup:

```bash
keytool -genkeypair -v -keystore notesplus-release.jks -alias notesplus \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass CHOOSE_A_PASSWORD -keypass CHOOSE_A_PASSWORD \
  -dname "CN=NotesPlus"
base64 -w0 notesplus-release.jks > keystore.b64      # macOS: base64 -i notesplus-release.jks -o keystore.b64
keytool -list -v -keystore notesplus-release.jks -alias notesplus -storepass CHOOSE_A_PASSWORD | grep SHA1
```

In the GitHub repository → **Settings → Secrets and variables → Actions**, add:

| Secret | Value |
|---|---|
| `NOTESPLUS_KEYSTORE_BASE64` | contents of `keystore.b64` |
| `NOTESPLUS_KEYSTORE_PASSWORD` | the password you chose |
| `NOTESPLUS_KEY_ALIAS` | `notesplus` |
| `NOTESPLUS_KEY_PASSWORD` | the password you chose |
| `NOTESPLUS_CLIENT_ID` | (optional) OneDrive client ID baked into builds |
| `NOTESPLUS_GOOGLE_CLIENT_ID` | (optional) Google client ID baked into builds |

Keep `notesplus-release.jks` somewhere safe; losing it means a fresh install for the next
version. The SHA-1 printed above is the one to register in the Google OAuth client.

Every push to the default branch then runs `.github/workflows/release.yml`, which tests,
builds a signed APK versioned `1.0.<run number>` and publishes it as a GitHub Release.

On the tablet:

1. Install the first release APK from the Releases page once (uninstall the debug build first).
2. Because the repository is private, create a fine-grained personal access token with
   read-only **Contents** permission on this repository and paste it in **Settings → Updates**.
3. Leave **Update automatically** on. New builds are checked for when the app opens; after the
   first self-install Android 12+ installs later ones without any prompt.

## Project layout

```
app/src/main/java/com/notesplus/app/
  cloud/      CloudProvider (OneDrive + Google Drive), CloudRepository (folder cache), SyncEngine, auth (PKCE)
  ink/        InkModel (format + merge), InkGeometry, Shapes (shape tool + recogniser)
  editor/     DocumentView (stylus input, zoom/scroll, drawing), PageSource, PageRenderer, UndoManager
  export/     PdfExporter (reversible annotation layer via PdfBox-Android)
  update/     UpdateManager (GitHub Releases self-update)
  data/       Room database, NoteRepository, Prefs
  ui/         Compose screens: home (browse + recent), editor, settings
```

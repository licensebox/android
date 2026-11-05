# licensebox for Android

Manage your [licensebox](https://licensebox.dev) products from your phone: answer
access requests, grant and take back modules, revoke users and machines, and see
who uses what. It does the day-to-day work of the `licensebox` admin command
(in [licensebox/go](https://github.com/licensebox/go)) and the admin console, through the
same admin API.

It works on any phone with Android 8 or later, including phones with a hardware
keyboard and a square screen: every action has a key, and no arrow keys are needed.

## Install

Download `licensebox-android-<version>.apk` from the
[latest release](https://github.com/licensebox/android/releases/latest) on the phone
and open it (Android asks once to allow installs from your browser or file manager).
Later releases install over it.

Every release is signed with the same key. Its certificate's SHA-256 is

```
DE:BD:35:41:FF:14:11:A3:EA:37:03:F1:89:F9:B7:19:5A:F1:42:B0:5F:12:43:52:BD:19:F3:DE:93:5F:25:62
```

which `apksigner verify --print-certs licensebox-android-<version>.apk` shows.

## What it does

A product opens on one page of cards: requests waiting (answer them right there),
figures, the newest users, recent activity, the modules and how many hold each,
usage over 30 days, settings, admins and signing keys. Each card leads on to the
whole list.

- **Requests**: those waiting come first. Approve all the modules asked for or some
  of them, for good or for 30 days, 90 days or a year, with a note to the user; or decline.
- **Notifications** when someone asks for modules, checked every 15 minutes (Android's
  shortest interval) while online. Tapping one opens that product's requests. Turn
  them off from the ⋯ menu.
- **Users**: search by email or purpose, sort by newest, last seen or name, see each user's grants and machines, grant
  modules (to anyone, even before they sign in), take grants back, revoke or restore a
  user or a single machine.
- **Activity**: a product's latest 200 events, by day, filtered to requests, access,
  sign-ins or admin work.
- **Admins**: add and remove them. **Signing keys**: tap one to copy its public key.

Creating products, changing their settings, adding or retiring signing keys,
deleting products and the operators' allow list stay with the console and the
`licensebox` command.

## Signing in, and the lock

Sign in as with `licensebox login`: your email address, then the 6-digit code
the server mails you. The session lasts 30 days. Its token is encrypted with a key
held by the Android Keystore and is left out of backups.

The app always opens behind your fingerprint or screen lock, and asks again after
five minutes in the background. This can't be turned off; a phone with no screen
lock is asked to set one first.

To use another server than licensebox.dev, choose **Another server** when signing in.

## Keyboard

Every action has a key, for phones with a built-in keyboard or one plugged in. **H** or
**?** shows them on any screen.

| Where | Keys |
| --- | --- |
| Everywhere | **J/K** or **N/P** next and previous, **Space** and **Shift+Space** page down and up, **T** and **B** top and bottom, **Enter** opens, **Esc** goes back |
| Products | **1–9** open a product, **R** reloads |
| A product | **R U E** open Requests, Users, Activity, **A**/**D** approve or decline the first waiting request, **S** search users, **G** grant, **Enter** opens the selected card |
| Requests | **A**/**D** approve or decline the selected (or first waiting) request, **Enter** opens its user, **R** reloads |
| Users | **S** or **/** search, **O** changes the order, **G** grant, **Enter** opens a user, **R** reloads |
| Activity | **F** changes the filter, **R** reloads |
| A user | **G** grant, **X** revoke or restore, **Enter** on a grant takes it back, on a machine revokes or restores it |
| Dialogs | **Enter** confirms, **Esc** cancels, **1–9** tick modules |

On keyboards where the digits share letter keys (W E R, S D F, Z X C, with Alt),
those letters give digits without Alt while you type a sign-in code. Key hints
only show when a keyboard is attached.

## Building

You need JDK 17 or later and the Android SDK, with platform 37 installed.

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

To put build output elsewhere (on a roomier disk, say), set `buildRoot=/some/dir` in
`local.properties`.

A release build is signed if `keystore.properties` exists beside this README
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Keep it, and the keystore,
out of the repository: both are in `.gitignore`.

### Against a local server

Debug builds also allow plain HTTP to `localhost` and `10.0.2.2`. Release builds
allow HTTPS only. Run the server's dev server, which prints sign-in codes instead
of mailing them, and forward its port to the phone or emulator:

```sh
go run ./cmd/devserver -operator you@example.org   # in licensebox/server
adb reverse tcp:8787 tcp:8787
```

Then sign in with **Another server**, `http://localhost:8787`.

## Licence

MIT. IBM Plex Sans and IBM Plex Mono are under the SIL Open Font License; see
[licenses](licenses).

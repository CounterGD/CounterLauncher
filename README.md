# CounterLauncher 0.2.1

Java Swing + Gradle Minecraft launcher foundation, intended for Linux/Termux/PRoot and currently tested conceptually against the Java 25 AWT runtime in the user's VNC environment. Source is restricted to Java 21 APIs/bytecode (`options.release = 21`) while Gradle uses a Java 25 toolchain so the GUI starts in the current setup.

## Implemented

- Official Mojang version manifest and per-version metadata retrieval.
- Client JAR, library artifact, native classifier, asset-index and asset-object downloads.
- SHA-1 and declared-size verification when metadata supplies those values; cached files are rechecked before reuse.
- Platform/rule filtering for Mojang library metadata; native archive extraction with path traversal protection.
- Ely.by OAuth 2.0 authorization-code flow via browser, loopback callback, state validation, token exchange and profile lookup.
- OAuth refresh-token helper.
- Encrypted local account vault using AES-GCM with a key derived from a user passphrase (PBKDF2-HMAC-SHA256); passphrase is never stored.
- Owner-only file permissions where POSIX permissions are supported.
- Modern Minecraft argument substitution and game process launch attempt.
- Settings persistence, asynchronous UI tasks and launcher log.

## Important limitations

- Ely.by OAuth application registration is required. Register the exact callback URI shown in the UI (default `http://127.0.0.1:8765/callback`); Ely.by requires the redirect URI to match the registered application. The OAuth client secret is entered at sign-in and is not persisted by CounterLauncher. Never commit it to source control.
- The callback listener binds only to `127.0.0.1`. Ensure that port 8765 is available. Browser launch uses Java Desktop or `xdg-open`.
- The account vault is encrypted, but the passphrase is the protection boundary. If forgotten, the vault cannot be recovered. This is local encryption, not Android Keystore integration.
- The launcher downloads the latest authlib-injector metadata and JAR, verifies the published SHA-256 checksum when present, and adds `-javaagent:<jar>=ely.by` to the game JVM. This follows Ely.by's documented launcher integration, but has not yet been end-to-end tested against a real account/server in this environment.
- The current launcher does not download/install Mojang's bundled Java runtime components, does not guarantee every modded/legacy version, and has not been end-to-end tested against every metadata generation. Some newer Minecraft versions may require additional JVM arguments or native libraries not available for Android ARM64.
- Asset virtual/legacy compatibility varies by version. Mod loaders (Fabric/Forge/NeoForge), log configs, version inheritance, and all legacy argument formats are not fully supported yet.
- The OAuth client secret must belong to an application you registered. Because the OAuth exchange requires a secret, this implementation is best treated as a personal/development build rather than a public distributable until a safer public-client/backend design is chosen.

## Build/run

Requires a full GUI-enabled JDK and Gradle 9.x. Current setup that was confirmed to display Swing:

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
DISPLAY=:1 gradle run
```

To build a distribution:

```sh
gradle installDist
./build/install/CounterLauncher/bin/CounterLauncher
```

## Storage

- Settings: `~/.counterlauncher/settings.properties` (owner-only permissions where supported).
- Account vault: `~/.counterlauncher/accounts.enc` (AES-GCM encrypted; passphrase not stored).
- Version metadata/client: `~/.counterlauncher/versions/<version-id>/`.
- Libraries: `~/.counterlauncher/libraries/`.
- Assets: `~/.counterlauncher/assets/`.

## Ely.by OAuth setup

1. Create an OAuth application in Ely.by's account settings.
2. Register the callback URI exactly as shown in the launcher.
3. Enter the application client ID and secret in the launcher.
4. Click **Sign in in browser**, approve the scopes `account_info`, `minecraft_server_session`, and `offline_access`, then return to the launcher.
5. Save the account with a passphrase of at least 10 characters. Use **Load saved account** after restarting.

Official docs: https://docs.ely.by/en/oauth.html and https://docs.ely.by/en/minecraft-auth.html

# The signing key

Every APK in the repository is signed by one key. Its certificate's SHA-256
fingerprint is the repository's identity: `repo.json` publishes it as
`meta.signingKeyFingerprint`, and tsundoku and Aniyomi install an extension only if
its signature verifies against it.

```
SHA-256  63:16:F4:46:AF:B5:96:E5:4E:E5:00:77:40:70:72:D2:51:0E:30:1F:CC:32:A8:FF:00:95:D6:D1:23:A5:B3:BA
hex      6316f446afb596e54ee50077407072d2510e301fcc32a8ff0095d6d123a5b3ba
subject  CN=masshirodev, OU=dramas-extensions, O=masshirodev
key      RSA 4096, SHA256withRSA; valid 2026-11-11 to 2076-10-29
alias    dramas-extensions
```

`repo-meta.json` holds the hex form; `scripts/release.sh` refuses to publish an APK
signed by anything else.

## Where it is kept

Never in this repository (`*.jks`, `*.keystore`, `*.p12` are ignored, and
`scripts/check.sh` refuses a staged key). On the owner's workstation:

| What | Where |
| ---- | ----- |
| The keystore (PKCS12) | `~/.config/myconf/secrets/dramas-extensions/release.jks`, folder mode 700, file 600 |
| Its passwords | `DRAMAS_EXT_KEYSTORE_PASS` and `DRAMAS_EXT_KEY_PASS` in `~/.config/myconf/.env` (gitignored) |

PKCS12 keeps one password for the store and its key, so the two variables hold the
same value; both exist so a future JKS or a split key needs no code change. The
build reads them from the environment (`buildSrc/src/main/kotlin/dramas.extension.gradle.kts`):

| Variable | Default |
| -------- | ------- |
| `DRAMAS_EXT_KEYSTORE` | `~/.config/myconf/secrets/dramas-extensions/release.jks` |
| `DRAMAS_EXT_KEYSTORE_PASS` | none: without it a release build is unsigned |
| `DRAMAS_EXT_KEY_PASS` | none |
| `DRAMAS_EXT_KEY_ALIAS` | `dramas-extensions` |

`scripts/release.sh` sources `~/.config/myconf/.env` (or `DRAMAS_ENV_FILE`) when
the passwords are not already set.

APKs are signed with v1 and v2. tsundoku checks v2 (it refuses v3-only and
v1-only APKs); Aniyomi accepts either.

## Back it up

**Losing this keystore means no update can ever be signed again.** Every install,
in tsundoku and in Aniyomi, would have to remove the extensions and install
replacements from a repository with a new fingerprint. Keep a copy of
`release.jks` and its password somewhere other than this machine (the password
manager, plus an offline copy), and check the copy opens:

```sh
keytool -list -v -keystore <copy of release.jks> | grep SHA256
```

The fingerprint must read as above.

## If it leaks

Anyone with the keystore and its password can publish APKs that tsundoku and
Aniyomi will accept as updates from this repository. Then: make a new key,
change `repo-meta.json`'s fingerprint and `doc/SIGNING.md`, bump every
`versionCode`, release, and tell users to remove and re-add the repository (its
fingerprint changed, so the apps will treat it as a new one).

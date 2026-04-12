<p align="center">
  <img src="docs-site/public/media/app-icon.png" alt="ShaderEditor app icon" width="120">
</p>

# ShaderEditor

ShaderEditor is a free, open-source Android app for writing GLSL fragment shaders, previewing them live, and using them as live wallpaper.

It is built for people who want to sketch visual ideas quickly on a phone or tablet without giving up useful editor features. Camera, audio, touch, sensors, and textures can all drive a shader.

<p>
  <a href="https://f-droid.org/en/packages/de.markusfisch.android.shadereditor/">
    <img src="docs-site/public/media/badge-fdroid.svg" alt="Get it on F-Droid" height="54">
  </a>
  <a href="https://play.google.com/store/apps/details?id=de.markusfisch.android.shadereditor">
    <img src="docs-site/public/media/badge-google-play.svg" alt="Get it on Google Play" height="54">
  </a>
</p>

<p align="center">
  <img src="docs-site/public/media/hero-editor.webp" alt="ShaderEditor live GLSL editing on Android" width="320">
</p>

## Why use it?

- Write and preview fragment shaders directly on your Android device.
- Turn any shader into a live wallpaper.
- Use touch, sensors, camera, microphone, and imported textures as inputs.
- Get syntax highlighting, inline error markers, completions, undo/redo, and built-in samples.
- Paste common ShaderToy-style shaders and let ShaderEditor adapt them automatically.

## Get started

- Install from [F-Droid](https://f-droid.org/en/packages/de.markusfisch.android.shadereditor/) or [Google Play](https://play.google.com/store/apps/details?id=de.markusfisch.android.shadereditor).
- Open a bundled sample or create a new shader.
- Edit with live preview enabled. If it feels heavy, lower render quality.
- For examples, videos, and walkthroughs, visit <https://markusfisch.github.io/ShaderEditor/>.

## Build from source

Requirements:

- Android SDK 36
- Java 17
- No NDK required

Build debug APK:

```bash
./gradlew assembleDebug
```

Useful commands:

```bash
./gradlew lintDebug
make install
make start
```

Android app code lives in [`app/`](app/). The documentation site lives in [`docs-site/`](docs-site/).

For setup, code style, and contribution workflow, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Learn more

- [Documentation site](https://markusfisch.github.io/ShaderEditor/) — feature tour, videos, examples, walkthroughs
- [FAQ.md](FAQ.md) — common questions and troubleshooting
- [CHANGELOG.md](CHANGELOG.md) — release history
- [PRIVACY.md](PRIVACY.md) — privacy policy

## Support

If ShaderEditor is useful to you:

- ☕ [Buy me a coffee](https://www.buymeacoffee.com/markusfisch)
- ❤️ [Support on Liberapay](https://liberapay.com/markusfisch/)
- ₿ Bitcoin: `bc1q2guk2rpll587aymrfadkdtpq32448x5khk5j8z`
- 🐛 [Report bugs or request features](https://github.com/markusfisch/ShaderEditor/issues)

## License

Released under [MIT](LICENSE).

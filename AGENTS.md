# Device installation

- Run device tests on the Pixel_9 emulator. Target its emulator serial explicitly for ADB installs and tests.
- Use Debug for development installs and updates.
- Never install or update Prod/release on the phone unless Michael explicitly requests that variant. A general request to install, deploy, test or update the app authorizes Debug only.
- Preserve installed app data: update with `adb install -r`; do not uninstall to resolve a signing mismatch.

# Public repository

- Keep credentials, signing material, device serials, display identifiers, personal paths and local debugging addresses out of commits. Use placeholders in examples and keep raw device logs outside the repository.

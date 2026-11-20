# Device installation

- Use Debug for development installs and updates.
- Never install or update Prod/release on the phone unless Michael explicitly requests that variant. A general request to install, deploy, test or update the app authorizes Debug only.
- Preserve installed app data: update with `adb install -r`; do not uninstall to resolve a signing mismatch.

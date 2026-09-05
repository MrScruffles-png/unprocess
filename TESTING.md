# Pixel 9 Pro testing checklist

Before publishing a signed release, test these on the physical Pixel 9 Pro:

- Launch camera repeatedly from a cold start.
- Capture DNG, JPEG (RAW), and Both.
- Switch 0.5× → 1× → 5× → 1× repeatedly.
- Switch rear → front → rear repeatedly when RAW front capture is available.
- Pinch zoom and confirm captured JPEG framing matches the viewfinder.
- Test portrait and both landscape holding directions; confirm saved orientation is correct.
- Test Standard, Highlight, and Spot exposure on a high-contrast scene.
- In Spot mode, tap bright and dark areas and confirm exposure follows the metering point.
- Test AE Lock and unlock.
- Test manual ISO and shutter values across their ranges.
- Test AF and MF where the active physical camera supports manual focus.
- Test flash in dark, medium, and bright environments.
- Capture several images quickly and confirm the app remains responsive.
- Background and resume the app, then capture again.
- Lock/unlock the phone while the app is open, then resume.
- Test low-storage behavior before public release.

If a Camera2 failure occurs, capture Logcat around the failure and include the active lens, capture format, exposure mode, and Android build in the bug report.

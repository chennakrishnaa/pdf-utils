# Offkit

Offkit is a free, open-source Android toolkit for everyday PDF jobs. It works entirely on your phone: it requests no permissions, never connects to the internet, and has no ads, accounts or tracking.

## What it does

- **Images to PDF**: turn photos and pictures into one PDF, choose the quality and see the estimated size before saving.
- **Compress PDF**: make a PDF smaller, with three compression levels.
- **Merge PDFs**: put several PDFs in order and combine them into one.
- **Add images to PDF**: append pictures as new pages at the end of a PDF.

GPS location and camera settings (EXIF) are removed from photos the app places into PDFs (see PRIVACY.md for what is kept, such as colour profiles). Recently saved files are listed on the home screen; the list stays on the device.

## Requirements

Android 8.0 or newer, on a 64-bit device.

## Privacy

Offkit collects no data. See the [privacy policy](PRIVACY.md).

## Support the project

Offkit is built and maintained in spare time. If it is useful to you, you can support its development:

[Buy Me a Coffee](https://www.buymeacoffee.com/neelima)

Bug reports and feature ideas are welcome in the [issue tracker](https://github.com/chennakrishnaa/pdf-utils/issues).

## Building

You need JDK 21 and the Android SDK.

```
./gradlew assembleDebug
```

`scripts/verify.sh` runs the full check (build, unit tests, lint and APK checks). The unit tests also need a Linux build of the MuPDF Java library, pointed to by `MUPDF_HOST_LIB_DIR`. See [RELEASING.md](RELEASING.md) for signed release builds.

## Licence

Offkit is licensed under the [GNU Affero General Public License v3.0](LICENSE). It uses [MuPDF](https://mupdf.com) by Artifex Software, also under the AGPL. Notices for all bundled components are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

# Retriever privacy policy

Effective 10 November 2026.

Retriever finds photos, screenshots, videos and documents on your device by their names, contents or a description.
It is published on Google Play by Dhruv Gupta and on the App Store by GlitchVerse Technologies ("we").

## The short version

Retriever reads your files on your device, builds its search index on your device, and answers your searches on your
device. We don't run a server for Retriever, there is no account, and the app contains no advertising, analytics or
tracking. We never receive your files, your searches or anything derived from them. The only data that leaves the
device is described under "When Retriever uses the network" below.

## What Retriever reads, and where it stays

- **Files you let it read.** With the access you grant (all files, photos and videos only, selected photos, or folders
  you pick), Retriever reads file names, dates and contents to index them.
- **What it derives.** Text in documents, text recognised in images (OCR), labels describing photos, and numeric
  "embeddings" of documents, photos and frames sampled from videos, used for search by description, are computed on
  the device and stored in the app's private storage.
- **People recognition (off unless you turn it on).** When enabled, Retriever detects faces in your photos and stores
  numeric face descriptors on the device so it can group photos of the same person. Turning it off deletes them. They
  are never uploaded.
- **Your searches** are processed on the device and are not sent anywhere.
- **Settings and starred files** are kept in the app's storage. Android may include them in your device backup (which
  you control in system settings); the search index and the downloaded model are excluded from backups.

Uninstalling Retriever deletes everything it stored. You can also clear its data from the system app settings.

## When Retriever uses the network

- **Search model download.** Search by description needs a model of several hundred megabytes, downloaded from
  Hugging Face (huggingface.co) when you choose to. Like any download, this request reveals your IP address and device type to
  Hugging Face, under its own privacy policy. Retriever sends nothing else.
- **Text recognition models.** On Android, Google Play services may download text-recognition models for some scripts
  (for example Chinese, Japanese and Korean). Google handles those downloads under its own privacy policy.
- **Google ML Kit diagnostics.** Retriever uses Google's ML Kit on the device for text recognition, photo labels and
  face detection. ML Kit normally reports diagnostics to Google; Retriever removes the component that sends them from
  the app itself. Text recognition for some scripts runs inside Google Play services, which may still send Google
  diagnostics about it: device model and OS version, app name and version, a per-installation identifier, timings and
  error codes, encrypted in transit and not shared with third parties. Never your images, text or files.
- **Cloud files.** If you open a file stored with a cloud provider (for example through the system file picker), that
  provider may fetch it under its own terms.

## Permissions

- **All files access (Android)** or **photos and videos** lets Retriever index the files you want to search. You can
  grant less: only photos and videos, only selected photos, or only folders you pick.
- **Notifications** show indexing progress and model downloads.
- **Running in the background** (a foreground service) lets indexing continue while you use other apps; it shows a
  notification while it runs.
- **Internet** is used only for the downloads above.

## Sharing and selling

We don't sell, share or transfer your data, because we never receive it. Google's handling of the diagnostics above is
covered by Google's privacy policy.

## Children

Retriever is not directed at children under 13, and we don't knowingly collect data from anyone.

## Changes

If this policy changes, the new version will be published at the same address with a new effective date.

## Contact

Questions about privacy: dhruv2295@gmail.com.

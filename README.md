# StarknetChat — Android App

Android app powering [starknet-chat.tech](https://starknet-chat.tech).

This repository is a modified fork of Stoat's Android app (AGPL-3.0). It is published here to comply with that license. Related repositories:

- Web client: https://github.com/EDGAR431/starknet-chat-web
- Desktop app: https://github.com/EDGAR431/starknet-chat-desktop
- Backend: https://github.com/EDGAR431/starknet-chat-backend

## Description

The codebase includes the app itself, as well as an internal library for interacting with the backend
API. The app is written in Kotlin, and wholly
uses [Jetpack Compose](https://developer.android.com/jetpack/compose).

## Stack

- [Kotlin](https://kotlinlang.org/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
    - For some Material components, the View-based
      [Material Components Android](https://github.com/material-components/material-components-android)
      (MDC-Android) library is used.
- [Ktor](https://ktor.io/)
- [Dagger](https://dagger.dev/) with [Hilt](https://dagger.dev/hilt/)

## Quick Start

Open the project in Android Studio. You can then run the app on an emulator or a physical device by
running the `app` module.

## License

AGPL-3.0 — see [LICENCE](./LICENCE).

# Altana is a Bitcoin Wallet for Android provided inbitcoin and powered by GreenAddress

Build status: [![Build Status](https://api.travis-ci.org/inbitcoin/altana-android.png?branch=master)](https://travis-ci.org/inbitcoin/altana-android)

## Build requirements

You need to have the following Android developer tools installed:

- "Android SDK Platform-tools" version 25.0.3 recommended
- "Android SDK Tools" version 25.2.5 recommended
- "Android SDK Build-tools" version 25.0.2 recommended
- "Android Support Library" version 25.1.1 recommended
- "Android NDK" version r13b recommended

The above tools can be installed from the Android SDK manager.

altana-android uses [libwally](https://github.com/jgriffiths/libwally-core) which
requires the following to be installed for building:

- [SWIG](http://www.swig.org/). Most Linux distributions have this packaged,
    for example on debian `sudo apt-get install swig` should work.

## Clone the repo

`git clone https://github.com/inbitcoin/altana-android.git`

`cd altana-android`

## How to build

#### Cross-compile the native libraries:

This step requires the environment variables `ANDROID_NDK` and `JAVA_HOME` to
be set correctly.

`cd app && ./prepare_libwally_clang.sh && cd ..`

#### Build the Android app

`./gradlew build`

This will build both MAINNET and TESTNET builds

For TESTNET only run `./gradlew assembleBtctestnetDebug`

#### Rebuild the checkpoints (optional)

Checkpoint files reduce the amount of data that SPV has to download. The
checkpoint data is rebuilt periodically but you may wish to update it if
you will be making and testing changes.

To rebuild, start both MAINNET and TESTNET instances of bitcoind on
localhost. Make sure they are fully synchronized and have finished
booting (verifying blocks, etc).

On MAINNET:

`./gradlew --project-dir=bitcoinj/tools buildMainnetCheckpoints && mv bitcoinj/tools/checkpoints app/src/production/assets/checkpoints`

On TESTNET:

`./gradlew --project-dir=bitcoinj/tools buildTestnetCheckpoints && mv bitcoinj/tools/checkpoints-testnet app/src/btctestnet/assets/checkpoints`

Or to build both at once, run:

`./buildCheckpoints.sh`

### Acknowledgements

Thanks to [GreenAddress team](https://github.com/greenaddress/GreenBits)

Thanks to [Bitcoin Wallet for Android](https://github.com/schildbach/bitcoin-wallet) for their QR scanning activity source code!

Thanks to [Riccardo Casatta](https://github.com/RCasatta) for code and big UX contributions!

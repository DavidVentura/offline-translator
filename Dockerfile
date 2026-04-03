FROM debian:trixie-slim

# Set environment variables
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_VERSION=28.0.12674087
ENV ANDROID_HOME=$ANDROID_SDK_ROOT
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Install system dependencies including JDK
RUN apt-get update && \
    apt-get install -y \
        openjdk-21-jdk \
        make \
        g++ \
        libc-dev \
        wget \
        unzip \
        git \
        cmake \
        ninja-build \
        curl \
        build-essential \
        libclang-dev && \
    rm -rf /var/lib/apt/lists/*

# Create Android SDK directory
RUN mkdir -p $ANDROID_SDK_ROOT

# Download and install Android command line tools
RUN wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdtools.zip && \
    unzip cmdtools.zip -d $ANDROID_SDK_ROOT && \
    mv $ANDROID_SDK_ROOT/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools-temp && \
    mkdir -p $ANDROID_SDK_ROOT/cmdline-tools/latest && \
    mv $ANDROID_SDK_ROOT/cmdline-tools-temp/* $ANDROID_SDK_ROOT/cmdline-tools/latest/ && \
    rm -rf $ANDROID_SDK_ROOT/cmdline-tools-temp && \
    rm cmdtools.zip

# Accept licenses and install Android SDK components
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" && \
    sdkmanager "ndk;${ANDROID_NDK_VERSION}" && \
    sdkmanager "cmake;3.22.1"

# Set NDK environment variable
ENV ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION
ENV ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION

# Install Rust 1.87 system-wide
ENV RUSTUP_HOME=/usr/local/rustup
ENV CARGO_HOME=/home/vagrant/.cargo
ENV PATH="$CARGO_HOME/bin:${PATH}"

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain 1.87.0 --no-modify-path && \
    chmod -R a+w $RUSTUP_HOME $CARGO_HOME

# Add Android targets for Rust
RUN rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi
RUN cargo install cargo-ndk@4.1.2
# Set working directory
WORKDIR /home/vagrant/build/dev.davidv.translator

# some commands run `git rev-parse --short` and may get different
# lengths depending on git version or repo status
RUN git config --system core.abbrev 10

RUN echo "sdk.dir=${ANDROID_SDK_ROOT}" > local.properties
RUN chmod a+rw -R $CARGO_HOME/registry
RUN mkdir /.gradle && chmod a+rw /.gradle
RUN mkdir /.android && chmod a+rw /.android
# Default command to build the project
CMD ["./gradlew", "assembleRelease"]

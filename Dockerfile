FROM registry.gitlab.com/fdroid/fdroidserver:buildserver

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_VERSION=28.1.13356709
ENV ANDROID_HOME=$ANDROID_SDK_ROOT
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

USER root

RUN apt-get update && \
    apt-get install -y \
        make \
        g++ \
        libc-dev \
        cmake \
        ninja-build \
        libclang-dev \
        rustup && \
    rm -rf /var/lib/apt/lists/*

RUN sdkmanager "ndk;${ANDROID_NDK_VERSION}"

ENV ANDROID_NDK_ROOT=$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION
ENV ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION

ENV RUSTUP_HOME=/home/vagrant/.rustup
ENV CARGO_HOME=/home/vagrant/.cargo
ENV PATH="$CARGO_HOME/bin:$PATH"

RUN mkdir -p /home/vagrant && \
    rustup default 1.88.0 && \
    rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi && \
    cargo install cargo-ndk@4.1.2 --locked && \
    chmod -R a+rwX /home/vagrant

RUN git config --system core.abbrev 10

WORKDIR /build/build/dev.davidv.translator

RUN echo "sdk.dir=${ANDROID_SDK_ROOT}" > local.properties
RUN mkdir /.gradle && chmod a+rw /.gradle
RUN mkdir /.android && chmod a+rw /.android
RUN chmod -R a+rwX /build

CMD ["./gradlew", "assembleRelease"]

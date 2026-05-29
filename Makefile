SHELL := /bin/bash

-include .env
PHONE_HOST ?= pixel-5
SSH_PORT   ?= 8022
APK        := app/build/outputs/apk/debug/app-debug.apk
APP_ID     := com.kafkasl.phonewhisper
MAIN_ACTIVITY := $(APP_ID)/.MainActivity

SDK_CANDIDATES := /opt/homebrew/share/android-commandlinetools $(HOME)/Library/Android/sdk
ANDROID_HOME ?= $(firstword $(wildcard $(SDK_CANDIDATES)))
ADB ?= $(ANDROID_HOME)/platform-tools/adb

export JAVA_HOME := /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME
export ANDROID_SDK_ROOT := $(ANDROID_HOME)
export GRADLE_USER_HOME := $(CURDIR)/.gradle-local
export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: build test install adb-install adb-devices adb-start adb-logcat adb-logcat-clear adb-reinstall push-model clean

build:
	./gradlew assembleDebug
	@echo "APK: $(APK)"

test:
	./gradlew testDebugUnitTest

install: build
	scp -P $(SSH_PORT) $(APK) $(PHONE_HOST):~/storage/downloads/phone-whisper.apk
	ssh -p $(SSH_PORT) $(PHONE_HOST) "termux-open ~/storage/downloads/phone-whisper.apk"
	@echo "APK sent — approve install on phone"

adb-install: build
	$(ADB) install -r $(APK)

adb-devices:
	$(ADB) devices -l

adb-start:
	$(ADB) shell am start -n $(MAIN_ACTIVITY)

adb-logcat:
	$(ADB) logcat -v color PhoneWhisper:I AudioRecord:I AudioManager:I *:S

adb-logcat-clear:
	$(ADB) logcat -c

adb-reinstall: adb-install adb-start
	@echo "Installed and launched via ADB"

## Push a model to the phone's internal storage (usage: make push-model MODEL=/path/to/model-dir)
push-model:
	@test -n "$(MODEL)" || (echo "Usage: make push-model MODEL=/path/to/model-dir" && exit 1)
	$(ADB) push $(MODEL)/ /data/local/tmp/$(notdir $(MODEL))/
	$(ADB) shell "run-as $(APP_ID) mkdir -p files/models/$(notdir $(MODEL))"
	@for f in $$(ls $(MODEL)/*.onnx $(MODEL)/*.ort $(MODEL)/*.txt 2>/dev/null); do \
		echo "  copying $$(basename $$f)..."; \
		$(ADB) shell "run-as $(APP_ID) cp /data/local/tmp/$(notdir $(MODEL))/$$(basename $$f) files/models/$(notdir $(MODEL))/$$(basename $$f)"; \
	done
	@echo "Model pushed: $(notdir $(MODEL))"

clean:
	./gradlew clean

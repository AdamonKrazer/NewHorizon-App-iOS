SHELL := /bin/bash
.SHELLFLAGS := -ec
BOOTJDK ?= /usr/bin
SOURCES := $(shell find src/main/java -name '*.java')
RESOURCES := $(shell find src/main/resources -type f)
CLASSPATH := $(subst $() $(),:,$(wildcard ../JavaApp/libs/lwjgl/*.jar))
JAR := build/nh-thin-client.jar

all: $(JAR)

$(JAR): $(SOURCES) $(RESOURCES)
	mkdir -p build/classes
	$(BOOTJDK)/javac -encoding UTF-8 -source 8 -target 8 -cp "$(CLASSPATH)" -d build/classes $(SOURCES)
	cp -R src/main/resources/. build/classes/
	cd build/classes && $(BOOTJDK)/jar xf ../../../Natives/resources/newhorizon/bundled-mods/webdisplays-2.0.2-1.20.1.jar assets/webdisplays/textures/item/model/minepad_item.png
	$(BOOTJDK)/jar cfe $(JAR) com.newhorizon.thinclient.ThinClientMain -C build/classes .

check: $(JAR)
	$(BOOTJDK)/java -Djava.awt.headless=true -cp "$(JAR):$(CLASSPATH)" com.newhorizon.thinclient.ThinClientSelfTest

.PHONY: all check

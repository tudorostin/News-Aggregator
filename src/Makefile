JAVAC = javac
JAVA  = java

OUT_DIR = bin

SRC_DIR = .

CP = .:lib/*:$(OUT_DIR)

MAIN_CLASS = Tema1

SOURCES = $(shell find $(SRC_DIR) -name "*.java")

build:
	@mkdir -p $(OUT_DIR)
	$(JAVAC) -d $(OUT_DIR) -cp $(CP) $(SOURCES)

run: build
	$(JAVA) -cp $(CP) $(MAIN_CLASS) $(ARGS)

clean:
	rm -rf $(OUT_DIR)

.PHONY: build run clean
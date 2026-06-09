# Makefile for SaComS_LibC (MinGW-w64 / GCC on Linux)
# Usage: make [all|clean]

JAVA_HOME = D:/APPs/SairFrameWork/8x64
CC       = gcc
CFLAGS   = -std=c11 -O2 -Wall -fPIC
INCLUDES = -I"$(JAVA_HOME)/include" -I"$(JAVA_HOME)/include/win32"
LDFLAGS  = -shared -o

SRCDIR   = src/main/c
OUTDIR   = lib
TARGET   = $(OUTDIR)/SaComS_LibC.dll

SRCS = $(wildcard $(SRCDIR)/*.c)
OBJS = $(SRCS:.c=.o)

.PHONY: all clean

all: $(TARGET)

$(TARGET): $(OBJS)
	$(CC) $(LDFLAGS) $@ $^
	mkdir -p $(OUTDIR)
	mv $@ $(OUTDIR)/ 2>/dev/null || true

$(SRCDIR)/%.o: $(SRCDIR)/%.c
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

clean:
	rm -f $(SRCDIR)/*.o
	rm -f $(OUTDIR)/SaComS_LibC.dll
	rm -f $(OUTDIR)/SaComS_LibC.so

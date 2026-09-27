LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := envprobe
LOCAL_SRC_FILES := native_path_probe.c
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)





#ifndef VIRTUALM_IO_H
#define VIRTUALM_IO_H

#include <jni.h>

#include <string>
#include "BoxCore.h"

class IO {
public:
    static void init(JNIEnv *env);
    static void addRule(const char *targetPath, const char *relocatePath);

    static jstring redirectPath(JNIEnv *env, jstring path);

    static jobject redirectPath(JNIEnv *env, jobject path);

    // False leaves the caller's original path untouched. Rules come only from IOCore.
    static bool translatePath(const char *path, std::string &translated);
    static bool restorePath(const char *path, std::string &logical);
};


#endif 

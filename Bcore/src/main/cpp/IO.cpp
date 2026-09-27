#include "IO.h"
#include "BoxCore.h"

#include <cstring>
#include <mutex>
#include <utility>
#include <vector>

namespace {
struct Rule {
    std::string logical;
    std::string backing;
};

std::mutex ruleMutex;
std::vector<Rule> rules;

bool pathPrefix(const char *path, const std::string &prefix) {
    const size_t length = prefix.size();
    return std::strncmp(path, prefix.c_str(), length) == 0 &&
           (prefix.back() == '/' || path[length] == '\0' || path[length] == '/');
}

std::string stripTrailingSlash(const char *path) {
    std::string result(path);
    while (result.size() > 1 && result.back() == '/') result.pop_back();
    return result;
}

bool escapesRoot(const char *suffix) {
    int depth = 0;
    const char *part = suffix;
    while (*part != '\0') {
        while (*part == '/') ++part;
        if (*part == '\0') break;
        const char *end = part;
        while (*end != '\0' && *end != '/') ++end;
        const size_t length = static_cast<size_t>(end - part);
        if (length == 2 && part[0] == '.' && part[1] == '.') {
            if (depth == 0) return true;
            --depth;
        } else if (!(length == 1 && part[0] == '.')) {
            ++depth;
        }
        part = end;
    }
    return false;
}

bool applyRule(const char *path, std::string &result, bool reverse) {
    if (path == nullptr || path[0] != '/') return false;
    std::lock_guard<std::mutex> lock(ruleMutex);
    const Rule *best = nullptr;
    size_t bestLength = 0;
    for (const Rule &rule : rules) {
        const std::string &source = reverse ? rule.backing : rule.logical;
        if (source.size() > bestLength && pathPrefix(path, source) &&
            (reverse || !escapesRoot(path + source.size()))) {
            best = &rule;
            bestLength = source.size();
        }
    }
    if (best == nullptr) return false;
    const std::string &destination = reverse ? best->logical : best->backing;
    result = destination + (path + bestLength);
    return true;
}
} // namespace

bool IO::translatePath(const char *path, std::string &translated) {
    if (path == nullptr || path[0] != '/') return false;
    // A physical path must never be translated a second time.
    {
        std::lock_guard<std::mutex> lock(ruleMutex);
        for (const Rule &rule : rules) {
            if (pathPrefix(path, rule.backing)) return false;
        }
    }
    return applyRule(path, translated, false);
}

bool IO::restorePath(const char *path, std::string &logical) {
    return applyRule(path, logical, true);
}

void IO::addRule(const char *targetPath, const char *relocatePath) {
    if (targetPath == nullptr || relocatePath == nullptr ||
        targetPath[0] != '/' || relocatePath[0] != '/') return;
    Rule newRule{stripTrailingSlash(targetPath), stripTrailingSlash(relocatePath)};
    std::lock_guard<std::mutex> lock(ruleMutex);
    for (Rule &rule : rules) {
        if (rule.logical == newRule.logical) {
            rule.backing = newRule.backing;
            return;
        }
    }
    rules.push_back(std::move(newRule));
}

jstring IO::redirectPath(JNIEnv *env, jstring path) {
    return BoxCore::redirectPathString(env, path);
}

jobject IO::redirectPath(JNIEnv *env, jobject path) {
    return BoxCore::redirectPathFile(env, path);
}

void IO::init(JNIEnv *env) {
    // Java path redirection uses NativeCore.redirectPath from the JNI hooks.
    // No separate native rule set is created here.
    (void) env;
}

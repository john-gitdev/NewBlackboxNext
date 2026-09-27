#define _GNU_SOURCE
#include <asm/perf_regs.h>
#include <errno.h>
#include <inttypes.h>
#include <linux/hw_breakpoint.h>
#include <linux/perf_event.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

enum { EVENT_COUNT = 4, RING_PAGES = 8 };
static const char *labels[5][EVENT_COUNT] = {
    {"BOOT_CALL", "BOOT_RETURN", "TPR_ENTRY", "UNUSED"},
    {"MAP_MATCH", "HELPER_TRUE", "DUMMY_TRUE", "RETURN_BOOL"},
    {"ISV_ENTRY", "MAP_MATCH", "RETURN_BOOL", "UNUSED"},
    {"DATA_DIR", "PACKAGE_NAME", "PATH_PREFIX", "PREFIX_HAS_DOT"},
    {"DATA_DIR", "PACKAGE_NAME", "PREFIX_HAS_DOT", "BOOT_RETURN"}
};
static const uint64_t offsets[5][EVENT_COUNT] = {
    {0x396f644, 0x396f648, 0x4a45f34, 0},
    {0x30814ac, 0x30814fc, 0x3081e24, 0x308124c},
    {0x3080458, 0x30814ac, 0x308124c, 0},
    {0x30811ac, 0x30811cc, 0x308122c, 0x3081238},
    {0x30811ac, 0x30811cc, 0x3081238, 0x396f648}
};
static const int reg_indices[] = {PERF_REG_ARM64_X0, PERF_REG_ARM64_X1, PERF_REG_ARM64_X2,
                                  PERF_REG_ARM64_X3, PERF_REG_ARM64_X8, PERF_REG_ARM64_X28, PERF_REG_ARM64_LR,
                                  PERF_REG_ARM64_SP, PERF_REG_ARM64_PC};
static const char *reg_names[] = {"x0", "x1", "x2", "x3", "x8", "x28", "lr", "sp", "pc"};
struct capture {
    int fd;
    struct perf_event_mmap_page *meta;
    size_t page_size;
};

static uint64_t load64(const uint8_t **cursor) {
    uint64_t value;
    memcpy(&value, *cursor, sizeof(value));
    *cursor += sizeof(value);
    return value;
}

static void dump_tpr_string(int tid, uint64_t ptr) {
    if (ptr < 0x10000) return;
    uint8_t data[220] = {0};
    struct iovec local = {.iov_base = data, .iov_len = sizeof(data)};
    struct iovec remote = {.iov_base = (void *)(uintptr_t)ptr, .iov_len = sizeof(data)};
    ssize_t got = process_vm_readv(tid, &local, 1, &remote, 1, 0);
    if (got < 20) {
        printf(" string_read_errno=%d", errno);
        return;
    }
    uint32_t count;
    memcpy(&count, data + 16, 4);
    if (count > 96 || 20 + 2 * count > got) {
        printf(" string_length_invalid=%u", count);
        return;
    }
    printf(" string='");
    for (uint32_t i = 0; i < count; i++) {
        uint16_t c;
        memcpy(&c, data + 20 + 2 * i, 2);
        putchar(c >= 32 && c < 127 && c != '\'' ? (int)c : '?');
    }
    printf("'");
}

static void dump_c_string(int tid, uint64_t ptr) {
    if (ptr < 0x10000) return;
    char data[512] = {0};
    struct iovec local = {.iov_base = data, .iov_len = sizeof(data)};
    struct iovec remote = {.iov_base = (void *)(uintptr_t)ptr, .iov_len = sizeof(data)};
    ssize_t got = process_vm_readv(tid, &local, 1, &remote, 1, 0);
    if (got < 1) {
        printf(" cstring_read_errno=%d", errno);
        return;
    }
    printf(" cstring='");
    for (ssize_t i = 0; i < got && i < 511 && data[i]; i++) {
        unsigned char c = (unsigned char)data[i];
        putchar(c >= 32 && c < 127 && c != '\'' ? c : '?');
    }
    printf("'");
}

static void drain(struct capture *c, int index, int tid, int mode) {
    uint64_t head = __atomic_load_n(&c->meta->data_head, __ATOMIC_ACQUIRE);
    uint64_t tail = c->meta->data_tail;
    const size_t ring_size = c->page_size * RING_PAGES;
    uint8_t *ring = (uint8_t *)c->meta + c->page_size;
    while (tail < head) {
        struct perf_event_header hdr;
        size_t pos = tail % ring_size;
        if (pos + sizeof(hdr) <= ring_size) memcpy(&hdr, ring + pos, sizeof(hdr));
        else {
            size_t first = ring_size - pos;
            memcpy(&hdr, ring + pos, first);
            memcpy((uint8_t *)&hdr + first, ring, sizeof(hdr) - first);
        }
        if (hdr.size < sizeof(hdr) || hdr.size > 1024) break;
        uint8_t record[1024];
        if (pos + hdr.size <= ring_size) memcpy(record, ring + pos, hdr.size);
        else {
            size_t first = ring_size - pos;
            memcpy(record, ring + pos, first);
            memcpy(record + first, ring, hdr.size - first);
        }
        if (hdr.type == PERF_RECORD_SAMPLE) {
            const uint8_t *p = record + sizeof(hdr);
            uint64_t ip = load64(&p);
            uint64_t tid_pair = load64(&p);
            uint64_t when = load64(&p);
            uint64_t abi = load64(&p);
            uint64_t regs[sizeof(reg_indices) / sizeof(reg_indices[0])] = {0};
            if (abi != PERF_SAMPLE_REGS_ABI_NONE) {
                for (size_t i = 0; i < sizeof(regs) / sizeof(regs[0]); i++) regs[i] = load64(&p);
            }
            printf("SAMPLE %s time_ns=%" PRIu64 " ip=%#" PRIx64 " pid=%u tid=%u abi=%" PRIu64,
                   labels[mode][index], when, ip, (unsigned)(tid_pair & 0xffffffff),
                   (unsigned)(tid_pair >> 32), abi);
            for (size_t i = 0; i < sizeof(regs) / sizeof(regs[0]); i++)
                printf(" %s=%#" PRIx64, reg_names[i], regs[i]);
            if (mode == 0 && index == 2) dump_tpr_string(tid, regs[0]);
            if (mode == 3 || (mode == 4 && index < 2)) dump_c_string(tid, regs[0]);
            putchar('\n');
            fflush(stdout);
        } else if (hdr.type == PERF_RECORD_LOST) {
            printf("LOST %s\n", labels[mode][index]);
            fflush(stdout);
        }
        tail += hdr.size;
    }
    __atomic_store_n(&c->meta->data_tail, tail, __ATOMIC_RELEASE);
}

int main(int argc, char **argv) {
    if (argc != 5) {
        fprintf(stderr, "usage: perf_capture TID IL2CPP_BASE DURATION_SECONDS MODE(0|1|2|3|4)\n");
        return 1;
    }
    int tid = atoi(argv[1]);
    uint64_t base = strtoull(argv[2], NULL, 0);
    int duration = atoi(argv[3]);
    int mode = atoi(argv[4]);
    if (tid <= 0 || base == 0 || duration < 1 || duration > 120 || mode < 0 || mode > 4) return 1;
    int count = mode == 1 || mode == 3 || mode == 4 ? 4 : 3;
    long page = sysconf(_SC_PAGESIZE);
    struct capture captures[EVENT_COUNT] = {0};
    struct pollfd polls[EVENT_COUNT] = {0};
    uint64_t mask = 0;
    for (size_t i = 0; i < sizeof(reg_indices) / sizeof(reg_indices[0]); i++)
        mask |= 1ULL << reg_indices[i];
    for (int i = 0; i < count; i++) {
        struct perf_event_attr attr = {0};
        attr.size = sizeof(attr);
        attr.type = PERF_TYPE_BREAKPOINT;
        attr.bp_type = HW_BREAKPOINT_X;
        attr.bp_addr = base + offsets[mode][i];
        attr.bp_len = sizeof(uint32_t);
        attr.sample_period = 1;
        attr.sample_type = PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_TIME | PERF_SAMPLE_REGS_USER;
        attr.sample_regs_user = mask;
        attr.exclude_kernel = 1;
        attr.wakeup_events = 1;
        int fd = syscall(__NR_perf_event_open, &attr, tid, -1, -1, 0);
        if (fd < 0) {
            fprintf(stderr, "OPEN_FAILED %s errno=%d %s\n", labels[mode][i], errno, strerror(errno));
            return 2;
        }
        size_t len = (RING_PAGES + 1) * (size_t)page;
        captures[i].fd = fd;
        captures[i].page_size = (size_t)page;
        captures[i].meta = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (captures[i].meta == MAP_FAILED) {
            fprintf(stderr, "MMAP_FAILED %s errno=%d %s\n", labels[mode][i], errno, strerror(errno));
            return 3;
        }
        polls[i].fd = fd;
        polls[i].events = POLLIN;
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        uint64_t when = (uint64_t)now.tv_sec * 1000000000ULL + (uint64_t)now.tv_nsec;
        printf("ARMED %s time_ns=%" PRIu64 " tid=%d addr=%#" PRIx64 "\n", labels[mode][i], when, tid, base + offsets[mode][i]);
    }
    fflush(stdout);
    time_t end = time(NULL) + duration;
    while (time(NULL) < end) {
        int pr = poll(polls, count, 200);
        if (pr < 0 && errno != EINTR) break;
        for (int i = 0; i < count; i++) drain(&captures[i], i, tid, mode);
    }
    for (int i = 0; i < count; i++) {
        drain(&captures[i], i, tid, mode);
        munmap(captures[i].meta, (RING_PAGES + 1) * (size_t)page);
        close(captures[i].fd);
    }
    puts("DONE");
    return 0;
}

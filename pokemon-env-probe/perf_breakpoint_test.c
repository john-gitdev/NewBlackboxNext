#include <asm/perf_regs.h>
#include <errno.h>
#include <inttypes.h>
#include <linux/hw_breakpoint.h>
#include <linux/perf_event.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

__attribute__((noinline)) static void marker(void) {
    __asm__ __volatile__("nop" ::: "memory");
}

int main(void) {
    struct perf_event_attr attr = {0};
    attr.size = sizeof(attr);
    attr.type = PERF_TYPE_BREAKPOINT;
    attr.bp_type = HW_BREAKPOINT_X;
    attr.bp_addr = (uintptr_t)&marker;
    attr.bp_len = sizeof(uint32_t);
    attr.sample_period = 1;
    attr.sample_type = PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_TIME | PERF_SAMPLE_REGS_USER;
    attr.sample_regs_user = (1ULL << PERF_REG_ARM64_X0) | (1ULL << PERF_REG_ARM64_LR)
                            | (1ULL << PERF_REG_ARM64_PC);
    attr.exclude_kernel = 1;
    attr.wakeup_events = 1;
    int fd = syscall(__NR_perf_event_open, &attr, (int)syscall(__NR_gettid), -1, -1, 0);
    if (fd < 0) {
        printf("perf_event_open failed errno=%d %s\n", errno, strerror(errno));
        return 2;
    }
    long page = sysconf(_SC_PAGESIZE);
    size_t len = (size_t)page * 9;
    struct perf_event_mmap_page *meta = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (meta == MAP_FAILED) {
        printf("mmap failed errno=%d %s\n", errno, strerror(errno));
        close(fd);
        return 3;
    }
    marker();
    struct pollfd p = {.fd = fd, .events = POLLIN};
    int pr = poll(&p, 1, 1000);
    printf("poll=%d revents=%d head=%" PRIu64 " tail=%" PRIu64 "\n",
           pr, p.revents, (uint64_t)meta->data_head, (uint64_t)meta->data_tail);
    if (meta->data_head > meta->data_tail) {
        uint8_t *data = (uint8_t *)meta + page;
        struct perf_event_header *h = (struct perf_event_header *)(data + (meta->data_tail % (page * 8)));
        printf("event type=%u size=%u\n", h->type, h->size);
    }
    munmap(meta, len);
    close(fd);
    return 0;
}

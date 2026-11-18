/*
 * ajtty – set a serial port to raw binary mode with the kernel's termios ioctl (no stty).
 * Android's toybox stty could not clear icrnl/ixon/... on the Judian X8, so AJ Panel ships this helper.
 * No libc: raw Linux syscalls, static, ~2 KB. Built for armeabi-v7a and arm64-v8a by build.sh.
 *   usage: libajtty.so /dev/ttyS3 [115200] [flow]     flow: rtscts (default) | none
 *   prints "ok iflag=.. oflag=.. cflag=.. lflag=.." (read back after setting) or "error ..."; exit 0 = ok
 */
typedef unsigned int u32;
struct termios { u32 c_iflag, c_oflag, c_cflag, c_lflag; unsigned char c_line, c_cc[19]; };
#define TCGETS 0x5401
#define TCSETS 0x5402
#define TCFLSH 0x540B
#define CBAUD 0010017
#define CBAUDEX 0010000
#define CSIZE 0000060
#define CS8 0000060
#define CSTOPB 0000100
#define CREAD 0000200
#define PARENB 0000400
#define HUPCL 0002000
#define CLOCAL 0004000
#define CRTSCTS 020000000000
#define VTIME 5
#define VMIN 6

#if defined(__x86_64__)   /* test build only (cloud check on a pty) */
static long sc(long n, long a, long b, long c, long d) { long r; register long r10 __asm__("r10") = d;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10) : "rcx", "r11", "memory"); return r; }
#define SYS_OPENAT 257
#define SYS_CLOSE 3
#define SYS_WRITE 1
#define SYS_IOCTL 16
#define SYS_EXIT 60
#elif defined(__aarch64__)
static long sc(long n, long a, long b, long c, long d) {
    register long x8 __asm__("x8") = n; register long x0 __asm__("x0") = a; register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c; register long x3 __asm__("x3") = d;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory"); return x0; }
#define SYS_OPENAT 56
#define SYS_CLOSE 57
#define SYS_WRITE 64
#define SYS_IOCTL 29
#define SYS_EXIT 93
#else
static long sc(long n, long a, long b, long c, long d) {
    register long r7 __asm__("r7") = n; register long r0 __asm__("r0") = a; register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c; register long r3 __asm__("r3") = d;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2), "r"(r3) : "memory"); return r0; }
#define SYS_OPENAT 322
#define SYS_CLOSE 6
#define SYS_WRITE 4
#define SYS_IOCTL 54
#define SYS_EXIT 1
#endif

static int slen(const char *s) { int n = 0; while (s[n]) n++; return n; }
static void out(const char *s) { sc(SYS_WRITE, 1, (long)s, slen(s), 0); }
static void hex(u32 v) { char b[11] = "0x"; for (int i = 0; i < 8; i++) b[2 + i] = "0123456789abcdef"[(v >> (28 - 4 * i)) & 15]; b[10] = 0; out(b); }
static int eq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
static u32 baud(const char *s) {
    if (eq(s, "9600")) return 0000015; if (eq(s, "19200")) return 0000016; if (eq(s, "38400")) return 0000017;
    if (eq(s, "57600")) return 0010001; if (eq(s, "115200")) return 0010002; if (eq(s, "230400")) return 0010003;
    if (eq(s, "460800")) return 0010004; return 0; }
static void dump(struct termios *t) { out(" iflag="); hex(t->c_iflag); out(" oflag="); hex(t->c_oflag); out(" cflag="); hex(t->c_cflag); out(" lflag="); hex(t->c_lflag); }

int main_(int argc, char **argv) {
    if (argc < 2) { out("usage: ajtty <tty> [baud] [rtscts|none]\n"); return 2; }
    u32 sp = baud(argc > 2 ? argv[2] : "115200");
    if (!sp) { out("error unsupported baud\n"); return 2; }
    int flow = !(argc > 3 && eq(argv[3], "none"));
    long fd = sc(SYS_OPENAT, -100 /*AT_FDCWD*/, (long)argv[1], 02 | 0400 | 04000 /*O_RDWR|O_NOCTTY|O_NONBLOCK*/, 0);
    if (fd < 0) { out("error open "); hex((u32)-fd); out("\n"); return 1; }
    struct termios t;
    if (sc(SYS_IOCTL, fd, TCGETS, (long)&t, 0) < 0) { out("error TCGETS\n"); return 1; }
    out("before"); dump(&t); out("\n");
    t.c_iflag = 0;                       /* no istrip, icrnl, inlcr, igncr, ixon, ixoff, iuclc, parity … */
    t.c_oflag = 0;                       /* no opost/onlcr */
    t.c_lflag = 0;                       /* no icanon, echo, isig, iexten */
    t.c_cflag = (t.c_cflag & ~(CBAUD | CSIZE | CSTOPB | PARENB | CRTSCTS | HUPCL)) | sp | CS8 | CREAD | CLOCAL | (flow ? CRTSCTS : 0);
    t.c_line = 0;
    for (int i = 0; i < 19; i++) t.c_cc[i] = 0;
    t.c_cc[VMIN] = 1; t.c_cc[VTIME] = 0;
    if (sc(SYS_IOCTL, fd, TCSETS, (long)&t, 0) < 0) { out("error TCSETS\n"); return 1; }
    sc(SYS_IOCTL, fd, TCFLSH, 2 /*TCIOFLUSH*/, 0);
    struct termios r;
    sc(SYS_IOCTL, fd, TCGETS, (long)&r, 0);
    int good = r.c_iflag == 0 && r.c_oflag == 0 && r.c_lflag == 0 && (r.c_cflag & (CBAUD | CSIZE)) == (sp | CS8);
    out(good ? "ok" : "mismatch"); dump(&r); out("\n");
    sc(SYS_CLOSE, fd, 0, 0, 0);
    return good ? 0 : 3;
}

#if defined(__x86_64__)
__asm__(".global _start\n_start:\n mov (%rsp),%rdi\n lea 8(%rsp),%rsi\n and $-16,%rsp\n call cmain\n mov %eax,%edi\n mov $60,%eax\n syscall\n");
#elif defined(__aarch64__)
__asm__(".global _start\n_start:\n ldr x0,[sp]\n add x1,sp,#8\n bl cmain\n mov x8,#93\n svc 0\n");
#else
__asm__(".global _start\n_start:\n ldr r0,[sp]\n add r1,sp,#4\n bl cmain\n mov r7,#1\n svc 0\n");
#endif
int cmain(int argc, char **argv) { return main_(argc, argv); }

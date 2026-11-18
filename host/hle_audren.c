// nn::audio AudioRenderer (audren) -- in-process rendering: use the complete renderer the game's SDK ships with, no mixer of our own.
// Newer SDKs link the renderer server side (nn::audio::server: InfoUpdater / CommandGenerator ...) and the CPU version of the DSP command processing (nn::audio::dsp::CommandListProcessor:
// ADPCM / PCM decoding, resampling, mixing, reverb / delay / biquad / limiter / compressor ...) into the application, for the "CPU rendering + manual execution" mode (AudioRendererParameter
// +0x2c renderingDevice = 1 CPU, +0x30 executionMode = 1 manual). In this mode OpenAudioRenderer goes through the in-process DFC manager (CreateAudioRendererManagerByDfc),
// RequestUpdate is parsed in-process, and rendering happens on the CPU of whichever thread calls nn::audio::ExecuteAudioRendererRendering, which then signals the game's renderer event.
// The host does three things:
//   1. Takes over GetAudioRendererWorkBufferSize / OpenAudioRenderer: switches the parameter to CPU + manual and calls the SDK original (the work buffer size follows the modified parameter);
//   2. A render thread (a guest thread started by the host): renders one frame whenever the output hands back a 5 ms buffer, so pacing follows the sound card clock;
//   3. Takes over AudioRendererImpl::ExecuteAudioRendererRendering (reached through the PLT): generates commands and runs CommandListProcessor like the original,
//      then finds the DeviceSink command in the command list (a no-op for the CPU processor) and clamps its input mix buffers (int32 holding 16-bit range) to int16 stereo for AudioOut (hle_audio.c).
// Command list format (SDK 16): header +0x04 command count, +0x08 mix buffer address, +0x10 buffer count, +0x14 samples per frame, +0x18 sample rate, commands from +0x20;
//   each command +0x00 magic 0xcafebabe, +0x04 enabled, +0x05 command ID (DeviceSink = 0x13), +0x06 size; DeviceSink +0x128 upsampler buffer, +0x130 input count, +0x134 input indices (s16).
#include "hle.h"
#include <math.h>
#include <stdatomic.h>
#include <time.h>
#include <unistd.h>

void *dm_guest_thread_spawn(void (*fn)(void *), void *arg, int prio, size_t stack_size);
void dm_guest_thread_join(void *tt);
void *dm_pcmout_open(int ch);
void dm_pcmout_close(void *k);
void dm_pcmout_start(void *k);
int dm_pcmout_append(void *k, void *b);
void *dm_pcmout_released(void *k);
void dm_pcmout_wait(void *k, int ms);

// P_SIZE: bytes of the game's AudioRendererParameter copied before the two fields are changed. SDK 16's struct is 0x38 bytes; SDK 8's (Link's Awakening) has one more word,
// its revision at +0x38: copying only 0x38 left the revision uninitialized and the SDK rejected it (CheckValidRevision, Result 0x499). The SDK reads only its own size of the copy.
// SDK 1's struct is 0x34 bytes (13 words, revision 0x3256 at +0x30): copying more reads past it. In SDK 1 +0x30 is the revision, not the execution mode — forcing
// the mode there corrupts the parameter the service receives. Old SDKs link no server-side renderer code at all (no AudioRenderSystem), so they get the parameter verbatim and
// no render thread (the renderer is driven by the audren service, see hle_sf.c); new SDKs keep the CPU/manual takeover
enum { P_DEVICE = 0x2c, P_MODE = 0x30, P_SIZE = 0x40, P_SIZE_OLD = 0x34, FRAME_MAX = 1024, NBUF = 4 };
static void *gsym(const char *n) { void *p = guest_sym(n); if (!p) die("audren: missing SDK symbol %s", n); return p; }
static int audren_server_side(void) {   // the SDK links the renderer's server-side code (AudioRenderSystem::Update): absent in SDK 1, present in SDK 6 and later
    static int has = -1;
    if (has < 0) has = guest_sym("_ZN2nn5audio6server17AudioRenderSystem6UpdateEPvmS3_mPKvm") != NULL;
    return has;
}
static void cpu_manual(uint8_t *dst, const uint8_t *src) { memcpy(dst, src, audren_server_side() ? P_SIZE : P_SIZE_OLD); if (!audren_server_side()) return; *(int32_t *)(dst + P_DEVICE) = 1; *(int32_t *)(dst + P_MODE) = 1; }

HLE_FORCE("_ZN2nn5audio30GetAudioRendererWorkBufferSizeERKNS0_22AudioRendererParameterE", "renderer work buffer size: computed for CPU rendering + manual execution",
          size_t, h_ar_WorkSize, const uint8_t *param) {
    static size_t (*orig)(const uint8_t *); if (!orig) orig = gsym("_ZN2nn5audio30GetAudioRendererWorkBufferSizeERKNS0_22AudioRendererParameterE");
    uint8_t p[P_SIZE]; cpu_manual(p, param); size_t n = orig(p);
    { static int once; if (!once++) LOG("audren: work buffer %#zx", n); }
    return n;
}
// ---------- rendering and output ----------
static void *g_handle[2];                    // AudioRendererHandle (renderer object, client state)
static _Atomic int g_open;
static void *g_render_tt;                    // the render thread (ThreadType from dm_guest_thread_spawn): Close joins it before the SDK tears the renderer down
static int16_t g_frame[FRAME_MAX * 2]; static int g_frame_n;   // the latest frame (stereo int16)
static _Atomic uint64_t g_frames, g_nonsilent;
enum { CQ = 4 }; static int16_t g_cq[CQ][FRAME_MAX * 2]; static int g_cq_n[CQ]; static _Atomic uint32_t g_cq_head, g_cq_tail;   // output queue of catch-up frames
static pthread_mutex_t g_cq_mu = PTHREAD_MUTEX_INITIALIZER; static pthread_cond_t g_cq_cv = PTHREAD_COND_INITIALIZER;   // the render thread signals each frame it takes off the queue (catch-up pacing)
static void cq_popped(void) { pthread_mutex_lock(&g_cq_mu); pthread_cond_broadcast(&g_cq_cv); pthread_mutex_unlock(&g_cq_mu); }

typedef struct { void *ptr; uint64_t cap, size; } gbuf;       // same as the front of hle_audio.c's AudioOutBuffer
static void render_main(void *arg) {
    (void)arg;
    void (*exec)(void *, void *) = gsym("_ZN2nn5audio29ExecuteAudioRendererRenderingENS0_19AudioRendererHandleE");
    void *out = dm_pcmout_open(2);
    static int16_t data[NBUF][FRAME_MAX * 2]; static gbuf bufs[NBUF]; gbuf *free_list[NBUF]; int nfree = NBUF;
    for (int i = 0; i < NBUF; i++) { bufs[i] = (gbuf){data[i], sizeof data[i], 0}; free_list[i] = &bufs[i]; }
    dm_pcmout_start(out);
    LOG("audren: render thread started (%d buffers in flight, paced by the output)", NBUF);
    while (atomic_load(&g_open)) {
        while (!nfree) {   // wait for the output to hand a block back (silent sink / sound card, one every 5 ms); it signals each release, so the thread sleeps until then (it used to poll every 1 ms)
            gbuf *b = dm_pcmout_released(out);
            if (b) { free_list[nfree++] = b; break; }
            dm_pcmout_wait(out, 10);
            if (!atomic_load(&g_open)) break;
        }
        if (!nfree) break;
        const int16_t *src; int got;
        uint32_t h = atomic_load(&g_cq_head);
        if (h != atomic_load(&g_cq_tail)) { src = g_cq[h % CQ]; got = g_cq_n[h % CQ]; atomic_store(&g_cq_head, h + 1); cq_popped(); }   // play queued catch-up output first
        else { g_frame_n = 0; exec(g_handle[0], g_handle[1]); src = g_frame; got = g_frame_n; }   // SDK: generate commands → CPU processing (output taken in the host takeover) → signal the game's renderer event
        int n = got ? got : 240;
        gbuf *b = free_list[--nfree];
        if (got) memcpy(b->ptr, src, (size_t)n * 4); else memset(b->ptr, 0, (size_t)n * 4);
        b->size = (uint64_t)n * 4;
        if (!dm_pcmout_append(out, b)) free_list[nfree++] = b;
    }
    dm_pcmout_close(out);   // the output would otherwise keep its sound card callback or silent sink running and hold one of the AudioOut slots after the renderer closes
    cq_popped();   // an update waiting for queue space (catch-up pacing) sees g_open cleared
}

// Both OpenAudioRenderer forms: CPU rendering + manual execution, then the host's render thread. With the event the game waits on it, which each render signals; without it the
// game paces its updates itself (Balatro: LÖVE's OpenAL Soft) and still needs the renderer to run every 5 ms: the form without an event used to start no render thread, so only the
// game's updates rendered (catch-up frames, which nothing played) and the game was silent.
// SDK 17.5.3's event form opens the renderer by calling the form without one through the PLT (sdk+0x1e489c, ROMANCE OF THE THREE KINGDOMS 8 REMAKE), which the loader binds to the
// hook below since that form has one (Balatro): the inner call started the render thread before the event form had created its event, and the outer call then logged a second
// renderer. The inner call (t_in_open) goes straight to the SDK with the host's copy of the parameter, as it did before the form without an event was hooked
static __thread int t_in_open;
static Result ar_open(void **handle, void *ev, const uint8_t *param, void *work, size_t sz) {
    static Result (*orig_ev)(void **, void *, const uint8_t *, void *, size_t), (*orig)(void **, const uint8_t *, void *, size_t);
    if (!orig_ev) orig_ev = gsym("_ZN2nn5audio17OpenAudioRendererEPNS0_19AudioRendererHandleEPNS_2os11SystemEventERKNS0_22AudioRendererParameterEPvm");
    if (!orig) orig = gsym("_ZN2nn5audio17OpenAudioRendererEPNS0_19AudioRendererHandleERKNS0_22AudioRendererParameterEPvm");
    if (t_in_open) return orig(handle, param, work, sz);
    uint8_t p[P_SIZE]; cpu_manual(p, param);
    LOG("audren: OpenAudioRenderer%s rate %d samples per frame %d mix buffers %d submixes %d voices %d sinks %d effects %d splitters %d (original device %d mode %d → CPU / manual) work buffer %p+%#zx",
        ev ? "" : " (no event)", *(int32_t *)param, *(int32_t *)(param + 4), *(int32_t *)(param + 8), *(int32_t *)(param + 0xc), *(int32_t *)(param + 0x10), *(int32_t *)(param + 0x14),
        *(int32_t *)(param + 0x18), *(int32_t *)(param + 0x24), *(int32_t *)(param + P_DEVICE), *(int32_t *)(param + P_MODE), work, sz);
    t_in_open = 1; Result r = ev ? orig_ev(handle, ev, p, work, sz) : orig(handle, p, work, sz); t_in_open = 0;
    if (r) { LOG("[RED] audren: SDK OpenAudioRenderer failed %#x", r); return r; }
    if (atomic_load(&g_open)) { LOG("[RED] audren: a second renderer (the host drives only one)"); return r; }
    g_handle[0] = handle[0]; g_handle[1] = handle[1]; atomic_store(&g_open, 1);
    if (!audren_server_side()) { audren_svc_note_game_event(ev); LOG("audren: opened (SDK 1, service-driven; no render thread)"); return r; }   // the renderer is driven by the audren service's RequestUpdate (hle_sf.c): starting the render thread would die on the missing ExecuteAudioRendererRendering symbol
    g_render_tt = dm_guest_thread_spawn(render_main, NULL, 9, 0x10000);
    return r;
}
HLE_FORCE("_ZN2nn5audio17OpenAudioRendererEPNS0_19AudioRendererHandleEPNS_2os11SystemEventERKNS0_22AudioRendererParameterEPvm", "open the renderer (with event): CPU rendering + manual execution, the host starts a render thread",
          Result, h_ar_Open, void **handle, void *ev, const uint8_t *param, void *work, size_t sz) { return ar_open(handle, ev, param, work, sz); }
HLE_FORCE("_ZN2nn5audio17OpenAudioRendererEPNS0_19AudioRendererHandleERKNS0_22AudioRendererParameterEPvm", "open the renderer (no event): CPU rendering + manual execution, the host starts a render thread",
          Result, h_ar_Open0, void **handle, const uint8_t *param, void *work, size_t sz) { return ar_open(handle, NULL, param, work, sz); }
HLE_FORCE("_ZN2nn5audio18CloseAudioRendererENS0_19AudioRendererHandleE", "close the renderer: stop the host render thread first, then hand over to the SDK", void, h_ar_Close, void *obj, void *state) {
    static void (*orig)(void *, void *); if (!orig) orig = gsym("_ZN2nn5audio18CloseAudioRendererENS0_19AudioRendererHandleE");
    // Join the render thread first (it checks g_open every round and at most every 10 ms while waiting for the output): it may be inside exec(), which uses the renderer the SDK is about to free.
    // A fixed 20 ms sleep used to stand in for this, and a renderer reopened inside that window ran a second thread over the first one's static buffers
    if (obj == g_handle[0] && g_render_tt) { atomic_store(&g_open, 0); dm_guest_thread_join(g_render_tt); g_render_tt = NULL; }
    if (!audren_server_side()) audren_svc_close();   // service-driven (SDK 1): stop the catch-up cadence and the PCM output before the SDK tears the renderer down
    orig(obj, state);
}

// DeviceSink input mix buffers → int16 stereo. The command ID is 0x13 in SDK 16; SDK 20 inserted a command before it, making it 0x14 (written at sdk+0x233f44); SDK 8 (Link's Awakening)
//   has four fewer commands before it, making it 0x10. The layout is the same in all three: 0x148 bytes, +0x20 device name ("MainAudioOut"), +0x128 upsample buffer, +0x130 input count,
//   +0x134 input indices. render_once sets the ID by layout; when the first full command list has no command with that ID, its one 0x148-byte command with 1 to 6 inputs is the sink
//   (without this, Link's Awakening rendered every frame and played silence: the music and sound effects never reached the output, only the movies' own AudioOut did)
// SDK 6 (Fire Emblem: Three Houses, CommandListProcessor::Process at sdk+0xaaa00) lays out the command header differently: +0x04 size (s16), +0x06 command ID (s16), +0x10 enabled (u8),
//   where SDK 8–20 have +0x04 enabled (u8), +0x05 ID (u8), +0x06 size (s16); DeviceSink is ID 16 there (its case in Process is a no-op). Walked with the newer header, the first command's ID
//   was taken as its size, the second "command" had no magic and the game was silent. The first full command list decides the layout: the one whose walk finds the magic on every command
// SDK 21 (Rhythm Heaven Groove, CommandListProcessor::Process at sdk+0x24fd50) has no magic: +0x00 enabled (bit 0), +0x01 command ID (u8), +0x02 size (s16), +0x04 estimated time, +0x08 node ID,
//   and every command body sits 8 bytes earlier. DeviceSink is still ID 0x14 but 0x140 bytes (written at sdk+0x23a7b0): +0x18 device name, +0x120 upsample buffer, +0x128 input count, +0x12c input
//   indices. Walked as SDK 20, the first command's enabled / ID / size bytes read as a magic of 0x181901 and the game was silent. With no magic, that walk must stay inside the generated list
// SDK 4.4.0 (Mario Kart 8 Deluxe, GenerateDeviceSinkCommand at sdk+0x19e690) has the SDK 6 header, and its DeviceSink is ID 0xf and 0x130 bytes: +0x14 device name, +0x118 upsample buffer,
//   +0x120 input count, +0x124 input indices as bytes (s8). No 0x148-byte command, so the first full command list's one 0x130-byte command with 1 to 6 inputs is the sink (g_sink4)
static int g_sink_id = 0x13, g_hdr6, g_hdr21, g_sink4;
static int cmd_size(const uint8_t *q) { return g_hdr21 ? *(const int16_t *)(q + 2) : g_hdr6 ? *(const int16_t *)(q + 4) : *(const int16_t *)(q + 6); }
static int cmd_id(const uint8_t *q) { return g_hdr21 ? q[1] : g_hdr6 ? *(const int16_t *)(q + 6) : q[5]; }
static int cmd_on(const uint8_t *q) { return g_hdr21 ? q[0] & 1 : g_hdr6 ? q[0x10] : q[4]; }
static int cmd_ok(const uint8_t *q, const uint8_t *end) { int n = cmd_size(q); return g_hdr21 ? n >= 0x10 && q + n <= end : *(const uint32_t *)q == 0xcafebabeu && n > 0; }
static int sink_body(void) { return g_hdr21 ? -8 : 0; }   // where DeviceSink's fields sit relative to SDK 8–20
static int cmd_walks(const uint8_t *cl, uint32_t ncmd, const uint8_t *end) {
    const uint8_t *q = cl + 0x20;
    for (uint32_t i = 0; i < ncmd; i++) { if (!cmd_ok(q, end)) return 0; q += cmd_size(q); }
    return 1;
}
// A renderer at 32 kHz (DRAGON QUEST VII Reimagined: 160 samples per 5 ms frame) has its DeviceSink upsampled to the output's 48 kHz by the SDK's upsampler, which AudioRenderSystem::
//   SetUpRenderResourceSet allocates only for the DSP (SDK 19: sdk+0x21eb6c, sample rate 0x7d00 and rendering device +0x48 == 0). With the host's CPU rendering there is no
//   UpsamplerManager, and the sink's command generation (GenerateSinkCommands, sdk+0x225a80) called Allocate on null and crashed in its mutex. The takeover below answers "no upsampler"
//   for a null manager (the generator then skips the upsample command, sdk+0x225b40), and the host upsamples the stereo downmix 3:2 itself: a windowed sinc (Blackman, 16 taps, cutoff 0.9 of
//   the input's Nyquist) in three phases, evaluated 8 input samples late so every tap is known, with the last 15 samples kept across frames
HLE_FORCE("_ZN2nn5audio6server16UpsamplerManager8AllocateEv", "upsampler for a DeviceSink below 48 kHz: none with CPU rendering (no manager), the host resamples its output",
          void *, h_up_Allocate, void *mgr) {
    static void *(*orig)(void *); if (!orig) orig = gsym("_ZN2nn5audio6server16UpsamplerManager8AllocateEv");
    return mgr ? orig(mgr) : NULL;
}
enum { RS_T = 16 };
static uint32_t up_3_2(const int32_t *in, uint32_t n, int32_t *out) {
    static float h[3][RS_T]; static int32_t buf[2 * (RS_T - 1 + FRAME_MAX)]; static uint32_t pos;   // pos: the next output's position in thirds of an input sample, from the frame start
    if (!h[0][RS_T / 2 - 1]) {
        for (int p = 0; p < 3; p++) {
            double sum = 0, w[RS_T];
            for (int k = 0; k < RS_T; k++) {
                double x = p / 3.0 + RS_T / 2 - 1 - k, fc = 0.9, s = x == 0 ? fc : sin(M_PI * fc * x) / (M_PI * x);
                double t = (x + RS_T / 2) / RS_T, win = 0.42 - 0.5 * cos(2 * M_PI * t) + 0.08 * cos(4 * M_PI * t);
                w[k] = s * win; sum += w[k];
            }
            for (int k = 0; k < RS_T; k++) h[p][k] = (float)(w[k] / sum);
        }
    }
    memcpy(buf + 2 * (RS_T - 1), in, (size_t)n * 8);   // the frame after the last RS_T - 1 samples of the one before (copied first: out may be in)
    uint32_t m = 0;
    for (; pos < 3 * n; pos += 2, m++) {
        const int32_t *x = buf + 2 * (pos / 3); const float *c = h[pos % 3]; float l = 0, r = 0;
        for (int k = 0; k < RS_T; k++) { l += c[k] * (float)x[2 * k]; r += c[k] * (float)x[2 * k + 1]; }
        out[2 * m] = (int32_t)lrintf(l); out[2 * m + 1] = (int32_t)lrintf(r);
    }
    pos -= 3 * n;
    memmove(buf, buf + 2 * n, (RS_T - 1) * 8);
    return m;
}
static int capture(const uint8_t *cl, size_t len, int16_t *dst) {
    uint32_t ncmd = *(const uint32_t *)(cl + 4), samples = *(const uint32_t *)(cl + 0x14), rate = *(const uint32_t *)(cl + 0x18);
    const int32_t *mix = *(const int32_t *const *)(cl + 8); const uint8_t *cend = cl + len;
    static int logged, hist_logged, layout_done;
    if (!layout_done && ncmd) {   // first command list: the header layout (SDK 21's first lists have 4 commands or fewer, which the old layout would report as malformed)
        layout_done = 1;
        if (!cmd_walks(cl, ncmd, cend)) { g_hdr6 = 1; if (cmd_walks(cl, ncmd, cend)) { g_sink_id = 0x10; LOG("audren: command headers in the SDK 6 layout (+4 size, +6 command ID, +0x10 enabled), DeviceSink is ID 0x10"); } else g_hdr6 = 0; }
        if (!g_hdr6 && !cmd_walks(cl, ncmd, cend)) { g_hdr21 = 1; if (cmd_walks(cl, ncmd, cend)) LOG("audren: command headers in the SDK 21 layout (no magic: +0 enabled, +1 command ID, +2 size), DeviceSink 0x140 bytes"); else g_hdr21 = 0; }
    }
    if (!hist_logged && ncmd > 4) {   // first full command list: command ID histogram and the sink ID check
        hist_logged = 1;
        int hist[64] = {0}, cand = -1, ncand = 0, cand4 = -1, ncand4 = 0, sink_sz = 0x148 + sink_body(); const uint8_t *q = cl + 0x20; char line[512]; size_t o = 0;
        for (uint32_t i = 0; i < ncmd && cmd_ok(q, cend); i++) {
            int sz = cmd_size(q); hist[cmd_id(q) & 63]++;
            if (sz == sink_sz && *(const uint32_t *)(q + 0x130 + sink_body()) - 1 < 6) { cand = cmd_id(q); ncand++; }
            if (g_hdr6 && sz == 0x130 && *(const uint32_t *)(q + 0x120) - 1 < 6) { cand4 = cmd_id(q); ncand4++; }
            q += sz;
        }
        for (int k = 0; k < 64; k++) if (hist[k]) o += snprintf(line + o, sizeof line - o, " %d×%d", k, hist[k]);
        LOG("audren: first-frame commands %u, by command ID: %s", ncmd, line);
        if (!hist[g_sink_id & 63] && ncand == 1) { LOG("audren: no command with ID %#x in the command list; the only 0x148-byte command is ID %#x, taking it as DeviceSink", g_sink_id, cand); g_sink_id = cand; }
        else if (!hist[g_sink_id & 63] && !ncand && ncand4 == 1) { LOG("audren: no command with ID %#x in the command list; the only 0x130-byte command is ID %#x, taking it as the SDK 4 DeviceSink", g_sink_id, cand4); g_sink_id = cand4; g_sink4 = 1; }
        else if (!hist[g_sink_id & 63]) LOG("[RED] audren: no DeviceSink (ID %#x) in the command list, %d commands of %#x bytes: the game will have no sound", g_sink_id, ncand, sink_sz);
    }
    const uint8_t *c = cl + 0x20;
    for (uint32_t i = 0; i < ncmd; i++) {
        int size = cmd_size(c);
        if (!cmd_ok(c, cend)) { if (!logged++) LOG("[RED] audren: command %u in the command list is malformed (first word %#x size %d)", i, *(const uint32_t *)c, size); return 0; }
        if (cmd_id(c) == g_sink_id && cmd_on(c)) {
            const uint8_t *b = c + sink_body();
            uint32_t nin = *(const uint32_t *)(b + (g_sink4 ? 0x120 : 0x130)); int16_t in[6];
            for (uint32_t j = 0; j < 6; j++) in[j] = g_sink4 ? ((const int8_t *)(b + 0x124))[j] : ((const int16_t *)(b + 0x134))[j];
            const int32_t *up = *(const int32_t *const *)(b + (g_sink4 ? 0x118 : 0x128));
            if (!logged++) LOG("audren: DeviceSink inputs %u (%d %d …) rate %u samples per frame %u mix buffer %p upsample buffer %p%s", nin, nin ? in[0] : -1, nin > 1 ? in[1] : -1, rate, samples, (void *)mix, (void *)up,
                               rate == 32000 ? " (32 kHz: resampled to 48 kHz on the host)" : "");
            if (!nin || samples > (rate == 48000 ? FRAME_MAX : FRAME_MAX * 2 / 3 - 1)) return 0;
            if (rate != 48000 && rate != 32000) { static int once; if (!once++) LOG("[RED] audren: renderer sample rate %u (the SDK takes 32000 or 48000): no sound", rate); return 0; }
            // Input buffers: the mix buffers the DeviceSink inputs name, at the renderer's rate (with CPU rendering the SDK makes no upsampler, below)
            const int32_t *ch[6]; uint32_t k = nin > 6 ? 6 : nin;
            for (uint32_t j = 0; j < k; j++) ch[j] = mix + (size_t)in[j] * samples;
            // Channel order front-left front-right center LFE back-left back-right; 6 → 2 with the system default downmix coefficients (front 1.0, center 0.707, LFE 0.251, back 0.707, Q15)
            enum { CF = 32768, CC = 23167, CL = 8224, CB = 23167 };
            static int32_t st[FRAME_MAX * 2];   // the stereo downmix before clamping (capture runs under g_render_mu)
            for (uint32_t s = 0; s < samples; s++) {
                int32_t l, r;
                if (k >= 6) { int64_t c = (int64_t)ch[2][s] * CC + (int64_t)ch[3][s] * CL;
                              l = (int32_t)(((int64_t)ch[0][s] * CF + c + (int64_t)ch[4][s] * CB) >> 15); r = (int32_t)(((int64_t)ch[1][s] * CF + c + (int64_t)ch[5][s] * CB) >> 15); }
                else { l = ch[0][s]; r = k > 1 ? ch[1][s] : l; }
                st[2 * s] = l; st[2 * s + 1] = r;
            }
            uint32_t nout = rate == 48000 ? samples : up_3_2(st, samples, st);
            uint64_t loud = 0;
            for (uint32_t s = 0; s < 2 * nout; s++) {
                int32_t v = st[s]; v = v > 32767 ? 32767 : v < -32768 ? -32768 : v;
                dst[s] = (int16_t)v; loud |= (uint64_t)v;
            }
            atomic_fetch_add(&g_frames, 1); if (loud) atomic_fetch_add(&g_nonsilent, 1);
            uint64_t f = atomic_load(&g_frames); if (f == 200 || f % 12000 == 0) LOG("audren: rendered %llu frames, %llu of them not silent", (unsigned long long)f, (unsigned long long)atomic_load(&g_nonsilent));
            return (int)nout;
        }
        c += size;
    }
    return 0;
}
// Render one frame (generate commands + CPU processing); dst = output (stereo int16, *nout samples). The two callers (render thread, catch-up) are serialized by a host lock: they share one command buffer
static pthread_mutex_t g_render_mu = PTHREAD_MUTEX_INITIALIZER;
static _Atomic uint64_t g_updates, g_renders, g_catchups;
// SDK 20's AudioRenderSystem (The Legend of Zelda: Breath of the Wild 1.9.0) has another layout: the member functions for mode / device / command buffer are inlined (sdk+0x22a70c ExecuteAudioRendererRendering:
//   +0x48 rendering device and +0x4c execution mode, both must be 1; +0xf8 / +0x100 command buffer start / length), GenerateCommand takes a Span<std::byte> and returns a Span (x0 start, x1 length: the generated command list),
//   and the mutex UpdateImpl holds is at +0x2d0 (+0x1f0 in SDK 16). The layout is recognized by the presence of the Span version of GenerateCommand
typedef struct { uint8_t *p; size_t n; } span_t;
// The update lock before SDK 19 is the one AudioRenderSystem::Update takes at its start, read off the SDK's own code: nn::os::LockMutex(MutexType*) at +0x1d0 in SDK 4.4.0 (Mario Kart 8 Deluxe)
//   and at +0x1d8 in SDK 6.4–8.2, SdkMutexType::Lock at +0x1d8 in SDK 10.4–11.4, +0x1f0 in 13.3–16.2, +0x1f8 in 17.5 (the sdk builds of 24 games on hand; 19 and 20 inline it).
//   The SDK 16 layout (SdkMutexType at +0x1f0) taken for all of them wrote a lock word into another field and excluded nothing. The prologue copies `this`, adds the offset into a register
//   and moves it to x0 before the first bl, a PLT stub whose GOT slot the loader bound to one of the two functions; anything else keeps the SDK 16 layout
static void *plt_target(const uint32_t *pc) {   // adrp x16, page; ldr x17, [x16, #off]; …; br x17 → the function the GOT slot holds; else the address itself
    uint32_t a = pc[0], l = pc[1];
    if ((a & 0x9f00001fu) != 0x90000010u || (l & 0xffc003ffu) != 0xf9400211u) return (void *)pc;
    int64_t imm = (int64_t)((uint64_t)((((a >> 5) & 0x7ffff) << 2) | ((a >> 29) & 3)) << 43) >> 31;   // immhi:immlo, sign-extended, × 4 KB
    uint64_t page = ((uint64_t)pc & ~0xfffull) + (uint64_t)imm;
    return *(void **)(page + ((l >> 10) & 0xfff) * 8);
}
static int update_lock(size_t *off, int *os_mutex) {
    const uint32_t *f = guest_sym("_ZN2nn5audio6server17AudioRenderSystem6UpdateEPvmS3_mPKvm");
    void *lock_mutex = guest_sym("_ZN2nn2os9LockMutexEPNS0_9MutexTypeE"), *sdk_lock = guest_sym("_ZN2nn2os12SdkMutexType4LockEv");
    if (!f) return 0;
    uint32_t self = 1u << 0; int64_t add[32]; for (int r = 0; r < 32; r++) add[r] = -1;
    int64_t x0 = -1;
    for (int i = 0; i < 24; i++) {
        uint32_t w = f[i]; int rd = w & 31, rn = (w >> 5) & 31, rm = (w >> 16) & 31;
        if ((w & 0xffe0ffe0u) == 0xaa0003e0u) {   // mov xd, xm
            if (self >> rm & 1) self |= 1u << rd;
            if (rd == 0) x0 = add[rm];
            else add[rd] = add[rm];
        } else if ((w & 0xffc00000u) == 0x91000000u && rn != 31 && (self >> rn & 1)) {   // add xd, x<this>, #imm12
            add[rd] = (w >> 10) & 0xfff; if (rd == 0) x0 = add[rd];
        } else if ((w & 0xfc000000u) == 0x94000000u) {   // the first bl
            void *t = plt_target(f + i + (int32_t)(w << 6) / 64);
            if (x0 < 0 || !t || (t != lock_mutex && t != sdk_lock)) return 0;
            *off = (size_t)x0; *os_mutex = t == lock_mutex; return 1;
        }
    }
    return 0;
}
static Result render_once(uint8_t *sys, int16_t *dst, int *nout) {
    static int (*mode)(void *), (*device)(void *); static uint8_t *(*cbuf)(void *); static size_t (*cbsz)(void *), (*gen)(void *, void *, size_t);
    static span_t (*gen20)(void *, void *, size_t); static size_t mu_off;
    static void (*tmax)(void *, uint32_t), (*setup)(void *, const void *, size_t), (*process)(void *), (*lock)(void *), (*unlock)(void *);
    if (!process) {
        lock = gsym("_ZN2nn2os12SdkMutexType4LockEv"); unlock = gsym("_ZN2nn2os12SdkMutexType6UnlockEv");
        gen20 = guest_sym("_ZN2nn5audio6server17AudioRenderSystem15GenerateCommandENS_4SpanISt4byteEE");
        if (!gen20) {
            mode = gsym("_ZNK2nn5audio6server17AudioRenderSystem16GetExecutionModeEv"); device = gsym("_ZNK2nn5audio6server17AudioRenderSystem18GetRenderingDeviceEv");
            cbuf = gsym("_ZNK2nn5audio6server17AudioRenderSystem26GetCommandBufferWorkBufferEv"); cbsz = gsym("_ZNK2nn5audio6server17AudioRenderSystem30GetCommandBufferWorkBufferSizeEv");
            gen = gsym("_ZN2nn5audio6server17AudioRenderSystem15GenerateCommandEPvm");
        }
        mu_off = gen20 ? 0x2d0 : 0x1f0; g_sink_id = gen20 ? 0x14 : 0x13;
        int os_mutex = 0;
        if (!gen20 && update_lock(&mu_off, &os_mutex) && os_mutex) { lock = gsym("_ZN2nn2os9LockMutexEPNS0_9MutexTypeE"); unlock = gsym("_ZN2nn2os11UnlockMutexEPNS0_9MutexTypeE"); }
        LOG("audren: AudioRenderSystem in the SDK %s layout (update lock %s at +%#zx)", gen20 ? "20" : "16", os_mutex ? "nn::os::MutexType" : "SdkMutexType", mu_off);
        tmax = gsym("_ZN2nn5audio3dsp20CommandListProcessor17SetProcessTimeMaxEj"); setup = gsym("_ZN2nn5audio3dsp20CommandListProcessor5SetupEPKvm");
        process = gsym("_ZN2nn5audio3dsp20CommandListProcessor7ProcessEv");
    }
    int md = gen20 ? *(int32_t *)(sys + 0x4c) : mode(sys), dv = gen20 ? *(int32_t *)(sys + 0x48) : device(sys);
    if (md != 1 || dv != 1) return 0x40299;   // same as the original: manual rendering needs CPU + manual mode
    pthread_mutex_lock(&g_render_mu);
    uint64_t t0 = now_ns();
    // Command generation must exclude the game thread's RequestUpdate (AudioRenderSystem::Update, which holds the mutex at mu_off): the original system thread (SendCommandToDsp) generates under the same lock. Processing (the CPU DSP) stays outside the lock, as in the original.
    uint8_t *cl; size_t n;
    lock(sys + mu_off);
    if (gen20) { span_t sp = gen20(sys, *(uint8_t **)(sys + 0xf8), *(size_t *)(sys + 0x100)); cl = sp.p; n = sp.n; }
    else { cl = cbuf(sys); n = gen(sys, cl, cbsz(sys)); }
    unlock(sys + mu_off);
    atomic_fetch_add(&g_renders, 1);
    uint64_t proc[16] __attribute__((aligned(16))) = {0};   // CommandListProcessor (0x58 bytes; 0x60 in SDK 21, whose Process reads +0x58)
    tmax(proc, 0xffffffffu); setup(proc, cl, n); process(proc);
    if (dst) *nout = capture(cl, n, dst);
    // render time per frame (budget: 5 ms per frame): mean / max every 2000 frames
    static uint64_t sum, mx, cnt; uint64_t d = now_ns() - t0; sum += d; if (d > mx) mx = d;
    if (++cnt == 2000) { LOG("audren: render time mean %.2f ms / max %.2f ms (5 ms budget per frame, last 2000 frames); catch-up renders so far %llu", sum / 2000 / 1e6, mx / 1e6, (unsigned long long)atomic_load(&g_catchups)); sum = mx = cnt = 0; }
    pthread_mutex_unlock(&g_render_mu);
    return RES_OK;
}
HLE_FORCE("_ZN2nn5audio6server24AudioRendererManagerImpl17AudioRendererImpl29ExecuteAudioRendererRenderingEv", "render one frame manually: SDK generates commands + CPU processing, the host takes the DeviceSink output",
          Result, h_ar_ImplExec, uint8_t *impl) { return render_once(impl + 0x60, g_frame, &g_frame_n); }   // the AudioRenderSystem sits at +0x60 inside AudioRendererImpl
// SDK 20 (The Legend of Zelda: Breath of the Wild 1.9.0): the public ExecuteAudioRendererRendering (sdk+0x221e90) calls the AudioRendererImpl version through the vtable (sdk+0x22ca88, just x0 += 0x60 and a jump through the PLT to here),
// so the takeover above is never reached. The original generates commands here without the update lock; with the render thread on the original and the game thread's RequestUpdate rendering catch-up frames ("nothing rendered since the last update"),
// both sides wrote the same command buffer at once and CommandListProcessor::Process sometimes read a half-written command list and crashed (once at 60 s and once at 134 s in the shrine). Taken over here, so rendering goes through the host's locked render as well
HLE_FORCE("_ZN2nn5audio6server17AudioRenderSystem29ExecuteAudioRendererRenderingEv", "render one frame manually (SDK 20, reached through the PLT): same as above, through the host's locked rendering",
          Result, h_ars_Exec, uint8_t *sys) { return render_once(sys, g_frame, &g_frame_n); }

// At least one rendered frame between two RequestUpdates. An effect's "needs initialization" is set only by the update in which the game flags it as new (e.g. LightLimiterInfo::Update: isNew → parameter status 0);
// the next update overwrites it with the game's value; if no commands were generated in between, the DSP never receives the initialization and ApplyLightLimiterEffect later reads a null pointer. The real renderer produces a frame every 5 ms on its own
// and the game waits on the renderer event, so normally it is one update per frame; but at start-up the game updates twice in a row (0.04 ms apart), and with real audio output the sound card opens slowly and the first frame comes late, leaving no frame in between.
// Here, when nothing has been rendered since the last update, one catch-up frame is rendered on the current thread first; its output is queued and played by the render thread in place of its next render.
// Catch-up frames keep real time: when the queue is full the update waits for the render thread to play one (at most 50 ms). Square Enix's sound driver (DRAGON QUEST XI S,
// SQEX::Sd::Driver::Core::CoreAudioOut, main+0x442c5c0) refills its voices in a loop: append a wave buffer, RequestUpdate, take the next released buffer, and it waits on the renderer
// event only when none is released. Every update there renders a catch-up frame, which releases the buffer just appended, so the loop never waits: discarding catch-up output when the
// queue was full ran it at 11,000 updates a second on one core, the game's sound about 50 times faster than the output. Waiting keeps the queue full instead: the render thread then
// only plays queued frames, and the driver's own updates render every frame. A queued frame does not signal the renderer event: the SDK signals it in each render, and a game woken by a
// queued frame updated with nothing rendered since, which rendered the next catch-up frame, and so on (Big Brain Academy: Brain vs. Brain, one catch-up per update instead of 37 a run)
HLE_FORCE("_ZN2nn5audio6server17AudioRenderSystem6UpdateEPvmS3_mPKvm", "renderer update: if nothing was rendered since the last update, render a catch-up frame first (so effects get initialized), then hand over to the SDK",
          Result, h_ars_Update, uint8_t *sys, void *o, size_t on, void *p, size_t pn, const void *in, size_t inn) {
    static Result (*orig)(void *, void *, size_t, void *, size_t, const void *, size_t); if (!orig) orig = gsym("_ZN2nn5audio6server17AudioRenderSystem6UpdateEPvmS3_mPKvm");
    static uint64_t last_r = ~0ull;
    uint64_t u = atomic_fetch_add(&g_updates, 1) + 1, r = atomic_load(&g_renders);
    if (last_r != ~0ull && r == last_r) {
        uint64_t c = atomic_fetch_add(&g_catchups, 1) + 1;
        if (c <= 3) LOG("audren: no frame rendered before update %llu, rendering a catch-up frame first (%llu so far)", (unsigned long long)u, (unsigned long long)c);
        uint32_t t = atomic_load(&g_cq_tail);
        if (t - atomic_load(&g_cq_head) >= CQ) {   // queue full: wait for the render thread to play one (every 5 ms), so catch-up frames keep real time; at most 50 ms (an output that stopped)
            struct timespec dl; clock_gettime(CLOCK_REALTIME, &dl); dl.tv_nsec += 50000000; if (dl.tv_nsec >= 1000000000) { dl.tv_sec++; dl.tv_nsec -= 1000000000; }
            pthread_mutex_lock(&g_cq_mu);
            while (atomic_load(&g_open) && t - atomic_load(&g_cq_head) >= CQ && !pthread_cond_timedwait(&g_cq_cv, &g_cq_mu, &dl)) {}
            pthread_mutex_unlock(&g_cq_mu);
        }
        if (t - atomic_load(&g_cq_head) < CQ) { render_once(sys, g_cq[t % CQ], &g_cq_n[t % CQ]); atomic_store(&g_cq_tail, t + 1); }
        else render_once(sys, NULL, NULL);   // still full (the output stopped): render anyway, output discarded
    }
    static int tr = -1; if (tr < 0) tr = getenv("DM_AUDREN_TRACE") != NULL;
    if (tr && u < 200) LOG("audren update #%llu t=%.3f ms, %llu frames rendered since the last update", (unsigned long long)u, now_ns() / 1e6, (unsigned long long)(atomic_load(&g_renders) - (last_r == ~0ull ? 0 : last_r)));
    Result res = orig(sys, o, on, p, pn, in, inn);
    last_r = atomic_load(&g_renders);
    return res;
}

// Output device: one built-in speaker, stereo (the audio device service still goes through IPC; the host answers directly)
enum { DEVNAME = 0x100 };
static void out_devname(char *out) { memset(out, 0, DEVNAME); strlcpy(out, "AudioBuiltInSpeakerOutput", DEVNAME); }
HLE_FORCE("_ZN2nn5audio25ListAudioOutputDeviceNameEPNS0_15AudioDeviceNameEi", "output device list: 1 built-in speaker", int, h_ad_List, char *names, int n) { if (n <= 0 || !names) return 0; out_devname(names); return 1; }
HLE_FORCE("_ZN2nn5audio30GetActiveAudioOutputDeviceNameEPNS0_15AudioDeviceNameE", "active output device: built-in speaker", void, h_ad_Active, char *name) { out_devname(name); }
// The SDK 12 names (Engage): the same service, the same answers
HLE_FORCE("_ZN2nn5audio19ListAudioDeviceNameEPNS0_15AudioDeviceNameEi", "output device list (SDK 12 name): 1 built-in speaker", int, h_ad_List12, char *names, int n) { return h_ad_List(names, n); }
HLE_FORCE("_ZN2nn5audio24GetActiveAudioDeviceNameEPNS0_15AudioDeviceNameE", "active output device (SDK 12 name): built-in speaker", void, h_ad_Active12, char *name) { out_devname(name); }
HLE_FORCE("_ZN2nn5audio41GetActiveAudioDeviceChannelCountForOutputEv", "output channel count: 2", int, h_ad_Channels, void) { return 2; }
// Device volume: kept and read back (DRAGON QUEST VII Reimagined reads it at boot, main+0xee8edc), not applied: the host's output has the system's own volume
static _Atomic float g_dev_vol = 1.0f;
HLE_FORCE("_ZN2nn5audio26SetAudioDeviceOutputVolumeEPKNS0_15AudioDeviceNameEf", "device volume; kept for the getter, not applied by the host", void, h_ad_SetVol, const char *name, float v) { (void)name; atomic_store(&g_dev_vol, v); }
HLE_FORCE("_ZN2nn5audio26GetAudioDeviceOutputVolumeEPKNS0_15AudioDeviceNameE", "device volume: what the game last set (1.0 at start)", float, h_ad_GetVol, const char *name) { (void)name; return atomic_load(&g_dev_vol); }
HLE_FORCE("_ZN2nn5audio39AcquireAudioDeviceNotificationForOutputEPNS_2os11SystemEventE", "output device change notification: bound, never signaled", void, h_ad_Notify, void *ev) { hle_bind_system_event(ev, 0); }
// SDK 7 names of the two above (Super Mario Odyssey): the device-switch event and the active channel count
HLE_FORCE("_ZN2nn5audio36AcquireAudioDeviceSwitchNotificationEPNS_2os11SystemEventE", "output device switch notification (SDK 7 name): bound, never signaled", void, h_ad_Notify7, void *ev) { hle_bind_system_event(ev, 0); }
HLE_FORCE("_ZN2nn5audio21GetActiveChannelCountEv", "output channel count (SDK 7 name): 2", int, h_ad_Channels7, void) { return 2; }

// ---------- audren:u service (SDK 1: the renderer lives in the service) ----------
// The client never sends a DSP command list over IPC: RequestUpdate (cmd 4) delivers
// UpdateData — the renderer's new state (voices, mixes, sinks, effects) in one input
// buffer, with two output buffers for the status and the performance data — and the
// server generates the DSP command list itself. So this service keeps the renderer's
// state, answers the manager and renderer commands (hle_audren_sf.c), and drives the
// output: v3 returns success with the output buffers zeroed (silent) and paces the
// game's audio thread to real time through the PCM output, so the game runs; the
// mixer over UpdateData (voices → stereo) is the next step, not this one, because the
// REV1 UpdateData section layouts were not recovered (the first update's header is
// logged below, so v4 can read the real values instead of guessing them).
// Event: the server's event (auto-clear on the device) is signaled at Start and at
// the end of each RequestUpdate — "the previous frame is done, the next update may
// run". The host kernel never clears an event on wait, so the update clears it at
// its start; the pacing itself comes from the PCM output, not from the event.
static uint32_t audren_align(uint32_t x, uint32_t a) { return (x + a - 1) & ~(a - 1); }
// Work buffer size, the service's formula (yuzu audren_u.cpp GetAudioRendererWorkBufferSize,
// REV1: no splitter, no variadic command buffer, performance v1). p is the parameter as
// the client sent it: rate, samples per frame, mix buffers, submixes, voices, sinks,
// effects, performance frames, ... unknown_30, revision (13 u32s, 0x34 bytes)
size_t audren_work_buffer_size(const uint32_t *p) {
    uint32_t count = p[1], mix = p[2], submix = p[3], voice = p[4], sink = p[5], effect = p[6];
    uint32_t perf_frames = p[7], unknown_30 = p[11];
    uint32_t mix_buffers = audren_align((mix + 6) * 4 * count + (mix + 6) * 4 * 240 + (submix + sink), 64)
                         + audren_align(unknown_30, 64) + audren_align(4 * mix, 64);
    uint32_t mix_info = audren_align(8 * (submix + 1), 16) + audren_align(0x940 * (submix + 1), 16) + audren_align(4 * 256 * submix, 16);
    uint32_t voice_info = audren_align(8 * voice, 16) + audren_align(0x220 * voice, 16) + audren_align(0xD0 * voice, 16) + audren_align(0x100 * voice, 16);
    uint32_t total = mix_buffers + mix_info + voice_info + 0x48 + audren_align(4 * (effect + voice) * 0x20, 16);
    total = audren_align(total, 64);
    total += audren_align(0x280 * (submix + sink), 64) + audren_align(0x2B0 * effect, 16) + audren_align(0x170 * sink, 16) + audren_align(0x100 * voice + 63, 16);
    uint32_t entries = effect + submix + sink + voice + 1;
    uint32_t size_per_frame = 0x18 + 0x10 * entries + 0x10 * 100;
    total += audren_align(size_per_frame * perf_frames + 1, 64) + audren_align(0x0C, 64) + 128;
    total += 0x18000 + 126;   // command buffer, REV1 fixed size (no variadic)
    return audren_align(total, 4096);
}
static uint32_t g_svc_rate, g_svc_count, g_svc_mix;   // the opened renderer's parameter (getters, pacing)
static _Atomic int g_svc_started;                     // Start / Stop (GetState: 0 started, 1 stopped)
static uint32_t g_svc_ev_w, g_svc_ev_r;               // the renderer's system event, write end kept, read end for QuerySystemEvent
static void *g_svc_game_ev;                           // the game's SystemEvent passed to OpenAudioRenderer (recorded; the host signals its own event)
static void *g_svc_pcm;                               // PCM output for the mixed frames (opened on the first update)
static uint64_t g_svc_updates;
// Per-voice playback state for the SDK 1 mixer (AUDREN-SDK1-SPEC.md §2/§5):
// which wave-buffer slot is being consumed and how far into it, plus the two
// counters the client reads back through OutStatus (played samples, released
// wave buffers). The client refills a slot only after the server reports it
// released, and the server only runs when the client sends a RequestUpdate,
// so the ring stays in lockstep and the server never laps the client.
typedef struct { uint32_t wb_idx, wb_off; uint64_t played, released; } svc_voice;
static svc_voice g_svc_voice[8];

// ---------- service catch-up (I am Setsuna, v5) ----------
// The game's audio clock runs at ~0.37x real time: its only RequestUpdate
// path is the per-frame pump (main+0xc56c88, at most 3 updates per 30 fps
// frame = 90/s, observed ~74/s), while the device consumes 200 frames/s,
// and the loop that would wait on the renderer event is dead (the
// SetVoicePlayState/GetVoicePlayState paradox — AUDREN-SDK1-SPEC.md §8 and
// PORT-NOTES.md "Teste v4"). A deeper output queue cannot help (the queue
// is starved, not full) and signalling the event faster cannot help
// (nothing waits on it). So the host renders the missing frames itself:
// a catch-up thread on a 5 ms cadence re-renders 240-sample frames from
// the last UpdateData the game sent and appends them to the same PCM
// output, advancing the same per-voice playback state the game's own
// updates advance. The next RequestUpdate's OutStatus then reports the
// advanced played/released counters, so the game's pump refills the wave
// buffers the host actually consumed. This follows the SDK >= 2 precedent
// (the g_cq catch-up queue in render_once/h_ars_Update above), adapted:
// there is no render thread and no command list here — the UpdateData
// snapshot IS the renderer's state.
// (a) Serialization: g_svc_mu below plays the role g_render_mu plays for
// the SDK >= 2 path — one lock for the whole service mixer. The game's
// RequestUpdate and the catch-up thread both render under it, so the
// voice state, the snapshot and the PCM output's free list are never
// touched by two threads at once. The two paths never run in the same
// game (audren_server_side() selects one), so the lock is separate from
// g_render_mu: the state it protects is disjoint.
// (b) Lifetime: the cadence only runs while the renderer is started.
// Start spawns the thread (a previous one was joined by Stop, or never
// existed); Stop joins it; Close stops it again (idempotent) and closes
// the PCM output. Nothing is appended after the output is closed, and no
// thread survives the renderer: the thread never blocks holding the lock
// (its append is non-blocking — see svc_pcm_append), so a join always
// completes within one 5 ms tick.
// (c) No race with the game's refills: the game refills a wave-buffer
// slot only after the server reported it released, and the server only
// reports a slot released after fully consuming it — at which point its
// own ring index has already moved past the slot. The catch-up thread
// reads the frozen snapshot, never a slot the game is refilling, and the
// descriptors it uses are corrected by the next RequestUpdate's fresh
// UpdateData. Between updates the game never touches the UpdateData
// buffer itself (it is regenerated only by RequestUpdate), so the
// snapshot stays valid until the next update replaces it.
// (d) Before the first RequestUpdate there is no snapshot yet, so the
// thread skips its ticks and emits nothing — the renderer is not started
// before then anyway (the game's init calls RequestUpdate before Start).
// Once a snapshot exists the thread appends every tick, silence when no
// voice has data: the device zero-pads an underrun with the same silence,
// but a fed queue keeps the underrun count at ~0 and the audio clock at
// real time, which is what the next log has to show.
static pthread_mutex_t g_svc_mu = PTHREAD_MUTEX_INITIALIZER;
static uint8_t g_svc_snap[65536]; static size_t g_svc_snap_len; static _Atomic int g_svc_snap_valid;
static _Atomic int g_svc_cu_run;      // the catch-up thread runs while set (Start sets, Stop/Close clears)
static _Atomic uint64_t g_svc_cu_frames, g_svc_cu_skipped;
static pthread_t g_svc_cu_thr; static int g_svc_cu_alive;
static int g_svc_opened;              // a service renderer is open (Close is a no-op otherwise)
static int16_t g_svc_frame[FRAME_MAX * 2];   // the frame being mixed / appended (both paths hold g_svc_mu)
static float g_svc_mixbuf[8][FRAME_MAX];     // float mix buffers, one per mix (this game: 2)
static int16_t g_svc_data[NBUF][FRAME_MAX * 2]; static gbuf g_svc_bufs[NBUF]; static gbuf *g_svc_free[NBUF]; static int g_svc_nfree = -1;   // the PCM output's buffer pool (was function-static in audren_svc_update)

// The UpdateData sections (AUDREN-SDK1-SPEC.md §1-2): the 0x40-byte header
// is a table of section sizes; the sections follow in fixed order.
typedef struct {
    uint32_t rev, sz_beh, sz_mp, sz_voice, sz_vcr, sz_fx, sz_mix, sz_sink, sz_perf, sz_split, total;
    size_t off_beh, off_mp, off_vcr, off_voice, off_fx, off_split, off_mix, off_sink;
    uint32_t n_mp, n_vcr, n_voice, n_fx, n_mix, n_sink;
} svc_sections;
static int svc_parse(const uint8_t *in, size_t in_len, svc_sections *d) {
    if (!in || in_len < 0x40) return 0;
    const uint32_t *h = (const uint32_t *)in;
    d->rev = h[0]; d->sz_beh = h[1]; d->sz_mp = h[2]; d->sz_voice = h[3]; d->sz_vcr = h[4];
    d->sz_fx = h[5]; d->sz_mix = h[6]; d->sz_sink = h[7]; d->sz_perf = h[8]; d->sz_split = h[9]; d->total = h[15];
    d->off_beh = 0x40; d->off_mp = d->off_beh + d->sz_beh; d->off_vcr = d->off_mp + d->sz_mp; d->off_voice = d->off_vcr + d->sz_vcr;
    d->off_fx = d->off_voice + d->sz_voice; d->off_split = d->off_fx + d->sz_fx; d->off_mix = d->off_split + d->sz_split;
    d->off_sink = d->off_mix + d->sz_mix;
    d->n_mp = d->sz_mp / 0x20; d->n_vcr = d->sz_vcr / 0x70; d->n_voice = d->sz_voice / 0x170;
    d->n_fx = d->sz_fx / 0xc0; d->n_mix = d->sz_mix / 0x930; d->n_sink = d->sz_sink / 0x140;
    return 1;
}
// Mix one frame (n samples) from an UpdateData image into dst (stereo
// int16). Advances g_svc_voice[].played/released/wb_idx/wb_off exactly as
// the game's RequestUpdate does; the caller holds g_svc_mu. `in` is either
// the live UpdateData of a RequestUpdate or the snapshot the catch-up
// thread renders from. u is the game's update number (0 for a catch-up
// frame: the first-updates diagnostics are logged only for real updates).
static void svc_mix_frame(const uint8_t *in, size_t in_len, int16_t *dst, uint32_t n, uint64_t u) {
    svc_sections d;
    if (!svc_parse(in, in_len, &d)) { memset(dst, 0, (size_t)n * 4); return; }
    // Mix buffers, one float accumulator per mix buffer (this game: 2).
    memset(g_svc_mixbuf, 0, sizeof g_svc_mixbuf);
    uint32_t nmix = d.n_mix ? d.n_mix : 1; if (nmix > 8) nmix = 8;
    float mix_vol = 1.0f;
    if (in && d.off_mix + 4 <= in_len) mix_vol = *(const float *)(in + d.off_mix);   // Mix+0x00 volume
    for (uint32_t vi = 0; vi < d.n_voice && vi < 8; vi++) {
        if (d.off_voice + vi * 0x170 + 0x170 > in_len) break;
        const uint8_t *v = in + d.off_voice + vi * 0x170;
        if (!v[0x09]) continue;   // not in use
        if (v[0x0b] != 2) { if (u && u <= 3) LOG("audren: voice %u format %u not PCM16, skipped", vi, v[0x0b]); continue; }
        if (*(const uint32_t *)(v + 0x0c) != g_svc_rate || *(const uint32_t *)(v + 0x18) != 2) {
            if (u && u <= 3) LOG("audren: voice %u rate %u ch %u (expected %u/2)", vi,
                *(const uint32_t *)(v + 0x0c), *(const uint32_t *)(v + 0x18), g_svc_rate);
        }
        float vvol = *(const float *)(v + 0x20);   // voice volume
        // Channel-resource volumes: the VCR entry for each input channel holds
        // the volumes to each mix buffer at +0x04 (one float per output).
        float cvol[2][8];
        for (int c = 0; c < 2; c++) {
            uint32_t rid = *(const uint32_t *)(v + 0x140 + c * 4);
            const uint8_t *e = NULL;
            for (uint32_t k = 0; k < d.n_vcr; k++) {
                const uint8_t *cand = in + d.off_vcr + k * 0x70;
                if (d.off_vcr + k * 0x70 + 0x70 > in_len) break;
                if (*(const uint32_t *)cand == rid) { e = cand; break; }
            }
            for (uint32_t m = 0; m < nmix; m++) {
                if (e && (size_t)(e - in) + 0x04 + m * 4 + 4 <= in_len) cvol[c][m] = *(const float *)(e + 0x04 + m * 4);
                else cvol[c][m] = (c == (int)m) ? 1.0f : 0.0f;   // stereo identity fallback
            }
        }
        // Consume n samples from the wave-buffer ring (4 x 0x38 entries at
        // +0x60). A slot is empty when its pointer is NULL or end <= start; a
        // finished slot is released and the ring advances. The client refills
        // a slot only after the server reports it released, so the ring stays
        // in lockstep; a starved voice contributes silence for the frame.
        svc_voice *st = &g_svc_voice[vi];
        for (uint32_t s = 0; s < n; s++) {
            int16_t smp[2] = {0, 0};
            for (int tries = 0; tries < 4; tries++) {
                if (st->wb_idx >= 4) st->wb_idx = 0;
                const uint8_t *wb = v + 0x60 + st->wb_idx * 0x38;
                if (d.off_voice + vi * 0x170 + 0x60 + st->wb_idx * 0x38 + 0x38 > in_len) break;
                uint64_t ptr = *(const uint64_t *)(wb + 0x00);
                uint32_t start = *(const uint32_t *)(wb + 0x10), end = *(const uint32_t *)(wb + 0x14);
                uint8_t loop = wb[0x18];
                // The frames the slot holds, clamped to the buffer's own size
                // so a bad end offset can never read past the wave buffer.
                uint32_t cap = (uint32_t)(*(const uint64_t *)(wb + 0x08) / 4);
                uint32_t avail = 0;
                if (end > start && start < cap) { uint32_t e = end < cap ? end : cap; avail = e - start; }
                if (!ptr || !avail) { st->wb_idx++; st->wb_off = 0; continue; }   // empty slot: skip
                if (st->wb_off >= avail) {
                    if (loop) st->wb_off = 0;
                    else { st->released++; st->wb_idx++; st->wb_off = 0; continue; }
                }
                const int16_t *pcm = (const int16_t *)(uintptr_t)ptr;
                uint32_t pos = start + st->wb_off;
                smp[0] = pcm[pos * 2]; smp[1] = pcm[pos * 2 + 1];
                st->wb_off++;
                break;
            }
            for (uint32_t m = 0; m < nmix; m++)
                g_svc_mixbuf[m][s] += ((float)smp[0] * cvol[0][m] + (float)smp[1] * cvol[1][m]) * vvol;
        }
        st->played += n;
    }
    for (uint32_t s = 0; s < n; s++) {
        for (uint32_t m = 0; m < nmix && m < 2; m++) {
            float v = g_svc_mixbuf[m][s] * mix_vol / 32768.0f;
            if (v > 1.0f) v = 1.0f; else if (v < -1.0f) v = -1.0f;
            dst[s * 2 + m] = (int16_t)(v * 32767.0f);
        }
        if (nmix == 1) dst[s * 2 + 1] = dst[s * 2];
    }
}
// Append one mixed frame to the service PCM output (caller holds g_svc_mu).
// wait: the game's own RequestUpdate waits for a free buffer, so a game
// updating faster than real time paces itself on the output. The catch-up
// thread never waits: when no buffer is free right now the frame is
// skipped — but only the frame: the caller must restore the voice state
// the mix advanced (svc_catchup_main snapshots g_svc_voice and restores it
// on failure). Skipping the state advance as well as the frame would jump
// 5 ms ahead in the wave data on every skip — one pop per skip, ~100/s
// when the queue is full (v5 log: played ran 962880 samples ahead of the
// rendered frames, ~ the 4017 skipped ticks). Returns 1 if the frame was queued.
static int svc_pcm_append(const int16_t *dst, uint32_t n, int wait) {
    if (!g_svc_pcm) { g_svc_pcm = dm_pcmout_open(2); dm_pcmout_start(g_svc_pcm); }
    if (g_svc_nfree < 0) { for (int i = 0; i < NBUF; i++) { g_svc_bufs[i] = (gbuf){g_svc_data[i], sizeof g_svc_data[i], 0}; g_svc_free[i] = &g_svc_bufs[i]; } g_svc_nfree = NBUF; }
    gbuf *b = NULL;
    while (!b) {
        if (g_svc_nfree) { b = g_svc_free[--g_svc_nfree]; break; }
        b = dm_pcmout_released(g_svc_pcm);
        if (!b) { if (!wait) return 0; dm_pcmout_wait(g_svc_pcm, 10); }
    }
    memcpy(b->ptr, dst, (size_t)n * 4);
    b->size = (uint64_t)n * 4;
    if (!dm_pcmout_append(g_svc_pcm, b)) { g_svc_free[g_svc_nfree++] = b; return 0; }
    return 1;
}
// The catch-up thread: one frame every 5 ms (200/s, the device's rate)
// from the last UpdateData, while the renderer is started. Absolute
// deadlines, so the cadence cannot drift; a tick that wakes late resyncs
// to now instead of rendering a burst of backlogged frames. The game
// keeps rendering its own ~74/s on top; the output queue caps the total
// at what the device consumes, and a tick that finds no free buffer
// skips its frame — the voice state the mix advanced is restored, so
// the next tick re-mixes the same 240 samples instead of jumping 5 ms
// ahead in the wave data (a skipped tick must never advance the voice
// state: v5 popped ~100/s that way). While the game is held (pause menu,
// background, text entry) the thread freezes outright: the game clock is
// frozen too, so advancing the voice would put the audio seconds ahead
// of the game's wave buffers on resume (v6: +144000 samples over one
// menu open). A plain pthread, not a guest thread: it calls no guest
// thread: it calls no guest code (only plain reads of guest memory and
// host calls — dm_pcmout_* and hle_signal_event, both safe on any thread),
// and spawning a guest thread would shift the game's thread handles.
static void *svc_catchup_main(void *arg) {
    (void)arg;
    struct timespec next;
    clock_gettime(CLOCK_MONOTONIC, &next);
    int frozen = 0;   // the game is held (pause menu / background / text entry): the voice stays frozen until it goes on
    for (;;) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (next.tv_sec < now.tv_sec || (next.tv_sec == now.tv_sec && next.tv_nsec <= now.tv_nsec)) next = now;   // late tick: resync, no burst
        next.tv_nsec += 5000000;
        if (next.tv_nsec >= 1000000000) { next.tv_sec++; next.tv_nsec -= 1000000000; }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, NULL);
        if (!atomic_load(&g_svc_cu_run)) break;
        if (!atomic_load(&g_svc_started) || !atomic_load(&g_svc_snap_valid)) continue;
        uint32_t n = g_svc_count && g_svc_count <= FRAME_MAX ? g_svc_count : 240;
        uint64_t f = 0, played0 = 0, released0 = 0, played1 = 0, released1 = 0, updates = 0;
        int appended = 0;
        pthread_mutex_lock(&g_svc_mu);
        if (atomic_load(&g_svc_cu_run) && atomic_load(&g_svc_started) && atomic_load(&g_svc_snap_valid)) {
            if (dm_audio_is_suspended()) {
                // Held: the game clock is frozen, so advancing the voice here would put the audio seconds ahead of the game's wave buffers on
                // resume (v6: +144000 samples over one menu open, a jump or silence until the game refills). Freeze the voice state instead;
                // the silent sink drains the queued audio meanwhile, and the next RequestUpdate after the hold refreshes the snapshot.
                if (!frozen) { frozen = 1; LOG("audren: catch-up frozen while the game is held (voice0 played %llu)", (unsigned long long)g_svc_voice[0].played); }
            } else {
                if (frozen) { frozen = 0; LOG("audren: catch-up resumed after hold (voice0 played %llu)", (unsigned long long)g_svc_voice[0].played); }
                // A skipped tick must not advance the voice state: svc_mix_frame
                // consumes the wave-buffer ring (played/released/wb_idx/wb_off)
                // as it mixes, so the state is snapshotted first and restored
                // when the append fails — the next tick then re-mixes the same
                // 240 samples instead of jumping 5 ms ahead in the wave data.
                svc_voice saved[8];
                memcpy(saved, g_svc_voice, sizeof saved);
                svc_mix_frame(g_svc_snap, g_svc_snap_len, g_svc_frame, n, 0);
                appended = svc_pcm_append(g_svc_frame, n, 0);
                if (appended) {
                    f = atomic_fetch_add(&g_svc_cu_frames, 1) + 1;
                    played0 = g_svc_voice[0].played; released0 = g_svc_voice[0].released;
                    played1 = g_svc_voice[1].played; released1 = g_svc_voice[1].released;
                    updates = g_svc_updates;
                    if (g_svc_ev_w) hle_signal_event(g_svc_ev_w);
                } else { memcpy(g_svc_voice, saved, sizeof saved); atomic_fetch_add(&g_svc_cu_skipped, 1); }
            }
        }
        pthread_mutex_unlock(&g_svc_mu);
        if (appended && f == 1) LOG("audren: catch-up started (5 ms cadence)");
        else if (appended && f % 200 == 0)
            // drift: voice0 played minus the frames actually rendered
            // (game updates + catch-up frames) x samples per frame — 0
            // when every rendered frame advanced the voice by exactly
            // one frame and no skipped tick did (v5: +962880 samples,
            // ~ the skipped ticks, one 5 ms jump in the wave per skip).
            LOG("audren: catch-up frame #%llu (game updates %llu, voice0 played %llu released %llu, voice1 played %llu released %llu, skipped %llu, drift %lld)",
                (unsigned long long)f, (unsigned long long)updates,
                (unsigned long long)played0, (unsigned long long)released0,
                (unsigned long long)played1, (unsigned long long)released1,
                (unsigned long long)atomic_load(&g_svc_cu_skipped),
                (long long)played0 - (long long)(updates + f) * (long long)n);
    }
    return NULL;
}
void audren_svc_open(const uint32_t *param) {
    g_svc_rate = param[0]; g_svc_count = param[1]; g_svc_mix = param[2];
    atomic_store(&g_svc_started, 0);
    memset(g_svc_voice, 0, sizeof g_svc_voice);
    atomic_store(&g_svc_snap_valid, 0); g_svc_snap_len = 0;   // a snapshot from a previous renderer must never be rendered
    g_svc_opened = 1;
    g_svc_ev_w = hle_create_event(&g_svc_ev_r);
    LOG("audren: service renderer opened rate %u samples %u mix %u submix %u voices %u sinks %u effects %u perf frames %u revision %#x (event read %#x)",
        param[0], param[1], param[2], param[3], param[4], param[5], param[6], param[7], param[12], g_svc_ev_r);
}
void audren_svc_note_game_event(void *ev) { g_svc_game_ev = ev; (void)g_svc_game_ev; }
uint32_t audren_svc_rate(void) { return g_svc_rate; }
uint32_t audren_svc_count(void) { return g_svc_count; }
uint32_t audren_svc_mix(void) { return g_svc_mix; }
uint32_t audren_svc_state(void) { return atomic_load(&g_svc_started) ? 0 : 1; }   // GetState: 0 Started, 1 Stopped
void audren_svc_start(void) {
    atomic_store(&g_svc_started, 1);
    if (g_svc_ev_w) hle_signal_event(g_svc_ev_w);
    LOG("audren: service renderer started");
    if (!atomic_exchange(&g_svc_cu_run, 1)) {   // (re)start the catch-up cadence; a previous thread was joined by Stop, or never existed
        if (pthread_create(&g_svc_cu_thr, NULL, svc_catchup_main, NULL)) {
            atomic_store(&g_svc_cu_run, 0);
            LOG("[RED] audren: catch-up thread did not start (audio keeps the game's own ~74 updates/s)");
        } else g_svc_cu_alive = 1;
    }
}
void audren_svc_stop(void) {
    atomic_store(&g_svc_started, 0);
    if (atomic_exchange(&g_svc_cu_run, 0) && g_svc_cu_alive) { pthread_join(g_svc_cu_thr, NULL); g_svc_cu_alive = 0; }   // the thread never blocks holding the lock, so this completes within one 5 ms tick
    LOG("audren: service renderer stopped");
}
// The renderer is closed: stop the cadence (idempotent — Stop already
// joined the thread) and close the PCM output, so nothing is appended
// after the game is done with the renderer and no thread survives it.
// Called from the SDK's CloseAudioRenderer takeover and from the
// audren:u renderer's session close, whichever the game reaches first.
void audren_svc_close(void) {
    if (!g_svc_opened) return;
    g_svc_opened = 0;
    audren_svc_stop();
    pthread_mutex_lock(&g_svc_mu);
    atomic_store(&g_svc_snap_valid, 0); g_svc_snap_len = 0;
    if (g_svc_pcm) { dm_pcmout_close(g_svc_pcm); g_svc_pcm = NULL; g_svc_nfree = -1; }
    pthread_mutex_unlock(&g_svc_mu);
    LOG("audren: service renderer closed");
}
uint32_t audren_svc_event_read(void) { return g_svc_ev_r; }
// RequestUpdate: the SDK 1 UpdateData (AUDREN-SDK1-SPEC.md §2/§6). There is no
// DSP command list in SDK 1: the client sends the state of its voices, mixes,
// sinks and memory pools, and the server renders from it. Parse the 0x40-byte
// header (section sizes), mix the in-use PCM16 stereo voices through their
// channel-resource volumes into the FinalMix buffers, write the OutStatus the
// client verifies (header sizes, mempool states, per-voice played/released
// counters), pace the call to real time with one mixed frame per update, and
// signal the event. Effects, splitters, submixes, ADPCM and biquad are out of
// scope: this game uses none of them (0 effects, 0 splitters, 0 submixes,
// PCM16 only, biquad parameters always zero).
Result audren_svc_update(const uint8_t *in, size_t in_len, uint8_t *out, size_t out_len, uint8_t *perf, size_t perf_len) {
    uint64_t u = ++g_svc_updates;
    uint32_t n = g_svc_count && g_svc_count <= FRAME_MAX ? g_svc_count : 240;   // samples per frame
    // UpdateData header (0x40): revision, then the section sizes in order
    // behavior, mempool, voice, VCR, effects, mix, sink, perf, splitter.
    svc_sections d = {0};
    svc_parse(in, in_len, &d);
    if (u == 1) {
        if (in && in_len >= 0x40) {
            char hx[3 * 0x40 + 1] = ""; for (size_t k = 0; k < 0x40; k++) snprintf(hx + 3 * k, 4, "%02x ", in[k]);
            LOG("audren: first RequestUpdate, %zu bytes in, rev %#x beh %u mp %u vcr %u voice %u fx %u mix %u sink %u perf %u split %u total %u: %s",
                in_len, d.rev, d.sz_beh, d.sz_mp, d.sz_vcr, d.sz_voice, d.sz_fx, d.sz_mix, d.sz_sink, d.sz_perf, d.sz_split, d.total, hx);
            if (d.n_voice && d.off_voice + 0x170 <= in_len) {
                const uint8_t *v0 = in + d.off_voice;
                LOG("audren: voice0 inuse %u fmt %u rate %u ch %u pitch %g vol %g res %08x/%08x q %u wb0 ptr %llx size %llu start %u end %u loop %u",
                    v0[0x09], v0[0x0b], *(const uint32_t *)(v0 + 0x0c), *(const uint32_t *)(v0 + 0x18),
                    *(const float *)(v0 + 0x1c), *(const float *)(v0 + 0x20),
                    *(const uint32_t *)(v0 + 0x140), *(const uint32_t *)(v0 + 0x144), *(const uint32_t *)(v0 + 0x3c),
                    (unsigned long long)*(const uint64_t *)(v0 + 0x60), (unsigned long long)*(const uint64_t *)(v0 + 0x68),
                    *(const uint32_t *)(v0 + 0x70), *(const uint32_t *)(v0 + 0x74), v0[0x78]);
            }
            if (d.n_vcr && d.off_vcr + 0x70 <= in_len) {
                const uint8_t *e0 = in + d.off_vcr, *e1 = d.n_vcr > 1 ? in + d.off_vcr + 0x70 : e0;
                LOG("audren: vcr0 id %08x vols %g %g %g %g, vcr1 id %08x vols %g %g %g %g",
                    *(const uint32_t *)e0, *(const float *)(e0 + 0x04), *(const float *)(e0 + 0x08),
                    *(const float *)(e0 + 0x0c), *(const float *)(e0 + 0x10),
                    *(const uint32_t *)e1, *(const float *)(e1 + 0x04), *(const float *)(e1 + 0x08),
                    *(const float *)(e1 + 0x0c), *(const float *)(e1 + 0x10));
            }
            if (d.n_mix && d.off_mix + 0x0c <= in_len)
                LOG("audren: mix0 vol %g rate %u bufs %u inuse %u", *(const float *)(in + d.off_mix),
                    *(const uint32_t *)(in + d.off_mix + 4), *(const uint32_t *)(in + d.off_mix + 8), (in + d.off_mix)[0x0c]);
            if (d.n_sink && d.off_sink + 0x140 <= in_len)
                LOG("audren: sink0 type %u node %08x name %.16s ch %u map %u %u", *(const uint32_t *)(in + d.off_sink),
                    *(const uint32_t *)(in + d.off_sink + 4), (const char *)(in + d.off_sink + 0x10),
                    *(const uint32_t *)(in + d.off_sink + 0x110), (in + d.off_sink)[0x114], (in + d.off_sink)[0x115]);
        } else LOG("audren: first RequestUpdate, %zu bytes in (shorter than the 0x40 header)", in_len);
    }
    if (out && out_len) memset(out, 0, out_len);
    if (perf && perf_len) memset(perf, 0, perf_len);
    if (g_svc_ev_w) hle_clear_event(g_svc_ev_w);
    // One frame under the service lock: snapshot this UpdateData for the
    // catch-up thread, mix it, and report the counters (which now include
    // the catch-up frames rendered since the last update) in the OutStatus
    // below. The lock is held across the mix, the OutStatus write and the
    // append, so a catch-up frame can never slip in between the mix and
    // the status the game reads back.
    pthread_mutex_lock(&g_svc_mu);
    if (in && in_len >= 0x40 && in_len <= sizeof g_svc_snap) { memcpy(g_svc_snap, in, in_len); g_svc_snap_len = in_len; atomic_store(&g_svc_snap_valid, 1); }
    svc_mix_frame(in, in_len, g_svc_frame, n, u);
    // OutStatus (0x1c0 for this game): header + Mempool + Voice + Effects +
    // Sink + Perf + Behavior. The client verifies every section size against
    // the header and the total against its config; any divergence fails the
    // update with 0x5299. Mempool 0 (the real pool) stays at state 4 once
    // attached; pools 1..7 are never attached, so their state stays 0.
    // Voice: played and released counters per voice. Sink and Perf are
    // zeroed; Behavior is 22 x u64 of zero (no error).
    if (out && out_len >= 0x40) {
        uint32_t os_beh = 0xb0, os_mp = d.n_mp * 0x10, os_voice = d.n_voice * 0x10, os_fx = d.n_fx * 0x10;
        uint32_t os_sink = d.n_sink * 0x20, os_perf = 0x10;
        uint32_t os_total = 0x40 + os_beh + os_mp + os_voice + os_fx + os_sink + os_perf;
        uint32_t *oh = (uint32_t *)out;
        oh[0] = 0x3256; oh[1] = os_beh; oh[2] = os_mp; oh[3] = os_voice; oh[4] = 0;
        oh[5] = os_fx; oh[6] = 0; oh[7] = os_sink; oh[8] = os_perf; oh[9] = 0; oh[15] = os_total;
        size_t o_mp = 0x40, o_voice = o_mp + os_mp, o_fx = o_voice + os_voice;
        size_t o_sink = o_fx + os_fx, o_perf = o_sink + os_sink, o_beh = o_perf + os_perf;
        (void)o_fx; (void)o_sink; (void)o_perf; (void)o_beh;   // zeroed by the memset above
        for (uint32_t k = 0; k < d.n_mp && o_mp + k * 0x10 + 4 <= out_len; k++)
            *(uint32_t *)(out + o_mp + k * 0x10) = (k == 0) ? 4 : 0;
        for (uint32_t k = 0; k < d.n_voice && o_voice + k * 0x10 + 16 <= out_len; k++) {
            *(uint64_t *)(out + o_voice + k * 0x10) = g_svc_voice[k].played;
            *(uint64_t *)(out + o_voice + k * 0x10 + 8) = g_svc_voice[k].released;
        }
    }
    // Pace: one mixed frame per update (the game's sample count, stereo
    // int16). The output hands each block back in real time, so a game that
    // updates faster than real time blocks here instead of spinning.
    svc_pcm_append(g_svc_frame, n, 1);
    pthread_mutex_unlock(&g_svc_mu);
    if (g_svc_ev_w) hle_signal_event(g_svc_ev_w);
    if (u <= 3 || (u % 1200) == 0)
        LOG("audren: RequestUpdate #%llu done (%u samples, voice0 played %llu released %llu, voice1 played %llu released %llu)",
            (unsigned long long)u, n,
            (unsigned long long)g_svc_voice[0].played, (unsigned long long)g_svc_voice[0].released,
            (unsigned long long)g_svc_voice[1].played, (unsigned long long)g_svc_voice[1].released);
    return RES_OK;
}

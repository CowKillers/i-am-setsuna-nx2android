// The audren:u IPC service for I am Setsuna (Unity 5.5, SDK 1), implemented
// in the host because this game connects to the service itself through HIPC
// and the SDK 1 app carries no server-side audio code (AUDREN-SDK1-SPEC.md).
// The manager (object 0 on a plain session, 1 after ConvertSessionToDomain)
// answers OpenAudioRenderer and GetAudioRendererWorkBufferSize; the renderer
// object (id 2, returned by OpenAudioRenderer) answers the IAudioRenderer
// commands and mixes the SDK 1 UpdateData into stereo PCM. On a plain session
// the renderer comes back as a move handle (a new host session serving object
// 2); in a domain it comes back as the domain object id 2. h_sf_Send routes
// here when the session name is "audren:u"; olsc:u keeps the generic zeroed
// response.
// DIVERGENCE from upstream: upstream serves only olsc:u (and only with the
// generic zeroed response); this whole service is new, for this game.
#include "hle.h"
#include "dm.h"
#include <string.h>

#define AUDREN_OBJ_MGR 1u
#define AUDREN_OBJ_RENDERER 2u
static uint32_t g_render_limit = 100;   // SetRenderingTimeLimit / GetRenderingTimeLimit (default 100)

// The response: no descriptors; the data words start at +8 (or +0x10 with a
// copy-handle special header) and the CMIF part at +0x10, or +0x20 in a
// domain session (the domain header {u32 objects, 3 x u32} sits before it).
// Returns the CMIF header; the payload starts at o[4]. copy_handle is 0 for
// a plain response, the read end of the event for QuerySystemEvent;
// move_handle is 0 for a plain response, the renderer session for
// OpenAudioRenderer on a plain session (the client takes the object from the
// move handle when the session is not a domain).
static uint32_t *audren_resp(uint8_t *base, uint64_t size, int dom, uint32_t ver, uint32_t copy_handle, uint32_t move_handle) {
    uint32_t *m = (uint32_t *)base;
    size_t end = size < 0x1000 ? size : 0x1000;
    memset(base, 0, end);
    unsigned ncopy = copy_handle ? 1 : 0, nmove = move_handle ? 1 : 0;
    size_t data = 8 + ((ncopy + nmove) ? 4 + 4 * (ncopy + nmove) : 0);   // the data section starts after the HIPC header and the special header (flags word + one word per handle)
    m[1] = (uint32_t)((end - data) / 4 < 0x3ff ? (end - data) / 4 : 0x3ff);
    if (ncopy + nmove) {   // special header: copy handles first, then move handles; no PID
        m[1] |= 1u << 31; m[2] = (ncopy << 1) | (nmove << 5);
        unsigned k = 3; if (ncopy) m[k++] = copy_handle; if (nmove) m[k++] = move_handle;
    }
    size_t out = 0x10 + (dom ? 16 : 0);
    uint32_t *o = (uint32_t *)(base + out);
    o[0] = 0x4f434653; o[1] = ver; o[2] = RES_OK; o[3] = 0;   // "SFCO"
    return o;
}

// An A/B/W buffer descriptor (Switchbrew HIPC): word 0 is the low 32 bits of
// the size, word 1 the low 32 bits of the address, word 2 the flags (bits
// 0-1), the address bits 36-38 (bits 2-4), the size bits 32-35 (bits 24-27)
// and the address bits 32-35 (bits 28-31). The descriptors run X, then A,
// then B, then W; audren sends no X descriptors.
static void audren_desc(const uint32_t *d, uint64_t *addr, uint64_t *sz) {
    uint32_t w0 = d[0], w1 = d[1], w2 = d[2];
    *sz = (uint64_t)w0 | ((uint64_t)((w2 >> 24) & 0xf) << 32);
    *addr = (uint64_t)w1 | ((uint64_t)((w2 >> 28) & 0xf) << 32) | ((uint64_t)((w2 >> 2) & 0x7) << 36);
}

uint32_t audren_sf_handle(uint8_t *base, uint64_t size, int dom, unsigned obj, unsigned cmd, unsigned ndw, uint32_t ver, size_t hin, const uint32_t *desc, unsigned na, unsigned nb) {
    (void)ndw;
    // The manager (object 0 on a plain session, 1 in a domain — h_sf_Send
    // resolves the effective object): cmd 1 on the plain session (before
    // ConvertSessionToDomain), cmd 0 on either. On a plain session the
    // renderer comes back as a move handle (a new host session serving
    // object 2); in a domain it comes back as the domain object id 2.
    if (obj == 0 || obj == AUDREN_OBJ_MGR) {
        if (cmd == 1) {   // GetAudioRendererWorkBufferSize: param at hin+0x10, 13 words
            const uint32_t *param = (const uint32_t *)(base + hin + 0x10);
            uint32_t sz = audren_work_buffer_size(param);
            LOG("audren: work buffer size %u (0x%x) for rate %u samples %u mix %u submix %u voices %u sinks %u effects %u perf %u rev %#x",
                sz, sz, param[0], param[1], param[2], param[3], param[4], param[5], param[6], param[7], param[12]);
            uint32_t *o = audren_resp(base, size, dom, ver, 0, 0);
            *(uint64_t *)(o + 4) = sz;   // u64 size right after the SFCO header
            return RES_OK;
        }
        if (cmd == 0) {   // OpenAudioRenderer: param at hin+0x10, then 4 zero bytes, u64 work buffer size, u64 AppletResourceUserId
            const uint32_t *param = (const uint32_t *)(base + hin + 0x10);
            uint64_t wbsz = *(const uint64_t *)(base + hin + 0x10 + 0x38);
            LOG("audren: OpenAudioRenderer, work buffer %llu bytes, AppletResourceUserId %llu",
                (unsigned long long)wbsz, (unsigned long long)*(const uint64_t *)(base + hin + 0x10 + 0x40));
            audren_svc_open(param);
            if (!dom) {
                // Plain session (this game never converts to a domain): the
                // renderer comes back as a move handle — a new host session
                // serving object 2 — as Horizon's CMIF server returns an
                // object on a plain session. The id is also written in the
                // first data word after SFCO, as in the domain reply
                // (belt-and-braces; AUDREN-SDK1-SPEC.md §7.3).
                uint32_t rh = sf_new_session_obj("audren:u", AUDREN_OBJ_RENDERER);
                uint32_t *o = audren_resp(base, size, dom, ver, 0, rh);
                o[4] = AUDREN_OBJ_RENDERER;
                LOG("audren: OpenAudioRenderer returned renderer session %#x (plain, move handle)", rh);
                return RES_OK;
            }
            uint32_t *o = audren_resp(base, size, dom, ver, 0, 0);
            // The renderer object: id 2, both in the response domain header
            // (count 1, then the id) and in the first data word after the
            // SFCO header, since the exact position the client reads was not
            // fully pinned in the disassembly (AUDREN-SDK1-SPEC.md §7.3).
            uint32_t *dh = (uint32_t *)(base + 0x10); dh[0] = 1; dh[1] = AUDREN_OBJ_RENDERER;
            o[4] = AUDREN_OBJ_RENDERER;
            LOG("audren: OpenAudioRenderer returned renderer object 2 (domain)");
            return RES_OK;
        }
        if (cmd == 2) { LOG("audren: GetAudioDeviceService (no caller in this game)"); return RES_FAIL; }
        LOG("[RED] audren: manager command %u not implemented", cmd);
        return RES_FAIL;
    }
    if (obj == AUDREN_OBJ_RENDERER) {   // the renderer session (plain, move-handle) or object 2 of the manager's domain
        switch (cmd) {
        case 0: { uint32_t *o = audren_resp(base, size, dom, ver, 0, 0); o[4] = audren_svc_rate(); return RES_OK; }   // GetSampleRate
        case 1: { uint32_t *o = audren_resp(base, size, dom, ver, 0, 0); o[4] = audren_svc_count(); return RES_OK; }   // GetSampleCount
        case 2: { uint32_t *o = audren_resp(base, size, dom, ver, 0, 0); o[4] = audren_svc_mix(); return RES_OK; }   // GetMixBufferCount
        case 3: { uint32_t *o = audren_resp(base, size, dom, ver, 0, 0); o[4] = audren_svc_state(); return RES_OK; }   // GetState
        case 4: {   // RequestUpdate: A[0] UpdateData in, B[0] OutStatus out, B[1] perf out
            if (na < 1 || nb < 2) { LOG("[RED] audren: RequestUpdate with %u A / %u B descriptors", na, nb); return RES_FAIL; }
            uint64_t in_addr, in_sz, out_addr, out_sz, perf_addr, perf_sz;
            audren_desc(desc, &in_addr, &in_sz);                    // A[0]: the input
            audren_desc(desc + 3 * na, &out_addr, &out_sz);         // B[0]: OutStatus
            audren_desc(desc + 3 * na + 3, &perf_addr, &perf_sz);   // B[1]: performance
            uint32_t r = audren_svc_update((const uint8_t *)(uintptr_t)in_addr, (size_t)in_sz,
                                           (uint8_t *)(uintptr_t)out_addr, (size_t)out_sz,
                                           (uint8_t *)(uintptr_t)perf_addr, (size_t)perf_sz);
            uint32_t *o = audren_resp(base, size, dom, ver, 0, 0);   // SFCO + Result only
            o[2] = r;
            return r;
        }
        case 5: audren_svc_start(); audren_resp(base, size, dom, ver, 0, 0); return RES_OK;   // Start
        case 6: audren_svc_stop(); audren_resp(base, size, dom, ver, 0, 0); return RES_OK;   // Stop
        case 7:   // QuerySystemEvent: one copy handle (the event's read end)
            audren_resp(base, size, dom, ver, audren_svc_event_read(), 0);
            return RES_OK;
        case 8:   // SetRenderingTimeLimit: u32 in
            g_render_limit = *(const uint32_t *)(base + hin + 0x10);
            LOG("audren: rendering time limit %u", g_render_limit);
            audren_resp(base, size, dom, ver, 0, 0);
            return RES_OK;
        case 9: { uint32_t *o = audren_resp(base, size, dom, ver, 0, 0); o[4] = g_render_limit; return RES_OK; }   // GetRenderingTimeLimit
        default:
            LOG("[RED] audren: renderer command %u not implemented", cmd);
            return RES_FAIL;
        }
    }
    LOG("[RED] audren: command %u on object %u (domain %d) not implemented", cmd, obj, dom);
    return RES_FAIL;
}

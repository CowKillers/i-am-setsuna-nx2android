# Survey: I am Setsuna. (0100849000BDA000)

- display version: 1.1.0
- base: `I am Setsuna [+UPDATE 1.1.0] [0100849000BDA000].nsp` (1.31 GB)

## Code modules

| module | what | build ID | text / ro / data (MB) | bss (MB) |
|---|---|---|---|---|
| main | the game | `0bba2167aed893be965dd8333864df4e` | 24.2 / 11.9 / 1.9 | 38.9 |
| rtld | rtld | `591b9846f4d95155823bd73e76be7230` | 0.0 / 0.0 / 0.0 | 0.0 |
| sdk | nnSdk | `9f841a6521ca02a184ce694c3e242519` | 2.8 / 3.7 / 0.2 | 0.6 |
| subsdk0 | NintendoSDK_movie | `ad84f543dad22836a94c58daae35ea3a` | 2.9 / 2.4 / 0.2 | 1.1 |
| subsdk1 | NintendoSDK_libcurl | `fb6cf575030b172de196a929241cac7f` | 0.2 / 0.1 / 0.0 | 0.0 |
| subsdk2 | NintendoSDK_NVN | `3612d66d30dbd18d6cce211138c88eec` | 0.7 / 0.7 / 0.1 | 0.1 |
| subsdk3 | nn::sf | `8a052b6dfd9b1515a85882ad8699869d` | 0.1 / 0.1 / 0.0 | 0.0 |

- main.npdm program ID: `0100849000BDA000`
- system resource: 0 MB
- SDK: 1_3_1 (Mario: 16_2_0 / 16_2_12)
- SDK components: NintendoSDK_NVN-1_3_1, NintendoSDK_libcurl-1_3_1, NintendoSDK_movie-1_3_1, NintendoSdk_nnSdk-1_3_1
- engine and API markers: NVN (main, subsdk2); Unity IL2CPP (main); Unity (main); Unreal (main); CRIWARE (main); FMOD (main)

## RomFS: 951 files, 1.28 GB

| top level | files | GB |
|---|---|---|
| Data/StreamingAssets | 911 | 1.18 |
| Data | 36 | 0.09 |
| Data/Managed | 2 | 0.01 |
| Data/Resources | 2 | 0.00 |

| header magic | files | GB |
|---|---|---|
| CRI UTF | 279 | 0.06 |
| UnityFS | 145 | 0.73 |
| CRI CPK | 2 | 0.15 |

extensions: `.out` 396, `.acb` 278, `(none)` 165, `.awb` 89, `.assets` 18, `.dat` 2, `.cpk` 2, `.acf` 1

## Rewrite-site census (insn_census.py)

```
/home/hatch/workspace/nx2a-iamsetsuna/0100849000BDA000_survey/extract/ExeFS/main: 6354364 instructions decoded (80 undecodable words)
  x18 use                14856
/home/hatch/workspace/nx2a-iamsetsuna/0100849000BDA000_survey/extract/ExeFS/sdk: 735676 instructions decoded (11 undecodable words)
  x18 use                4999
  svc                    104
  mrs tpidrro_el0        47
  mrs cntpct_el0         33
  msr tpidr_el0          3
  mrs tpidr_el0          2
  svc numbers: #1 x1, #2 x1, #3 x1, #4 x1, #5 x1, #6 x1, #7 x1, #8 x1, #9 x1, #0xa x1, #0xb x1, #0xc x1, #0xd x1, #0xe x1, #0xf x1, #0x10 x1, #0x11 x1, #0x12 x1, #0x13 x1, #0x14 x1, #0x15 x1, #0x16 x1, #0x17 x1, #0x18 x1, #0x19 x1, #0x1a x1, #0x1b x1, #0x1c x1, #0x1d x1, #0x1e x1, #0x1f x1, #0x20 x1, #0x21 x1, #0x22 x1, #0x23 x1, #0x24 x1, #0x25 x1, #0x26 x1, #0x27 x1, #0x28 x1, #0x29 x1, #0x2a x1, #0x2b x1, #0x2f x1, #0x30 x1, #0x31 x1, #0x32 x1, #0x33 x1, #0x3c x1, #0x40 x1, #0x41 x1, #0x42 x1, #0x43 x1, #0x44 x1, #0x45 x1, #0x4d x1, #0x4e x1, #0x4f x1, #0x50 x1, #0x51 x1, #0x52 x1, #0x53 x1, #0x54 x1, #0x55 x1, #0x56 x1, #0x57 x1, #0x58 x1, #0x59 x1, #0x5a x1, #0x5b x1, #0x5c x1, #0x5d x1, #0x5e x1, #0x5f x1, #0x60 x1, #0x61 x1, #0x62 x1, #0x63 x1, #0x64 x1, #0x65 x1, #0x66 x1, #0x67 x1, #0x68 x1, #0x69 x1, #0x6a x1, #0x6b x1, #0x6c x1, #0x6d x1, #0x70 x1, #0x71 x1, #0x72 x1, #0x73 x1, #0x74 x1, #0x75 x1, #0x76 x1, #0x77 x1, #0x78 x1, #0x79 x1, #0x7a x1, #0x7b x1, #0x7c x1, #0x7d x1, #0x7e x1, #0x7f x1
/home/hatch/workspace/nx2a-iamsetsuna/0100849000BDA000_survey/extract/ExeFS/subsdk0: 769605 instructions decoded (7 undecodable words)
  x18 use                6916
/home/hatch/workspace/nx2a-iamsetsuna/0100849000BDA000_survey/extract/ExeFS/subsdk1: 43241 instructions decoded (6 undecodable words)
  x18 use                108
/home/hatch/workspace/nx2a-iamsetsuna/0100849000BDA000_survey/extract/ExeFS/subsdk2: 188047 instructions decoded (7 undecodable words)
  x18 use                834
/home/hatch/workspace/nx2a-iamsetsuna/0100849000BDA000_survey/extract/ExeFS/subsdk3: 14584 instructions decoded (6 undecodable words)
  x18 use                49
```

## Imports vs host hooks (nso_syms.py)

```
HLE hooks in host: 965 | present in new game sdk exports: 529
main imports: old game 0, new game 564, shared 0, new-only 564

new game main imports by area (total / not imported by old game):
  C / libc                 169 /  169
  other C++                110 /  110
  nn::os                    74 /   74
  nn::audio                 41 /   41
  nn::socket                32 /   32
  nn::fs                    29 /   29
  nn::hid                   20 /   20
  nn::oe                    14 /   14
  nn::account               12 /   12
  nn::nifm                   9 /    9
  nn::mem                    9 /    9
  nn::swkbd                  8 /    8
  nn::ngc                    7 /    7
  nn::diag                   6 /    6
  nn::time                   6 /    6
  nn::vi                     5 /    5
  nn::pl                     3 /    3
  nn::util                   2 /    2
  nn::aoc                    2 /    2
  nn::settings               1 /    1
  nn::ssl                    1 /    1
  nvn                        1 /    1
  nn::crypto                 1 /    1
  nn::err                    1 /    1
  nn::pctl                   1 /    1

examples of new-only imports per area:
  C / libc: _Unwind_Resume, __assert_fail, __cxa_allocate_exception, __cxa_begin_catch
  other C++: _ZN2nv18InitializeGraphicsEPvm, _ZN2nv20SetGraphicsAllocatorEPFPvmmS0_EPFvS0_S0_EPFS0_S0_mS0_ES0_, _ZN2nv28SetGraphicsDevtoolsAllocatorEPFPvmmS0_EPFvS0_S0_EPFS0_S0_mS0_ES0_, _ZN5movie12SetAllocatorEPFPvmmS0_EPFvS0_S0_EPFS0_S0_mS0_ES0_
  nn::os: _ZN2nn2os10ClearEventEPNS0_9EventTypeE, _ZN2nn2os10WaitThreadEPNS0_10ThreadTypeE, _ZN2nn2os11FreeTlsSlotENS0_7TlsSlotE, _ZN2nn2os11GetHostArgcEv
  nn::audio: _ZN2nn5audio12StopAudioOutEPNS0_8AudioOutE, _ZN2nn5audio13AddDeviceSinkEPNS0_19AudioRendererConfigEPNS0_14DeviceSinkTypeEPNS0_12FinalMixTypeEPKaiPKc, _ZN2nn5audio13CloseAudioOutEPNS0_8AudioOutE, _ZN2nn5audio13ListAudioOutsEPNS0_12AudioOutInfoEi
  nn::socket: _ZN2nn6socket10GetSockOptEiiiPvPj, _ZN2nn6socket10InitializeEPvmmi, _ZN2nn6socket10SetSockOptEiiiPKvj, _ZN2nn6socket11GAIStrErrorEi
  nn::fs: _ZN2nn2fs10CreateFileEPKcl, _ZN2nn2fs10DeleteFileEPKc, _ZN2nn2fs10RenameFileEPKcS2_, _ZN2nn2fs11GetFileSizeEPlNS0_10FileHandleE
  nn::hid: _ZN2nn3hid12GetNpadStateEPNS0_16NpadFullKeyStateERKj, _ZN2nn3hid12GetNpadStateEPNS0_16NpadJoyDualStateERKj, _ZN2nn3hid12GetNpadStateEPNS0_17NpadHandheldStateERKj, _ZN2nn3hid13GetNpadStatesEPNS0_16NpadFullKeyStateEiRKj
  nn::oe: _ZN2nn2oe10InitializeEv, _ZN2nn2oe16GetOperationModeEv, _ZN2nn2oe18GetDesiredLanguageEv, _ZN2nn2oe18GetPerformanceModeEv
  nn::account: _ZN2nn7account10InitializeEv, _ZN2nn7account11GetNicknameEPNS0_8NicknameERKNS0_3UidE, _ZN2nn7account12AsyncContext14GetSystemEventEPNS_2os11SystemEventE, _ZN2nn7account12AsyncContext9GetResultEv
  nn::nifm: _ZN2nn4nifm10InitializeEv, _ZN2nn4nifm18IsNetworkAvailableEv, _ZN2nn4nifm20CancelNetworkRequestEv, _ZN2nn4nifm20SubmitNetworkRequestEv
  nn::mem: _ZN2nn3mem17StandardAllocator10InitializeEPvm, _ZN2nn3mem17StandardAllocator10ReallocateEPvm, _ZN2nn3mem17StandardAllocator4FreeEPv, _ZN2nn3mem17StandardAllocator8AllocateEm
  nn::swkbd: _ZN2nn5swkbd10MakePresetEPNS0_14KeyboardConfigENS0_6PresetE, _ZN2nn5swkbd12ShowKeyboardEPNS0_6StringERKNS0_15ShowKeyboardArgE, _ZN2nn5swkbd14SetSubTextUtf8EPNS0_14KeyboardConfigEPKc, _ZN2nn5swkbd16SetGuideTextUtf8EPNS0_14KeyboardConfigEPKc
  nn::ngc: _ZN2nn3ngc15ProfanityFilter10InitializeEPvm, _ZN2nn3ngc15ProfanityFilter11SetMaskModeENS1_8MaskModeE, _ZN2nn3ngc15ProfanityFilter17GetContentVersionEv, _ZN2nn3ngc15ProfanityFilter19CheckProfanityWordsEPjjPPKcm
  nn::diag: _ZN2nn4diag34InitializeApplicationAbortObserverEv, _ZN2nn4diag6detail18OnAssertionFailureENS0_13AssertionTypeEPKcS4_S4_i, _ZN2nn4diag6detail18OnAssertionFailureENS0_13AssertionTypeEPKcS4_S4_iS4_z, _ZN2nn4diag6detail9AbortImplEPKcS3_S3_i
```

## What it means for a port

- modules besides main / sdk: subsdk0 (NintendoSDK_movie), subsdk1 (NintendoSDK_libcurl), subsdk2 (NintendoSDK_NVN), subsdk3 (nn::sf); the provisional profile loads main subsdk0 subsdk1 subsdk2 subsdk3 sdk (load order main, subsdk*, sdk)
- Unity: the shaders are Shader objects in UnityFS bundles and data.unity3d, which shader_split.py reads through unityfs.py (written for Unity 2020.3's Switch format, also read for 2017.4, 2018.3, 2022.3 and 6000.0; check other Unity versions first)
- SDK 1, not Mario's 16: NVN object sizes, API version and service layouts differ by SDK (the host was converted from D2R's SDK 12 to SDK 16 sizes); check them one by one per docs/agents/03

## Provisional profile keys (dm/games/profile_guess.py)

What the game files decide; mem_used follows the rewrite, and a game with virtual address memory that sets a heap gets a heap check on the Mac (scripts/build_ipa_from_nsp.py heap_check).

```
modules	main subsdk0 subsdk1 subsdk2 subsdk3 sdk	# every module main imports from
mem_reserve	0x100000000	# main sets a heap of at most the pool − 64 MB: a 3328 MB heap region
mem_alias	0x10000000
mem_stack	0x10000000
app_pool	0xCD500000
sys_resource	0x0	# the NPDM declares 0 MB of system resource: IsVirtualAddressMemoryEnabled is false, as on Horizon (Big Brain Academy: Brain vs. Brain sets a heap only then)
```

Next steps: docs/agents (02 survey, 03 bring-up).

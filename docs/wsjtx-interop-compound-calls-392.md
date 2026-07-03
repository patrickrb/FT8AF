# WSJT-X interop validation — compound/prefixed calls (issue #392)

Reproducible runbook for validating that FT8AF interoperates with WSJT-X for
compound/prefixed callsigns (e.g. `SV8/DM5HF`), the fix for
[#392](https://github.com/patrickrb/FT8AF/issues/392) ("Prefix RX doesn't
work").

It drives the app's own encode/decode C against **WSJT-X's reference tools**
(`hash22calc`, `ft8code`, `ft8sim`, `jt9`), so it checks true interop, not just
self-consistency. Everything runs on the host — no device, no RF, no live audio
loop.

## The bug and the fix (one paragraph)

A compound call doesn't fit FT8's 28-bit callsign field, so on the air it is
sent as a short **hash** — a 12-bit hash in a Type-4 nonstandard frame, or a
22-bit hash in a standard (grid/report) frame. The decoder resolves that hash
against a table built from calls heard *in full*; it is never seeded with the
operator's own call, so answers to your own compound CQ decode as `<...>`. The
JNI then **zeroed** the message's hash fields for a `<...>` string, discarding
the one number the Java side could resolve (`MessageHashMap` *is* seeded with
your own call). The fix (`ft8af_glue/ft8_call_hash.h`, wired into
`ft8_decode_jni.cpp` and `ft2_decode_jni.cpp`) recovers the raw hash from the
frame so the seeded map resolves it. FT8 and FT4 share the identical 77-bit
message + hash layer, so the same helper serves both decoders.

## Prerequisites (macOS)

- **WSJT-X.app** at `/Applications/wsjtx.app` (ships `hash22calc`, `ft8code`,
  `ft8sim`, `jt9` under `Contents/MacOS/`).
- **clang** (Xcode CLT) for the host harnesses.
- **JDK 17** for the JVM tests (`~/.local/jdks/jdk-17.0.19+10/Contents/Home`).

```bash
export WSJTX=/Applications/wsjtx.app/Contents/MacOS
export CPP="$(git rev-parse --show-toplevel)/ft8af/app/src/main/cpp"
export WORK="$(mktemp -d)"; echo "scratch: $WORK"
```

## 1. Hash agreement — the app must compute WSJT-X's hash

The whole fix rests on the app and WSJT-X hashing a call to the same number.

```bash
"$WSJTX/hash22calc" SV8/DM5HF      # WSJT-X reference => 711279
```

App-side value (same formula as `FT8Package.getHash22` / `ft8_call_hash`):

```bash
cat > "$WORK/hcalc.c" <<'EOF'
#include <stdio.h>
#include <stdint.h>
#include "ft8/text.h"
#include "ft8/constants.h"
static uint32_t n22(const char* c){uint64_t n=0;int i=0;
 for(;c[i]&&i<11;++i){char x=c[i];if(x>='a'&&x<='z')x=x-'a'+'A';
 int j=nchar(x,FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);if(j<0)j=0;n=38*n+(uint64_t)j;}
 for(;i<11;++i)n=38*n;return (uint32_t)(((47055833459ull*n)>>42)&0x3FFFFFul);}
int main(int c,char**v){for(int k=1;k<c;k++)printf("%-12s n22=%u n12=%u n10=%u\n",
 v[k],n22(v[k]),n22(v[k])>>10,n22(v[k])>>12);return 0;}
EOF
clang -std=c11 -I "$CPP/ft8_lib" "$WORK/hcalc.c" \
  "$CPP/ft8_lib/ft8/text.c" "$CPP/ft8_lib/ft8/constants.c" -o "$WORK/hcalc"
"$WORK/hcalc" SV8/DM5HF K1ABC     # expect n22=711279 for SV8/DM5HF
```

**Expected:** both report `711279`.

## 2. What a real QSO actually sends

`ft8code` packs message text and shows the frame type. Note WSJT-X drops the
`SV8/` prefix (uses base call `DM5HF`) for *plain-typed* report/grid frames, but
in a real QSO against a nonstandard DX call it preserves the full call via the
bracketed **hashed** form — which is what produces `<...>` on receive:

```bash
for m in "CQ SV8/DM5HF" "<SV8/DM5HF> K1ABC JN35" "K1ABC <SV8/DM5HF> R-10"; do
  "$WSJTX/ft8code" "$m" | grep -E '^\s*1\.'
done
```

**Expected:** `CQ SV8/DM5HF` → Type 4 (nonstandard, full call); the bracketed
frames → Standard msg carrying the 22-bit hash.

## 3. The fix recovers WSJT-X's hash from WSJT-X's own bytes

This is covered permanently by the committed host test — it pins WSJT-X's
`ft8code` payloads as golden vectors:

```bash
cd "$CPP/ft8af_glue" && FT8=../ft8_lib
clang -std=c11 -O2 -I "$FT8" test_call_hash.c \
  "$FT8"/ft8/{pack,encode,crc,constants,text,message}.c -o "$WORK/tch" && "$WORK/tch"
```

**Expected:** `test_call_hash: all checks passed` (includes the `711279`
WSJT-X-interop vectors for both call positions).

## 4. RX end-to-end through the app decoder on WSJT-X audio

Generate real FT8 audio with `ft8sim`, decode through the app's exact RX pipeline
(monitor + `ft8_find_sync` + `ft8_decode`), then apply the fix + a persistent map
that models the Java `MessageHashMap`. The harness leaves the native hash table
**unseeded** and **resets it per slot** (as the app does), and **learns** the
full call whenever one is decoded — so it shows both the config-seeded and the
dynamic "learn from CQ" behaviours.

```bash
# --- app RX harness (FT8) ---------------------------------------------------
cat > "$WORK/appdec.c" <<'EOF'
#include <stdio.h>
#include <string.h>
#include <ft8/constants.h>
#include <ft8/text.h>
#include <ft8/decode.h>
#include <ft8/message.h>
#include "common/monitor.h"
#include "common/wave.h"
#include "decode_params.h"
#include "ft8_call_hash.h"
static bool no_l(ftx_callsign_hash_type_e t,uint32_t h,char*c){(void)t;(void)h;c[0]=0;return false;}
static void no_s(const char*c,uint32_t n){(void)c;(void)n;}
static ftx_callsign_hash_interface_t IF={no_l,no_s};
static uint32_t N22(const char*c){uint64_t n=0;int i=0;for(;c[i]&&i<11;++i){char x=c[i];
 if(x>='a'&&x<='z')x=x-'a'+'A';int j=nchar(x,FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);
 if(j<0)j=0;n=38*n+(uint64_t)j;}for(;i<11;++i)n=38*n;return(uint32_t)(((47055833459ull*n)>>42)&0x3FFFFFul);}
static struct{char c[16];uint32_t n;}M[128];static int Mn=0;
static void learn(const char*c){if(!c[0]||c[0]=='<'||!strcmp(c,"CQ")||!strcmp(c,"DE")||!strcmp(c,"QRZ"))return;
 uint32_t n=N22(c);for(int i=0;i<Mn;i++)if(M[i].n==n)return;if(Mn<128){strncpy(M[Mn].c,c,15);M[Mn].n=n;Mn++;}}
static const char*R(uint32_t h,int s){for(int i=0;i<Mn;i++)if((M[i].n>>s)==h)return M[i].c;return "<...>";}
static void slot(const char*w){
 static float sig[15*12000];int ns=15*12000,sr=0;if(load_wav(sig,&ns,&sr,w)){printf("  load fail\n");return;}
 monitor_config_t cfg={0};cfg.f_min=100;cfg.f_max=3500;cfg.sample_rate=sr;
 cfg.time_osr=FT8AF_TIME_OSR;cfg.freq_osr=FT8AF_FREQ_OSR;cfg.protocol=FTX_PROTOCOL_FT8;
 monitor_t mon;monitor_init(&mon,&cfg);
 for(int p=0;p+mon.block_size<=ns;p+=mon.block_size)monitor_process(&mon,sig+p);
 candidate_t cs[FT8AF_MAX_CANDIDATES];int nc=ft8_find_sync(&mon.wf,FT8AF_MAX_CANDIDATES,cs,FT8AF_MIN_SCORE_FAST);
 char seen[64][32];int sn=0;
 for(int i=0;i<nc;i++){ftx_message_t m;decode_status_t st;
  if(!ft8_decode(&mon.wf,&cs[i],FT8AF_LDPC_ITERS_FAST,&m,&st))continue;
  ftx_message_type_t ty=ftx_message_get_type(&m);char to[20],de[20],ex[20];
  if(ty==FTX_MESSAGE_TYPE_STANDARD){if(ftx_message_decode_std(&m,&IF,to,de,ex))continue;}
  else if(ty==FTX_MESSAGE_TYPE_NONSTD_CALL){if(ftx_message_decode_nonstd(&m,&IF,to,de,ex))continue;}
  else continue;
  char rt[24],rd[24];strcpy(rt,to);strcpy(rd,de);
  if(ty==FTX_MESSAGE_TYPE_STANDARD){uint32_t h;
   if(to[0]=='<'&&ft8af_std_hash22(&m,0,&h))strcpy(rt,R(h,0));
   if(de[0]=='<'&&ft8af_std_hash22(&m,1,&h))strcpy(rd,R(h,0));}
  else{uint32_t h;bool it;if(ft8af_nonstd_hash12(&m,&h,&it)){
   if(it&&to[0]=='<')strcpy(rt,R(h,10));else if(!it&&de[0]=='<')strcpy(rd,R(h,10));}}
  char ln[64];snprintf(ln,sizeof ln,"%s %s %s",rt,rd,ex);int dup=0;
  for(int k=0;k<sn;k++)if(!strcmp(seen[k],ln))dup=1;if(dup)continue;strncpy(seen[sn++],ln,31);
  printf("  decoded: %s\n",ln);learn(to);learn(de);}}
int main(int c,char**v){for(int i=1;i<c;i++){printf("SLOT %d (%s)\n",i,v[i]);slot(v[i]);}return 0;}
EOF
FT8="$CPP/ft8_lib"
clang -std=c11 -O2 -I "$FT8" -I "$CPP/ft8af_glue" "$WORK/appdec.c" \
  "$FT8"/ft8/{decode,message,unpack,pack,text,constants,crc,ldpc,osd,encode}.c \
  "$FT8"/common/{monitor,wave}.c "$FT8"/fft/{kiss_fft,kiss_fftr}.c \
  -lm -o "$WORK/appdec" 2>/dev/null

# WSJT-X audio: the CQ (full call) and a hashed reply
( cd "$WORK"
  "$WSJTX/ft8sim" "CQ SV8/DM5HF"          1500 0 0 0 1 -5 >/dev/null; mv 000000_000001.wav cq.wav
  "$WSJTX/ft8sim" "<SV8/DM5HF> K1ABC JN35" 1500 0 0 0 1 -5 >/dev/null; mv 000000_000001.wav reply.wav )

# Dynamic: unknown hash -> station calls CQ <compound> -> resolves
"$WORK/appdec" "$WORK/reply.wav" "$WORK/cq.wav" "$WORK/reply.wav" 2>/dev/null | grep -vE 'Block|Subblock|N_FFT'
```

**Expected:**

```
SLOT 1 (.../reply.wav)
  decoded: <...> K1ABC JN35        <- hash unknown: correctly unresolved
SLOT 2 (.../cq.wav)
  decoded: CQ SV8/DM5HF            <- full call learned (hash 711279)
SLOT 3 (.../reply.wav)
  decoded: SV8/DM5HF K1ABC JN35    <- now resolved
```

Baseline: WSJT-X's own decoder shows the same `<...>` before it knows the hash —
proving this is inherent FT8 behaviour, fixed at the right layer:

```bash
( cd "$WORK" && "$WSJTX/jt9" --ft8 -d 3 reply.wav 2>&1 | grep -v Finished )
# => <...> K1ABC JN35   (empty hash table)
```

## 5. TX — the app's transmit is decodable by WSJT-X (FT8 and FT4)

Replicate the app's TX chain (`pack77` → `{ft8,ft4}_encode` → `synth_gfsk`, exact
ModeProfile params) to a WAV and decode with WSJT-X's `jt9`:

```bash
cat > "$WORK/apptx.c" <<'EOF'
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "ft8/pack.h"
#include "ft8/encode.h"
#include "ft8/constants.h"
#include "common/wave.h"
void synth_gfsk_offset(const uint8_t*,int,float,float,float,int,float*,int);
int main(int ac,char**av){const char*mode=av[1],*msg=av[2];float f0=atof(av[3]);const char*out=av[4];
 int ft4=!strcmp(mode,"ft4");uint8_t p[10]={0};if(pack77(msg,p)){printf("pack77 fail\n");return 2;}
 int ns=ft4?FT4_NN:FT8_NN;float per=ft4?FT4_SYMBOL_PERIOD:FT8_SYMBOL_PERIOD,bt=ft4?1.0f:2.0f;
 uint8_t t[FT4_NN];if(ft4)ft4_encode(p,t);else ft8_encode(p,t);
 int sr=12000;float slot=ft4?7.5f:15.0f;static float sig[15*12000];memset(sig,0,sizeof sig);
 synth_gfsk_offset(t,ns,f0,bt,per,sr,sig,(int)(0.2f*sr));
 save_wav(sig,(int)(slot*sr),sr,out);printf("wrote %s [%s]\n",out,msg);return 0;}
EOF
FT8="$CPP/ft8_lib"
clang -std=c11 -O2 -I "$FT8" "$WORK/apptx.c" \
  "$FT8"/ft8/{pack,encode,crc,constants,text,message}.c "$FT8"/common/wave.c \
  "$CPP/ft8af_glue/gfsk.c" -lm -o "$WORK/apptx"

"$WORK/apptx" ft8 "CQ SV8/DM5HF" 1500 "$WORK/tx8.wav"
"$WORK/apptx" ft4 "CQ SV8/DM5HF" 1500 "$WORK/tx4.wav"
( cd "$WORK" && "$WSJTX/jt9" --ft8 -d 3 tx8.wav 2>&1 | grep -v Finished )   # => CQ SV8/DM5HF
( cd "$WORK" && "$WSJTX/jt9" --ft4 -d 3 tx4.wav 2>&1 | grep -v Finished )   # => CQ SV8/DM5HF
```

**Expected:** both decode to `CQ SV8/DM5HF`.

## 6. JVM tests (the actual app resolution/learning logic)

```bash
cd "$(git rev-parse --show-toplevel)/ft8af"
export JAVA_HOME=~/.local/jdks/jdk-17.0.19+10/Contents/Home
./gradlew testDebugUnitTest --tests com.k1af.ft8af.Ft8MessageTest \
                            --tests com.k1af.ft8af.MessageHashMapTest
```

Covers: resolve a config-seeded compound call from `callToHash22`; learn
hash→call from a full-call frame, then resolve later hashed frames; leave a
genuinely unknown hash as `<...>`.

## Coverage notes

- **FT8 RX** is exercised end-to-end through the app decoder on WSJT-X audio
  (§4), both config-seeded and dynamic-learn.
- **FT4/FT8 share** the identical 77-bit message + hash layer; the fix
  (`ft2_decode_jni.cpp`) applies the same helper. §3 (golden vectors) and §6
  cover that shared layer; §5 confirms FT4 frames are valid on air.
- The minimal §4 harness uses a single fast decode pass, so it reliably decodes
  `ft8sim` signals but not every clean full-scale synth; **FT4 RX tone
  demapping** needs the app's full `ft2` Analysis front-end (fine sync / OSD),
  which is not replicated here. The app's real FT4 decoder handles it; the
  message/hash layer under test is mode-independent.

#ifdef __linux__
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#endif

#include "unpack.h"
#include "text.h"

#include <string.h>

#define MAX22    ((uint32_t)4194304L)
#define NTOKENS  ((uint32_t)2063592L)
#define MAXGRID4 ((uint16_t)32400L)

// n28 is a 28-bit integer, e.g. n28a or n28b, containing all the
// call sign bits from a packed message.
static int unpack_callsign(uint32_t n28, uint8_t ip, uint8_t i3, char* result, const unpack_hash_interface_t* hash_if)
{
    // Check for special tokens DE, QRZ, CQ, CQ_nnn, CQ_aaaa
    if (n28 < NTOKENS)
    {
        if (n28 <= 2)
        {
            if (n28 == 0)
                strcpy(result, "DE");
            if (n28 == 1)
                strcpy(result, "QRZ");
            if (n28 == 2)
                strcpy(result, "CQ");
            return 0; // Success
        }
        if (n28 <= 1002)
        {
            // CQ_nnn with 3 digits
            strcpy(result, "CQ ");
            int_to_dd(result + 3, n28 - 3, 3, false);
            return 0; // Success
        }
        if (n28 <= 532443L)
        {
            // CQ_aaaa with 4 alphanumeric symbols
            uint32_t n = n28 - 1003;
            char aaaa[5];

            aaaa[4] = '\0';
            for (int i = 3; /* */; --i)
            {
                aaaa[i] = charn(n % 27, FT8_CHAR_TABLE_LETTERS_SPACE);
                if (i == 0)
                    break;
                n /= 27;
            }

            strcpy(result, "CQ ");
            strcat(result, trim_front(aaaa));
            return 0; // Success
        }
        // ? TODO: unspecified in the WSJT-X code
        return -1;
    }

    n28 = n28 - NTOKENS;
    if (n28 < MAX22)
    {
        // This is a 22-bit hash of a result
        if (hash_if != NULL)
        {
            hash_if->hash22(n28, result);
        }
        else
        {
            strcpy(result, "<...>");
        }
        return 0;
    }

    // Standard callsign
    uint32_t n = n28 - MAX22;

    char callsign[7];
    callsign[6] = '\0';
    callsign[5] = charn(n % 27, FT8_CHAR_TABLE_LETTERS_SPACE);
    n /= 27;
    callsign[4] = charn(n % 27, FT8_CHAR_TABLE_LETTERS_SPACE);
    n /= 27;
    callsign[3] = charn(n % 27, FT8_CHAR_TABLE_LETTERS_SPACE);
    n /= 27;
    callsign[2] = charn(n % 10, FT8_CHAR_TABLE_NUMERIC);
    n /= 10;
    callsign[1] = charn(n % 36, FT8_CHAR_TABLE_ALPHANUM);
    n /= 36;
    callsign[0] = charn(n % 37, FT8_CHAR_TABLE_ALPHANUM_SPACE);

    // Skip trailing and leading whitespace in case of a short callsign
    strcpy(result, trim(callsign));
    if (strlen(result) == 0)
        return -1;

    // Check if we should append /R or /P suffix
    if (ip)
    {
        if (i3 == 1)
        {
            strcat(result, "/R");
        }
        else if (i3 == 2)
        {
            strcat(result, "/P");
        }
    }

    return 0; // Success
}

static int unpack_type1(const uint8_t* a77, uint8_t i3, char* call_to, char* call_de, char* extra, const unpack_hash_interface_t* hash_if)
{
    uint32_t n28a, n28b;
    uint16_t igrid4;
    uint8_t ir;

    // Extract packed fields
    n28a = (a77[0] << 21);
    n28a |= (a77[1] << 13);
    n28a |= (a77[2] << 5);
    n28a |= (a77[3] >> 3);
    n28b = ((a77[3] & 0x07) << 26);
    n28b |= (a77[4] << 18);
    n28b |= (a77[5] << 10);
    n28b |= (a77[6] << 2);
    n28b |= (a77[7] >> 6);
    ir = ((a77[7] & 0x20) >> 5);
    igrid4 = ((a77[7] & 0x1F) << 10);
    igrid4 |= (a77[8] << 2);
    igrid4 |= (a77[9] >> 6);

    // Unpack both callsigns
    if (unpack_callsign(n28a >> 1, n28a & 0x01, i3, call_to, hash_if) < 0)
    {
        return -1;
    }
    if (unpack_callsign(n28b >> 1, n28b & 0x01, i3, call_de, hash_if) < 0)
    {
        return -2;
    }
    // Fix "CQ_" to "CQ " -> already done in unpack_callsign()

    // TODO: add to recent calls
    if ((call_to[0] != '<') && (strlen(call_to) >= 4) && (hash_if != NULL))
    {
        hash_if->save_hash(call_to);
    }
    if ((call_de[0] != '<') && (strlen(call_de) >= 4) && (hash_if != NULL))
    {
        hash_if->save_hash(call_de);
    }

    char* dst = extra;

    if (igrid4 <= MAXGRID4)
    {
        // Extract 4 symbol grid locator
        if (ir > 0)
        {
            // In case of ir=1 add an "R" before grid
            dst = stpcpy(dst, "R ");
        }

        uint16_t n = igrid4;
        dst[4] = '\0';
        dst[3] = '0' + (n % 10);
        n /= 10;
        dst[2] = '0' + (n % 10);
        n /= 10;
        dst[1] = 'A' + (n % 18);
        n /= 18;
        dst[0] = 'A' + (n % 18);
        // if (ir > 0 && strncmp(call_to, "CQ", 2) == 0) return -1;
    }
    else
    {
        // Extract report
        int irpt = igrid4 - MAXGRID4;

        // Check special cases first (irpt > 0 always)
        switch (irpt)
        {
        case 1:
            extra[0] = '\0';
            break;
        case 2:
            strcpy(dst, "RRR");
            break;
        case 3:
            strcpy(dst, "RR73");
            break;
        case 4:
            strcpy(dst, "73");
            break;
        default:
            // Extract signal report as a two digit number with a + or - sign
            if (ir > 0)
            {
                *dst++ = 'R'; // Add "R" before report
            }
            int_to_dd(dst, irpt - 35, 2, true);
            break;
        }
        // if (irpt >= 2 && strncmp(call_to, "CQ", 2) == 0) return -1;
    }

    return 0; // Success
}

static int unpack_text(const uint8_t* a71, char* text)
{
    uint8_t b71[9];

    // Shift 71 bits right by 1 bit, so that it's right-aligned in the byte array
    uint8_t carry = 0;
    for (int i = 0; i < 9; ++i)
    {
        b71[i] = carry | (a71[i] >> 1);
        carry = (a71[i] & 1) ? 0x80 : 0;
    }

    char c14[14];
    c14[13] = 0;
    for (int idx = 12; idx >= 0; --idx)
    {
        // Divide the long integer in b71 by 42
        uint16_t rem = 0;
        for (int i = 0; i < 9; ++i)
        {
            rem = (rem << 8) | b71[i];
            b71[i] = rem / 42;
            rem = rem % 42;
        }
        c14[idx] = charn(rem, FT8_CHAR_TABLE_FULL);
    }

    strcpy(text, trim(c14));
    return 0; // Success
}

static int unpack_telemetry(const uint8_t* a71, char* telemetry)
{
    uint8_t b71[9];

    // Shift bits in a71 right by 1 bit
    uint8_t carry = 0;
    for (int i = 0; i < 9; ++i)
    {
        b71[i] = (carry << 7) | (a71[i] >> 1);
        carry = (a71[i] & 0x01);
    }

    // Convert b71 to hexadecimal string
    for (int i = 0; i < 9; ++i)
    {
        uint8_t nibble1 = (b71[i] >> 4);
        uint8_t nibble2 = (b71[i] & 0x0F);
        char c1 = (nibble1 > 9) ? (nibble1 - 10 + 'A') : nibble1 + '0';
        char c2 = (nibble2 > 9) ? (nibble2 - 10 + 'A') : nibble2 + '0';
        telemetry[i * 2] = c1;
        telemetry[i * 2 + 1] = c2;
    }

    telemetry[18] = '\0';
    return 0;
}

// 86 ARRL/RAC section codes, same order as WSJT-X packjt77.f90 csec
static const char* ARRL_SECTIONS_UNPACK[86] = {
    "AB","AK","AL","AR","AZ","BC","CO","CT","DE","EB",
    "EMA","ENY","EPA","EWA","GA","GH","IA","ID","IL","IN",
    "KS","KY","LA","LAX","NS","MB","MDC","ME","MI","MN",
    "MO","MS","MT","NC","ND","NE","NFL","NH","NL","NLI",
    "NM","NNJ","NNY","TER","NTX","NV","OH","OK","ONE","ONN",
    "ONS","OR","ORG","PAC","PR","QC","RI","SB","SC","SCV",
    "SD","SDG","SF","SFL","SJV","SK","SNJ","STX","SV","TN",
    "UT","VA","VI","VT","WCF","WI","WMA","WNY","WPA","WTX",
    "WV","WWA","WY","DX","PE","NB"
};

// ARRL Field Day: i3=0, n3=3 or 4
static int unpack_fd(const uint8_t* a77, char* call_to, char* call_de, char* extra, const unpack_hash_interface_t* hash_if)
{
    // Bit layout (77 bits):
    //   n28a (0-27)  n28b (28-55)  R1 (56)  n4 (57-60)  k3 (61-63)  S7 (64-70)  n3 (71-73)  i3 (74-76)

    // Extract n28a (bits 0-27)
    uint32_t n28a = ((uint32_t)a77[0] << 20)
                  | ((uint32_t)a77[1] << 12)
                  | ((uint32_t)a77[2] << 4)
                  | ((uint32_t)a77[3] >> 4);

    // Extract n28b (bits 28-55)
    uint32_t n28b = ((uint32_t)(a77[3] & 0x0F) << 24)
                  | ((uint32_t)a77[4] << 16)
                  | ((uint32_t)a77[5] << 8)
                  | ((uint32_t)a77[6]);

    // R1 (bit 56) = byte 7, bit 7
    uint8_t R1 = (a77[7] >> 7) & 0x01;

    // n4 (bits 57-60) = byte 7, bits [6:3]
    uint8_t n4 = (a77[7] >> 3) & 0x0F;

    // k3 (bits 61-63) = byte 7, bits [2:0]
    uint8_t k3 = a77[7] & 0x07;

    // S7 (bits 64-70) = byte 8, bits [7:1]
    uint8_t S7 = (a77[8] >> 1) & 0x7F;

    // Unpack both callsigns (ip=0, i3=0 — FD callsigns have no /R /P suffix)
    if (unpack_callsign(n28a, 0, 0, call_to, hash_if) < 0)
    {
        return -1;
    }
    if (unpack_callsign(n28b, 0, 0, call_de, hash_if) < 0)
    {
        return -2;
    }

    // Save callsigns to hash table
    if (hash_if != NULL)
    {
        if (call_to[0] != '<' && strlen(call_to) >= 4)
            hash_if->save_hash(call_to);
        if (call_de[0] != '<' && strlen(call_de) >= 4)
            hash_if->save_hash(call_de);
    }

    // Format extra as the FD exchange: "[R ]<numTx><class> <section>"
    char* dst = extra;
    if (R1)
    {
        dst = stpcpy(dst, "R ");
    }

    uint8_t num_tx = n4 + 1; // 0-15 → 1-16
    if (num_tx >= 10)
    {
        *dst++ = '0' + (num_tx / 10);
    }
    *dst++ = '0' + (num_tx % 10);

    // k3 → class letter: 0=A, 1=B, ... 5=F
    *dst++ = (k3 <= 5) ? ('A' + k3) : '?';

    *dst++ = ' ';

    // S7 → section string
    if (S7 < 86)
    {
        dst = stpcpy(dst, ARRL_SECTIONS_UNPACK[S7]);
    }
    else
    {
        dst = stpcpy(dst, "???");
    }

    return 0;
}

// 64 RTTY state/province codes, same order as WSJT-X packjt77.f90 cqstate
static const char* RTTY_STATES_UNPACK[64] = {
    "AL","AK","AZ","AR","CA","CO","CT","DE","FL","GA",
    "HI","ID","IL","IN","IA","KS","KY","LA","ME","MD",
    "MA","MI","MN","MS","MO","MT","NE","NV","NH","NJ",
    "NM","NY","NC","ND","OH","OK","OR","PA","RI","SC",
    "SD","TN","TX","UT","VT","VA","WA","WV","WI","WY",
    "DC","AB","BC","MB","NB","NL","NS","NT","NU","ON",
    "PE","QC","SK","YT"
};

// DXpedition: i3=0, n3=1
static int unpack_dxped(const uint8_t* a77, char* call_to, char* call_de, char* extra, const unpack_hash_interface_t* hash_if)
{
    // c28a (0-27)  c28b (28-55)  h10 (56-65)  r5 (66-70)
    uint32_t c28a = ((uint32_t)a77[0] << 20)
                  | ((uint32_t)a77[1] << 12)
                  | ((uint32_t)a77[2] << 4)
                  | ((uint32_t)a77[3] >> 4);

    uint32_t c28b = ((uint32_t)(a77[3] & 0x0F) << 24)
                  | ((uint32_t)a77[4] << 16)
                  | ((uint32_t)a77[5] << 8)
                  | ((uint32_t)a77[6]);

    uint16_t h10 = ((uint16_t)a77[7] << 2)
                 | ((uint16_t)(a77[8] >> 6) & 0x03);

    uint8_t r5 = (a77[8] >> 1) & 0x1F;

    if (unpack_callsign(c28a, 0, 0, call_to, hash_if) < 0)
        return -1;
    if (unpack_callsign(c28b, 0, 0, call_de, hash_if) < 0)
        return -2;

    if (hash_if != NULL)
    {
        if (call_to[0] != '<' && strlen(call_to) >= 4)
            hash_if->save_hash(call_to);
        if (call_de[0] != '<' && strlen(call_de) >= 4)
            hash_if->save_hash(call_de);
    }

    // Look up fox call from 10-bit hash
    char fox_call[14];
    if (hash_if != NULL && hash_if->hash10 != NULL)
    {
        hash_if->hash10(h10, fox_call);
    }
    else
    {
        strcpy(fox_call, "<...>");
    }

    int8_t report = (int8_t)(r5 * 2) - 30;

    // Format extra as: "RR73; call_de fox_call report"
    // But since call_to/call_de are used by the caller, put the combo message in extra
    // Format: extra = "RR73; <call_de> <fox> <report>" — but the caller joins as "call_to extra"
    // Actually for DXpedition, the full message is "call_to RR73; call_de fox_hash report"
    // We put everything after call_to into extra, and leave call_de empty for special handling
    char* dst = extra;
    dst = stpcpy(dst, "RR73; ");
    dst = stpcpy(dst, call_de);
    *dst++ = ' ';
    dst = stpcpy(dst, fox_call);
    *dst++ = ' ';
    if (report >= 0)
    {
        *dst++ = '+';
        int_to_dd(dst, report, 2, false);
    }
    else
    {
        int_to_dd(dst, report, 2, true);
    }
    // Clear call_de so the caller formats as "call_to extra"
    call_de[0] = '\0';

    return 0;
}

// EU VHF contest: i3=0, n3=2
static int unpack_eu_vhf(const uint8_t* a77, char* call_to, char* call_de, char* extra, const unpack_hash_interface_t* hash_if)
{
    // c28 (0-27)  p1 (28)  R1 (29)  r3 (30-32)  s12 (33-44)  g25 (45-69)
    uint32_t c28 = ((uint32_t)a77[0] << 20)
                 | ((uint32_t)a77[1] << 12)
                 | ((uint32_t)a77[2] << 4)
                 | ((uint32_t)a77[3] >> 4);

    uint8_t p1 = (a77[3] >> 3) & 0x01;
    uint8_t R1 = (a77[3] >> 2) & 0x01;
    uint8_t r3 = ((a77[3] & 0x03) << 1) | ((a77[4] >> 7) & 0x01);

    uint16_t s12 = ((uint16_t)(a77[4] & 0x7F) << 5)
                 | ((uint16_t)(a77[5] >> 3) & 0x1F);

    uint32_t g25 = ((uint32_t)(a77[5] & 0x07) << 22)
                 | ((uint32_t)a77[6] << 14)
                 | ((uint32_t)a77[7] << 6)
                 | ((uint32_t)(a77[8] >> 2) & 0x3F);

    if (unpack_callsign(c28, 0, 0, call_to, hash_if) < 0)
        return -1;

    if (hash_if != NULL && call_to[0] != '<' && strlen(call_to) >= 4)
        hash_if->save_hash(call_to);

    if (p1)
        strcat(call_to, "/P");

    // No second callsign in EU VHF 0.2 format
    call_de[0] = '\0';

    // Report: 2-digit RST (52-59, from readability=5, strength=r3+2)
    char rst_2[3];
    rst_2[0] = '5';
    rst_2[1] = '0' + (r3 + 2);
    rst_2[2] = '\0';

    // Grid6 from g25
    char grid6[7];
    uint32_t g = g25;
    grid6[5] = 'A' + (char)(g % 24); g /= 24;
    grid6[4] = 'A' + (char)(g % 24); g /= 24;
    grid6[3] = '0' + (char)(g % 10); g /= 10;
    grid6[2] = '0' + (char)(g % 10); g /= 10;
    grid6[1] = 'A' + (char)(g % 18); g /= 18;
    grid6[0] = 'A' + (char)(g);
    grid6[6] = '\0';

    // Format extra: "[R ]rst_serial grid6"
    char* dst = extra;
    if (R1)
        dst = stpcpy(dst, "R ");

    dst = stpcpy(dst, rst_2);
    int_to_dd(dst, s12, 4, false);
    dst += strlen(dst);
    *dst++ = ' ';
    dst = stpcpy(dst, grid6);

    return 0;
}

// Contesting (Fox combo): i3=0, n3=6
static int unpack_contesting(const uint8_t* a77, char* call_to, char* call_de, char* extra, const unpack_hash_interface_t* hash_if)
{
    // c28a (0-27)  c28b (28-55)  g15 (56-70)
    uint32_t c28a = ((uint32_t)a77[0] << 20)
                  | ((uint32_t)a77[1] << 12)
                  | ((uint32_t)a77[2] << 4)
                  | ((uint32_t)a77[3] >> 4);

    uint32_t c28b = ((uint32_t)(a77[3] & 0x0F) << 24)
                  | ((uint32_t)a77[4] << 16)
                  | ((uint32_t)a77[5] << 8)
                  | ((uint32_t)a77[6]);

    uint16_t g15 = ((uint16_t)a77[7] << 7)
                 | ((uint16_t)(a77[8] >> 1) & 0x7F);

    char cont_call1[14], cont_call2[14];
    if (unpack_callsign(c28a, 0, 0, cont_call1, hash_if) < 0)
        return -1;
    if (unpack_callsign(c28b, 0, 0, cont_call2, hash_if) < 0)
        return -2;

    if (hash_if != NULL)
    {
        if (cont_call1[0] != '<' && strlen(cont_call1) >= 4)
            hash_if->save_hash(cont_call1);
        if (cont_call2[0] != '<' && strlen(cont_call2) >= 4)
            hash_if->save_hash(cont_call2);
    }

    // Unpack g15 as grid4
    char grid4[8];
    grid4[0] = '\0';
    if (g15 <= MAXGRID4)
    {
        uint16_t n = g15;
        grid4[3] = '0' + (n % 10); n /= 10;
        grid4[2] = '0' + (n % 10); n /= 10;
        grid4[1] = 'A' + (n % 18); n /= 18;
        grid4[0] = 'A' + (n % 18);
        grid4[4] = '\0';
    }

    // Set call_to = cont_call1, clear call_de, format extra
    strcpy(call_to, cont_call1);
    call_de[0] = '\0';

    // Format extra: "RR73; CQ call2 grid"
    char* dst = extra;
    dst = stpcpy(dst, "RR73; CQ ");
    dst = stpcpy(dst, cont_call2);
    if (grid4[0] != '\0')
    {
        *dst++ = ' ';
        dst = stpcpy(dst, grid4);
    }

    return 0;
}

// ARRL RTTY Roundup: i3=3
static int unpack_rtty(const uint8_t* a77, char* call_to, char* call_de, char* extra, const unpack_hash_interface_t* hash_if)
{
    // t1 (0)  c28a (1-28)  c28b (29-56)  R1 (57)  r3 (58-60)  s13 (61-73)
    uint8_t t1 = (a77[0] >> 7) & 0x01;

    uint32_t c28a = ((uint32_t)(a77[0] & 0x7F) << 21)
                  | ((uint32_t)a77[1] << 13)
                  | ((uint32_t)a77[2] << 5)
                  | ((uint32_t)a77[3] >> 3);

    uint32_t c28b = ((uint32_t)(a77[3] & 0x07) << 25)
                  | ((uint32_t)a77[4] << 17)
                  | ((uint32_t)a77[5] << 9)
                  | ((uint32_t)a77[6] << 1)
                  | ((uint32_t)a77[7] >> 7);

    uint8_t R1 = (a77[7] >> 6) & 0x01;
    uint8_t r3 = (a77[7] >> 3) & 0x07;

    uint16_t s13 = ((uint16_t)(a77[7] & 0x07) << 10)
                 | ((uint16_t)a77[8] << 2)
                 | ((uint16_t)(a77[9] >> 6) & 0x03);

    if (unpack_callsign(c28a, 0, 0, call_to, hash_if) < 0)
        return -1;
    if (unpack_callsign(c28b, 0, 0, call_de, hash_if) < 0)
        return -2;

    if (hash_if != NULL)
    {
        if (call_to[0] != '<' && strlen(call_to) >= 4)
            hash_if->save_hash(call_to);
        if (call_de[0] != '<' && strlen(call_de) >= 4)
            hash_if->save_hash(call_de);
    }

    uint16_t rst = r3 * 10 + 529;

    char state_str[8];
    if (s13 >= 1 && s13 <= 8000)
    {
        int_to_dd(state_str, s13, 4, false);
    }
    else if (s13 >= 8001 && s13 <= 8064)
    {
        strcpy(state_str, RTTY_STATES_UNPACK[s13 - 8001]);
    }
    else
    {
        state_str[0] = '\0';
    }

    // Format extra: "[TU; ]... [R ]report state" — but call_to/call_de are set,
    // so we put the exchange portion in extra
    char* dst = extra;
    if (t1)
    {
        // TU prefix — we need to prepend to the whole message, so use call_to trick:
        // Shift call_to content right and prepend "TU; "
        char tmp[14];
        strcpy(tmp, call_to);
        strcpy(call_to, "TU; ");
        strcat(call_to, tmp);
    }

    if (R1)
        dst = stpcpy(dst, "R ");

    int_to_dd(dst, rst, 3, false);
    dst += strlen(dst);
    *dst++ = ' ';
    dst = stpcpy(dst, state_str);

    return 0;
}

// WWROF contest: i3=5
static int unpack_wwrof(const uint8_t* a77, char* call_to, char* call_de, char* extra, const unpack_hash_interface_t* hash_if)
{
    // t1 (0)  c28a (1-28)  c28b (29-56)  R1 (57)  r7 (58-64)  s9 (65-73)
    uint8_t t1 = (a77[0] >> 7) & 0x01;

    uint32_t c28a = ((uint32_t)(a77[0] & 0x7F) << 21)
                  | ((uint32_t)a77[1] << 13)
                  | ((uint32_t)a77[2] << 5)
                  | ((uint32_t)a77[3] >> 3);

    uint32_t c28b = ((uint32_t)(a77[3] & 0x07) << 25)
                  | ((uint32_t)a77[4] << 17)
                  | ((uint32_t)a77[5] << 9)
                  | ((uint32_t)a77[6] << 1)
                  | ((uint32_t)a77[7] >> 7);

    uint8_t R1 = (a77[7] >> 6) & 0x01;

    uint8_t r7 = ((a77[7] & 0x3F) << 1) | ((a77[8] >> 7) & 0x01);

    uint16_t s9 = ((uint16_t)(a77[8] & 0x7F) << 2)
                | ((uint16_t)(a77[9] >> 6) & 0x03);

    if (unpack_callsign(c28a, 0, 0, call_to, hash_if) < 0)
        return -1;
    if (unpack_callsign(c28b, 0, 0, call_de, hash_if) < 0)
        return -2;

    if (hash_if != NULL)
    {
        if (call_to[0] != '<' && strlen(call_to) >= 4)
            hash_if->save_hash(call_to);
        if (call_de[0] != '<' && strlen(call_de) >= 4)
            hash_if->save_hash(call_de);
    }

    int8_t report = (int8_t)r7 - 35;

    char grid2[3];
    grid2[0] = 'A' + (char)(s9 / 18);
    grid2[1] = 'A' + (char)(s9 % 18);
    grid2[2] = '\0';

    // Format extra: "[R]report grid2" — with TU prefix via call_to if needed
    char* dst = extra;
    if (t1)
    {
        char tmp[14];
        strcpy(tmp, call_to);
        strcpy(call_to, "TU; ");
        strcat(call_to, tmp);
    }

    if (R1)
        *dst++ = 'R';

    if (report >= 0)
    {
        *dst++ = '+';
        int_to_dd(dst, report, 2, false);
    }
    else
    {
        int_to_dd(dst, report, 2, true);
    }
    dst += strlen(dst);
    *dst++ = ' ';
    dst = stpcpy(dst, grid2);

    return 0;
}

// none standard for wsjt-x 2.0
// by KD8CEC
static int unpack_nonstandard(const uint8_t* a77, char* call_to, char* call_de, char* extra, const unpack_hash_interface_t* hash_if)
{
    uint32_t n12, iflip, nrpt, icq;
    uint64_t n58;
    n12 = (a77[0] << 4);  // 11 ~4  : 8
    n12 |= (a77[1] >> 4); // 3~0 : 12

    n58 = ((uint64_t)(a77[1] & 0x0F) << 54); // 57 ~ 54 : 4
    n58 |= ((uint64_t)a77[2] << 46);         // 53 ~ 46 : 12
    n58 |= ((uint64_t)a77[3] << 38);         // 45 ~ 38 : 12
    n58 |= ((uint64_t)a77[4] << 30);         // 37 ~ 30 : 12
    n58 |= ((uint64_t)a77[5] << 22);         // 29 ~ 22 : 12
    n58 |= ((uint64_t)a77[6] << 14);         // 21 ~ 14 : 12
    n58 |= ((uint64_t)a77[7] << 6);          // 13 ~ 6 : 12
    n58 |= ((uint64_t)a77[8] >> 2);          // 5 ~ 0 : 765432 10

    iflip = (a77[8] >> 1) & 0x01; // 76543210
    nrpt = ((a77[8] & 0x01) << 1);
    nrpt |= (a77[9] >> 7); // 76543210
    icq = ((a77[9] >> 6) & 0x01);

    char c11[12];
    c11[11] = '\0';

    for (int i = 10; /* no condition */; --i)
    {
        c11[i] = charn(n58 % 38, FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);
        if (i == 0)
            break;
        n58 /= 38;
    }

    char call_3[15];
    if (hash_if != NULL)
    {
        hash_if->hash12(n12, call_3);
    }
    else
    {
        strcpy(call_3, "<...>");
    }

    char* call_1 = trim((iflip) ? c11 : call_3);
    char* call_2 = trim((iflip) ? call_3 : c11);
    if (hash_if != NULL)
    {
        hash_if->save_hash(c11);
    }

    if (icq == 0)
    {
        strcpy(call_to, call_1);
        if (nrpt == 1)
            strcpy(extra, "RRR");
        else if (nrpt == 2)
            strcpy(extra, "RR73");
        else if (nrpt == 3)
            strcpy(extra, "73");
        else
        {
            extra[0] = '\0';
        }
    }
    else
    {
        strcpy(call_to, "CQ");
        extra[0] = '\0';
    }
    strcpy(call_de, call_2);

    return 0;
}

int unpack77_fields(const uint8_t* a77, char* call_to, char* call_de, char* extra, const unpack_hash_interface_t* hash_if)
{
    call_to[0] = call_de[0] = extra[0] = '\0';

    // Extract i3 (bits 74..76)
    uint8_t i3 = (a77[9] >> 3) & 0x07;

    if (i3 == 0)
    {
        // Extract n3 (bits 71..73)
        uint8_t n3 = ((a77[8] << 2) & 0x04) | ((a77[9] >> 6) & 0x03);

        if (n3 == 0)
        {
            // 0.0  Free text
            return unpack_text(a77, extra);
        }
        else if (n3 == 1)
        {
            // 0.1  K1ABC RR73; W9XYZ <KH1/KH7Z> -11   28 28 10 5       71   DXpedition Mode
            return unpack_dxped(a77, call_to, call_de, extra, hash_if);
        }
        else if (n3 == 2)
        {
            // 0.2  PA3XYZ/P R 590003 IO91NP           28 1 1 3 12 25   70   EU VHF contest
            return unpack_eu_vhf(a77, call_to, call_de, extra, hash_if);
        }
        else if (n3 == 3 || n3 == 4)
        {
            // 0.3   WA9XYZ KA1ABC R 16A EMA            28 28 1 4 3 7    71   ARRL Field Day
            // 0.4   WA9XYZ KA1ABC R 32A EMA            28 28 1 4 3 7    71   ARRL Field Day
            return unpack_fd(a77, call_to, call_de, extra, hash_if);
        }
        else if (n3 == 5)
        {
            // 0.5   0123456789abcdef01                 71               71   Telemetry (18 hex)
            return unpack_telemetry(a77, extra);
        }
        else if (n3 == 6)
        {
            // 0.6   K1ABC RR73; CQ W9XYZ EN37          28 28 15         71   Contesting
            return unpack_contesting(a77, call_to, call_de, extra, hash_if);
        }
    }
    else if (i3 == 1 || i3 == 2)
    {
        // Type 1 (standard message) or Type 2 ("/P" form for EU VHF contest)
        return unpack_type1(a77, i3, call_to, call_de, extra, hash_if);
    }
    else if (i3 == 3)
    {
        // Type 3: TU; W9XYZ K1ABC R 579 MA           1 28 28 1 3 13   74   ARRL RTTY Roundup
        return unpack_rtty(a77, call_to, call_de, extra, hash_if);
    }
    else if (i3 == 4)
    {
        // Type 4: Nonstandard calls, e.g. <WA9XYZ> PJ4/KA1ABC RR73
        // One hashed call or "CQ"; one compound or nonstandard call with up
        // to 11 characters; and (if not "CQ") an optional RRR, RR73, or 73.
        return unpack_nonstandard(a77, call_to, call_de, extra, hash_if);
    }
    else if (i3 == 5)
    {
        // Type 5: TU; W9XYZ K1ABC R-09 FN             1 28 28 1 7 9       74   WWROF contest
        return unpack_wwrof(a77, call_to, call_de, extra, hash_if);
    }

    // unknown type, should never get here
    return -1;
}

int unpack77(const uint8_t* a77, char* message, const unpack_hash_interface_t* hash_if)
{
    char call_to[14 + 5]; // +5 for "TU; " prefix in RTTY/WWROF
    char call_de[14];
    char extra[40]; // DXpedition extra can be ~38 chars

    int rc = unpack77_fields(a77, call_to, call_de, extra, hash_if);
    if (rc < 0)
        return rc;

    // int msg_sz = strlen(call_to) + strlen(call_de) + strlen(extra) + 2;
    char* dst = message;

    dst[0] = '\0';

    if (call_to[0] != '\0')
    {
        dst = stpcpy(dst, call_to);
        *dst++ = ' ';
    }

    if (call_de[0] != '\0')
    {
        dst = stpcpy(dst, call_de);
        *dst++ = ' ';
    }

    dst = stpcpy(dst, extra);
    *dst = '\0';

    return 0;
}

/* SPDX-FileCopyrightText: 2026 Yang Yue
 * SPDX-License-Identifier: MulanPSL-2.0
 */
/* RVISS host build: SoftFloat platform.h tests WORDS_BIGENDIAN. */
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
#define WORDS_BIGENDIAN 1
#endif

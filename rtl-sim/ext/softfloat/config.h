/* RVISS host build: SoftFloat platform.h tests WORDS_BIGENDIAN. */
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
#define WORDS_BIGENDIAN 1
#endif

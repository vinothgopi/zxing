/*
 * Copyright 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.qrcode.decoder;

import com.google.zxing.ReaderException;

import java.io.UnsupportedEncodingException;

/**
 * See ISO 18004:2006, 6.4.3 - 6.4.7
 *
 * @author srowen@google.com (Sean Owen)
 */
final class DecodedBitStreamParser {

  /**
   * See ISO 18004:2006, 6.4.4 Table 5
   */
  private static final char[] ALPHANUMERIC_CHARS = new char[]{
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B',
      'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N',
      'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
      ' ', '$', '%', '*', '+', '-', '.', '/', ':'
  };
  private static final String SHIFT_JIS = "Shift_JIS";
  private static final boolean ASSUME_SHIFT_JIS;

  static {
    String platformDefault = System.getProperty("file.encoding");
    ASSUME_SHIFT_JIS = SHIFT_JIS.equalsIgnoreCase(platformDefault) ||
        "EUC-JP".equalsIgnoreCase(platformDefault);
  }

  private DecodedBitStreamParser() {
  }

  static String decode(byte[] bytes, Version version) throws ReaderException {
    BitSource bits = new BitSource(bytes);
    StringBuffer result = new StringBuffer();
    Mode mode;
    do {
      // While still another segment to read...
      mode = Mode.forBits(bits.readBits(4));
      if (!mode.equals(Mode.TERMINATOR)) {
        int count = bits.readBits(mode.getCharacterCountBits(version));
        if (mode.equals(Mode.NUMERIC)) {
          decodeNumericSegment(bits, result, count);
        } else if (mode.equals(Mode.ALPHANUMERIC)) {
          decodeAlphanumericSegment(bits, result, count);
        } else if (mode.equals(Mode.BYTE)) {
          decodeByteSegment(bits, result, count);
        } else if (mode.equals(Mode.KANJI)) {
          decodeKanjiSegment(bits, result, count);
        } else {
          throw new ReaderException("Unsupported mode indicator: " + mode);
        }
      }
    } while (!mode.equals(Mode.TERMINATOR));

    /*
    int bitsLeft = bits.available();
    if (bitsLeft > 0) {
      if (bitsLeft > 6 || bits.readBits(bitsLeft) != 0) {
        throw new ReaderException("Excess bits or non-zero bits after terminator mode indicator");
      }
    }
     */
    return result.toString();
  }

  private static void decodeKanjiSegment(BitSource bits,
                                         StringBuffer result,
                                         int count) throws ReaderException {
    byte[] buffer = new byte[2 * count];
    int offset = 0;
    while (count > 0) {
      int twoBytes = bits.readBits(13);
      int assembledTwoBytes = ((twoBytes / 0x0C0) << 8) | (twoBytes % 0x0C0);
      if (assembledTwoBytes < 0x01F00) {
        // In the 0x8140 to 0x9FFC range
        assembledTwoBytes += 0x08140;
      } else {
        // In the 0xE040 to 0xEBBF range
        assembledTwoBytes += 0x0C140;
      }
      buffer[offset] = (byte) (assembledTwoBytes >> 8);
      buffer[offset + 1] = (byte) assembledTwoBytes;
      offset += 2;
      count--;
    }
    // Shift_JIS may not be supported in some environments:
    try {
      result.append(new String(buffer, "Shift_JIS"));
    } catch (UnsupportedEncodingException uee) {
      throw new ReaderException("Can't decode SHIFT_JIS string: " + uee);
    }
  }

  private static void decodeByteSegment(BitSource bits,
                                        StringBuffer result,
                                        int count) throws ReaderException {
    byte[] readBytes = new byte[count];
    if (count << 3 > bits.available()) {
      throw new ReaderException("Count too large: " + count);
    }
    for (int i = 0; i < count; i++) {
      readBytes[i] = (byte) bits.readBits(8);
    }
    // The spec isn't clear on this mode; see
    // section 6.4.5: t does not say which encoding to assuming
    // upon decoding. I have seen ISO-8859-1 used as well as
    // Shift_JIS -- without anything like an ECI designator to
    // give a hint.
    String encoding = guessEncoding(readBytes);
    try {
      result.append(new String(readBytes, encoding));
    } catch (UnsupportedEncodingException uce) {
      throw new ReaderException(uce.toString());
    }
  }

  private static void decodeAlphanumericSegment(BitSource bits,
                                                StringBuffer result,
                                                int count) {
    // Read two characters at a time
    while (count > 1) {
      int nextTwoCharsBits = bits.readBits(11);
      result.append(ALPHANUMERIC_CHARS[nextTwoCharsBits / 45]);
      result.append(ALPHANUMERIC_CHARS[nextTwoCharsBits % 45]);
      count -= 2;
    }
    if (count == 1) {
      // special case on char left
      result.append(ALPHANUMERIC_CHARS[bits.readBits(6)]);
    }
  }

  private static void decodeNumericSegment(BitSource bits,
                                           StringBuffer result,
                                           int count) throws ReaderException {
    while (count >= 3) {
      int threeDigitsBits = bits.readBits(10);
      if (threeDigitsBits >= 1000) {
        throw new ReaderException("Illegal value for 3-digit unit: " + threeDigitsBits);
      }
      result.append(ALPHANUMERIC_CHARS[threeDigitsBits / 100]);
      result.append(ALPHANUMERIC_CHARS[(threeDigitsBits / 10) % 10]);
      result.append(ALPHANUMERIC_CHARS[threeDigitsBits % 10]);
      count -= 3;
    }
    if (count == 2) {
      int twoDigitsBits = bits.readBits(7);
      if (twoDigitsBits >= 100) {
        throw new ReaderException("Illegal value for 2-digit unit: " + twoDigitsBits);
      }
      result.append(ALPHANUMERIC_CHARS[twoDigitsBits / 10]);
      result.append(ALPHANUMERIC_CHARS[twoDigitsBits % 10]);
    } else if (count == 1) {
      int digitBits = bits.readBits(4);
      if (digitBits >= 10) {
        throw new ReaderException("Illegal value for digit unit: " + digitBits);
      }
      result.append(ALPHANUMERIC_CHARS[digitBits]);
    }
  }

  private static String guessEncoding(byte[] bytes) {
    if (ASSUME_SHIFT_JIS) {
      return SHIFT_JIS;
    }
    // For now, merely tries to distinguish ISO-8859-1 and Shift_JIS,
    // which should be by far the most common encodings. ISO-8859-1
    // should not have bytes in the 0x80 - 0x9F range, while Shift_JIS
    // uses this as a first byte of a two-byte character. If we see this
    // followed by a valid second byte in Shift_JIS, assume it is Shift_JIS.
    int length = bytes.length;
    for (int i = 0; i < length; i++) {
      int value = bytes[i] & 0xFF;
      if (value >= 0x80 && value <= 0x9F && i < length - 1) {
        // ISO-8859-1 shouldn't use this, but before we decide it is Shift_JIS,
        // just double check that it is followed by a byte that's valid in
        // the Shift_JIS encoding
        int nextValue = bytes[i + 1] & 0xFF;
        if ((value & 0x1) == 0) {
          // if even,
          if (nextValue >= 0x40 && nextValue <= 0x9E) {
            return SHIFT_JIS;
          }
        } else {
          if (nextValue >= 0x9F && nextValue <= 0x7C) {
            return SHIFT_JIS;
          }
        }
      }
    }
    return "ISO-8859-1";
  }

}
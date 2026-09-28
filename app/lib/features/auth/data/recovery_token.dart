import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

/// Encodes the device's 128-bit entropy (the same bytes [DeviceCredential.fromEntropy] HKDF-derives
/// the Ed25519 seed from, Story 8.5) as one short, typeable token — the *only* thing that survives
/// reinstall or a move to a new device. Supersedes Story 7.2/7.3's 24-word phrase-based encoding
/// (beta testers balked at it, manual test 2026-09-20).
///
/// Format (Timo, 2026-09-28, D1): Crockford Base32 — 26 data characters (the 128 entropy bits plus
/// 2 zero padding bits, so 130 bits divide evenly into 5-bit characters) followed by 2 checksum
/// characters (the first 10 bits of SHA-256(entropy), catching a mistype without needing a second
/// independent transcription). [format] renders all 28 characters as 7 hyphen-separated groups of
/// four, e.g. `K7QM-2XRA-9FDT-HB4W-0NCE-M3PY-Q8ZJ`. [parse] is forgiving: case-insensitive, any
/// whitespace/hyphens are ignored, and Crockford's standard aliases (`O`→`0`, `I`/`L`→`1`) are
/// accepted — `U` is not an alias target and is simply illegal, exactly as Crockford defines it.
///
/// Hand-rolled (no base32 package): the alphabet and grouping are simple enough that a dependency
/// would only add ceremony (YAGNI). The checksum hash reuses the already-declared `cryptography`
/// package (the same one [DeviceCredential] uses for HKDF/Ed25519) rather than adding a new one.
abstract final class RecoveryToken {
  /// Crockford's 32-symbol alphabet — deliberately excludes `I`, `L`, `O`, `U` (visually confusable
  /// with `1`/`1`/`0`/`V`), so [_normalize] can alias the first three back in without ambiguity.
  static const _alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

  static const _entropyLengthBytes = 16;
  static const _dataCharCount = 26; // 130 bits: 128 entropy + 2 zero padding bits
  static const _checksumCharCount = 2; // 10 bits: the first 10 bits of SHA-256(entropy)
  static const _checksumBitLength = 10;
  static const _groupSize = 4;

  /// @return the 28-character token for [entropy], grouped as 7 hyphen-separated groups of 4.
  static Future<String> format(Uint8List entropy) async {
    if (entropy.length != _entropyLengthBytes) {
      throw ArgumentError.value(entropy.length, 'entropy.length', 'must be $_entropyLengthBytes bytes');
    }
    final dataValue = _bigIntFromBytes(entropy) << 2; // append the 2 zero padding bits
    final dataChars = _charsFromValue(dataValue, _dataCharCount * 5, _dataCharCount);
    final checksumChars = _charsFromValue(await _checksumValue(entropy), _checksumBitLength, _checksumCharCount);
    return _group(dataChars + checksumChars);
  }

  /// The inverse of [format]: validates [input] — length, alphabet, and checksum, all three in one
  /// call — and reconstructs the original 16-byte entropy. Throws [InvalidRecoveryToken] on any
  /// invalid input rather than letting a raw parsing error escape, so callers can fail fast with one
  /// typed error regardless of which rule was violated, and never proceed with partial state.
  static Future<Uint8List> parse(String input) async {
    final normalized = _normalize(input);
    if (normalized.length != _dataCharCount + _checksumCharCount) {
      throw const InvalidRecoveryToken();
    }
    if (!normalized.split('').every(_alphabet.contains)) {
      throw const InvalidRecoveryToken();
    }
    final dataChars = normalized.substring(0, _dataCharCount);
    final checksumChars = normalized.substring(_dataCharCount);

    final dataValue = _valueFromChars(dataChars);
    // The 2 low bits are always-zero padding ([format] never sets them) — a nonzero value here
    // means a mistyped/corrupted last data character, so reject it explicitly rather than letting
    // 4 different spellings of that character silently decode to the same entropy.
    if (dataValue & BigInt.from(0x3) != BigInt.zero) {
      throw const InvalidRecoveryToken();
    }
    final entropy = _bytesFromBigInt(dataValue >> 2, _entropyLengthBytes); // drop the 2 padding bits

    final expectedChecksumChars = _charsFromValue(await _checksumValue(entropy), _checksumBitLength, _checksumCharCount);
    if (checksumChars != expectedChecksumChars) {
      throw const InvalidRecoveryToken();
    }
    return entropy;
  }

  /// The first 10 bits of SHA-256(entropy), as an unsigned integer value (0–1023) — a mistype
  /// checksum, not a security boundary: the entropy itself already carries the full 128 bits.
  static Future<BigInt> _checksumValue(Uint8List entropy) async {
    final digest = await Sha256().hash(entropy);
    final bytes = digest.bytes;
    return BigInt.from((bytes[0] << 2) | (bytes[1] >> 6));
  }

  /// Renders [value] (holding exactly [bitLength] significant bits) as [charCount] Crockford
  /// characters, most-significant 5-bit group first.
  static String _charsFromValue(BigInt value, int bitLength, int charCount) {
    final buffer = StringBuffer();
    for (var index = 0; index < charCount; index++) {
      final shift = bitLength - 5 * (index + 1);
      final chunk = (value >> shift) & BigInt.from(0x1F);
      buffer.write(_alphabet[chunk.toInt()]);
    }
    return buffer.toString();
  }

  /// The inverse of [_charsFromValue]: reassembles the big-endian bit value the characters encode.
  static BigInt _valueFromChars(String chars) {
    var value = BigInt.zero;
    for (final char in chars.split('')) {
      value = (value << 5) | BigInt.from(_alphabet.indexOf(char));
    }
    return value;
  }

  static BigInt _bigIntFromBytes(Uint8List bytes) {
    var value = BigInt.zero;
    for (final byte in bytes) {
      value = (value << 8) | BigInt.from(byte);
    }
    return value;
  }

  static Uint8List _bytesFromBigInt(BigInt value, int byteLength) {
    final bytes = Uint8List(byteLength);
    var remaining = value;
    for (var index = byteLength - 1; index >= 0; index--) {
      bytes[index] = (remaining & BigInt.from(0xFF)).toInt();
      remaining = remaining >> 8;
    }
    return bytes;
  }

  static String _group(String characters) {
    final buffer = StringBuffer();
    for (var index = 0; index < characters.length; index += _groupSize) {
      if (index > 0) buffer.write('-');
      buffer.write(characters.substring(index, index + _groupSize));
    }
    return buffer.toString();
  }

  /// Forgiving input handling (Boundaries & Constraints): uppercases, strips whitespace/hyphens, and
  /// applies Crockford's standard aliases — `O`→`0`, `I`/`L`→`1`. `U` is deliberately not an alias
  /// target (Crockford excludes it entirely) and is left as-is, so it correctly fails the alphabet
  /// check in [parse] rather than being silently accepted.
  static String _normalize(String input) {
    // Strips whitespace, the ASCII hyphen-minus, and the Unicode dashes a notes app or
    // autocorrect commonly substitutes for it (en dash, em dash, minus sign, etc.), so a pasted
    // token is not rejected over punctuation alone.
    final cleaned = input.toUpperCase().replaceAll(RegExp(r'[\s\-‐-―−]'), '');
    final buffer = StringBuffer();
    for (final char in cleaned.split('')) {
      buffer.write(switch (char) {
        'O' => '0',
        'I' || 'L' => '1',
        _ => char,
      });
    }
    return buffer.toString();
  }
}

/// Thrown by [RecoveryToken.parse] when the given input fails validation (wrong length, an illegal
/// character, or a failed checksum). Callers rely on this one type covering every rejection case, so
/// the recovery UI can show a single "token invalid" message with a plain `catch`/error-code branch
/// instead of inspecting exception messages.
class InvalidRecoveryToken implements Exception {
  const InvalidRecoveryToken();

  @override
  String toString() => 'InvalidRecoveryToken';
}

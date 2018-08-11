//: ----------------------------------------------------------------------------
//: Copyright (C) 2017 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package nelson
package crypto
package protocol

import cats.effect.IO

import scodec._
import scodec.bits.{BitVector, ByteVector}

/**
 * a codec that delegates to the `target` codec but applies encryption during
 * encoding and decryption during decoding.
 *
 * Note: when decoding, this consumes the entire remaning `BitVector`, even if
 * the `target` codec wouldn't normally consume it in entirety. This is because
 * when it decrypts the `BitVector`, it doesn't know how much of it has been
 * encrypted, so it must assume the whole `BitVector` has been. If only part of
 * your encoding is to be encrypted, then you should make sure it's either the
 * last part, or you should perform special handling to grab the subsection of
 * the `BitVector` that you want to decrypt and pass only that to `decode`.
 *
 * During encryption, a one-time initialization vector is used. It is based on
 * the `keyId` and a nonce generated by `nextNonce`.
 *
 * @see `scodec.codecs.encrypted`, which is similar but doesn't have our key
 * ID and nonce usage built into it.
 *
 * @param target The underlying codec to use.
 *  During encoding, this codec is used, and then the resulting bits are
 *  encrypted. During decoding, the bits are decrypted, and then this codec is used.
 *
 * @param nextNonce a `IO` that, when called, will generate an unpredictable
 *  random number. Typically this will be produced by
 *  `java.security.SecureRandom`.
 */
private[protocol] final class EncryptedCodec[A](target: Codec[A], key: EncryptionKey, encryptor: Encryptor[AuthResult], decryptor: Decryptor[AuthResult], nextNonce: IO[Nonce]) extends Codec[A] {
  import EncryptedCodec._

  def attemptNextNonce(): Attempt[Nonce] =
    nextNonce.attempt.unsafeRunSync().fold(
      err => Attempt.failure(Err(s"failed to create nonce for encryption: $err.getMessage")),
      Attempt.successful)

  def encrypt(bits: BitVector, iv: InitializationVector): Attempt[ByteVector] =
    authResultToAttempt(encryptor.encrypt(bits.toByteVector, key, iv))

  def decrypt(bits: BitVector, iv: InitializationVector): Attempt[ByteVector] =
    authResultToAttempt(decryptor.decrypt(bits.toByteVector, key, iv))

  def ivFromNonce(nonceBytes: ByteVector): InitializationVector =
    InitializationVector.unsafe(nonceBytes)

  override def sizeBound: SizeBound = nonceBytesCodec.sizeBound.atLeast

  final override def encode(a: A): Attempt[BitVector] =
    for {
      nonce <- attemptNextNonce()
      nonceBytes = nonce.toBytes
      bits <- target.encode(a)
      encrypted <- encrypt(bits, ivFromNonce(nonceBytes))
    } yield (nonceBytes ++ encrypted).toBitVector

  final override def decode(buffer: BitVector): Attempt[DecodeResult[A]] =
    for {
      nonce <- nonceBytesCodec.decode(buffer)
      decrypted <- decrypt(nonce.remainder, ivFromNonce(nonce.value))
      a <- target.decode(decrypted.toBitVector)
    } yield a.mapRemainder(_ => BitVector.empty)

  override def toString: String = s"EncryptedCodec($target)"
}

object EncryptedCodec {
  private val nonceBytesCodec: Codec[ByteVector] = codecs.bytes(16)

  def instance[A](target: Codec[A], key: EncryptionKey, encryptor: Encryptor[AuthResult], decryptor: Decryptor[AuthResult], nextNonce: IO[Nonce]): Codec[A] = new EncryptedCodec(target, key, encryptor, decryptor, nextNonce)

  def fromAuthEnv[A](env: AuthEnv, key: EncryptionKey, codec: Codec[A]): Codec[A] =
    instance(codec, key, env.encryptor, env.decryptor, env.nextNonce)
}
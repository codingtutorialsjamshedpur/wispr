/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a hardware-backed key that never leaves the Android Keystore. */
object Crypto {
    private const val ALIAS = "wispr_dg_master_v1"
    private const val STORE = "AndroidKeyStore"

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    fun decrypt(data: ByteArray): ByteArray {
        val n = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + n)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(data, 1 + n, data.size - 1 - n)
    }

    fun encStr(s: String): String = Base64.encodeToString(encrypt(s.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    fun decStr(s: String): String = String(decrypt(Base64.decode(s, Base64.NO_WRAP)), Charsets.UTF_8)
}

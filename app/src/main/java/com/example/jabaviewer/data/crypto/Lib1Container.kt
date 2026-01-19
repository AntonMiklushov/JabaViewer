package com.example.jabaviewer.data.crypto

data class Lib1Container(
    val iterations: Int,
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

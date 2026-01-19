package com.example.jabaviewer.ui.screens.details

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jabaviewer.data.crypto.CryptoEngine
import com.example.jabaviewer.data.repository.DownloadRepository
import com.example.jabaviewer.data.repository.LibraryRepository
import com.example.jabaviewer.data.security.PassphraseStore
import com.example.jabaviewer.data.storage.DocumentStorage
import com.example.jabaviewer.domain.model.LibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.AEADBadTagException
import javax.inject.Inject

data class ItemDetailsState(
    val item: LibraryItem? = null,
    val isRemoving: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val message: String? = null,
)

@HiltViewModel
class ItemDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val storage: DocumentStorage,
    private val passphraseStore: PassphraseStore,
    private val cryptoEngine: CryptoEngine,
) : ViewModel() {
    private val itemId = savedStateHandle.get<String>("itemId").orEmpty()
    private val _state = MutableStateFlow(ItemDetailsState())
    val state: StateFlow<ItemDetailsState> = _state

    init {
        viewModelScope.launch {
            libraryRepository.observeLibrary()
                .map { items -> items.firstOrNull { it.id == itemId } }
                .collect { item ->
                    _state.value = _state.value.copy(item = item)
                }
        }
    }

    fun download() {
        if (itemId.isNotBlank()) {
            downloadRepository.enqueueDownload(itemId)
        }
    }

    fun cancelDownload() {
        if (itemId.isNotBlank()) {
            downloadRepository.cancelDownload(itemId)
        }
    }

    fun removeDownload(onRemoved: () -> Unit) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isRemoving = true, errorMessage = null, message = null)
            withContext(Dispatchers.IO) {
                val encryptedFile = storage.encryptedFileFor(item.objectKey)
                if (encryptedFile.exists()) {
                    encryptedFile.delete()
                }
                val decryptedFile = storage.decryptedFileFor(item.id)
                if (decryptedFile.exists()) {
                    decryptedFile.delete()
                    decryptedFile.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
                }
            }
            libraryRepository.clearDownloadState(item.id)
            libraryRepository.updateReadingState(
                itemId = item.id,
                decryptedCachePath = null,
                lastPage = null,
                lastOpenedAt = null,
            )
            _state.value = _state.value.copy(isRemoving = false)
            onRemoved()
        }
    }

    fun saveDecryptedCopy(contentResolver: ContentResolver, targetUri: Uri) {
        val item = _state.value.item ?: return
        if (_state.value.isSaving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, errorMessage = null, message = null)
            try {
                withContext(Dispatchers.IO) {
                    val encryptedFile = libraryRepository.getLocalDocument(item.id)
                        ?.encryptedFilePath
                        ?.let { File(it) }
                        ?: storage.encryptedFileFor(item.objectKey)
                    if (!encryptedFile.exists()) {
                        throw IllegalStateException("Document is not downloaded")
                    }
                    val decryptedFile = storage.decryptedFileFor(item.id)
                    val hasValidDecrypted = decryptedFile.exists() && isPdfValid(decryptedFile)
                    if (!hasValidDecrypted) {
                        if (decryptedFile.exists()) {
                            decryptedFile.delete()
                        }
                        decryptToFile(encryptedFile, decryptedFile)
                    }
                    contentResolver.openOutputStream(targetUri)?.use { output ->
                        decryptedFile.inputStream().use { input ->
                            input.copyTo(output)
                            output.flush()
                        }
                    } ?: throw IllegalStateException("Unable to open destination")
                    if (!hasValidDecrypted) {
                        decryptedFile.delete()
                        decryptedFile.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
                    }
                }
                _state.value = _state.value.copy(isSaving = false, message = "Decrypted PDF saved")
            } catch (error: Exception) {
                val message = when (error) {
                    is AEADBadTagException -> "Wrong passphrase or corrupted file"
                    else -> error.message ?: "Failed to save decrypted file"
                }
                _state.value = _state.value.copy(isSaving = false, errorMessage = message)
            }
        }
    }

    private suspend fun decryptToFile(encryptedFile: File, decryptedFile: File) {
        withContext(Dispatchers.IO) {
            val passphrase = passphraseStore.getPassphrase()
                ?: throw IllegalStateException("Passphrase is missing")
            cryptoEngine.decryptToFile(encryptedFile, decryptedFile, passphrase.toCharArray())
        }
        if (!isPdfValid(decryptedFile)) {
            decryptedFile.delete()
            throw IllegalStateException("Wrong passphrase or corrupted file")
        }
    }

    private fun isPdfValid(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val header = ByteArray(5)
            file.inputStream().use { input ->
                val read = input.read(header)
                read == 5 && String(header, Charsets.US_ASCII).startsWith("%PDF-")
            }
        } catch (_: Exception) {
            false
        }
    }
}

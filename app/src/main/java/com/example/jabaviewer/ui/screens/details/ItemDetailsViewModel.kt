package com.example.jabaviewer.ui.screens.details

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jabaviewer.core.isPdfValid
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
import java.io.IOException
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
                    val encryptedFile = resolveEncryptedFile(item)
                    val decryptedFile = storage.decryptedFileFor(item.id)
                    val usedTempDecrypt = ensureDecryptedFile(encryptedFile, decryptedFile)
                    writeDecryptedCopy(contentResolver, targetUri, decryptedFile)
                    if (usedTempDecrypt) {
                        decryptedFile.delete()
                        decryptedFile.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
                    }
                }
                _state.value = _state.value.copy(isSaving = false, message = "Decrypted PDF saved")
            } catch (error: AEADBadTagException) {
                Log.e(TAG, "Failed to save decrypted file: bad tag", error)
                _state.value = _state.value.copy(
                    isSaving = false,
                    errorMessage = "Wrong passphrase or corrupted file",
                )
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Failed to save decrypted file: state", error)
                _state.value = _state.value.copy(
                    isSaving = false,
                    errorMessage = error.message ?: "Failed to save decrypted file",
                )
            } catch (error: IOException) {
                Log.e(TAG, "Failed to save decrypted file: IO", error)
                _state.value = _state.value.copy(
                    isSaving = false,
                    errorMessage = error.message ?: "Failed to save decrypted file",
                )
            } catch (error: SecurityException) {
                Log.e(TAG, "Failed to save decrypted file: security", error)
                _state.value = _state.value.copy(
                    isSaving = false,
                    errorMessage = error.message ?: "Failed to save decrypted file",
                )
            }
        }
    }

    private suspend fun resolveEncryptedFile(item: LibraryItem): File {
        val localPath = libraryRepository.getLocalDocument(item.id)
            ?.encryptedFilePath
            ?.let { File(it) }
        val encryptedFile = localPath ?: storage.encryptedFileFor(item.objectKey)
        check(encryptedFile.exists()) { "Document is not downloaded" }
        return encryptedFile
    }

    private suspend fun ensureDecryptedFile(encryptedFile: File, decryptedFile: File): Boolean {
        val hasValidDecrypted = decryptedFile.exists() && isPdfValid(decryptedFile)
        if (!hasValidDecrypted) {
            if (decryptedFile.exists()) {
                decryptedFile.delete()
            }
            decryptToFile(encryptedFile, decryptedFile)
        }
        return !hasValidDecrypted
    }

    private fun writeDecryptedCopy(
        contentResolver: ContentResolver,
        targetUri: Uri,
        decryptedFile: File,
    ) {
        val outputStream = contentResolver.openOutputStream(targetUri)
        checkNotNull(outputStream) { "Unable to open destination" }
        outputStream.use { output ->
            decryptedFile.inputStream().use { input ->
                input.copyTo(output)
                output.flush()
            }
        }
    }

    private suspend fun decryptToFile(encryptedFile: File, decryptedFile: File) {
        withContext(Dispatchers.IO) {
            val passphrase = checkNotNull(passphraseStore.getPassphrase()) {
                "Passphrase is missing"
            }
            cryptoEngine.decryptToFile(encryptedFile, decryptedFile, passphrase.toCharArray())
        }
        if (!isPdfValid(decryptedFile)) {
            decryptedFile.delete()
            error("Wrong passphrase or corrupted file")
        }
    }

    private companion object {
        private const val TAG = "ItemDetailsViewModel"
    }
}

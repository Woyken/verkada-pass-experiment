package com.woyken.verkadapass

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.woyken.verkadapass.data.DoorItem
import com.woyken.verkadapass.data.RemoteBuild
import com.woyken.verkadapass.data.SessionData
import com.woyken.verkadapass.data.SessionStore
import com.woyken.verkadapass.data.UpdateChecker
import com.woyken.verkadapass.data.VerkadaApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class AppScreen { Loading, EmailInput, MagicLinkInput, DoorList, TileSettings }

data class AppUiState(
    val screen: AppScreen = AppScreen.Loading,
    val email: String = "",
    val magicLinkUrl: String = "",
    val session: SessionData? = null,
    val doors: List<DoorItem> = emptyList(),
    val favoriteDoorIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val unlockingDoorId: String? = null,
    val unlockedDoorId: String? = null,
    val updateAvailable: RemoteBuild? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore = SessionStore(application)
    private val api = VerkadaApi()
    private val updateChecker = UpdateChecker(application)
    private val favorites = application.getSharedPreferences("door_favorites", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(AppUiState(
        favoriteDoorIds = favorites.getStringSet("door_ids", emptySet())?.toSet() ?: emptySet(),
    ))
    val state: StateFlow<AppUiState> = _state

    private var updateCheckStarted = false

    init {
        viewModelScope.launch {
            val saved = sessionStore.session.first()
            if (saved != null) {
                _state.value = _state.value.copy(screen = AppScreen.DoorList, session = saved, email = saved.email)
                loadDoors(saved)
            } else {
                _state.value = _state.value.copy(screen = AppScreen.EmailInput)
            }
        }
    }

    fun onEmailChanged(email: String) {
        _state.value = _state.value.copy(email = email, error = null)
    }

    fun onMagicLinkUrlChanged(url: String) {
        _state.value = _state.value.copy(magicLinkUrl = url, error = null)
    }

    fun submitEmail() {
        val email = _state.value.email.trim()
        if (email.isBlank()) {
            _state.value = _state.value.copy(error = "Email is required")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val result = api.requestMagicLink(email)
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(isLoading = false, screen = AppScreen.MagicLinkInput)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Failed to request magic link")
                }
            )
        }
    }

    fun submitMagicLink() {
        val url = _state.value.magicLinkUrl.trim()
        if (url.isBlank()) {
            _state.value = _state.value.copy(error = "Paste the magic link URL from your email")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val params = api.parseMagicLinkUrl(url)
                val result = api.redeemMagicLink(params)
                result.fold(
                    onSuccess = { session ->
                        sessionStore.save(session)
                        _state.value = _state.value.copy(
                            isLoading = false,
                            screen = AppScreen.DoorList,
                            session = session,
                            email = session.email,
                        )
                        loadDoors(session)
                    },
                    onFailure = { e ->
                        _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Login failed")
                    }
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Invalid magic link URL")
            }
        }
    }

    fun refreshDoors() {
        val session = _state.value.session ?: return
        loadDoors(session)
    }

    fun toggleFavorite(door: DoorItem) {
        val ids = _state.value.favoriteDoorIds
        val updated = if (door.accessPointId in ids) ids - door.accessPointId else ids + door.accessPointId
        favorites.edit().putStringSet("door_ids", updated).apply()
        _state.value = _state.value.copy(favoriteDoorIds = updated)
    }

    private fun loadDoors(session: SessionData) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val result = api.listDoors(session)
            result.fold(
                onSuccess = { doors ->
                    _state.value = _state.value.copy(isLoading = false, doors = doors)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Failed to load doors")
                }
            )
        }
    }

    fun unlockDoor(door: DoorItem) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(unlockingDoorId = door.accessPointId, unlockedDoorId = null, error = null)
            val result = api.unlockDoor(session, door.accessPointId)
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(unlockingDoorId = null, unlockedDoorId = door.accessPointId)
                    // Clear success indicator after 3 seconds
                    kotlinx.coroutines.delay(3000)
                    if (_state.value.unlockedDoorId == door.accessPointId) {
                        _state.value = _state.value.copy(unlockedDoorId = null)
                    }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(unlockingDoorId = null, error = e.message ?: "Unlock failed")
                }
            )
        }
    }

    fun navigateToTileSettings() {
        _state.value = _state.value.copy(screen = AppScreen.TileSettings)
    }

    fun navigateBack() {
        _state.value = _state.value.copy(screen = AppScreen.DoorList)
    }

    fun logout() {
        viewModelScope.launch {
            sessionStore.clear()
            _state.value = AppUiState(screen = AppScreen.EmailInput, favoriteDoorIds = _state.value.favoriteDoorIds)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Called when the door list is shown. At most one GitHub request per day;
     * failures are silent because an update is never urgent enough to interrupt
     * unlocking.
     */
    fun checkForUpdate() {
        if (updateCheckStarted) return
        updateCheckStarted = true
        if (!updateChecker.isCheckDue()) return
        updateChecker.markChecked()
        viewModelScope.launch {
            updateChecker.latestBuild().onSuccess { build ->
                if (build.versionCode <= BuildConfig.VERSION_CODE) return@onSuccess
                if (updateChecker.isDismissed()) return@onSuccess
                _state.value = _state.value.copy(updateAvailable = build)
            }
        }
    }

    fun dismissUpdate() {
        updateChecker.dismiss()
        _state.value = _state.value.copy(updateAvailable = null)
    }

    fun openReleasePage() {
        val url = _state.value.updateAvailable?.pageUrl ?: return
        val context = getApplication<Application>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

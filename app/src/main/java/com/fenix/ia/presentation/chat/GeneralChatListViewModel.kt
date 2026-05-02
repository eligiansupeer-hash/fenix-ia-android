package com.fenix.ia.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.domain.model.Chat
import com.fenix.ia.domain.usecase.CreateGeneralChatUseCase
import com.fenix.ia.domain.usecase.GetGeneralChatsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// P4: ViewModel para la lista de chats globales (sin proyecto)
@HiltViewModel
class GeneralChatListViewModel @Inject constructor(
    private val getGeneralChats: GetGeneralChatsUseCase,
    private val createGeneralChat: CreateGeneralChatUseCase
) : ViewModel() {

    val chats: StateFlow<List<Chat>> = getGeneralChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createNewChat(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val chat = createGeneralChat()
            onCreated(chat.id)
        }
    }
}

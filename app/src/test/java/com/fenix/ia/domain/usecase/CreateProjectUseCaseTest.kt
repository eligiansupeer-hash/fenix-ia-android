package com.fenix.ia.domain.usecase

import com.fenix.ia.domain.repository.ProjectRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CreateProjectUseCaseTest {

    private val repository: ProjectRepository = mockk(relaxed = true)
    private val useCase = CreateProjectUseCase(repository)

    @Test
    fun `crear proyecto con nombre válido invoca repository`() = runTest {
        useCase("Álgebra Lineal", "Eres un tutor de matemáticas")
        coVerify { repository.createProject(any()) }
    }

    @Test
    fun `crear proyecto con nombre vacío lanza excepción`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { useCase("  ", "") }
        }
    }
}

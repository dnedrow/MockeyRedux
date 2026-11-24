package example

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class EmbeddedMockTest {
    @Test
    fun answersARequestWithoutOpeningAnHttpServer() = runBlocking {
        EmbeddedMock.create().use { mock ->
            val response = mock.handle("customer/42").response

            assertEquals(200, response.status)
            assertEquals("""{"id":1}""", response.bodyText)
        }
    }
}

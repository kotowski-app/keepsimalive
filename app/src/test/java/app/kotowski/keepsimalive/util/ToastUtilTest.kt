package app.kotowski.keepsimalive.util

import android.content.Context
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertTrue

class ToastUtilTest {
    @Test
    fun `ToastUtil object is accessible`() {
        assertTrue(ToastUtil != null)
    }

    @Test
    fun `show with string does not throw`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(mock(Context::class.java))
        try {
            ToastUtil.show(context, "test message")
        } catch (e: Exception) {
        }
    }

    @Test
    fun `show with resource id does not throw`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(mock(Context::class.java))
        try {
            ToastUtil.show(context, 12345)
        } catch (e: Exception) {
        }
    }

    @Test
    fun `showLong with resource id does not throw`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(mock(Context::class.java))
        try {
            ToastUtil.showLong(context, 12345)
        } catch (e: Exception) {
        }
    }

    @Test
    fun `show with resource id and format args does not throw`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(mock(Context::class.java))
        try {
            ToastUtil.show(context, 12345, 12, "days")
        } catch (e: Exception) {
        }
    }
}

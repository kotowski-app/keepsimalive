package app.kotowski.keepsimalive.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object ToastUtil {
    fun show(
        context: Context,
        message: String,
    ) {
        showOnMain(context) { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    fun show(
        context: Context,
        resId: Int,
    ) {
        showOnMain(context) { Toast.makeText(it, resId, Toast.LENGTH_SHORT).show() }
    }

    fun show(
        context: Context,
        resId: Int,
        vararg args: Any,
    ) {
        showOnMain(context) { Toast.makeText(it, it.getString(resId, *args), Toast.LENGTH_SHORT).show() }
    }

    fun showLong(
        context: Context,
        resId: Int,
    ) {
        showOnMain(context) { Toast.makeText(it, resId, Toast.LENGTH_LONG).show() }
    }

    // Toast must run on the main looper: calling it from a background coroutine throws
    // "Can't toast on a thread that has not called Looper.prepare()". The ViewModel's commit()
    // launches coroutines that show error toasts on whatever thread the coroutine resumes on.
    private fun showOnMain(
        context: Context,
        block: (Context) -> Unit,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block(context)
        } else {
            Handler(Looper.getMainLooper()).post { block(context) }
        }
    }
}

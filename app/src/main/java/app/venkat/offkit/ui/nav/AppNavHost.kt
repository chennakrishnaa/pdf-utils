package app.venkat.offkit.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.venkat.offkit.core.ReaderCopyHandoff
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.venkat.offkit.feature.addimages.AddImagesScreen
import app.venkat.offkit.feature.compress.CompressScreen
import app.venkat.offkit.feature.imagestopdf.ImagesToPdfScreen
import app.venkat.offkit.feature.merge.MergeScreen
import app.venkat.offkit.feature.reader.ReaderScreen
import app.venkat.offkit.ui.about.AboutScreen
import app.venkat.offkit.ui.home.HomeScreen
import app.venkat.offkit.ui.support.SupportScreen

/** Navigation route names. */
object Routes {
    const val HOME = "home"
    const val IMAGES_TO_PDF = "images-to-pdf"
    const val COMPRESS = "compress"
    const val MERGE = "merge"
    const val ADD_IMAGES = "add-images"
    const val READER = "reader"
    const val ABOUT = "about"
    const val SUPPORT = "support"
}

/**
 * A PDF to open in the reader: one another app asked Offkit to open ("Open with"), or one chosen
 * in the app (a recent file on home). [id] tells two requests for the same [uri] apart. [typeVerified] is false when the intent named no type: the reader then asks the
 * provider for it off the main thread before copying anything. [fromOtherApp] is true for "Open
 * with" (an intent from another app), false for an in-app open: Back from the reader then returns
 * to that other app. Held in memory only, never in navigation state.
 */
class ViewRequest(val uri: Uri, val id: Long, val typeVerified: Boolean = true, val fromOtherApp: Boolean = false)

/**
 * The reader's document handed to the Compress tool (the reader's "Compress"): posted by the
 * reader just before it opens Compress, taken once by Compress. Activity-scoped and in memory
 * only (gone after process death, when Compress shows its empty state); never in navigation state.
 */
class ToolHandoffs : ViewModel() {
    private var pending: ReaderCopyHandoff? = null

    fun post(handoff: ReaderCopyHandoff) {
        pending = handoff
    }

    /** The handed-off document, once; null afterwards. */
    fun take(): ReaderCopyHandoff? = pending.also { pending = null }
}

/**
 * The app's navigation graph, starting at [Routes.HOME].
 *
 * A non-null [viewRequest] opens the reader on its document, on top of the current back stack (so
 * Back returns to whatever was open, e.g. a tool's unsaved result, or to home); the reader calls
 * [onViewRequestHandled] once it has started opening it. [onOpenDocument] asks for such a request
 * from inside the app (home's recent files, a tool's saved result); it must post a [ViewRequest],
 * the only way a Uri enters the reader. The reader goes on top, so Back returns to that screen.
 *
 * Back from a reader whose document came from another app ([ViewRequest.fromOtherApp]) pops the
 * reader as usual (its copy is deleted) and then calls [onReturnToCaller], which moves the task to
 * the back so the user lands in that app; the screen under the reader is kept for their return.
 * The rules for mixed cases are on [ReaderScreen]. [launcherReturns] counts the user's returns
 * through the launcher icon; a new one makes that reader's Back stay in the app again.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    viewRequest: ViewRequest? = null,
    onViewRequestHandled: (ViewRequest) -> Unit = {},
    onOpenDocument: (Uri) -> Unit = {},
    onReturnToCaller: () -> Unit = {},
    launcherReturns: Int = 0,
) {
    // Outside the NavHost: scoped to the activity (or whatever owns this composition).
    val handoffs: ToolHandoffs = viewModel()
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(viewRequest, route) {
        // Not gated on RESUMED: the request arrives while the activity is starting. Keyed on the
        // route too, so a request still pending when the reader leaves (e.g. its cancelled
        // picker) opens it again.
        if (viewRequest != null && route != null && route != Routes.READER) {
            navController.navigate(Routes.READER) { launchSingleTop = true }
        }
    }
    // Only from the destination on screen: a request made after it was left (e.g. once home's
    // availability check returns) is dropped, so it never pushes the reader over another screen.
    val openFrom: (NavBackStackEntry) -> (Uri) -> Unit = { entry ->
        { uri -> if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onOpenDocument(uri) }
    }
    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) { entry ->
            // Ignore taps while a transition is in progress, so a double tap navigates once.
            val navigate: (String) -> Unit = { route ->
                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    navController.navigate(route) { launchSingleTop = true }
                }
            }
            HomeScreen(
                onOpenTool = navigate,
                onOpenAbout = { navigate(Routes.ABOUT) },
                onOpenSupport = { navigate(Routes.SUPPORT) },
                onOpenDocument = openFrom(entry),
            )
        }
        composable(Routes.IMAGES_TO_PDF) { entry ->
            ImagesToPdfScreen(onBack = dropUnlessResumed { navController.popBackStack() }, onOpenDocument = openFrom(entry))
        }
        composable(Routes.COMPRESS) { entry ->
            CompressScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenDocument = openFrom(entry),
                takeReaderCopy = handoffs::take,
            )
        }
        composable(Routes.MERGE) { entry ->
            MergeScreen(onBack = dropUnlessResumed { navController.popBackStack() }, onOpenDocument = openFrom(entry))
        }
        composable(Routes.ADD_IMAGES) { entry ->
            AddImagesScreen(onBack = dropUnlessResumed { navController.popBackStack() }, onOpenDocument = openFrom(entry))
        }
        composable(Routes.READER) { entry ->
            // Pops this reader's entry only, and only while it is on top: a reader still composed
            // during its exit transition must never pop the screens below it (e.g. another
            // reader and a tool's unsaved result). True if it popped.
            val popThis: () -> Boolean = {
                navController.currentBackStackEntry?.id == entry.id && navController.popBackStack()
            }
            ReaderScreen(
                // Compress goes on top of the reader, which stays below with its document (and
                // its private copy, which the job copies). An "Open" of Compress's saved result
                // then pushes a second reader: Back returns to Compress, then to this reader.
                onCompress = { handoff ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        handoffs.post(handoff)
                        navController.navigate(Routes.COMPRESS)
                    }
                },
                onBack = dropUnlessResumed { navController.popBackStack() },
                // Not gated on RESUMED: a cancelled picker reports before the activity resumes.
                // Idempotent: does nothing once this reader is no longer the top entry.
                onExit = { popThis() },
                viewRequest = viewRequest,
                onViewRequestHandled = onViewRequestHandled,
                // Not gated on RESUMED, so a Back during the reader's enter transition (its handler
                // has taken it) is not lost. Idempotent: a second call during the exit transition
                // neither pops the screen under the reader nor moves the task again.
                onReturnToCaller = { if (popThis()) onReturnToCaller() },
                launcherReturns = launcherReturns,
            )
        }
        composable(Routes.ABOUT) { entry ->
            AboutScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenSupport = {
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        navController.navigate(Routes.SUPPORT) { launchSingleTop = true }
                    }
                },
            )
        }
        composable(Routes.SUPPORT) {
            SupportScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }
}

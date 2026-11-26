package app.venkat.offkit.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
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
 * provider for it off the main thread before copying anything. Held in memory only, never in
 * navigation state.
 */
class ViewRequest(val uri: Uri, val id: Long, val typeVerified: Boolean = true)

/**
 * The app's navigation graph, starting at [Routes.HOME].
 *
 * A non-null [viewRequest] opens the reader on its document, on top of the current back stack (so
 * Back returns to whatever was open, e.g. a tool's unsaved result, or to home); the reader calls
 * [onViewRequestHandled] once it has started opening it. [onOpenDocument] asks for such a request
 * from inside the app (home's recent files, a tool's saved result); it must post a [ViewRequest],
 * the only way a Uri enters the reader. The reader goes on top, so Back returns to that screen.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    viewRequest: ViewRequest? = null,
    onViewRequestHandled: (ViewRequest) -> Unit = {},
    onOpenDocument: (Uri) -> Unit = {},
) {
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
            CompressScreen(onBack = dropUnlessResumed { navController.popBackStack() }, onOpenDocument = openFrom(entry))
        }
        composable(Routes.MERGE) { entry ->
            MergeScreen(onBack = dropUnlessResumed { navController.popBackStack() }, onOpenDocument = openFrom(entry))
        }
        composable(Routes.ADD_IMAGES) { entry ->
            AddImagesScreen(onBack = dropUnlessResumed { navController.popBackStack() }, onOpenDocument = openFrom(entry))
        }
        composable(Routes.READER) {
            ReaderScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                // Not gated on RESUMED: a cancelled picker reports before the activity resumes.
                // Idempotent: does nothing once the reader has left the back stack.
                onExit = { navController.popBackStack(Routes.READER, inclusive = true) },
                viewRequest = viewRequest,
                onViewRequestHandled = onViewRequestHandled,
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
